package com.aifuli.channel.taobaoflash.service;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelTransactionDO;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelTransactionMapper;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.O2OChannelCommandApi;
import com.aifuli.module.supplychain.api.o2o.dto.PaymentPreparationTicketDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PreparePaymentReqDTO;
import eleme.openapi.h5.sdk.pay.enums.PayStatus;
import eleme.openapi.h5.sdk.pay.enums.ReturnCode;
import eleme.openapi.h5.sdk.pay.model.request.PayRequest;
import eleme.openapi.h5.sdk.pay.model.request.QueryPayRequest;
import eleme.openapi.h5.sdk.pay.model.response.QueryPayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;

/**
 * 渠道支付编排：ele.me /pay 请求验签后，构造中台预下单命令并调用中台 O2OChannelCommandApi。
 *
 * <p>原 yudao-module-taobao 在此调海狸 /cash/prepay，新渠道服务改为调供应链中台 preparePayment，
 * 由中台建单、冻结资金并拉起采购商收银台，返回 launchDescriptor 供渠道服务跳转。
 * 渠道服务不持有订单资金真相，不访问中台表。详见
 * docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPaymentService {

    private static final String CHANNEL_CODE = "ELEME";

    private final O2OChannelCommandApi o2oChannelCommandApi;
    private final TbsgChannelTransactionMapper channelTransactionMapper;

    /** 机构支付码，对应原 tbsg.pay.code，回调 ele.me 时填入 PayCallbackRequest.payCode */
    @Value("${tbsg.pay.code:}")
    private String payCode;

    /** 渠道外部商户标识；本期配置化，TODO 后续从 PayRequest.extendParams 解析。 */
    @Value("${tbsg.pay.merchant-id:}")
    private String defaultExternalMerchantId;

    /**
     * 联调开关：开启后把中台 stub 收银台跳转改写为同域 mock-eleme 收银台页。
     * 详见 docs/集成测试.md「淘宝闪购 O2O 支付闭环」。
     */
    @Value("${tbsg.mock-eleme.enabled:false}")
    private boolean mockElemeEnabled;

    /** mock 收银台 H5 基址，默认走当前渠道 context（含 /taobaoh5）。 */
    @Value("${tbsg.mock-eleme.h5-base-url:}")
    private String mockElemeH5BaseUrl;

    /**
     * 编排支付预下单：把 ele.me PayRequest 映射为中台 PreparePaymentReqDTO 并调用中台，
     * 成功后落本地渠道交易记录缓存 notifyUrl 等协议字段，供中台 CHANNEL_NOTIFY 回调 ele.me 时查询。
     *
     * @return 中台返回的收银台跳转描述；调用失败时返回 null（由 Controller 决定降级）
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentPreparationTicketDTO preparePayment(PayRequest payRequest) {
        PreparePaymentReqDTO req = new PreparePaymentReqDTO();
        req.setChannelCode(CHANNEL_CODE);
        req.setTransactionId(payRequest.getTransactionId());
        // ele.me uid 即渠道用户标识，对应中台 O2OChannelUserBinding.channelUserId
        req.setChannelUserId(payRequest.getUid());
        req.setPayAmount(payRequest.getPayAmount() == null ? null : payRequest.getPayAmount().longValue());
        req.setExternalMerchantId(resolveExternalMerchantId(payRequest));
        req.setSubject(payRequest.getSubject());
        // TODO: externalStoreId/externalStoreName 后续从 extendParams 解析，本期留空。
        // 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7.4

        log.info("[渠道支付] 调用中台 preparePayment 请求 channelCode={}, transactionId={}, channelUserId={}, payAmount={}, merchantId={}",
                req.getChannelCode(), req.getTransactionId(), req.getChannelUserId(),
                req.getPayAmount(), req.getExternalMerchantId());
        long startMs = System.currentTimeMillis();
        CommonResult<PaymentPreparationTicketDTO> result = o2oChannelCommandApi.preparePayment(req);
        long costMs = System.currentTimeMillis() - startMs;
        if (result == null || !result.isSuccess() || result.getData() == null) {
            log.warn("[渠道支付] 中台 preparePayment 失败, transactionId={}, code={}, msg={}, cost={}ms",
                    req.getTransactionId(), result == null ? null : result.getCode(),
                    result == null ? null : result.getMsg(), costMs);
            return null;
        }
        PaymentPreparationTicketDTO ticket = result.getData();
        log.info("[渠道支付] 中台 preparePayment 成功 transactionId={}, orderNo={}, outTradeNo={}, invocationId={}, cost={}ms",
                req.getTransactionId(), ticket.getOrderNo(), ticket.getOutTradeNo(), ticket.getInvocationId(), costMs);

        // 落本地渠道交易记录：缓存 ele.me notifyUrl，供中台通知后回调 ele.me payCallback。
        // 幂等：同一 transactionId 重复 pay 时中台会返回已有 ticket，此处用 insertIgnore 思路——先查再插。
        TbsgChannelTransactionDO existing = channelTransactionMapper.selectByTransactionId(payRequest.getTransactionId());
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            TbsgChannelTransactionDO transaction = TbsgChannelTransactionDO.builder()
                    .transactionId(payRequest.getTransactionId())
                    .outTradeNo(ticket.getOutTradeNo())
                    .orderNo(ticket.getOrderNo())
                    .payAmount(payRequest.getPayAmount())
                    .channelUserId(payRequest.getUid())
                    .notifyUrl(payRequest.getNotifyUrl())
                    .subject(payRequest.getSubject())
                    .payStatus(PayStatus.NOTPAY.getCode())
                    .createTime(now)
                    .updateTime(now)
                    .build();
            channelTransactionMapper.insert(transaction);
        } else {
            // 已存在记录（重复 pay），仅刷新 outTradeNo/notifyUrl，避免覆盖已有支付状态。
            existing.setOutTradeNo(ticket.getOutTradeNo());
            existing.setOrderNo(ticket.getOrderNo());
            existing.setNotifyUrl(payRequest.getNotifyUrl());
            existing.setUpdateTime(LocalDateTime.now());
            channelTransactionMapper.updateById(existing);
        }
        rewriteLaunchDescriptorForMock(ticket, payRequest);
        return ticket;
    }

    /**
     * 查询支付状态：按本地渠道交易表投影，供旅通/mock queryPay。
     *
     * <p>联调切片不查中台；本地 payStatus 在 CHANNEL_NOTIFY 回调成功后推进为 SUCCESS。</p>
     */
    public QueryPayResponse queryPay(QueryPayRequest request) {
        String transactionId = request.getTransactionId();
        log.info("[渠道支付] queryPay 入口 transactionId={}", transactionId);
        TbsgChannelTransactionDO transaction = channelTransactionMapper.selectByTransactionId(transactionId);
        if (transaction == null) {
            QueryPayResponse missing = new QueryPayResponse();
            missing.setReturnCode(ReturnCode.ORDER_NOT_EXIST.getCode());
            missing.setReturnMsg("订单不存在");
            return missing;
        }
        QueryPayResponse response = new QueryPayResponse();
        response.setTransactionId(transaction.getTransactionId());
        response.setOutTradeNo(transaction.getOutTradeNo());
        response.setPayAmount(transaction.getPayAmount());
        response.setPayStatus(transaction.getPayStatus());
        response.setReturnCode(ReturnCode.SUCCESS.getCode());
        response.setReturnMsg(ReturnCode.SUCCESS.getCode());
        return response;
    }

    private void rewriteLaunchDescriptorForMock(PaymentPreparationTicketDTO ticket, PayRequest payRequest) {
        if (!mockElemeEnabled || ticket == null || ticket.getOutTradeNo() == null) {
            return;
        }
        String base = mockElemeH5BaseUrl == null || mockElemeH5BaseUrl.isBlank()
                ? ""
                : mockElemeH5BaseUrl.replaceAll("/$", "");
        String cashierUrl = UriComponentsBuilder
                .fromUriString(base + "/mock-eleme/cashier.html")
                .queryParam("outTradeNo", ticket.getOutTradeNo())
                .queryParam("transactionId", payRequest.getTransactionId())
                .queryParam("payAmount", payRequest.getPayAmount())
                .queryParam("orderNo", ticket.getOrderNo())
                .build(true)
                .toUriString();
        log.info("[渠道支付] mock-eleme 改写收银台跳转 outTradeNo={}, cashierUrl={}",
                ticket.getOutTradeNo(), cashierUrl);
        ticket.setLaunchDescriptor(cashierUrl);
    }

    private String resolveExternalMerchantId(PayRequest payRequest) {
        // TODO: 从 payRequest.getExtendParams() 解析实际渠道商户标识，对齐原模块 resolveExtraOrderId 逻辑。
        if (defaultExternalMerchantId != null && !defaultExternalMerchantId.isBlank()) {
            return defaultExternalMerchantId;
        }
        throw new IllegalStateException("tbsg.pay.merchant-id 未配置，无法解析渠道外部商户标识");
    }
}
