package com.aifuli.channel.taobaoflash.service;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelRefundDO;
import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelTransactionDO;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelRefundMapper;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelTransactionMapper;
import com.aifuli.channel.taobaoflash.dto.ChannelPaymentNotifyPayload;
import com.aifuli.channel.taobaoflash.dto.ChannelRefundNotifyPayload;
import eleme.openapi.h5.sdk.pay.enums.PayStatus;
import eleme.openapi.h5.sdk.pay.enums.RefundStatus;
import eleme.openapi.h5.sdk.pay.model.request.PayCallbackRequest;
import eleme.openapi.h5.sdk.pay.model.request.RefundCallbackRequest;
import eleme.openapi.h5.sdk.pay.utils.PaySignUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 渠道通知编排：接收中台 CHANNEL_NOTIFY，按本地交易记录取 notifyUrl，构造 ele.me PayCallbackRequest
 * 加签后 POST 到 ele.me 平台，并按响应更新本地交易状态。
 *
 * <p>迁移自原 yudao-module-taobao HaiLiPaymentCallbackService/HaiLiCallbackService 的回调构造逻辑与
 * NotifyTaskService 的 HTTP 投递逻辑；新架构下触发源由海狸回调改为中台 Outbox 投递。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelNotifyService {

    private static final String ELEME_PAID = "PAID";
    private static final String ELEME_REFUND_SUCCESS = "SUCCESS";
    private static final String ELEME_REFUND_FAILED = "FAILED";
    private static final String PLATFORM_SUCCESS = "SUCCESS";

    private final TbsgChannelTransactionMapper channelTransactionMapper;
    private final TbsgChannelRefundMapper channelRefundMapper;

    @Value("${tbsg.pay.code:}")
    private String payCode;

    @Value("${tbsg.pay.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${tbsg.notify.http-connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;

    @Value("${tbsg.notify.http-request-timeout-seconds:10}")
    private int requestTimeoutSeconds;

    /**
     * 处理中台支付通知：查本地交易 → 构造 PayCallbackRequest → 加签 POST ele.me → 更新本地状态。
     *
     * @return true 表示 ele.me 平台返回 SUCCESS；false 表示未回调或回调失败
     */
    public boolean handlePaymentNotify(ChannelPaymentNotifyPayload payload) {
        if (payload == null || payload.getTransactionId() == null) {
            log.warn("[渠道通知] 支付通知 payload 缺失 transactionId, payload={}", payload);
            return false;
        }

        // 仅支付成功才回调 ele.me 平台；失败/关闭本期不触发 payCallback。
        if (!ELEME_PAID.equals(payload.getPaymentStatus())) {
            log.info("[渠道通知] 支付状态非 PAID，跳过 ele.me 回调, transactionId={}, status={}",
                    payload.getTransactionId(), payload.getPaymentStatus());
            return false;
        }

        TbsgChannelTransactionDO transaction = channelTransactionMapper.selectByTransactionId(payload.getTransactionId());
        if (transaction == null) {
            log.warn("[渠道通知] 本地交易记录不存在, transactionId={}", payload.getTransactionId());
            return false;
        }
        if (PLATFORM_SUCCESS.equals(transaction.getPayStatus())) {
            log.info("[渠道通知] 本地交易已回调成功，跳过重复回调, transactionId={}, status={}",
                    transaction.getTransactionId(), transaction.getPayStatus());
            return true;
        }
        String notifyUrl = transaction.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            log.warn("[渠道通知] 本地交易缺少 notifyUrl, transactionId={}", transaction.getTransactionId());
            return false;
        }

        PayCallbackRequest callbackRequest = new PayCallbackRequest();
        callbackRequest.setPayCode(payCode);
        callbackRequest.setTransactionId(transaction.getTransactionId());
        callbackRequest.setOutTradeNo(transaction.getOutTradeNo());
        callbackRequest.setPayAmount(transaction.getPayAmount());
        callbackRequest.setPayStatus(PayStatus.SUCCESS.getCode());

        String signedBody = PaySignUtil.sign(callbackRequest, merchantPrivateKey);
        log.info("[渠道通知] 回调 ele.me payCallback, transactionId={}, outTradeNo={}, payAmount={}, notifyUrl={}",
                callbackRequest.getTransactionId(), callbackRequest.getOutTradeNo(),
                callbackRequest.getPayAmount(), notifyUrl);

        try {
            String response = httpPost(notifyUrl, signedBody);
            boolean success = response != null && response.contains(PLATFORM_SUCCESS);
            LocalDateTime now = LocalDateTime.now();
            if (success) {
                transaction.setPayStatus(PayStatus.SUCCESS.getCode());
                transaction.setUpdateTime(now);
                channelTransactionMapper.updateById(transaction);
                log.info("[渠道通知] ele.me 回调成功, transactionId={}", transaction.getTransactionId());
            } else {
                log.warn("[渠道通知] ele.me 回调未返回 SUCCESS, transactionId={}, response={}",
                        transaction.getTransactionId(), response);
            }
            return success;
        } catch (Exception e) {
            log.error("[渠道通知] ele.me 回调异常, transactionId={}", transaction.getTransactionId(), e);
            return false;
        }
    }

    /**
     * 处理中台退款通知：查本地退款记录 → 构造 RefundCallbackRequest → 加签 POST ele.me refundCallback → 更新本地状态。
     *
     * <p>仅退款终态（SUCCESS/FAILED）回调 ele.me；中间状态跳过。</p>
     *
     * @return true 表示 ele.me 平台返回 SUCCESS；false 表示未回调或回调失败
     */
    public boolean handleRefundNotify(ChannelRefundNotifyPayload payload) {
        if (payload == null || payload.getChannelRefundNo() == null) {
            log.warn("[渠道通知] 退款通知 payload 缺失 channelRefundNo, payload={}", payload);
            return false;
        }

        String elemeRefundStatus = mapRefundStatus(payload.getRefundStatus());
        if (elemeRefundStatus == null) {
            log.info("[渠道通知] 退款状态非终态，跳过 ele.me 回调, channelRefundNo={}, status={}",
                    payload.getChannelRefundNo(), payload.getRefundStatus());
            return false;
        }

        TbsgChannelRefundDO refund = channelRefundMapper.selectByChannelRefundNo(payload.getChannelRefundNo());
        if (refund == null) {
            log.warn("[渠道通知] 本地退款记录不存在, channelRefundNo={}", payload.getChannelRefundNo());
            return false;
        }
        if (PLATFORM_SUCCESS.equals(refund.getRefundStatus()) || RefundStatus.FAIL.getCode().equals(refund.getRefundStatus())) {
            log.info("[渠道通知] 本地退款已回调终态，跳过重复回调, channelRefundNo={}, status={}",
                    refund.getChannelRefundNo(), refund.getRefundStatus());
            return true;
        }
        String notifyUrl = refund.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            log.warn("[渠道通知] 本地退款记录缺少 notifyUrl, channelRefundNo={}", refund.getChannelRefundNo());
            return false;
        }

        RefundCallbackRequest callbackRequest = new RefundCallbackRequest();
        callbackRequest.setPayCode(payCode);
        callbackRequest.setTransactionId(refund.getTransactionId());
        callbackRequest.setOutTradeNo(refund.getOutTradeNo());
        callbackRequest.setRefundNo(refund.getChannelRefundNo());
        callbackRequest.setOutRefundNo(refund.getRefundCaseNo());
        callbackRequest.setRefundAmount(refund.getRefundAmount());
        callbackRequest.setRefundStatus(elemeRefundStatus);

        String signedBody = PaySignUtil.sign(callbackRequest, merchantPrivateKey);
        log.info("[渠道通知] 回调 ele.me refundCallback, channelRefundNo={}, refundCaseNo={}, refundAmount={}, status={}, notifyUrl={}",
                callbackRequest.getRefundNo(), callbackRequest.getOutRefundNo(),
                callbackRequest.getRefundAmount(), elemeRefundStatus, notifyUrl);

        try {
            String response = httpPost(notifyUrl, signedBody);
            boolean success = response != null && response.contains(PLATFORM_SUCCESS);
            LocalDateTime now = LocalDateTime.now();
            if (success) {
                refund.setRefundStatus(elemeRefundStatus);
                refund.setUpdateTime(now);
                channelRefundMapper.updateById(refund);
                log.info("[渠道通知] ele.me 退款回调成功, channelRefundNo={}", refund.getChannelRefundNo());
            } else {
                log.warn("[渠道通知] ele.me 退款回调未返回 SUCCESS, channelRefundNo={}, response={}",
                        refund.getChannelRefundNo(), response);
            }
            return success;
        } catch (Exception e) {
            log.error("[渠道通知] ele.me 退款回调异常, channelRefundNo={}", refund.getChannelRefundNo(), e);
            return false;
        }
    }

    /**
     * 中台 O2ORefundStatus 枚举名 → ele.me RefundStatus code；仅终态映射，中间态返回 null。
     */
    private String mapRefundStatus(String middleRefundStatus) {
        if (ELEME_REFUND_SUCCESS.equals(middleRefundStatus)) {
            return RefundStatus.SUCCESS.getCode();
        }
        if (ELEME_REFUND_FAILED.equals(middleRefundStatus)) {
            return RefundStatus.FAIL.getCode();
        }
        return null;
    }

    private String httpPost(String url, String body) throws Exception {
        long startMs = System.currentTimeMillis();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
        log.info("[渠道通知] HTTP POST 请求 url={}, requestBody={}", url, body);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("[渠道通知] HTTP POST 完成, url={}, status={}, responseBody={}, cost={}ms",
                url, response.statusCode(), response.body(), System.currentTimeMillis() - startMs);
        if (response.statusCode() == 302) {
            log.warn("[渠道通知] 收到 302 重定向，可能签名异常, url={}", url);
            return null;
        }
        return response.body();
    }
}
