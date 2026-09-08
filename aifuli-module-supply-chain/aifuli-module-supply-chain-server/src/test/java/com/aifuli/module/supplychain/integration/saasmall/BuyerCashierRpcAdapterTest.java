package com.aifuli.module.supplychain.integration.saasmall;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.sso.MemberSsoTokenApi;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SaaS H5 收银台适配器单测：验证 SSO 三元身份透传、URL 编码、租户恢复与日志脱敏。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#6-测试</p>
 */
@ExtendWith(MockitoExtension.class)
class BuyerCashierRpcAdapterTest {

    private static final String TOKEN = "token+/=&? secret-sentinel";
    private static final String BUYER_USER_ID = "buyer-secret-sentinel";
    private static final String OUT_TRADE_NO = "SC/order 001";
    private static final String LEGACY_DESCRIPTOR = "stub://cashier/launch";

    @Mock private MemberSsoTokenApi memberSsoTokenApi;

    private BuyerCashierProperties properties;
    private BuyerCashierRpcAdapter adapter;
    private Long savedTenantId;
    private Boolean savedIgnore;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        savedTenantId = TenantContextHolder.getTenantId();
        savedIgnore = TenantContextHolder.isIgnore();
        properties = new BuyerCashierProperties();
        properties.setH5BaseUrl("https://mall.example.com/");
        adapter = new BuyerCashierRpcAdapter(memberSsoTokenApi, properties, LEGACY_DESCRIPTOR);
        logger = (Logger) LoggerFactory.getLogger(BuyerCashierRpcAdapter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        TenantContextHolder.setTenantId(savedTenantId);
        TenantContextHolder.setIgnore(savedIgnore);
    }

    @Test
    void createsEncodedH5UrlAndPassesOpaqueIdentityVerbatim() {
        when(memberSsoTokenApi.createToken(any())).thenAnswer(invocation -> {
            MemberSsoTokenReqDTO request = invocation.getArgument(0);
            assertThat(request.getSourceSystem()).isEqualTo("AIFULI_MALL");
            assertThat(request.getExternalTenantId()).isEqualTo("100");
            assertThat(request.getBuyerUserId()).isEqualTo(BUYER_USER_ID);
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(100L);
            assertThat(TenantContextHolder.isIgnore()).isFalse();
            return CommonResult.success(token(TOKEN));
        });

        BuyerCashierPort.CreateCashierOrderResult result = adapter.createCashierOrder(command(validRoute()));

        assertThat(result.status()).isEqualTo(BuyerCashierPort.CashierOrderStatus.CREATED);
        assertThat(result.cashierOrderNo()).isEqualTo(OUT_TRADE_NO);
        assertThat(result.confirmedAmountCny()).isNull();
        URI cashierUri = URI.create(result.launchDescriptor());
        assertThat(cashierUri.getScheme()).isEqualTo("https");
        assertThat(cashierUri.getHost()).isEqualTo("mall.example.com");
        assertThat(cashierUri.getPath()).isEqualTo("/h5/tbsg-cashier.html");
        assertThat(decodedQuery(cashierUri)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "orderNo", OUT_TRADE_NO,
                "orderPrice", "123.45",
                "token", TOKEN,
                "source", "rtmart"));
        assertLogsAreSafe(result.launchDescriptor());
    }

    @Test
    void restoresPreviousTenantContextAfterSuccessAndFailure() {
        TenantContextHolder.setTenantId(999L);
        TenantContextHolder.setIgnore(true);
        when(memberSsoTokenApi.createToken(any())).thenReturn(CommonResult.success(token(TOKEN)));
        adapter.createCashierOrder(command(validRoute()));
        assertThat(TenantContextHolder.getTenantId()).isEqualTo(999L);
        assertThat(TenantContextHolder.isIgnore()).isTrue();

        RuntimeException failure = new RuntimeException("rpc-secret-sentinel");
        when(memberSsoTokenApi.createToken(any())).thenThrow(failure);
        assertThatThrownBy(() -> adapter.createCashierOrder(command(validRoute()))).isSameAs(failure);
        assertThat(TenantContextHolder.getTenantId()).isEqualTo(999L);
        assertThat(TenantContextHolder.isIgnore()).isTrue();
        assertLogsAreSafe(null);
    }

    @Test
    void rejectsUnsupportedRouteAndInvalidTenantBeforeRpc() {
        BuyerIntegrationRoute unsupported = route("OTHER", "100", "RPC", "SAAS_CASHIER_RPC", "v1");
        assertThatThrownBy(() -> adapter.createCashierOrder(command(unsupported)))
                .isInstanceOf(IllegalStateException.class).hasMessage("unsupported cashier route");

        BuyerIntegrationRoute invalidTenant = route("AIFULI_MALL", "0100", "RPC", "SAAS_CASHIER_RPC", "v1");
        assertThatThrownBy(() -> adapter.createCashierOrder(command(invalidTenant)))
                .isInstanceOf(IllegalStateException.class).hasMessage("invalid cashier tenant route");
        verifyNoInteractions(memberSsoTokenApi);
    }

    @Test
    void failsClosedForMissingOrInvalidH5Configuration() {
        properties.setH5BaseUrl(" ");
        assertThatThrownBy(() -> adapter.createCashierOrder(command(validRoute())))
                .isInstanceOf(IllegalStateException.class).hasMessage("cashier H5 base URL is not configured");

        properties.setH5BaseUrl("javascript:alert(1)");
        assertThatThrownBy(() -> adapter.createCashierOrder(command(validRoute())))
                .isInstanceOf(IllegalStateException.class).hasMessage("cashier H5 base URL is invalid");

        properties.setH5BaseUrl("https://mall.example.com");
        properties.setH5Path("relative/page");
        assertThatThrownBy(() -> adapter.createCashierOrder(command(validRoute())))
                .isInstanceOf(IllegalStateException.class).hasMessage("cashier H5 path is invalid");
        verifyNoInteractions(memberSsoTokenApi);
    }

    @Test
    void failsClosedForEmptyErrorOrNullSsoResponse() {
        when(memberSsoTokenApi.createToken(any()))
                .thenReturn(null)
                .thenReturn(CommonResult.error(1_004_001_005, "route invalid"))
                .thenReturn(CommonResult.success(null))
                .thenReturn(CommonResult.success(token(" ")));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> adapter.createCashierOrder(command(validRoute())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void keepsPendingRefundAndLegacyStubSemanticsUntilLaterMilestone() {
        assertThat(adapter.queryPayment(new BuyerCashierPort.QueryPaymentCommand(validRoute(), OUT_TRADE_NO)).status())
                .isEqualTo(BuyerCashierPort.PaymentQueryStatus.PENDING);
        assertThat(adapter.refund(new BuyerCashierPort.RefundCommand(validRoute(), "I1", "R1", OUT_TRADE_NO, 100L)).status())
                .isEqualTo(BuyerCashierPort.RefundStatus.PROCESSING);
        assertThat(adapter.queryRefund(new BuyerCashierPort.QueryRefundCommand(validRoute(), "R1")).status())
                .isEqualTo(BuyerCashierPort.RefundQueryStatus.PROCESSING);

        BuyerCashierPort.CreateCashierOrderResult legacy = adapter.createLegacyCashierOrder(
                new BuyerCashierPort.LegacyCreateCashierOrderCommand("I1", "O1", 100L, "subject",
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
        assertThat(legacy.cashierOrderNo()).isEqualTo("STUB-O1");
        assertThat(legacy.launchDescriptor()).isEqualTo("stub://cashier/launch?outTradeNo=O1");
        assertThat(adapter.queryLegacyPayment("O1").status())
                .isEqualTo(BuyerCashierPort.PaymentQueryStatus.PENDING);
        verifyNoInteractions(memberSsoTokenApi);
    }

    private void assertLogsAreSafe(String fullCashierUrl) {
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(TOKEN, BUYER_USER_ID, "rpc-secret-sentinel");
            if (fullCashierUrl != null) {
                assertThat(event.getFormattedMessage()).doesNotContain(fullCashierUrl);
            }
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static BuyerCashierPort.CreateCashierOrderCommand command(BuyerIntegrationRoute route) {
        return new BuyerCashierPort.CreateCashierOrderCommand(route, "INV-1", OUT_TRADE_NO,
                BUYER_USER_ID, 12_345L, "测试订单", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
    }

    private static BuyerIntegrationRoute validRoute() {
        return route("AIFULI_MALL", "100", "RPC", "SAAS_CASHIER_RPC", "v1");
    }

    private static BuyerIntegrationRoute route(String sourceSystem, String externalTenantId,
                                               String transportMode, String adapterCode, String contractVersion) {
        return new BuyerIntegrationRoute(1L, 10L, BuyerIntegrationCapabilityCode.CASHIER,
                sourceSystem, externalTenantId, transportMode, adapterCode, contractVersion, null);
    }

    private static MemberSsoTokenRespDTO token(String accessToken) {
        MemberSsoTokenRespDTO response = new MemberSsoTokenRespDTO();
        response.setAccessToken(accessToken);
        return response;
    }

    private static Map<String, String> decodedQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
