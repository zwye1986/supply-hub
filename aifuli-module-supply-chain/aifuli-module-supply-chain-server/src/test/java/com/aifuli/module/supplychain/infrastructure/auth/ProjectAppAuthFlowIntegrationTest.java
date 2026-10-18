package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.SupplyChainServerApplication;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAppCredentialAdminService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目应用认证真实 HTTP 门禁：完整应用、Spring Security、真实 Filter/Accessor/Facade、Liquibase
 * 与 MySQL 共同参与，不以 MockMvc、测试上下文 Bean 或固定本地端口替代生产装配。
 *
 * <p>Testcontainers {@code mysql:8.4} 是发布基线；无 Docker 时可通过
 * {@code supply-chain.test.jdbc-*} 指向本测试独占的外部空 MySQL 库。合法签名分别穿透四组 API，
 * 以确定的业务错误证明三型可信 Context；认证拒绝同时校验 HTTP/CommonResult 契约和 nonce 写入顺序。
 * 详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁}。</p>
 */
class ProjectAppAuthFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final GenericContainer<?> ORDER_REDIS = new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379);
    private static RedissonClient orderRedisClient;

    /** 测试持有的原始主密钥字节；正常结束与异常启动均清零。 */
    private static final byte[] MASTER_KEY = Base64.getDecoder()
            .decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=");

    private static final long BUYER_ID = 9_300_001L;
    private static final long PROJECT_ID = 9_310_001L;
    private static final long CAPABILITY_ID = 9_320_001L;
    private static final long STANDARD_CAPABILITY_ID = 9_320_002L;
    private static final long APPLICATION_ID = 9_330_001L;
    private static final long CURRENT_CREDENTIAL_ID = 9_340_001L;
    private static final long GRACE_CREDENTIAL_ID = 9_340_002L;
    private static final long REVOKED_CREDENTIAL_ID = 9_340_003L;

    private static final int CURRENT_KEY_VERSION = 1;
    private static final int GRACE_KEY_VERSION = 2;
    private static final int REVOKED_KEY_VERSION = 3;
    private static final int ENABLED_STATUS = 10;
    private static final int PAUSED_STATUS = 20;

    private static final String APP_ID = "project-auth-flow-it";
    private static final long ISSUANCE_PROJECT_ID = 9_310_002L;
    private static final String ISSUANCE_PROJECT_CODE = "PROJECT_APP_ISSUANCE_IT";
    private static final String APP_KEY = "project-auth-flow-app-key";
    private static final String CHANNEL_CODE = "RT_MART";
    /** 数字外观且超出 long 表示范围，必须作为不透明字符串原样进入 BuyerContext。 */
    private static final String OPAQUE_BUYER_USER_ID = "000123456789012345678901234567890123456789";

    private static final String CREATE_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String MEMBER_PATH =
            "/rpc-api/supply-chain/instore/channel-member/resolve";
    private static final String HOME_PATH =
            "/rpc-api/supply-chain/instore/buyer-entry/home";
    private static final String PAYMENT_CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";
    private static final String UNKNOWN_CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/unknownOperation";

    private static final RestTemplate REST = new RestTemplate();
    private static final AtomicLong UNIQUE_SEQUENCE = new AtomicLong();

    private static ConfigurableApplicationContext server;
    private static ObjectMapper objectMapper;
    private static JdbcTemplate jdbc;
    private static String baseUrl;

    /**
     * 以高优先级命令行参数启动完整 Web 应用；禁止使用 builder.properties 覆盖端口，因为其优先级
     * 低于 application.yaml 的 58190。启动中任一步失败也必须关闭已创建资源并清零主密钥字节。
     */
    @BeforeAll
    static void startDatabaseAndServer() throws Throwable {
        try {
            if (EXTERNAL_JDBC_URL == null) {
                MYSQL.start();
            }
            ORDER_REDIS.start();
            Config redisConfig = new Config();
            redisConfig.useSingleServer().setAddress(
                    "redis://" + ORDER_REDIS.getHost() + ":" + ORDER_REDIS.getMappedPort(6379));
            orderRedisClient = Redisson.create(redisConfig);
            server = new SpringApplicationBuilder(SupplyChainServerApplication.class, IdentityKeyTestConfiguration.class)
                    // 完整进程只扫描生产组件，不让其它测试的裸@Configuration污染本门禁。
                    .initializers(context -> context.getBeanFactory().registerSingleton("authFixtureTypeExcludeFilter",
                            new org.springframework.boot.context.TypeExcludeFilter() {
                                @Override
                                public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                                     org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                                    return reader.getResource().toString().contains("/test-classes/");
                                }
                            }))
                    .run(commandLineArguments());
            assertThat(server).isInstanceOf(WebServerApplicationContext.class);
            WebServerApplicationContext webServer = (WebServerApplicationContext) server;
            int actualPort = webServer.getWebServer().getPort();
            assertThat(actualPort).isPositive().isNotEqualTo(58_190);
            baseUrl = "http://127.0.0.1:" + actualPort;

            DataSource dataSource = server.getBean("supplyChainDataSource", DataSource.class);
            jdbc = new JdbcTemplate(dataSource);
            objectMapper = server.getBean(ObjectMapper.class);
            String mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
            assertThat(mysqlVersion).isNotBlank();
            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            }
            System.out.println("[ProjectAppAuthFlowIntegrationTest] MySQL version=" + mysqlVersion);
            seedAuthenticationFixtures();
        } catch (Throwable startupFailure) {
            try {
                cleanupResources();
            } catch (Throwable cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
    }

    @AfterAll
    static void stopDatabaseAndServer() {
        cleanupResources();
    }

    /** 真实 MySQL 验证开通→轮换→停用期间吊销→恢复启用后仍拒绝旧密钥；见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#32-管理服务。 */
    @Test
    void projectCredentialLifecyclePersistsVersionSlotsAndAudit() {
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, ?, '项目发码联调', ?, 'MANAGED', 10, 0, NOW(3), NOW(3))
                """, ISSUANCE_PROJECT_ID, ISSUANCE_PROJECT_CODE, BUYER_ID);
        ProjectAppCredentialAdminService admin = server.getBean(ProjectAppCredentialAdminService.class);

        var issued = admin.enable(ISSUANCE_PROJECT_CODE, 99L);
        assertThat(issued.keyVersion()).isEqualTo(1);
        assertThat(issued.appKey()).hasSize(43);
        String storedSecretRef = jdbc.queryForObject("""
                SELECT c.secret_ref FROM sc_project_app_credential_version c
                JOIN sc_project_application a ON a.id = c.application_id
                WHERE a.project_id = ? AND c.key_version = 1
                """, String.class, ISSUANCE_PROJECT_ID);
        assertThat(storedSecretRef).doesNotContain(issued.appKey());
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, issued.appId(), 1, storedSecretRef))
                .isEqualTo(issued.appKey());

        var rotated = admin.rotate(ISSUANCE_PROJECT_CODE, 99L);
        assertThat(rotated.appId()).isEqualTo(issued.appId());
        assertThat(rotated.keyVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sc_project_app_credential_version c
                JOIN sc_project_application a ON a.id = c.application_id
                WHERE a.project_id = ? AND c.active_slot IN ('CURRENT', 'GRACE')
                """, Integer.class, ISSUANCE_PROJECT_ID)).isEqualTo(2);

        jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", PAUSED_STATUS, ISSUANCE_PROJECT_ID);
        admin.revoke(ISSUANCE_PROJECT_CODE, true, "停用期间联调吊销", 99L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sc_project_app_credential_version c
                JOIN sc_project_application a ON a.id = c.application_id
                WHERE a.project_id = ? AND c.active_slot IS NOT NULL
                """, Integer.class, ISSUANCE_PROJECT_ID)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_project_app_credential_audit WHERE project_id = ?",
                Integer.class, ISSUANCE_PROJECT_ID)).isEqualTo(3);
        jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ENABLED_STATUS, ISSUANCE_PROJECT_ID);
        ProjectAppCredentialService credentialService = server.getBean(ProjectAppCredentialService.class);
        assertThatThrownBy(() -> credentialService.authenticate(issued.appId(), issued.keyVersion()))
                .hasFieldOrPropertyWithValue("code", BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> credentialService.authenticate(rotated.appId(), rotated.keyVersion()))
                .hasFieldOrPropertyWithValue("code", BUYER_AUTH_INVALID.getCode());
    }

    /** ChannelContext：合法命令必须越过认证与 Adapter，到达未配置渠道用户绑定的业务分支。 */
    @Test
    void signedChannelCommandInjectsChannelContextIntoBusinessLayer() {
        String nonce = unique("nonce-channel-command");

        CommonResult<?> result = postSigned(CREATE_PATH, createPaymentBody(),
                channelCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
        assertNonceRows(nonce, 1L);
    }

    /** ChannelContext：会员入口使用合法 DTO；随机 token 应在真实凭证服务返回稳定业务错误。 */
    @Test
    void signedChannelMemberInjectsChannelContextIntoBusinessLayer() {
        ResolveInstoreMemberReqDTO request = new ResolveInstoreMemberReqDTO();
        request.setToken(unique("credential-token"));
        String nonce = unique("nonce-channel-member");

        CommonResult<?> result = postSigned(MEMBER_PATH, json(request),
                channelCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(INSTORE_CREDENTIAL_INVALID.getCode());
        assertNonceRows(nonce, 1L);
    }

    /** BuyerContext：数字外观 buyerUserId 不做 Long 转换，业务层按字符串身份继续解析项目商户绑定。 */
    @Test
    void signedBuyerEntryInjectsOpaqueBuyerContextIntoBusinessLayer() {
        HomeEntryReqDTO request = new HomeEntryReqDTO();
        request.setLongitude("121.4737");
        request.setLatitude("31.2304");
        String nonce = unique("nonce-buyer-entry");

        CommonResult<?> result = postSigned(HOME_PATH, json(request),
                buyerCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND.getCode());
        assertNonceRows(nonce, 1L);
    }

    /** ProjectContext：无 scope 的 SaaS 收银台支付回调按 outTradeNo 到业务层查找支付流水。 */
    @Test
    void signedPaymentCallbackInjectsProjectContextIntoBusinessLayer() {
        ConfirmPaymentReqDTO request = new ConfirmPaymentReqDTO();
        request.setOutTradeNo(unique("OUT-TRADE"));
        request.setActualAmountCny(10_000L);
        request.setCashierOrderNo(unique("CASHIER-ORDER"));
        request.setCashierTransactionNo(unique("CASHIER-TXN"));
        String nonce = unique("nonce-payment-callback");

        CommonResult<?> result = postSigned(PAYMENT_CALLBACK_PATH, json(request),
                projectCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(INSTORE_PAYMENT_FLOW_NOT_FOUND.getCode());
        assertNonceRows(nonce, 1L);
    }

    /**
     * 注册表十三个 operation 均先由 HMAC Filter 失败关闭，证明配置挂载覆盖精确路径。
     * 标准 Controller 与 Security 放行属于后续阶段，本测试不以 404 证明业务已开放。
     */
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("registeredOperations")
    void everyRegisteredOperationWithoutAuthenticationIsProtected(ProjectAuthOperations.Operation operation) {
        CommonResult<?> result = post(
                operation.path(), "{}".getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    /** 有效签名只允许同 appId/nonce 首次进入业务；重放失败且数据库保持一行。 */
    @Test
    void replayedNonceIsRejectedAfterFirstBusinessResponse() {
        byte[] body = createPaymentBody();
        String nonce = unique("nonce-replay");
        long timestamp = System.currentTimeMillis();

        CommonResult<?> first = postSigned(CREATE_PATH, body,
                channelCredentials(CURRENT_KEY_VERSION), timestamp, nonce);
        CommonResult<?> replay = postSigned(CREATE_PATH, body,
                channelCredentials(CURRENT_KEY_VERSION), timestamp, nonce);

        assertThat(first.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
        assertThat(replay.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 1L);
    }

    /** body 摘要被篡改时必须在 nonce 写入前拒绝。 */
    @Test
    void tamperedBodyIsRejectedBeforeNonceInsert() {
        byte[] signedBody = createPaymentBody();
        byte[] sentBody = createPaymentBody();
        String nonce = unique("nonce-tampered-body");
        Map<String, String> headers = sign(CREATE_PATH, signedBody,
                channelCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        CommonResult<?> result = post(CREATE_PATH, sentBody, headers);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    /** 超出默认五分钟窗口的请求必须在凭证与 nonce 数据库链路前拒绝。 */
    @Test
    void expiredTimestampIsRejectedBeforeNonceInsert() {
        byte[] body = createPaymentBody();
        String nonce = unique("nonce-expired");
        long expiredAt = System.currentTimeMillis() - 6L * 60_000L;

        CommonResult<?> result = postSigned(CREATE_PATH, body,
                channelCredentials(CURRENT_KEY_VERSION), expiredAt, nonce);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    /** GRACE 是合法轮换位，必须与 CURRENT 一样可进入业务层。 */
    @Test
    void graceCredentialCanReachBusinessLayer() {
        String nonce = unique("nonce-grace");

        CommonResult<?> result = postSigned(CREATE_PATH, createPaymentBody(),
                channelCredentials(GRACE_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
        assertNonceRows(nonce, 1L);
    }

    /** REVOKED 不能参与验签，拒绝发生在 nonce 写入之前。 */
    @Test
    void revokedCredentialIsRejectedBeforeNonceInsert() {
        String nonce = unique("nonce-revoked");

        CommonResult<?> result = postSigned(CREATE_PATH, createPaymentBody(),
                channelCredentials(REVOKED_KEY_VERSION), System.currentTimeMillis(), nonce);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    /** 暂停项目必须失败关闭；finally 恢复状态，避免任何后续用例依赖执行顺序。 */
    @Test
    void pausedProjectIsRejectedAndRestoredWithoutNonceInsert() {
        String nonce = unique("nonce-paused-project");
        assertThat(jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?",
                PAUSED_STATUS, PROJECT_ID)).isEqualTo(1);
        try {
            CommonResult<?> result = postSigned(CREATE_PATH, createPaymentBody(),
                    channelCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

            assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
            assertNonceRows(nonce, 0L);
        } finally {
            assertThat(jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?",
                    ENABLED_STATUS, PROJECT_ID)).isEqualTo(1);
        }
    }

    /**
     * 先按已登记 createPaymentOrder 生成格式、摘要和签名均合法的 headers，再只改变 URL；因此失败
     * 唯一证明前缀内未知路径由注册表在凭证/nonce 之前关闭，而不是签名格式伪失败。
     */
    @Test
    void unknownPathUnderProtectedPrefixIsRejectedBeforeNonceInsert() {
        byte[] body = createPaymentBody();
        String nonce = unique("nonce-unknown-path");
        Map<String, String> validHeaders = sign(CREATE_PATH, body,
                channelCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);

        CommonResult<?> result = post(UNKNOWN_CHANNEL_PATH, body, validHeaders);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    /**
     * 标准操作真实认证到能力门禁后失败；未建立标准业务 Controller，不把 404 当成业务成功。
     * 详见 docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界。
     */
    @ParameterizedTest
    @MethodSource("standardOperations")
    void standardOperationsWithoutCapabilityAreRejectedBeforeNonce(ProjectAuthOperations.Operation operation) {
        assertThat(server.getBean(ProjectAppCredentialService.class)
                .authenticate(APP_ID, CURRENT_KEY_VERSION, operation.operationCode()).projectId()).isEqualTo(PROJECT_ID);
        String nonce = unique("nonce-standard-no-capability");
        CommonResult<?> result = postSigned(operation.path(), "{}".getBytes(StandardCharsets.UTF_8),
                projectCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);
        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    @Test
    void suspendedStandardQueryPassesCredentialPolicyButCannotBypassCapability() {
        jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.SUSPENDED.code(), PROJECT_ID);
        try {
            ProjectAppCredentialService service = server.getBean(ProjectAppCredentialService.class);
            assertThat(service.authenticate(APP_ID, CURRENT_KEY_VERSION,
                    ProjectAuthOperations.STANDARD_ORDER_QUERY).projectId()).isEqualTo(PROJECT_ID);
            assertThatThrownBy(() -> service.authenticate(APP_ID, CURRENT_KEY_VERSION))
                    .hasFieldOrPropertyWithValue("code", BUYER_AUTH_INVALID.getCode());
            for (String code : java.util.List.of(ProjectAuthOperations.STANDARD_ORDER_CREATE,
                    ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY)) {
                assertThatThrownBy(() -> service.authenticate(APP_ID, CURRENT_KEY_VERSION, code))
                        .hasFieldOrPropertyWithValue("code", BUYER_AUTH_INVALID.getCode());
            }
            assertStandardQueryRejectedWithoutNonce(CURRENT_KEY_VERSION, "suspended-no-capability");
        } finally {
            jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.ENABLED.code(), PROJECT_ID);
        }
    }

    /**
     * 已开通再停用只保留历史摘要查询；合法正文穿透到查单业务，禁止用参数错误证明放行。
     * 设计见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#1-已确认范围。
     */
    @Test
    void suspendedStandardCapabilityAllowsOnlySummaryQueries() {
        jdbc.update("""
                INSERT INTO sc_project_capability(
                    id, project_id, capability_code, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, STANDARD_CAPABILITY_ID, PROJECT_ID, ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT,
                ProjectAuthStatus.SUSPENDED.code());
        try {
            jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.SUSPENDED.code(), PROJECT_ID);
            byte[] body = "{\"buyerOrderNo\":\"AUTH-SUSPENDED-HISTORY\"}".getBytes(StandardCharsets.UTF_8);
            for (ProjectAuthOperations.Operation operation : standardOperations().toList()) {
                boolean summaryQuery = ProjectAuthOperations.STANDARD_ORDER_QUERY.equals(operation.operationCode())
                        || ProjectAuthOperations.STANDARD_ORDER_QUERY_V2.equals(operation.operationCode());
                String nonce = unique("nonce-disabled-capability");
                CommonResult<?> result = postSigned(operation.path(), body,
                        projectCredentials(CURRENT_KEY_VERSION), System.currentTimeMillis(), nonce);
                assertThat(result.getCode()).as(operation.operationCode()).isEqualTo(summaryQuery
                        ? SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode() : BUYER_AUTH_INVALID.getCode());
                assertNonceRows(nonce, summaryQuery ? 1L : 0L);
            }
        } finally {
            jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.ENABLED.code(), PROJECT_ID);
            jdbc.update("DELETE FROM sc_project_capability WHERE id = ?", STANDARD_CAPABILITY_ID);
        }
    }

    @Test
    void suspendedStandardQueryCannotBypassDisabledApp() {
        jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.SUSPENDED.code(), PROJECT_ID);
        try {
            jdbc.update("UPDATE sc_project_application SET status = ? WHERE id = ?", ProjectAuthStatus.SUSPENDED.code(), APPLICATION_ID);
            assertStandardQueryRejectedWithoutNonce(CURRENT_KEY_VERSION, "disabled-application");
        } finally {
            jdbc.update("UPDATE sc_project_application SET status = ? WHERE id = ?", ProjectAuthStatus.ENABLED.code(), APPLICATION_ID);
            jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.ENABLED.code(), PROJECT_ID);
        }
    }

    @Test
    void suspendedStandardQueryCannotBypassRevokedOrExpiredCredential() {
        jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.SUSPENDED.code(), PROJECT_ID);
        try {
            assertStandardQueryRejectedWithoutNonce(REVOKED_KEY_VERSION, "revoked-standard-query");
            jdbc.update("UPDATE sc_project_app_credential_version SET valid_to = UTC_TIMESTAMP(3) WHERE id = ?",
                    CURRENT_CREDENTIAL_ID);
            assertStandardQueryRejectedWithoutNonce(CURRENT_KEY_VERSION, "expired-standard-query");
        } finally {
            jdbc.update("UPDATE sc_project_app_credential_version SET valid_to = NULL WHERE id = ?", CURRENT_CREDENTIAL_ID);
            jdbc.update("UPDATE sc_buyer_project SET status = ? WHERE id = ?", ProjectAuthStatus.ENABLED.code(), PROJECT_ID);
        }
    }

    private static void assertStandardQueryRejectedWithoutNonce(int keyVersion, String label) {
        String nonce = unique("nonce-standard-" + label);
        CommonResult<?> result = postSigned(ProjectAuthOperations.STANDARD_ORDERS_PATH + "/query",
                "{}".getBytes(StandardCharsets.UTF_8), projectCredentials(keyVersion), System.currentTimeMillis(), nonce);
        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertNonceRows(nonce, 0L);
    }

    private static Stream<ProjectAuthOperations.Operation> standardOperations() {
        return ProjectAuthOperations.OPERATIONS.stream()
                .filter(op -> ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(op.requiredCapability()));
    }

    private static Stream<ProjectAuthOperations.Operation> registeredOperations() {
        return ProjectAuthOperations.OPERATIONS.stream();
    }

    /** 种子只建立认证身份链，刻意不建立渠道用户/商户绑定、短期凭证或支付流水。 */
    private static void seedAuthenticationFixtures() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer", Long.class)).isZero();
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, 'BUYER_PROJECT_AUTH_IT', '项目认证门禁采购商', '认证门禁', 'SAAS_MALL',
                    ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, BUYER_ID, ENABLED_STATUS);
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, 'PROJECT_AUTH_FLOW_IT', '项目认证真实HTTP门禁', ?, 'INDEPENDENT',
                    ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_ID, BUYER_ID, ENABLED_STATUS);
        jdbc.update("""
                INSERT INTO sc_project_capability(
                    id, project_id, capability_code, status, version, created_at, updated_at)
                VALUES (?, ?, 'INSTORE', ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, CAPABILITY_ID, PROJECT_ID, ENABLED_STATUS);
        jdbc.update("""
                INSERT INTO sc_project_application(
                    id, project_id, app_id, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, APPLICATION_ID, PROJECT_ID, APP_ID, ENABLED_STATUS);
        insertCredential(CURRENT_CREDENTIAL_ID, CURRENT_KEY_VERSION, "CURRENT", "CURRENT");
        insertCredential(GRACE_CREDENTIAL_ID, GRACE_KEY_VERSION, "GRACE", "GRACE");
        insertCredential(REVOKED_CREDENTIAL_ID, REVOKED_KEY_VERSION, "REVOKED", null);
    }

    /** secret_ref 必须走生产 AES-GCM codec，AAD 分别绑定 APP_ID 与各 keyVersion。 */
    private static void insertCredential(long id, int keyVersion, String status, String activeSlot) {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, APP_ID, keyVersion, APP_KEY);
        assertThat(jdbc.update("""
                INSERT INTO sc_project_app_credential_version(
                    id, application_id, key_version, secret_ref, masked_hint, status,
                    valid_from, valid_to, active_slot, created_at)
                VALUES (?, ?, ?, ?, 'pro***key', ?,
                    UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE, NULL, ?, UTC_TIMESTAMP(3))
                """, id, APPLICATION_ID, keyVersion, secretRef, status, activeSlot)).isEqualTo(1);
    }

    private static byte[] createPaymentBody() {
        String suffix = unique("ORDER");
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("CHANNEL-" + suffix);
        request.setTradeId("TRADE-" + suffix);
        request.setUserCode("opaque-user-" + suffix);
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY_AUTH_IT");
        request.setExternalMerchantId("MERCHANT_AUTH_IT");
        request.setShopCode("SHOP_AUTH_IT");
        request.setShopName("项目认证测试门店");
        request.setSubject("项目认证真实 HTTP 门禁");
        request.setTraceId("TRACE-" + suffix);
        return json(request);
    }

    private static ProjectAppAuthCredentials channelCredentials(int keyVersion) {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, keyVersion, null, CHANNEL_CODE);
    }

    private static ProjectAppAuthCredentials buyerCredentials(int keyVersion) {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, keyVersion, OPAQUE_BUYER_USER_ID, null);
    }

    private static ProjectAppAuthCredentials projectCredentials(int keyVersion) {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, keyVersion, null, null);
    }

    private static CommonResult<?> postSigned(String path, byte[] body,
                                              ProjectAppAuthCredentials credentials,
                                              long timestamp, String nonce) {
        return post(path, body, sign(path, body, credentials, timestamp, nonce));
    }

    private static Map<String, String> sign(String registeredPath, byte[] body,
                                            ProjectAppAuthCredentials credentials,
                                            long timestamp, String nonce) {
        return ProjectAppAuthSigner.sign(
                credentials, HttpMethod.POST.name(), registeredPath, body, timestamp, nonce);
    }

    /** 所有 HTTP 场景统一验证 transport=200、响应为 JSON CommonResult 且 body 非 null。 */
    private static CommonResult<?> post(String path, byte[] body, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::add);
        ResponseEntity<CommonResult<Object>> response = REST.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, httpHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(contentType -> assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                        .isTrue());
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static byte[] json(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("集成测试 DTO JSON 序列化失败", exception);
        }
    }

    private static void assertNonceRows(String nonce, long expected) {
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM sc_project_auth_nonce
                        WHERE app_id = ? AND nonce = ?
                        """, Long.class, APP_ID, nonce))
                .isEqualTo(expected);
    }

    private static String unique(String prefix) {
        return prefix + '-' + UNIQUE_SEQUENCE.incrementAndGet();
    }

    /**
     * 高优先级启动参数隔离本机 local profile 与固定端口，同时禁用本门禁无关的注册中心、监控、
     * 文档、定时器、Outbox、渠道 RPC 及本门禁不覆盖的租户子系统。项目认证 nonce 使用 MySQL，
     * 订单编号装配则使用真实隔离 Redis，不伪造客户端或关闭生产缺依赖门禁。
     * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁}。
     */
    private static String[] commandLineArguments() {
        String masterKeyBase64 = Base64.getEncoder().encodeToString(MASTER_KEY);
        return new String[]{
                "--spring.profiles.active=project-app-auth-it",
                "--spring.config.import=optional:classpath:application-project-app-auth-it.yaml",
                "--server.port=0",
                "--spring.cloud.bootstrap.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.config.import-check.enabled=false",
                "--spring.boot.admin.client.enabled=false",
                "--aifuli.tenant.enable=false",
                "--aifuli.supply-chain.order-no.env=test",
                "--aifuli.supply-chain.order-no.scope=project-app-auth",
                "--spring.data.redis.repositories.enabled=false",
                "--spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfigurationV2,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "com.aifuli.framework.redis.config.AifuliRedisAutoConfiguration,"
                        + "com.aifuli.framework.redis.config.AifuliCacheAutoConfiguration",
                "--springdoc.api-docs.enabled=false",
                "--springdoc.swagger-ui.enabled=false",
                "--knife4j.enable=false",
                "--aifuli.supply-chain.openapi.enabled=false",
                "--aifuli.supply-chain.timer.enabled=false",
                "--aifuli.supply-chain.outbox.enabled=false",
                "--tbsg.channel.rpc.enabled=false",
                "--aifuli.tracer.enable=false",
                "--aifuli.metrics.enable=false",
                "--aifuli.access-log.enable=false",
                "--aifuli.xss.enable=false",
                "--aifuli.web.admin-ui.url=http://127.0.0.1/project-auth-it",
                "--aifuli.supply-chain.enabled=true",
                "--aifuli.supply-chain.security.key-version=project-auth-it-v1",
                "--aifuli.supply-chain.security.envelope-key-base64=" + masterKeyBase64,
                "--aifuli.supply-chain.security.fingerprint-key-base64=" + masterKeyBase64,
                "--aifuli.supply-chain.auth.enabled=true",
                "--aifuli.supply-chain.auth.master-key=" + masterKeyBase64,
                "--aifuli.supply-chain.auth.nonce-cleanup-interval=PT24H",
                "--aifuli.supply-chain.datasource.url=" + jdbcUrl(),
                "--aifuli.supply-chain.datasource.username=" + username(),
                "--aifuli.supply-chain.datasource.password=" + password(),
                "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
        };
    }

    /**
     * 仅本类显式装配的限频身份端口，不替换认证Filter、nonce、Facade或生产安全配置。
     * 每次返回新32字节测试快照，既不读取环境密钥，也不改变默认provider缺密钥失败关闭。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md
     * 「九测试／夹具限定修复设计」。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class IdentityKeyTestConfiguration {
        /**
         * 仅为完整应用的生产发号配置提供真实隔离客户端；资源由本类统一回收。
         * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#104-t00基线收口修订2026-10-16。
         */
        @org.springframework.context.annotation.Bean(destroyMethod = "")
        RedissonClient orderNoRedisClient() {
            return orderRedisClient;
        }

        @org.springframework.context.annotation.Bean
        com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider
                testRechargeFrequencyIdentityKeyProvider() {
            return () -> new byte[32];
        }
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    /**
     * 逆序删除测试种子、关闭容器并清零主密钥；每一步都尝试执行，首个失败保留为 cause，后续失败
     * 作为 suppressed 附加，禁止清理异常覆盖或吞掉原始问题。
     */
    private static void cleanupResources() {
        Throwable firstFailure = null;
        firstFailure = cleanupDatabaseFixtures(firstFailure);
        try {
            if (server != null) {
                server.close();
                server = null;
            }
        } catch (Throwable failure) {
            firstFailure = appendCleanupFailure(firstFailure, failure);
        }
        try {
            if (orderRedisClient != null) {
                orderRedisClient.shutdown();
                orderRedisClient = null;
            }
        } catch (Throwable failure) {
            firstFailure = appendCleanupFailure(firstFailure, failure);
        }
        try {
            if (ORDER_REDIS.isRunning()) {
                ORDER_REDIS.stop();
            }
        } catch (Throwable failure) {
            firstFailure = appendCleanupFailure(firstFailure, failure);
        }
        try {
            if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
                MYSQL.stop();
            }
        } catch (Throwable failure) {
            firstFailure = appendCleanupFailure(firstFailure, failure);
        } finally {
            Arrays.fill(MASTER_KEY, (byte) 0);
        }
        if (firstFailure != null) {
            throw new AssertionError("项目认证 Flow 集成测试资源清理失败", firstFailure);
        }
    }

    private static Throwable appendCleanupFailure(Throwable firstFailure, Throwable nextFailure) {
        if (firstFailure == null) {
            return nextFailure;
        }
        firstFailure.addSuppressed(nextFailure);
        return firstFailure;
    }

    /** 每条逆序删除独立尝试，避免某一张表清理失败后跳过其余可清理种子。 */
    private static Throwable cleanupDatabaseFixtures(Throwable firstFailure) {
        if (jdbc == null) {
            return firstFailure;
        }
        try {
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_project_auth_nonce WHERE app_id = ?", APP_ID);
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_project_app_credential_version WHERE application_id = ?", APPLICATION_ID);
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_project_application WHERE id = ?", APPLICATION_ID);
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_project_capability WHERE id = ?", CAPABILITY_ID);
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_buyer_project WHERE id = ?", PROJECT_ID);
            firstFailure = deleteFixture(firstFailure,
                    "DELETE FROM sc_buyer WHERE id = ?", BUYER_ID);
            return firstFailure;
        } finally {
            jdbc = null;
            objectMapper = null;
            baseUrl = null;
        }
    }

    private static Throwable deleteFixture(Throwable firstFailure, String sql, Object... arguments) {
        try {
            jdbc.update(sql, arguments);
        } catch (Throwable failure) {
            return appendCleanupFailure(firstFailure, failure);
        }
        return firstFailure;
    }
}
