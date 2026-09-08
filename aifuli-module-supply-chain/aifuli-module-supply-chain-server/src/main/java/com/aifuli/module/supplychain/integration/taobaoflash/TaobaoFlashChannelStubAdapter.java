package com.aifuli.module.supplychain.integration.taobaoflash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 淘宝闪购渠道服务 stub 适配器：渠道服务尚未独立部署时，仅记录日志供联调。
 *
 * <p>渠道服务就绪后替换为 Feign/HTTP 实现（{@code TaobaoFlashChannelRpcAdapter}），
 * 调用渠道服务 CHANNEL_NOTIFY 端点。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7-渠道服务侧改造}。</p>
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "taobaoFlashChannelRpcAdapter")
public class TaobaoFlashChannelStubAdapter implements TaobaoFlashChannelPort {

    @Override
    public void notifyPayment(ChannelPaymentNotify notify) {
        log.info("[O2O][stub] notifyPayment channelCode={}, transactionId={}, outTradeNo={}, amount={}, status={}",
                notify.channelCode(), notify.transactionId(), notify.outTradeNo(),
                notify.payAmount(), notify.paymentStatus());
    }

    @Override
    public void notifyRefund(ChannelRefundNotify notify) {
        log.info("[O2O][stub] notifyRefund channelCode={}, channelRefundNo={}, transactionId={}, amount={}, status={}",
                notify.channelCode(), notify.channelRefundNo(), notify.transactionId(),
                notify.refundAmount(), notify.refundStatus());
    }
}
