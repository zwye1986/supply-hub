package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationLogSanitizer;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

/**
 * SaaS 商城收银台 RPC 适配器：为 INSTORE 建单签发会员 SSO 并生成真实 H5 地址。
 *
 * <p>本实现不创建预支付单；{@code outTradeNo} 即收银台业务单号。访问令牌和 buyerUserId
 * 不进入日志，externalTenantId 只用于设置 member-server 的框架租户头。支付查询和退款仍按
 * 当前裁剪返回处理中状态，O2O legacy 入口保持原 stub 行为。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server}。</p>
 */
@Slf4j
public class BuyerCashierRpcAdapter implements BuyerCashierPort {

    static final String SOURCE_RTMART = "rtmart";
    private static final String LEGACY_CASHIER_ORDER_PREFIX = "STUB-";

    private final MemberSsoTokenApi memberSsoTokenApi;
    private final BuyerCashierProperties properties;
    private final String legacyStubLaunchDescriptor;

    public BuyerCashierRpcAdapter(MemberSsoTokenApi memberSsoTokenApi,
                                  BuyerCashierProperties properties,
                                  String legacyStubLaunchDescriptor) {
        this.memberSsoTokenApi = memberSsoTokenApi;
        this.properties = properties;
        this.legacyStubLaunchDescriptor = legacyStubLaunchDescriptor;
    }

    /**
     * 调用 member-server 创建会员 SSO，并把安全编码后的令牌放入商城收银台 URL。
     */
    @Override
    public CreateCashierOrderResult createCashierOrder(CreateCashierOrderCommand command) {
        BuyerIntegrationRoute route = command.route();
        long startedAt = System.nanoTime();
        String tenantDigest = BuyerIntegrationLogSanitizer.externalTenantDigest(route.externalTenantId());
        log.info("[SAAS-CASHIER][create] request projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantDigest={}, adapterCode={}, invocationId={}, outTradeNo={}, amount={}",
                route.projectId(), route.profileVersionId(), SaaSCashierRoutePolicy.safeSourceSystem(route.sourceSystem()),
                tenantDigest, SaaSCashierRoutePolicy.safeAdapterCode(route.adapterCode()),
                command.invocationId(), command.outTradeNo(), command.amountCny());

        if (!SaaSCashierRoutePolicy.supports(route)) {
            throw integrationFailure("unsupported cashier route");
        }
        Long tenantId = SaaSCashierRoutePolicy.frameworkTenantId(route.externalTenantId());
        if (tenantId == null) {
            throw integrationFailure("invalid cashier tenant route");
        }
        String targetBaseUrl = validatedBaseUrl(properties.getH5BaseUrl());
        String targetPath = validatedPath(properties.getH5Path());

        MemberSsoTokenReqDTO request = new MemberSsoTokenReqDTO();
        request.setSourceSystem(route.sourceSystem());
        request.setExternalTenantId(route.externalTenantId());
        request.setBuyerUserId(command.buyerUserId());

        CommonResult<MemberSsoTokenRespDTO> envelope;
        Long oldTenantId = TenantContextHolder.getTenantId();
        Boolean oldIgnore = TenantContextHolder.isIgnore();
        try {
            TenantContextHolder.setTenantId(tenantId);
            TenantContextHolder.setIgnore(false);
            envelope = memberSsoTokenApi.createToken(request);
        } catch (RuntimeException failure) {
            log.warn("[SAAS-CASHIER][create] failed projectId={}, profileVersionId={}, sourceSystem={}, "
                            + "externalTenantDigest={}, adapterCode={}, outTradeNo={}, errorClass={}, costMs={}",
                    route.projectId(), route.profileVersionId(),
                    SaaSCashierRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
                    SaaSCashierRoutePolicy.safeAdapterCode(route.adapterCode()), command.outTradeNo(),
                    failure.getClass().getName(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            throw failure;
        } finally {
            TenantContextHolder.setTenantId(oldTenantId);
            TenantContextHolder.setIgnore(oldIgnore);
        }
        if (envelope == null || envelope.isError()) {
            logCompletion(command, route, tenantDigest, false,
                    envelope == null ? null : envelope.getCode(), startedAt);
            throw integrationFailure("member SSO call failed");
        }
        MemberSsoTokenRespDTO tokenData = envelope.getData();
        if (tokenData == null || tokenData.getAccessToken() == null || tokenData.getAccessToken().isBlank()) {
            logCompletion(command, route, tenantDigest, false, envelope.getCode(), startedAt);
            throw integrationFailure("member SSO token is unavailable");
        }

        String cashierUrl = buildCashierUrl(targetBaseUrl, targetPath, command, tokenData.getAccessToken());
        CreateCashierOrderResult result = new CreateCashierOrderResult(CashierOrderStatus.CREATED,
                command.outTradeNo(), cashierUrl, null, null, OffsetDateTime.now(ZoneOffset.UTC));
        logCompletion(command, route, tenantDigest, true, envelope.getCode(), startedAt);
        return result;
    }

    /** 真实支付查询尚未接入，保持原 stub 的 PENDING 语义。 */
    @Override
    public QueryPaymentResult queryPayment(QueryPaymentCommand command) {
        log.debug("[SAAS-CASHIER][query-payment] pending projectId={}, profileVersionId={}, outTradeNo={}",
                command.route().projectId(), command.route().profileVersionId(), command.outTradeNo());
        return pendingPaymentResult();
    }

    /** 真实退款尚未接入，保持原 stub 的 PROCESSING 语义。 */
    @Override
    public RefundResult refund(RefundCommand command) {
        log.debug("[SAAS-CASHIER][refund] processing projectId={}, profileVersionId={}, refundCaseNo={}, amount={}",
                command.route().projectId(), command.route().profileVersionId(),
                command.refundCaseNo(), command.amountCny());
        return new RefundResult(RefundStatus.PROCESSING, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 真实退款查询尚未接入，保持原 stub 的 PROCESSING 语义。 */
    @Override
    public QueryRefundResult queryRefund(QueryRefundCommand command) {
        log.debug("[SAAS-CASHIER][query-refund] processing projectId={}, profileVersionId={}, refundCaseNo={}",
                command.route().projectId(), command.route().profileVersionId(), command.refundCaseNo());
        return new QueryRefundResult(RefundQueryStatus.PROCESSING, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** O2O 未迁移采购项目档案前继续返回原 stub 地址，不签发会员令牌。 */
    @Deprecated
    @Override
    public CreateCashierOrderResult createLegacyCashierOrder(LegacyCreateCashierOrderCommand command) {
        log.info("[O2O][cashier-legacy] create invocationId={}, outTradeNo={}, amount={}, subject={}",
                command.invocationId(), command.outTradeNo(), command.amountCny(), command.subject());
        return new CreateCashierOrderResult(CashierOrderStatus.CREATED,
                LEGACY_CASHIER_ORDER_PREFIX + command.outTradeNo(),
                legacyStubLaunchDescriptor + "?outTradeNo=" + command.outTradeNo(),
                command.amountCny(), null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** O2O 未迁移采购项目档案前继续返回原 PENDING 查询结果。 */
    @Deprecated
    @Override
    public QueryPaymentResult queryLegacyPayment(String outTradeNo) {
        log.debug("[O2O][cashier-legacy] query-payment pending outTradeNo={}", outTradeNo);
        return pendingPaymentResult();
    }

    private static String buildCashierUrl(String baseUrl, String path,
                                          CreateCashierOrderCommand command, String accessToken) {
        String orderPrice = BigDecimal.valueOf(command.amountCny(), 2)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        return baseUrl + path
                + "?orderNo=" + encode(command.outTradeNo())
                + "&orderPrice=" + encode(orderPrice)
                + "&token=" + encode(accessToken)
                + "&source=" + encode(SOURCE_RTMART);
    }

    private static String validatedBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw integrationFailure("cashier H5 base URL is not configured");
        }
        String baseUrl = configuredBaseUrl.strip();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw integrationFailure("cashier H5 base URL is invalid");
            }
            return baseUrl;
        } catch (IllegalArgumentException invalidUri) {
            throw integrationFailure("cashier H5 base URL is invalid");
        }
    }

    private static String validatedPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw integrationFailure("cashier H5 path is not configured");
        }
        String path = configuredPath.strip();
        if (!path.startsWith("/") || path.contains("?") || path.contains("#")
                || path.chars().anyMatch(Character::isWhitespace)) {
            throw integrationFailure("cashier H5 path is invalid");
        }
        return path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private QueryPaymentResult pendingPaymentResult() {
        return new QueryPaymentResult(PaymentQueryStatus.PENDING, null,
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void logCompletion(CreateCashierOrderCommand command, BuyerIntegrationRoute route,
                               String tenantDigest, boolean hasLaunchDescriptor,
                               Integer remoteCode, long startedAt) {
        log.info("[SAAS-CASHIER][create] response projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantDigest={}, adapterCode={}, outTradeNo={}, status={}, "
                        + "hasLaunchDescriptor={}, remoteCode={}, costMs={}",
                route.projectId(), route.profileVersionId(), SaaSCashierRoutePolicy.safeSourceSystem(route.sourceSystem()),
                tenantDigest, SaaSCashierRoutePolicy.safeAdapterCode(route.adapterCode()), command.outTradeNo(),
                hasLaunchDescriptor ? CashierOrderStatus.CREATED : CashierOrderStatus.UNKNOWN,
                hasLaunchDescriptor, remoteCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static IllegalStateException integrationFailure(String message) {
        return new IllegalStateException(message);
    }
}
