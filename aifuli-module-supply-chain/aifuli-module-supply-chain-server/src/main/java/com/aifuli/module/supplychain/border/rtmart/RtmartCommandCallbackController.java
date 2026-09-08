package com.aifuli.module.supplychain.border.rtmart;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.AcceptRefundCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.CancelPaymentCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.PaymentTicket;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.PaymentStatusView;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.QueryPaymentCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.QueryRefundCommand;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.RefundAcceptance;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.RefundStatusView;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.CancelPaymentTicket;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade.WireCreatePaymentCommand;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_APP_KEY;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_ARGUMENT;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.INVALID_SIGNATURE;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.REQUEST_EXPIRED;
import static com.aifuli.module.supplychain.border.rtmart.RtmartResponseCode.SYSTEM_ERROR;
import static com.aifuli.module.supplychain.border.rtmart.RtmartOrderType.OFFLINE_SCAN_PURCHASE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 旅通商户接口②③④⑤⑥入站适配器：创建订单、查询支付、取消支付、申请退款、查询退款。
 *
 * <p>验签协议与"获取会员信息"接口①一致：SHA1withRSA + 旅通公钥，规范串为除 sign 外
 * 非 null 字段按 key 字典序 key+value 拼接。响应格式为 {@code result/code/msg/data}。
 * 设计见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md}
 * 与 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}、
 * {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#82-申请退款--查询退款成功-data-字段始终输出}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/rtmart")
public class RtmartCommandCallbackController {

    private static final Set<Integer> CALLER_CORRECTABLE_CODES = Set.of(
            INSTORE_CHANNEL_USER_BINDING_MISSING.getCode(), INSTORE_IDENTITY_CONFLICT.getCode(),
            INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND.getCode(), INSTORE_REFUND_AMOUNT_EXCEEDED.getCode(),
            INSTORE_ORDER_NOT_FOUND.getCode(), INSTORE_PAYMENT_FLOW_NOT_FOUND.getCode(),
            INSTORE_REFUND_CASE_NOT_FOUND.getCode(), INSTORE_IDEMPOTENCY_CONFLICT.getCode(),
            INSTORE_TENANT_SCOPE_CONFLICT.getCode(), INSTORE_CONCURRENT_UPDATE.getCode(),
            INSTORE_MEMBER_PROFILE_INVALID.getCode(), INSTORE_REFUND_AMOUNT_INVALID.getCode());

    /** 旅通协议：未支付/取消时 pay_time 填 0（Unix 秒）。 */
    private static final int UNPAID_PAY_TIME_SECONDS = 0;

    private final RtmartInboundProperties properties;
    private final InstoreChannelCommandFacade commandFacade;
    @Nullable
    private final StringRedisTemplate redisTemplate;

    private PublicKey verifyKey;

    /**
     * 关闭旅通入站时仍保留 Controller，以稳定返回旅通错误信封；此时不要求 Redis 自动配置存在。
     * 启用态在 {@link #initializeVerifyKey()} 中校验 Redis 强依赖并 fail-fast。
     * 设计见 docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#5-签名与-nonce。
     */
    public RtmartCommandCallbackController(RtmartInboundProperties properties,
            InstoreChannelCommandFacade commandFacade,
            @Nullable StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.commandFacade = commandFacade;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void initializeVerifyKey() {
        if (properties.isEnabled()) {
            if (redisTemplate == null) {
                throw new IllegalStateException(
                        "rtmart inbound requires StringRedisTemplate when enabled");
            }
            this.verifyKey = RtmartPublicKeyParser.parseX509(properties.getPublicKey());
        }
    }

    @PostMapping("/order/create")
    @PermitAll
    public RtmartResponse createOrder(@RequestBody RtmartCreateOrderRequest request) {
        Map<String, String> signFields = buildCreateOrderSignFields(request);
        RtmartResponse error = validateProtocol(request.getAppKey(), request.getVersion(),
                request.getTimestamp(), request.getNonceStr(), request.getSign(), signFields);
        if (error != null) {
            return error;
        }

        error = validateRequired(
                "order_id", request.getOrderId(),
                "trade_id", request.getTradeId(),
                "user_code", request.getUserCode(),
                "company_code", request.getCompanyCode(),
                "total_amount", request.getTotalAmount(),
                "order_time", request.getOrderTime(),
                "order_type", request.getOrderType(),
                "shop_code", request.getShopCode(),
                "shop_name", request.getShopName()
        );
        if (error != null) {
            return error;
        }

        int orderType = request.getOrderType();
        if (!OFFLINE_SCAN_PURCHASE.matches(orderType)) {
            log.warn("[RTMART][inbound] createOrder invalid order_type={}", orderType);
            return RtmartResponse.fail(INVALID_ARGUMENT, "order_type 当前仅支持 1");
        }

        log.info("[RTMART][inbound] createOrder entry channelOrderId={}, tradeId={}, userCode={}",
                request.getOrderId(), request.getTradeId(), request.getUserCode());

        String traceId = "rtmart:" + request.getNonceStr();
        try {
            PaymentTicket ticket = commandFacade.createPaymentOrder(new WireCreatePaymentCommand(
                    properties.getProjectId(), properties.getChannelCode(),
                    request.getOrderId(), request.getTradeId(), request.getUserCode(),
                    request.getTotalAmount(), orderType, request.getTotalAmount(),
                    request.getCompanyCode(), request.getCompanyCode(),
                    request.getShopCode(), request.getShopName(), "到店购", traceId));

            RtmartCreateOrderData data = new RtmartCreateOrderData();
            data.setOutTradeNo(ticket.outTradeNo());
            data.setCashierUrl(ticket.cashierUrl());

            log.info("[RTMART][inbound] createOrder success outTradeNo={}", ticket.outTradeNo());
            return RtmartResponse.success(data);
        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] createOrder failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] createOrder unexpected error", exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    @PostMapping("/payment/query")
    @PermitAll
    public RtmartResponse queryPayment(@RequestBody RtmartQueryPaymentRequest request) {
        Map<String, String> signFields = buildQueryPaymentSignFields(request);
        RtmartResponse error = validateProtocol(request.getAppKey(), request.getVersion(),
                request.getTimestamp(), request.getNonceStr(), request.getSign(), signFields);
        if (error != null) {
            return error;
        }

        error = validateRequired(
                "trade_id", request.getTradeId(),
                "user_code", request.getUserCode(),
                "company_code", request.getCompanyCode()
        );
        if (error != null) {
            return error;
        }

        log.info("[RTMART][inbound] queryPayment entry tradeId={}", request.getTradeId());

        try {
            PaymentStatusView view = commandFacade.queryAndConvergePayment(
                    new QueryPaymentCommand(properties.getProjectId(),
                            properties.getChannelCode(), request.getTradeId(),
                            "rtmart:" + request.getNonceStr()));

            RtmartQueryPaymentData data = new RtmartQueryPaymentData();
            int payStatus = mapPaymentStatus(view.payStatus());
            data.setOutTradeNo(view.outTradeNo());
            data.setPayStatus(payStatus);
            data.setPayFee(mapPayFee(view.payAmount()));
            data.setPayTime(mapPayTime(payStatus, view.payTime()));
            data.setTransactionId(mapTransactionId(payStatus, view.externalTransactionId()));

            log.info("[RTMART][inbound] queryPayment success outTradeNo={}, payStatus={}",
                    view.outTradeNo(), view.payStatus());
            return RtmartResponse.success(data);
        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] queryPayment failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] queryPayment unexpected error", exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    @PostMapping("/payment/cancel")
    @PermitAll
    public RtmartResponse cancelPayment(@RequestBody RtmartCancelPaymentRequest request) {
        Map<String, String> signFields = buildCancelPaymentSignFields(request);
        RtmartResponse error = validateProtocol(request.getAppKey(), request.getVersion(),
                request.getTimestamp(), request.getNonceStr(), request.getSign(), signFields);
        if (error != null) {
            return error;
        }

        error = validateRequired(
                "trade_id", request.getTradeId(),
                "user_code", request.getUserCode(),
                "company_code", request.getCompanyCode()
        );
        if (error != null) {
            return error;
        }

        log.info("[RTMART][inbound] cancelPayment entry tradeId={}", request.getTradeId());

        try {
            CancelPaymentTicket ticket = commandFacade.cancelPaymentFlow(
                    new CancelPaymentCommand(properties.getProjectId(),
                            properties.getChannelCode(), request.getTradeId(),
                            "rtmart:" + request.getNonceStr()));

            RtmartCancelPaymentData data = new RtmartCancelPaymentData();
            data.setOutTradeNo(ticket.outTradeNo());

            log.info("[RTMART][inbound] cancelPayment success outTradeNo={}, convertedToRefund={}",
                    ticket.outTradeNo(), ticket.convertedToRefund());
            return RtmartResponse.success(data);
        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] cancelPayment failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] cancelPayment unexpected error", exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    @PostMapping("/refund/apply")
    @PermitAll
    public RtmartResponse acceptRefund(@RequestBody RtmartAcceptRefundRequest request) {
        Map<String, String> signFields = buildAcceptRefundSignFields(request);
        RtmartResponse error = validateProtocol(request.getAppKey(), request.getVersion(),
                request.getTimestamp(), request.getNonceStr(), request.getSign(), signFields);
        if (error != null) {
            return error;
        }

        if (request.getRefundFee() == null || request.getRefundFee() <= 0) {
            log.warn("[RTMART][inbound] acceptRefund invalid refundFee={}", request.getRefundFee());
            return RtmartResponse.fail(INVALID_ARGUMENT, "退款金额必须大于0");
        }

        error = validateRequired(
                "order_id", request.getOrderId(),
                "trade_id", request.getTradeId(),
                "refund_bn", request.getRefundBn(),
                "user_code", request.getUserCode(),
                "company_code", request.getCompanyCode()
        );
        if (error != null) {
            return error;
        }

        log.info("[RTMART][inbound] acceptRefund entry refundBn={}, tradeId={}, refundFee={}",
                request.getRefundBn(), request.getTradeId(), request.getRefundFee());

        try {
            RefundAcceptance acceptance = commandFacade.acceptRefund(
                    new AcceptRefundCommand(properties.getProjectId(),
                            properties.getChannelCode(),
                            request.getRefundBn(), request.getTradeId(),
                            "CHANNEL_REFUND", request.getRefundFee(),
                            "rtmart:" + request.getNonceStr()));

            RtmartAcceptRefundData data = new RtmartAcceptRefundData();
            data.setOutTradeNo(acceptance.outTradeNo());
            data.setOutRefundNo(acceptance.refundCaseNo());
            data.setRefundStatus(mapRefundStatus(InstoreRefundStatus.fromCode(acceptance.refundStatus())));
            data.setRefundFee(request.getRefundFee());
            data.setRefundTime(currentEpochSeconds());
            data.setRefuseReason("");

            log.info("[RTMART][inbound] acceptRefund success refundCaseNo={}, refundStatus={}",
                    acceptance.refundCaseNo(), data.getRefundStatus());
            return RtmartResponse.success(data);
        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] acceptRefund failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] acceptRefund unexpected error", exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    @PostMapping("/refund/query")
    @PermitAll
    public RtmartResponse queryRefund(@RequestBody RtmartQueryRefundRequest request) {
        Map<String, String> signFields = buildQueryRefundSignFields(request);
        RtmartResponse error = validateProtocol(request.getAppKey(), request.getVersion(),
                request.getTimestamp(), request.getNonceStr(), request.getSign(), signFields);
        if (error != null) {
            return error;
        }

        error = validateRequired(
                "trade_id", request.getTradeId(),
                "refund_bn", request.getRefundBn(),
                "user_code", request.getUserCode(),
                "company_code", request.getCompanyCode()
        );
        if (error != null) {
            return error;
        }

        log.info("[RTMART][inbound] queryRefund entry refundBn={}", request.getRefundBn());

        try {
            RefundStatusView view = commandFacade.queryAndConvergeRefund(
                    new QueryRefundCommand(properties.getProjectId(),
                            properties.getChannelCode(), request.getRefundBn(),
                            "rtmart:" + request.getNonceStr()));

            RtmartQueryRefundData data = new RtmartQueryRefundData();
            data.setOutTradeNo(view.outTradeNo());
            data.setOutRefundNo(view.refundCaseNo());
            InstoreRefundStatus refundStatus = InstoreRefundStatus.fromCode(view.status());
            data.setRefundStatus(mapRefundStatus(refundStatus));
            data.setRefundFee(refundResponseAmount(refundStatus, view));
            data.setRefundTime(resolveRefundTime(view.refundTime()));
            data.setRefuseReason(emptyIfBlank(view.refuseReason()));

            log.info("[RTMART][inbound] queryRefund success refundCaseNo={}, status={}",
                    view.refundCaseNo(), view.status());
            return RtmartResponse.success(data);
        } catch (ServiceException exception) {
            log.warn("[RTMART][inbound] queryRefund failed, code={}, message={}",
                    exception.getCode(), exception.getMessage());
            return mapServiceException(exception);
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] queryRefund unexpected error", exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        }
    }

    private RtmartResponse validateProtocol(String appKey, String version, Long timestamp,
            String nonceStr, String sign, Map<String, String> signFields) {
        if (!properties.isEnabled()) {
            log.warn("[RTMART][inbound] adapter disabled");
            return RtmartResponse.fail(SYSTEM_ERROR, "服务未开启");
        }

        if (isBlank(sign)) {
            log.warn("[RTMART][inbound] signature missing");
            return RtmartResponse.fail(INVALID_SIGNATURE, "签名不能为空");
        }

        if (isBlank(appKey) || isBlank(version) || timestamp == null || isBlank(nonceStr)) {
            log.warn("[RTMART][inbound] missing required fields");
            return RtmartResponse.fail(INVALID_ARGUMENT, "请求参数缺失");
        }

        if (!properties.getAppKey().equals(appKey)) {
            log.warn("[RTMART][inbound] app_key mismatch");
            return RtmartResponse.fail(INVALID_APP_KEY, "app_key 不正确");
        }

        long nowSeconds = System.currentTimeMillis() / 1000;
        long tolerance = properties.getTimestampToleranceSeconds();
        if (timestamp < nowSeconds - tolerance || timestamp > nowSeconds + tolerance) {
            log.warn("[RTMART][inbound] timestamp out of tolerance, requestTs={}, serverTs={}",
                    timestamp, nowSeconds);
            return RtmartResponse.fail(REQUEST_EXPIRED, "请求已过期");
        }

        if (!RtmartSignVerifier.verify(signFields, sign, verifyKey)) {
            log.warn("[RTMART][inbound] signature verification failed, nonceHash={}", nonceHashPrefix(nonceStr));
            return RtmartResponse.fail(INVALID_SIGNATURE, "签名验证失败");
        }

        String nonceHash = sha256Hex(appKey + ":" + nonceStr);
        String nonceKey = "rtmart:nonce:" + nonceHash;
        try {
            if (redisTemplate == null) {
                // Spring 管理的启用态会在初始化阶段失败；保留运行期防线，避免手工构造绕过校验。
                log.error("[RTMART][inbound] nonce store bean unavailable, nonceHash={}",
                        nonceHashPrefix(nonceStr));
                return RtmartResponse.fail(SYSTEM_ERROR, "服务暂时不可用");
            }
            Boolean consumed = redisTemplate.opsForValue().setIfAbsent(nonceKey, "1",
                    Duration.ofSeconds(properties.getTimestampToleranceSeconds() * 2L));
            if (!Boolean.TRUE.equals(consumed)) {
                if (consumed == null) {
                    log.error("[RTMART][inbound] nonce store returned null, nonceHash={}", nonceHashPrefix(nonceStr));
                    return RtmartResponse.fail(SYSTEM_ERROR, "服务暂时不可用");
                }
                log.warn("[RTMART][inbound] nonce replay detected, nonceHash={}", nonceHashPrefix(nonceStr));
                return RtmartResponse.fail(INVALID_ARGUMENT, "请求已处理，请勿重复提交");
            }
        } catch (RuntimeException exception) {
            log.error("[RTMART][inbound] nonce store unavailable, nonceHash={}",
                    nonceHashPrefix(nonceStr), exception);
            return RtmartResponse.fail(SYSTEM_ERROR, "服务暂时不可用");
        }

        return null;
    }

    /** 将内部 ServiceException 映射为旅通协议错误码。 */
    private static RtmartResponse mapServiceException(ServiceException exception) {
        Integer code = exception.getCode();
        RtmartResponse response = code != null && CALLER_CORRECTABLE_CODES.contains(code)
                ? RtmartResponse.fail(INVALID_ARGUMENT, "请求参数或业务状态无效")
                : RtmartResponse.fail(SYSTEM_ERROR, "系统错误");
        log.warn("[RTMART][inbound] ServiceException mapped internalCode={} -> rtmartCode={}",
                code, response.getCode());
        return response;
    }

    /** 校验业务必填字段：String 检查非空非 blank，其他类型检查非 null。 */
    private RtmartResponse validateRequired(Object... fieldNameValuePairs) {
        for (int i = 0; i < fieldNameValuePairs.length; i += 2) {
            String name = (String) fieldNameValuePairs[i];
            Object value = fieldNameValuePairs[i + 1];
            if (value == null || (value instanceof String s && s.isBlank())) {
                log.warn("[RTMART][inbound] missing required field: {}", name);
                return RtmartResponse.fail(INVALID_ARGUMENT, "请求参数缺失: " + name);
            }
        }
        return null;
    }

    private Map<String, String> buildCreateOrderSignFields(RtmartCreateOrderRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", request.getTimestamp() != null ? String.valueOf(request.getTimestamp()) : null);
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        fields.put("total_amount", request.getTotalAmount() != null ? String.valueOf(request.getTotalAmount()) : null);
        fields.put("order_time", request.getOrderTime() != null ? String.valueOf(request.getOrderTime()) : null);
        fields.put("order_type", request.getOrderType() != null ? String.valueOf(request.getOrderType()) : null);
        fields.put("shop_code", request.getShopCode());
        fields.put("shop_name", request.getShopName());
        fields.put("dynamic_code", request.getDynamicCode());
        return fields;
    }

    private Map<String, String> buildQueryPaymentSignFields(RtmartQueryPaymentRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", request.getTimestamp() != null ? String.valueOf(request.getTimestamp()) : null);
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return fields;
    }

    private Map<String, String> buildCancelPaymentSignFields(RtmartCancelPaymentRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", request.getTimestamp() != null ? String.valueOf(request.getTimestamp()) : null);
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return fields;
    }

    private Map<String, String> buildAcceptRefundSignFields(RtmartAcceptRefundRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", request.getTimestamp() != null ? String.valueOf(request.getTimestamp()) : null);
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("refund_bn", request.getRefundBn());
        fields.put("refund_fee", request.getRefundFee() != null ? String.valueOf(request.getRefundFee()) : null);
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return fields;
    }

    private Map<String, String> buildQueryRefundSignFields(RtmartQueryRefundRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_key", request.getAppKey());
        fields.put("version", request.getVersion());
        fields.put("timestamp", request.getTimestamp() != null ? String.valueOf(request.getTimestamp()) : null);
        fields.put("nonce_str", request.getNonceStr());
        fields.put("order_id", request.getOrderId());
        fields.put("trade_id", request.getTradeId());
        fields.put("refund_bn", request.getRefundBn());
        fields.put("user_code", request.getUserCode());
        fields.put("company_code", request.getCompanyCode());
        return fields;
    }

    /**
     * 中台流水状态映射为旅通协议 pay_status。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
     */
    private static int mapPaymentStatus(short internalStatus) {
        if (internalStatus == InstorePaymentFlowStatus.PAID.getCode()) {
            return RtmartPayStatus.SUCCESS.code();
        }
        if (internalStatus == InstorePaymentFlowStatus.CANCELLED.getCode()) {
            return RtmartPayStatus.CANCELLED.code();
        }
        return RtmartPayStatus.PENDING.code();
    }

    /**
     * 旅通接口③ pay_fee：Integer，单位分，未支付也要填。
     *
     * <p>取中台流水建单金额 {@code pay_amount}，与 HTML「支付金额，单位：分」一致。
     * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
     */
    private static Integer mapPayFee(long payAmount) {
        if (payAmount < 0 || payAmount > Integer.MAX_VALUE) {
            throw new IllegalStateException("pay_fee is out of Integer range");
        }
        return (int) payAmount;
    }

    /**
     * 旅通接口③ pay_time：Integer，单位秒。未支付/取消填 0。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
     */
    private static Integer mapPayTime(int payStatus, Long payTime) {
        if (payStatus != RtmartPayStatus.SUCCESS.code()) {
            return UNPAID_PAY_TIME_SECONDS;
        }
        if (payTime == null || payTime <= 0 || payTime > Integer.MAX_VALUE) {
            throw new IllegalStateException("successful payment is missing pay_time");
        }
        return payTime.intValue();
    }

    /**
     * 旅通 Word：transaction_id 为支付流水号（可为空，支付成功时必传）。
     *
     * <p>待支付/取消输出空字符串，键必须保留；支付成功必须回收银台 {@code externalTransactionId}，
     * 缺失则失败关闭。详见
     * {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
     */
    private static String mapTransactionId(int payStatus, String externalTransactionId) {
        if (payStatus == RtmartPayStatus.SUCCESS.code()) {
            if (isBlank(externalTransactionId)) {
                throw new IllegalStateException("successful payment is missing transaction_id");
            }
            return externalTransactionId;
        }
        return isBlank(externalTransactionId) ? "" : externalTransactionId;
    }

    /**
     * 旅通 Word：{@code refund_time} 为 Integer Unix 秒；不传则取接口调用当前时间。
     *
     * <p>详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#82-申请退款--查询退款成功-data-字段始终输出}。</p>
     */
    private static int resolveRefundTime(Long refundTime) {
        if (refundTime == null) {
            return currentEpochSeconds();
        }
        return refundTime.intValue();
    }

    private static int currentEpochSeconds() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    /** 旅通 Word：{@code refuse_reason} 没有时填空字符串，禁止 JSON null。 */
    private static String emptyIfBlank(String value) {
        return isBlank(value) ? "" : value;
    }

    private static int mapRefundStatus(InstoreRefundStatus status) {
        return switch (status) {
            case SUCCESS, COMPLETED -> 1;
            case FAILED -> 2;
            default -> 0;
        };
    }

    private static Long refundResponseAmount(InstoreRefundStatus status, RefundStatusView view) {
        return switch (status) {
            case SUCCESS, COMPLETED -> {
                if (view.buyerActualRefundAmount() == null) {
                    throw new IllegalStateException("successful refund is missing buyer actual refund amount");
                }
                yield view.buyerActualRefundAmount();
            }
            case FAILED, MANUAL -> null;
            default -> view.channelRefundAmount();
        };
    }

    private static String nonceHashPrefix(String nonce) {
        return sha256Hex(nonce == null ? "" : nonce).substring(0, 12);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
