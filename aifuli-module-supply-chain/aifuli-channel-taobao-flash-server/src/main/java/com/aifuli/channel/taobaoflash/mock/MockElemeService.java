package com.aifuli.channel.taobaoflash.mock;

import com.aifuli.channel.taobaoflash.dal.dataobject.TbsgChannelTransactionDO;
import com.aifuli.channel.taobaoflash.dal.mysql.TbsgChannelTransactionMapper;
import com.aifuli.channel.taobaoflash.mock.dto.CashierPayRequest;
import com.aifuli.channel.taobaoflash.mock.dto.CashierPayResponse;
import com.aifuli.channel.taobaoflash.mock.dto.MockElemeRequest;
import com.aifuli.channel.taobaoflash.mock.dto.MockElemeResponse;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.BuyerO2OPaymentCallbackApi;
import com.aifuli.module.supplychain.api.o2o.dto.ConfirmPaymentReqDTO;
import com.alibaba.fastjson.JSONObject;
import eleme.openapi.h5.sdk.pay.enums.PayStatus;
import eleme.openapi.h5.sdk.pay.utils.PaySignUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 假饿了么驱动：签名后打真实渠道 {@code /tbsg/channel/*}，mock 收银台直调中台 confirmPayment。
 *
 * <p>对标 novo-mall {@code MockElemeService}，适配 aifuli：无海狸，支付确认走
 * {@link BuyerO2OPaymentCallbackApi}。仅在 {@code tbsg.mock-eleme.enabled=true} 时装配。
 * 详见 {@code docs/集成测试.md}「淘宝闪购 O2O 支付闭环」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tbsg.mock-eleme.enabled", havingValue = "true")
public class MockElemeService {

    private static final String PLATFORM_NOTIFY_SUCCESS = "SUCCESS";
    private static final String PLATFORM_NOTIFY_FAIL = "FAIL";
    private static final DateTimeFormatter EXPIRE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TbsgChannelTransactionMapper channelTransactionMapper;
    private final BuyerO2OPaymentCallbackApi buyerO2OPaymentCallbackApi;

    @Value("${tbsg.mock-eleme.platform-private-key:}")
    private String platformPrivateKey;

    @Value("${tbsg.mock-eleme.merchant-public-key:}")
    private String merchantPublicKey;

    @Value("${tbsg.mock-eleme.default-pay-amount:19600}")
    private int defaultPayAmount;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public MockElemeResponse pay(String baseUrl, MockElemeRequest request) {
        return execute("pay", endpoint(baseUrl, "/tbsg/channel/pay"), () -> {
            MockElemeRequest safeRequest = safeRequest(request);
            JSONObject payload = new JSONObject();
            payload.put("subject", defaultIfBlank(safeRequest.getSubject(), "Mock Ele.me 淘宝闪购订单"));
            payload.put("body", defaultIfBlank(safeRequest.getBody(), "mock-eleme-page"));
            payload.put("transactionId", requireText(safeRequest.getTransactionId(), "transactionId"));
            payload.put("payAmount", amountOrDefault(safeRequest.getPayAmount()));
            payload.put("timestamp", String.valueOf(System.currentTimeMillis()));
            payload.put("timeExpire", LocalDateTime.now().plusMinutes(30).format(EXPIRE_FORMATTER));
            payload.put("redirectUrl", defaultIfBlank(safeRequest.getRedirectUrl(), baseUrl + "/mock-eleme/index.html"));
            payload.put("backUrl", defaultIfBlank(safeRequest.getBackUrl(), baseUrl + "/mock-eleme/index.html"));
            payload.put("notifyUrl", defaultIfBlank(safeRequest.getNotifyUrl(), baseUrl + "/tbsg/mock-eleme/notify/pay"));
            payload.put("extendParams", defaultIfBlank(safeRequest.getExtendParams(),
                    defaultExtendParams(safeRequest.getTransactionId())));
            payload.put("uid", requireText(safeRequest.getUid(), "uid"));

            JSONObject signed = JSONObject.parseObject(PaySignUtil.sign(payload, platformPrivateKey));
            String formBody = signed.entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + "=" + encode(String.valueOf(entry.getValue())))
                    .collect(Collectors.joining("&"));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint(baseUrl, "/tbsg/channel/pay")))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            return new SignedRequest(httpRequest, formBody, false);
        });
    }

    public MockElemeResponse queryPay(String baseUrl, MockElemeRequest request) {
        return signedJson("queryPay", endpoint(baseUrl, "/tbsg/channel/queryPay"), jsonPayload ->
                jsonPayload.put("transactionId", requireText(safeRequest(request).getTransactionId(), "transactionId")));
    }

    /**
     * mock 收银台点支付：按 outTradeNo 加载本地交易 → Feign 调中台 confirmPayment → 短轮询本地 SUCCESS（notify 后）。
     */
    public CashierPayResponse cashierPay(CashierPayRequest request) {
        CashierPayResponse response = new CashierPayResponse();
        String outTradeNo = request == null ? null : request.getOutTradeNo();
        response.setOutTradeNo(outTradeNo);
        try {
            requireConfig();
            if (!StringUtils.hasText(outTradeNo)) {
                response.setSuccess(false);
                response.setMessage("outTradeNo 不能为空");
                return response;
            }
            TbsgChannelTransactionDO transaction = channelTransactionMapper.selectByOutTradeNo(outTradeNo.trim());
            if (transaction == null) {
                response.setSuccess(false);
                response.setMessage("本地交易不存在: " + outTradeNo);
                return response;
            }
            fillCashierResponse(response, transaction);
            if (PayStatus.SUCCESS.getCode().equals(transaction.getPayStatus())) {
                response.setSuccess(true);
                response.setMessage("订单已支付 (幂等)");
                response.setMiddleConfirmed(true);
                return response;
            }
            if (PayStatus.CLOSED.getCode().equals(transaction.getPayStatus())) {
                response.setSuccess(false);
                response.setMessage("订单已关闭，无法支付");
                return response;
            }

            ConfirmPaymentReqDTO confirm = new ConfirmPaymentReqDTO();
            confirm.setOutTradeNo(transaction.getOutTradeNo());
            confirm.setBuyerPaidAmount(transaction.getPayAmount() == null
                    ? null
                    : transaction.getPayAmount().longValue());
            confirm.setCashierReference("MOCK-CASHIER-" + transaction.getOutTradeNo());
            confirm.setObservedAt(OffsetDateTime.now(ZoneOffset.UTC));
            log.info("[Mock收银台] 调用中台 confirmPayment outTradeNo={}, amount={}",
                    confirm.getOutTradeNo(), confirm.getBuyerPaidAmount());
            CommonResult<Void> result = buyerO2OPaymentCallbackApi.confirmPayment(confirm);
            if (result == null || !result.isSuccess()) {
                response.setSuccess(false);
                response.setMiddleConfirmed(false);
                response.setMessage("中台 confirmPayment 失败: "
                        + (result == null ? "null" : result.getCode() + "/" + result.getMsg()));
                return response;
            }
            response.setMiddleConfirmed(true);

            TbsgChannelTransactionDO notified = waitForPaySuccess(transaction.getTransactionId());
            if (notified != null) {
                fillCashierResponse(response, notified);
                response.setSuccess(true);
                response.setMessage("支付成功 (中台已确认且渠道 notify 完成)");
                return response;
            }
            fillCashierResponse(response, channelTransactionMapper.selectByOutTradeNo(outTradeNo.trim()));
            response.setSuccess(true);
            response.setMessage("中台已确认支付；渠道 notify 尚未回写本地 SUCCESS，可用 queryPay 继续观察");
            return response;
        } catch (Exception e) {
            log.error("[Mock收银台] 支付处理异常 outTradeNo={}", outTradeNo, e);
            response.setSuccess(false);
            response.setMessage("支付异常: " + e.getMessage());
            return response;
        }
    }

    public String handlePayNotify(String body) {
        boolean signatureValid = false;
        try {
            signatureValid = StringUtils.hasText(body)
                    && StringUtils.hasText(merchantPublicKey)
                    && PaySignUtil.verify(body, merchantPublicKey);
        } catch (Exception e) {
            log.warn("[Ele.me Mock通知] 支付通知验签异常", e);
        }
        log.info("[Ele.me Mock通知] 收到支付通知 signatureValid={}, body={}", signatureValid, body);
        return signatureValid ? PLATFORM_NOTIFY_SUCCESS : PLATFORM_NOTIFY_FAIL;
    }

    private TbsgChannelTransactionDO waitForPaySuccess(String transactionId) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            TbsgChannelTransactionDO latest = channelTransactionMapper.selectByTransactionId(transactionId);
            if (latest != null && PayStatus.SUCCESS.getCode().equals(latest.getPayStatus())) {
                return latest;
            }
            Thread.sleep(300L);
        }
        return null;
    }

    private void fillCashierResponse(CashierPayResponse response, TbsgChannelTransactionDO transaction) {
        if (transaction == null) {
            return;
        }
        response.setTransactionId(transaction.getTransactionId());
        response.setOrderNo(transaction.getOrderNo());
        response.setOutTradeNo(transaction.getOutTradeNo());
        response.setPayStatus(transaction.getPayStatus());
    }

    private MockElemeResponse signedJson(String operation, String targetUrl, JsonPayloadCustomizer customizer) {
        return execute(operation, targetUrl, () -> {
            JSONObject payload = new JSONObject();
            customizer.customize(payload);
            String signedBody = PaySignUtil.sign(payload, platformPrivateKey);
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(signedBody, StandardCharsets.UTF_8))
                    .build();
            return new SignedRequest(request, signedBody, true);
        });
    }

    private MockElemeResponse execute(String operation, String targetUrl, SignedRequestFactory requestFactory) {
        MockElemeResponse result = new MockElemeResponse();
        result.setOperation(operation);
        result.setTargetUrl(targetUrl);
        try {
            requireConfig();
            SignedRequest signedRequest = requestFactory.create();
            result.setRequestBody(signedRequest.requestBody());
            HttpResponse<String> response = httpClient.send(signedRequest.httpRequest(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            result.setStatusCode(response.statusCode());
            result.setRedirectLocation(response.headers().firstValue("Location").orElse(null));
            result.setResponseBody(response.body());
            if (signedRequest.verifyResponse()) {
                result.setSignatureValid(StringUtils.hasText(response.body())
                        && PaySignUtil.verify(response.body(), merchantPublicKey));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[Ele.me Mock] {} 请求失败 targetUrl={}", operation, targetUrl, e);
            result.setErrorMessage(e.getMessage());
        }
        return result;
    }

    private void requireConfig() {
        requireText(platformPrivateKey, "tbsg.mock-eleme.platform-private-key");
        requireText(merchantPublicKey, "tbsg.mock-eleme.merchant-public-key");
    }

    private String endpoint(String baseUrl, String path) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + path;
    }

    private MockElemeRequest safeRequest(MockElemeRequest request) {
        return request == null ? new MockElemeRequest() : request;
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private int amountOrDefault(Integer amount) {
        return amount != null && amount > 0 ? amount : defaultPayAmount;
    }

    private String defaultExtendParams(String transactionId) {
        return JSONObject.toJSONString(Map.of("orderId", "MOCK_ORDER_" + transactionId));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface JsonPayloadCustomizer {
        void customize(JSONObject payload);
    }

    @FunctionalInterface
    private interface SignedRequestFactory {
        SignedRequest create() throws Exception;
    }

    private record SignedRequest(HttpRequest httpRequest, String requestBody, boolean verifyResponse) {
    }
}
