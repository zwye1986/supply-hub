package com.aifuli.channel.taobaoflash.service;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelRefundDO;
import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelTransactionDO;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelRefundMapper;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelTransactionMapper;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.O2OChannelCommandApi;
import com.aifuli.module.supplychain.api.o2o.dto.AcceptRefundReqDTO;
import eleme.openapi.h5.sdk.pay.enums.RefundStatus;
import eleme.openapi.h5.sdk.pay.model.request.RefundRequest;
import eleme.openapi.h5.sdk.pay.model.response.RefundResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 渠道退款受理编排：ele.me /refund 验签后，构造中台退款受理命令并调用中台 O2OChannelCommandApi.acceptRefund，
 * 落本地退款记录缓存 notifyUrl 与中台 refundCaseNo，返回 RefundResponse 供 Controller 签响应。
 *
 * <p>原 yudao-module-taobao 在此调海狸退款，新渠道服务改为调供应链中台 acceptRefund，由中台创建退款 Case、
 * 推进售后事实，退款结果由收银台回调 reportRefundResult 驱动、经中台 Outbox 通知渠道服务回调 ele.me。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRefundService {

    private static final String CHANNEL_CODE = "ELEME";
    /** 默认退款类型；TODO 后续从 RefundRequest.extendParams 解析（ele.me RefundRequest 本身无 refundType 字段） */
    private static final String DEFAULT_REFUND_TYPE = "CHANNEL_REFUND";

    private final O2OChannelCommandApi o2oChannelCommandApi;
    private final TbsgChannelRefundMapper channelRefundMapper;
    private final TbsgChannelTransactionMapper channelTransactionMapper;

    /**
     * 受理退款：调中台 acceptRefund 拿 refundCaseNo，落本地退款记录，返回 RefundResponse。
     *
     * <p>幂等：同一 channelRefundNo 重复受理时中台返回已有 refundCaseNo，本地先查再插/更新。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundResponse acceptRefund(RefundRequest refundRequest) {
        String channelRefundNo = refundRequest.getRefundNo();
        String transactionId = refundRequest.getTransactionId();

        // 本地幂等：已有退款记录直接返回受理中响应。
        TbsgChannelRefundDO existing = channelRefundMapper.selectByChannelRefundNo(channelRefundNo);
        if (existing != null) {
            log.info("[渠道退款] 退款记录已存在，返回受理中, channelRefundNo={}, refundCaseNo={}",
                    channelRefundNo, existing.getRefundCaseNo());
            return buildResponse(transactionId, existing.getOutTradeNo(), channelRefundNo,
                    existing.getRefundCaseNo(), refundRequest.getRefundAmount(), RefundStatus.PROCESSING.getCode());
        }

        AcceptRefundReqDTO req = new AcceptRefundReqDTO();
        req.setChannelCode(CHANNEL_CODE);
        req.setChannelRefundNo(channelRefundNo);
        req.setTransactionId(transactionId);
        req.setRefundType(DEFAULT_REFUND_TYPE);
        req.setChannelRefundAmount(refundRequest.getRefundAmount() == null
                ? null : refundRequest.getRefundAmount().longValue());

        log.info("[渠道退款] 调用中台 acceptRefund 请求 channelCode={}, channelRefundNo={}, transactionId={}, refundType={}, refundAmount={}",
                req.getChannelCode(), req.getChannelRefundNo(), req.getTransactionId(),
                req.getRefundType(), req.getChannelRefundAmount());
        long startMs = System.currentTimeMillis();
        CommonResult<String> result = o2oChannelCommandApi.acceptRefund(req);
        long costMs = System.currentTimeMillis() - startMs;
        if (result == null || !result.isSuccess() || result.getData() == null) {
            log.warn("[渠道退款] 中台 acceptRefund 失败, channelRefundNo={}, code={}, msg={}, cost={}ms",
                    channelRefundNo, result == null ? null : result.getCode(),
                    result == null ? null : result.getMsg(), costMs);
            return buildResponse(transactionId, null, channelRefundNo, null,
                    refundRequest.getRefundAmount(), RefundStatus.FAIL.getCode());
        }
        String refundCaseNo = result.getData();
        log.info("[渠道退款] 中台 acceptRefund 成功 channelRefundNo={}, refundCaseNo={}, cost={}ms",
                channelRefundNo, refundCaseNo, costMs);

        // 查本地交易表取原支付 outTradeNo，回填 RefundResponse.outTradeNo。
        TbsgChannelTransactionDO transaction = channelTransactionMapper.selectByTransactionId(transactionId);
        String outTradeNo = transaction == null ? null : transaction.getOutTradeNo();

        LocalDateTime now = LocalDateTime.now();
        TbsgChannelRefundDO refund = TbsgChannelRefundDO.builder()
                .channelRefundNo(channelRefundNo)
                .transactionId(transactionId)
                .outTradeNo(outTradeNo)
                .refundCaseNo(refundCaseNo)
                .refundAmount(refundRequest.getRefundAmount())
                .notifyUrl(refundRequest.getNotifyUrl())
                .refundStatus(RefundStatus.PROCESSING.getCode())
                .createTime(now)
                .updateTime(now)
                .build();
        channelRefundMapper.insert(refund);

        return buildResponse(transactionId, outTradeNo, channelRefundNo, refundCaseNo,
                refundRequest.getRefundAmount(), RefundStatus.PROCESSING.getCode());
    }

    private RefundResponse buildResponse(String transactionId, String outTradeNo, String refundNo,
                                           String outRefundNo, Integer refundAmount, String refundStatus) {
        RefundResponse response = new RefundResponse();
        response.setTransactionId(transactionId);
        response.setOutTradeNo(outTradeNo);
        response.setRefundNo(refundNo);
        response.setOutRefundNo(outRefundNo);
        response.setRefundAmount(refundAmount);
        response.setRefundStatus(refundStatus);
        return response;
    }
}
