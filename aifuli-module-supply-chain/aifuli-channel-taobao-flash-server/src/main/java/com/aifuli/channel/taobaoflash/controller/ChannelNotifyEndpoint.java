package com.aifuli.channel.taobaoflash.controller;

import com.aifuli.channel.taobaoflash.dto.ChannelPaymentNotifyPayload;
import com.aifuli.channel.taobaoflash.dto.ChannelRefundNotifyPayload;
import com.aifuli.channel.taobaoflash.service.ChannelNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 渠道通知端点：接收供应链中台 Outbox 投递的 CHANNEL_NOTIFY，再调用 ele.me payCallback/refundCallback。
 *
 * <p>中台 O2O 支付确认/退款结果后写 Outbox，由 Outbox worker 投递到本端点；本端点转发给 ele.me。
 * 支付/退款通知经 {@link ChannelNotifyService} 查本地记录取 notifyUrl，构造 PayCallbackRequest /
 * RefundCallbackRequest 加签后 POST 到 ele.me。详见
 * docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/tbsg/channel/notify")
public class ChannelNotifyEndpoint {

    private final ChannelNotifyService channelNotifyService;

    /**
     * 中台投递的支付通知：查本地交易 → 构造 PayCallbackRequest → 加签 POST ele.me payCallback。
     */
    @PostMapping("/payment")
    public Map<String, Object> notifyPayment(@RequestBody ChannelPaymentNotifyPayload payload) {
        log.info("[渠道通知] 收到中台支付通知, transactionId={}, outTradeNo={}, status={}",
                payload == null ? null : payload.getTransactionId(),
                payload == null ? null : payload.getOutTradeNo(),
                payload == null ? null : payload.getPaymentStatus());
        boolean success = channelNotifyService.handlePaymentNotify(payload);
        return Map.of("returnCode", success ? "SUCCESS" : "FAIL",
                "returnMsg", success ? "OK" : "回调未成功");
    }

    /**
     * 中台投递的退款通知：查本地退款记录 → 构造 RefundCallbackRequest → 加签 POST ele.me refundCallback。
     */
    @PostMapping("/refund")
    public Map<String, Object> notifyRefund(@RequestBody ChannelRefundNotifyPayload payload) {
        log.info("[渠道通知] 收到中台退款通知, channelRefundNo={}, transactionId={}, status={}",
                payload == null ? null : payload.getChannelRefundNo(),
                payload == null ? null : payload.getTransactionId(),
                payload == null ? null : payload.getRefundStatus());
        boolean success = channelNotifyService.handleRefundNotify(payload);
        return Map.of("returnCode", success ? "SUCCESS" : "FAIL",
                "returnMsg", success ? "OK" : "回调未成功");
    }
}
