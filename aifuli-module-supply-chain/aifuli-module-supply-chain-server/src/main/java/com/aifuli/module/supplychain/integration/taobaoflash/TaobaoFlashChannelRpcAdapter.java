package com.aifuli.module.supplychain.integration.taobaoflash;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 淘宝闪购渠道服务 RPC 适配器：通过 HTTP 将 CHANNEL_NOTIFY 投递到渠道服务端点，
 * 替代 {@link TaobaoFlashChannelStubAdapter}。启用方式：{@code tbsg.channel.rpc.enabled=true}。
 *
 * <p>渠道服务收到后调用 ele.me payCallback/refundCallback。中台不感知 ele.me 协议，
 * 只发送类型化命令。详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4}。</p>
 */
@Slf4j
@Component("taobaoFlashChannelRpcAdapter")
@Primary
@ConditionalOnProperty(name = "tbsg.channel.rpc.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TaobaoFlashChannelRpcAdapter implements TaobaoFlashChannelPort {

    private static final String NOTIFY_PAYMENT_PATH = "/tbsg/channel/notify/payment";
    private static final String NOTIFY_REFUND_PATH = "/tbsg/channel/notify/refund";

    private final ObjectMapper objectMapper;

    /** 渠道服务 base URL，如 http://taobao-flash-channel:48400/taobaoh5 */
    @org.springframework.beans.factory.annotation.Value("${tbsg.channel.notify-base-url:}")
    private String channelNotifyBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${tbsg.channel.http-connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Value("${tbsg.channel.http-request-timeout-seconds:10}")
    private int requestTimeoutSeconds;

    @Override
    public void notifyPayment(ChannelPaymentNotify notify) {
        post(NOTIFY_PAYMENT_PATH, notify, "notifyPayment");
    }

    @Override
    public void notifyRefund(ChannelRefundNotify notify) {
        post(NOTIFY_REFUND_PATH, notify, "notifyRefund");
    }

    private void post(String path, Object payload, String op) {
        if (channelNotifyBaseUrl == null || channelNotifyBaseUrl.isBlank()) {
            throw new IllegalStateException("tbsg.channel.notify-base-url 未配置，无法投递渠道通知");
        }
        String url = channelNotifyBaseUrl + path;
        long startMs = System.currentTimeMillis();
        try {
            String body = objectMapper.writeValueAsString(payload);
            log.info("[O2O][rpc] {} 请求 url={}, payload={}", op, url, body);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - startMs;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[O2O][rpc] {} 投递成功, url={}, status={}, body={}, cost={}ms",
                        op, url, response.statusCode(), response.body(), costMs);
            } else {
                throw new IllegalStateException(op + " 投递失败, status=" + response.statusCode()
                        + ", body=" + response.body() + ", cost=" + costMs + "ms");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(op + " 投递异常, url=" + url
                    + ", cost=" + (System.currentTimeMillis() - startMs) + "ms", e);
        }
    }
}
