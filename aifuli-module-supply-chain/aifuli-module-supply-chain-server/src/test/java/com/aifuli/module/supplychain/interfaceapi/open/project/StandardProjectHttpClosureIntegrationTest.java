package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.SupplyChainServerApplication;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationExecuteHandler;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService;
import com.aifuli.module.supplychain.infrastructure.auth.LocalEnvelopeAppKeyCodec;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.testsupport.RoutingPolicyPresetFixture;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.CurrentProjectPricing;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ASSET_QUERY_ORDER_NOT_ACCEPTED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ASSET_QUERY_ORDER_TERMINATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标准项目 HTTP 接入与自动化闭环集成测试。
 * 真实 MySQL + Liquibase、真实 WebServer、真实 HMAC 签名与 Filter、生产 Controller 与 Facade。
 * 设计依据：
 * {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#5-自动化http闭环与完成判定}。
 */
class StandardProjectHttpClosureIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static final byte[] MASTER_KEY = Base64.getDecoder()
            .decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=");

    private static final long BUYER_ID = 9_500_001L;
    private static final String BUYER_CODE = "BUYER_CLOSURE_IT";
    private static final long PROJECT_A_ID = 9_510_001L;
    private static final String PROJECT_A_CODE = "PRJ_CLOSURE_A";
    private static final long PROJECT_B_ID = 9_510_002L;
    private static final String PROJECT_B_CODE = "PRJ_CLOSURE_B";

    private static final String APP_A = "app-closure-a";
    private static final String APP_B = "app-closure-b";
    private static final String APP_KEY = "test-closure-app-key-secret-32-chars-long";
    private static final int KEY_VERSION = 1;

    private static final String SKU_VOUCHER_CODE = "SKU_001_001";
    private static final String BUYER_SKU_VOUCHER = "BUYER_SKU_VOUCHER_100";
    private static final String SKU_DIRECT_CODE = "SKU_002_001";
    private static final String BUYER_SKU_DIRECT = "BUYER_SKU_DIRECT_100";

    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "tenant-closure";

    /** 供应商 HTTP 挡板凭证（EnvironmentCredentialProvider 经命令行注入的 env:QNM_CREDENTIAL）。 */
    private static final String QNM_CREDENTIAL = "qnm-closure-credential";
    /** 轮换后的项目应用密钥（keyVersion=2）。 */
    private static final String APP_KEY_V2 = "test-closure-app-key-v2-secret-32ch";

    private static final String ORDERS_PATH = ProjectAuthOperations.STANDARD_ORDERS_PATH;
    private static final String QUERY_PATH = ORDERS_PATH + "/query";
    private static final String ASSETS_PATH = ORDERS_PATH + "/assets/query";

    private static final AtomicLong UNIQUE_SEQ = new AtomicLong(1000);
    private static final RestTemplate REST = new RestTemplate();
    /** 本地 QNM 供应商 HTTP 挡板：履约外呼不再被 JDBC 模拟替代，走真实适配器与状态机。 */
    private static final MockWebServer MOCK_SUPPLIER = new MockWebServer();

    private static ConfigurableApplicationContext server;
    private static String baseUrl;
    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static StandardPricingQuoteService quoteService;
    private static EnvelopeCryptoPort envelopeCrypto;

    @BeforeAll
    static void start() throws Throwable {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        MOCK_SUPPLIER.start();
        server = new SpringApplicationBuilder(SupplyChainServerApplication.class, TestOverrides.class)
                .run(commandLineArguments());
        WebServerApplicationContext webContext = (WebServerApplicationContext) server;
        int port = webContext.getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        DataSource dataSource = server.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = server.getBean(ObjectMapper.class);
        quoteService = server.getBean(StandardPricingQuoteService.class);
        envelopeCrypto = server.getBean(EnvelopeCryptoPort.class);

        seedFixtures();
        // 供应商连接版本指向本地挡板，履约外呼 /GameOrder/Send(SendKm)/Query 全部进入测试闭环
        String mockBaseUrl = MOCK_SUPPLIER.url("/").toString();
        jdbc.update("UPDATE sc_supplier_connector_version SET base_url = ?",
                mockBaseUrl.endsWith("/") ? mockBaseUrl.substring(0, mockBaseUrl.length() - 1) : mockBaseUrl);
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        MOCK_SUPPLIER.shutdown();
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    /**
     * 场景一：虚拟卡券标准订单全流程闭环：
     * 内部报价 -> 签名下单成功 -> 同参新nonce重放返回相同orderNo且不重扣 -> 签名查单返回PROCESSING+未就绪 ->
     * 供应商HTTP挡板返回SendKm成功报文并由真实履约执行处理器完成状态机与加密资产闭环 ->
     * 查单返回SUCCEEDED+就绪 -> 资产查询返回解密明文卡密与独立审计。
     */
    @Test
    void testVoucherOrderFullClosure() throws Exception {
        String buyerOrderNo = "VOUCHER-ORDER-" + UNIQUE_SEQ.incrementAndGet();

        // 1. 签发卡券报价
        issueQuote(PROJECT_A_CODE, buyerOrderNo, SKU_VOUCHER_CODE);

        // 2. 签名下单
        String createJson = """
                {
                    "buyerOrderNo": "%s",
                    "buyerSkuCode": "%s",
                    "quantity": 1,
                    "target": {
                        "deliveryMode": "BUYER_ASSET_QUERY"
                    }
                }
                """.formatted(buyerOrderNo, BUYER_SKU_VOUCHER);
        String createNonce = "nonce-create-" + UNIQUE_SEQ.incrementAndGet();
        ResponseEntity<CommonResult<Map<String, Object>>> createResp = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), createNonce);

        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        CommonResult<Map<String, Object>> createResult = createResp.getBody();
        assertThat(createResult).isNotNull();
        assertThat(createResult.getCode()).isZero();
        Map<String, Object> createData = createResult.getData();
        assertThat(createData).isNotNull();
        String orderNo = (String) createData.get("orderNo");
        String requestId = (String) createData.get("requestId");
        assertThat(orderNo).isNotBlank();
        assertThat(requestId).isNotBlank();
        assertThat(createData.get("buyerOrderNo")).isEqualTo(buyerOrderNo);
        // 封闭响应白名单：绝不暴露供应商或履约号
        assertThat(createData).doesNotContainKeys("supplierId", "supplierCode", "bOrderNo", "fulfillmentRequestNo");

        // 3. 同参新 nonce 重放：必须返回相同 requestId 与 orderNo，不重复扣款
        String replayNonce = "nonce-create-replay-" + UNIQUE_SEQ.incrementAndGet();
        ResponseEntity<CommonResult<Map<String, Object>>> replayResp = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), replayNonce);
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayResp.getBody()).isNotNull();
        assertThat(replayResp.getBody().getCode()).isZero();
        Map<String, Object> replayData = replayResp.getBody().getData();
        assertThat(replayData.get("orderNo")).isEqualTo(orderNo);
        assertThat(replayData.get("requestId")).isEqualTo(requestId);
        assertThat(replayData.get("idempotentReplay")).isEqualTo(true);

        // 4. 签名查单：PROCESSING，未就绪资产
        String queryJson = """
                {"buyerOrderNo": "%s"}
                """.formatted(buyerOrderNo);
        String queryNonce = "nonce-query-" + UNIQUE_SEQ.incrementAndGet();
        ResponseEntity<CommonResult<Map<String, Object>>> queryResp = postSigned(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), queryNonce);
        assertThat(queryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        CommonResult<Map<String, Object>> queryResult = queryResp.getBody();
        assertThat(queryResult).isNotNull();
        assertThat(queryResult.getCode()).isZero();
        Map<String, Object> queryData = queryResult.getData();
        assertThat(queryData.get("orderNo")).isEqualTo(orderNo);
        assertThat(queryData.get("assetAvailable")).isEqualTo(false);

        // 5. 供应商HTTP挡板返回 SendKm 成功报文；受理事务已创建 BOrder 与 SEND_KM 操作，
        //    由真实 FulfillmentOperationExecuteHandler（与生产 Timer 同一入口）完成凭证解析、
        //    QNM 协议外呼、状态机推进与信封加密资产持久化
        enqueueQnmResponse(200, """
                {"ResCode":"OK","ResMsg":"出单成功","orderSn":"QNM_KM_CLOSURE_1","sumPrice":9000,
                 "cards":[{"cardNo":"CARD_CLOSURE_123","cardPwd":"SECRET_CLOSURE_999",
                 "expireTime":"2028-06-30 23:59:59"}]}
                """);
        driveOperationHandler(latestOperationNo(orderNo), "SUCCEEDED");

        // 6. 再次查单：SUCCEEDED，且 assetAvailable == true
        ResponseEntity<CommonResult<Map<String, Object>>> queryResp2 = postSigned(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-query-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(queryResp2.getBody()).isNotNull();
        assertThat(queryResp2.getBody().getData().get("orderStatus")).isEqualTo("SUCCEEDED");
        assertThat(queryResp2.getBody().getData().get("assetAvailable")).isEqualTo(true);

        // 7. 查询资产：返回解密明文卡密，并落独立审计记录
        long auditCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Long.class);
        String assetNonce = "nonce-asset-" + UNIQUE_SEQ.incrementAndGet();
        ResponseEntity<CommonResult<Map<String, Object>>> assetResp = postSigned(
                ASSETS_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), assetNonce);

        assertThat(assetResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        CommonResult<Map<String, Object>> assetResult = assetResp.getBody();
        assertThat(assetResult).isNotNull();
        assertThat(assetResult.getCode()).isZero();
        Map<String, Object> assetData = assetResult.getData();
        assertThat(assetData).isNotNull();
        assertThat(assetData.get("buyerOrderNo")).isEqualTo(buyerOrderNo);
        assertThat(assetData.get("orderNo")).isEqualTo(orderNo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assets = (List<Map<String, Object>>) assetData.get("assets");
        assertThat(assets).hasSize(1);
        Map<String, Object> assetItem = assets.get(0);
        assertThat(assetItem.get("cardCode")).isEqualTo("CARD_CLOSURE_123");
        assertThat(assetItem.get("cardSecret")).isEqualTo("SECRET_CLOSURE_999");
        assertThat(assetItem.get("assetType")).isEqualTo("CARD_CODE_SECRET");

        // 验证独立审计写入（包含 ASSET_DECRYPT 与 ASSET_QUERY 2条记录）
        long auditCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Long.class);
        assertThat(auditCountAfter).isEqualTo(auditCountBefore + 2);
    }

    /**
     * 场景二：MOBILE 直充标准订单闭环：
     * 报价 -> 签名下单成功（限频入队+门禁READY）-> 资产查询明确拒绝非券类 ->
     * 供应商挡板返回 Send 受理成功，真实处理器经限频认领与发送边界推进 ->
     * 插入 QUERY_ORDER 补偿操作并由真实处理器查单收敛 ->
     * 直充终态：查单 SUCCEEDED + assetAvailable=false（直充无卡券资产）。
     */
    @Test
    void testDirectRechargeOrderClosure() throws Exception {
        String buyerOrderNo = "DIRECT-ORDER-" + UNIQUE_SEQ.incrementAndGet();

        // 1. 签发直充报价
        issueQuote(PROJECT_A_CODE, buyerOrderNo, SKU_DIRECT_CODE);

        // 2. 签名下单
        String createJson = """
                {
                    "buyerOrderNo": "%s",
                    "buyerSkuCode": "%s",
                    "quantity": 1,
                    "target": {
                        "accountType": "MOBILE",
                        "accountValue": "13800138000"
                    }
                }
                """.formatted(buyerOrderNo, BUYER_SKU_DIRECT);
        String createNonce = "nonce-direct-" + UNIQUE_SEQ.incrementAndGet();
        ResponseEntity<CommonResult<Map<String, Object>>> createResp = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), createNonce);

        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(createResp.getBody()).isNotNull();
        assertThat(createResp.getBody().getCode()).isZero();
        String orderNo = (String) createResp.getBody().getData().get("orderNo");
        assertThat(orderNo).isNotBlank();

        // 3. 查单
        String queryJson = """
                {"buyerOrderNo": "%s"}
                """.formatted(buyerOrderNo);
        ResponseEntity<CommonResult<Map<String, Object>>> queryResp = postSigned(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-dq-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(queryResp.getBody()).isNotNull();
        assertThat(queryResp.getBody().getCode()).isZero();
        assertThat(queryResp.getBody().getData().get("assetAvailable")).isEqualTo(false);

        // 4. 资产查询：直充不支持卡券资产，返回业务错误或不支持提示
        ResponseEntity<CommonResult<Object>> assetResp = postSignedRaw(
                ASSETS_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-da-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(assetResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        // 直充订单查询资产契约：返回业务错误或不支持提示
        assertThat(assetResp.getBody()).isNotNull();
        assertThat(assetResp.getBody().getCode()).isEqualTo(ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE.getCode());

        // 5. 供应商挡板返回 Send 受理成功：真实处理器走限频认领（READY门禁+MOBILE入队）
        //    -> PREPARED/SEND_POSSIBLE 发送边界 -> QNM /GameOrder/Send -> ACCEPTED 等待回调或查单
        enqueueQnmResponse(200, """
                {"ResCode":"OK","ResMsg":"充值受理成功","orderSn":"QNM_DIRECT_CLOSURE_1","sumPrice":9000}
                """);
        driveOperationHandler(latestOperationNo(orderNo), "ACCEPTED");

        // 6. 受理后订单未终态：查单仍 PROCESSING 且资产不可用
        Map<String, Object> processingResp = postSignedForBody(
                QUERY_PATH, queryJson, "nonce-dq2-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(processingResp.get("orderStatus")).isEqualTo("PROCESSING");
        assertThat(processingResp.get("assetAvailable")).isEqualTo(false);

        // 7. 模拟补偿主动查单：与生产补偿流程同形态插入 QUERY_ORDER 操作（非充值副作用，
        //    走普通认领）。生产上续查操作经受控续履约切换为频控当前持有操作，测试同构地
        //    把 active_operation_no 切到该操作，否则可信结论只落观察记录不收敛终态
        Long bOrderId = jdbc.queryForObject("""
                SELECT b.id FROM sc_b_order b
                JOIN sc_supply_chain_order o ON b.order_id = o.id
                WHERE o.order_no = ?
                """, Long.class, orderNo);
        String queryOpNo = "OPIT-Q-" + UNIQUE_SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO sc_fulfillment_operation(id, operation_no, b_order_id, operation_kind,
                    adapter_step_code, sequence_no, idempotency_key, config_digest, status, attempt_count,
                    created_at, updated_at, version)
                VALUES (?, ?, ?, 'QUERY_ORDER', 'SEND', 2, ?, UNHEX(REPEAT('06', 32)), 0, 0,
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0)
                """, UNIQUE_SEQ.incrementAndGet(), queryOpNo, bOrderId, "OPKEY-Q-" + queryOpNo);
        jdbc.update("""
                UPDATE sc_recharge_frequency_entry SET active_operation_no = ?
                WHERE order_id = (SELECT id FROM sc_supply_chain_order WHERE order_no = ?)
                """, queryOpNo, orderNo);
        enqueueQnmResponse(200, """
                {"ResCode":"1","ResMsg":"成功","orderSn":"QNM_DIRECT_CLOSURE_1","sumPrice":9000,
                 "orderStatus":1,"cards":[]}
                """);
        driveOperationHandler(queryOpNo, "SUCCEEDED");

        // 8. 直充终态：查单 SUCCEEDED + assetAvailable=false；资产查询仍明确拒绝
        Map<String, Object> terminalResp = postSignedForBody(
                QUERY_PATH, queryJson, "nonce-dq3-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(terminalResp.get("orderStatus")).isEqualTo("SUCCEEDED");
        assertThat(terminalResp.get("assetAvailable")).isEqualTo(false);
    }

    /**
     * 场景三：同分销商跨项目同订单号隔离：
     * 项目 A 和项目 B 共享相同 buyerOrderNo，各自独立签发、独立建单，互不干扰；未建单返回不存在。
     */
    @Test
    void testCrossProjectOrderIsolation() {
        String sharedOrderNo = "SHARED-ORDER-" + UNIQUE_SEQ.incrementAndGet();

        // 为项目 A 和项目 B 各自签发报价
        issueQuote(PROJECT_A_CODE, sharedOrderNo, SKU_VOUCHER_CODE);
        issueQuote(PROJECT_B_CODE, sharedOrderNo, SKU_VOUCHER_CODE);

        String createJson = """
                {
                    "buyerOrderNo": "%s",
                    "buyerSkuCode": "%s",
                    "quantity": 1,
                    "target": {
                        "deliveryMode": "BUYER_ASSET_QUERY"
                    }
                }
                """.formatted(sharedOrderNo, BUYER_SKU_VOUCHER);

        // 项目 A 下单
        ResponseEntity<CommonResult<Map<String, Object>>> respA = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-iso-a-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(respA.getBody()).isNotNull();
        assertThat(respA.getBody().getCode()).isZero();
        String orderNoA = (String) respA.getBody().getData().get("orderNo");

        // 项目 B 下单
        ResponseEntity<CommonResult<Map<String, Object>>> respB = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsB(), System.currentTimeMillis(), "nonce-iso-b-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(respB.getBody()).isNotNull();
        assertThat(respB.getBody().getCode()).isZero();
        String orderNoB = (String) respB.getBody().getData().get("orderNo");

        // 订单号必须互相独立
        assertThat(orderNoA).isNotEqualTo(orderNoB);

        // 项目 A 查单只返回 orderNoA
        String queryJson = """
                {"buyerOrderNo": "%s"}
                """.formatted(sharedOrderNo);
        ResponseEntity<CommonResult<Map<String, Object>>> queryA = postSigned(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-q-a-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(queryA.getBody().getData().get("orderNo")).isEqualTo(orderNoA);

        // 项目 B 查单只返回 orderNoB
        ResponseEntity<CommonResult<Map<String, Object>>> queryB = postSigned(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsB(), System.currentTimeMillis(), "nonce-q-b-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(queryB.getBody().getData().get("orderNo")).isEqualTo(orderNoB);

        // 查询不存在的订单号返回统一错误
        String notFoundJson = """
                {"buyerOrderNo": "NON-EXISTENT-ORDER"}
                """;
        ResponseEntity<CommonResult<Map<String, Object>>> notFoundResp = postSigned(
                QUERY_PATH, notFoundJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-nf-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(notFoundResp.getBody().getCode()).isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
    }

    /**
     * 场景四：传输层严格解析与安全防护矩阵：
     * 非空 Query String、非法数量、重复 JSON key、尾随字符、篡改签名、未授权访问等全部被安全拦截。
     */
    @Test
    void testSecurityAndStrictValidationMatrix() {
        // 1. 非空 Query String 拦截：必须返回 400，带 Cache-Control: no-store
        String validBody = "{\"buyerOrderNo\":\"SEC-01\"}";
        ResponseEntity<CommonResult<Object>> queryParamResp = postSignedRaw(
                QUERY_PATH + "?tamper=1", validBody.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-sec-1-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(queryParamResp.getBody().getCode()).isEqualTo(400);
        assertThat(queryParamResp.getBody().getMsg()).isEqualTo("请求参数不正确");
        assertThat(queryParamResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");

        // 2. 数量不为 1 拦截：浮点数、字符串、大于 1
        String[] invalidQuantityBodies = {
                """
                {"buyerOrderNo":"SEC-02","buyerSkuCode":"%s","quantity":2,"target":{"account":"a"}}
                """.formatted(BUYER_SKU_VOUCHER),
                """
                {"buyerOrderNo":"SEC-02","buyerSkuCode":"%s","quantity":1.0,"target":{"account":"a"}}
                """.formatted(BUYER_SKU_VOUCHER),
                """
                {"buyerOrderNo":"SEC-02","buyerSkuCode":"%s","quantity":"1","target":{"account":"a"}}
                """.formatted(BUYER_SKU_VOUCHER)
        };
        for (String body : invalidQuantityBodies) {
            ResponseEntity<CommonResult<Object>> resp = postSignedRaw(
                    ORDERS_PATH, body.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-sec-q-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(resp.getBody().getCode()).isEqualTo(400);
            assertThat(resp.getBody().getMsg()).isEqualTo("请求参数不正确");
        }

        // 3. 重复 JSON 键拦截
        String duplicateKeyBody = """
                {"buyerOrderNo":"SEC-03","buyerOrderNo":"SEC-03-DUP","buyerSkuCode":"%s","quantity":1,"target":{"account":"a"}}
                """.formatted(BUYER_SKU_VOUCHER);
        ResponseEntity<CommonResult<Object>> dupResp = postSignedRaw(
                ORDERS_PATH, duplicateKeyBody.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-sec-dup-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(dupResp.getBody().getCode()).isEqualTo(400);

        // 4. 尾随非法字符拦截
        String trailingBody = """
                {"buyerOrderNo":"SEC-04","buyerSkuCode":"%s","quantity":1,"target":{"account":"a"}} trailing-junk
                """.formatted(BUYER_SKU_VOUCHER);
        ResponseEntity<CommonResult<Object>> trailResp = postSignedRaw(
                ORDERS_PATH, trailingBody.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-sec-tr-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(trailResp.getBody().getCode()).isEqualTo(400);

        // 5. 篡改 body 签名拦截：修改 body 发送原签名，必须被拒绝且 nonce 不写入
        byte[] originalBody = validBody.getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"buyerOrderNo\":\"SEC-TAMPERED\"}".getBytes(StandardCharsets.UTF_8);
        String tamperedNonce = "nonce-tampered-" + UNIQUE_SEQ.incrementAndGet();
        Map<String, String> signedHeaders = ProjectAppAuthSigner.sign(
                credentialsA(), HttpMethod.POST.name(), QUERY_PATH, originalBody,
                System.currentTimeMillis(), tamperedNonce);

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        signedHeaders.forEach(httpHeaders::add);

        ResponseEntity<CommonResult<Object>> tamperedResp = REST.exchange(
                baseUrl + QUERY_PATH, HttpMethod.POST,
                new HttpEntity<>(tamperedBody, httpHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(tamperedResp.getBody().getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        long nonceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_project_auth_nonce WHERE nonce = ?", Long.class, tamperedNonce);
        assertThat(nonceCount).isZero();

        // 6. 缺少签名头拦截
        ResponseEntity<CommonResult<Object>> noAuthResp = REST.exchange(
                baseUrl + QUERY_PATH, HttpMethod.POST,
                new HttpEntity<>(originalBody, httpHeadersWithJsonOnly()),
                new ParameterizedTypeReference<>() {});
        assertThat(noAuthResp.getBody().getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    /**
     * 场景五：直充迁移发布门禁未就绪不受理：
     * 门禁置非 READY 后 MOBILE 直充下单被安全拒绝（不泄露内部原因），回执与订单均不落库。
     */
    @Test
    void testDirectRechargeRejectedWhenMigrationGateNotReady() {
        String buyerOrderNo = "DIRECT-NOTREADY-" + UNIQUE_SEQ.incrementAndGet();
        issueQuote(PROJECT_A_CODE, buyerOrderNo, SKU_DIRECT_CODE);
        jdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state = 'CLOSED' WHERE id = 1");
        try {
            String createJson = """
                    {
                        "buyerOrderNo": "%s",
                        "buyerSkuCode": "%s",
                        "quantity": 1,
                        "target": {
                            "accountType": "MOBILE",
                            "accountValue": "13900001111"
                        }
                    }
                    """.formatted(buyerOrderNo, BUYER_SKU_DIRECT);
            ResponseEntity<CommonResult<Object>> resp = postSignedRaw(
                    ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-nr-" + UNIQUE_SEQ.incrementAndGet());
            // 门禁异常走脱敏兜底路径：统一 500 报文（HTTP 200 + 内部错误码），不暴露"迁移门禁"字样
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getCode()).isEqualTo(500);

            // 未受理：查单返回统一不存在错误，回执与订单均未创建
            String queryJson = """
                    {"buyerOrderNo": "%s"}
                    """.formatted(buyerOrderNo);
            ResponseEntity<CommonResult<Object>> queryResp = postSignedRaw(
                    QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-nrq-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(queryResp.getBody()).isNotNull();
            assertThat(queryResp.getBody().getCode()).isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
        } finally {
            restoreMigrationGateReady();
        }
    }

    /**
     * 场景六：限购跳过仅写入订单内部备注：
     * 启用每人限购但卡券目标无可用手机号时，订单创建成功且内部备注追加一次跳过记录，
     * 跳过不向项目响应说明，也不影响下单结果。
     */
    @Test
    void testVoucherPersonalLimitSkipRecordedAsInternalRemarkOnly() {
        jdbc.update("UPDATE sc_platform_sku SET user_limit_enabled = 1 WHERE sku_code = ?", SKU_VOUCHER_CODE);
        try {
            String buyerOrderNo = "VOUCHER-LIMITSKIP-" + UNIQUE_SEQ.incrementAndGet();
            String orderNo = createVoucherOrder(buyerOrderNo);
            String remark = jdbc.queryForObject(
                    "SELECT remark FROM sc_supply_chain_order WHERE order_no = ?", String.class, orderNo);
            assertThat(remark).isEqualTo("该商品无可用手机号，本单未执行每人每周期限购");
        } finally {
            jdbc.update("UPDATE sc_platform_sku SET user_limit_enabled = 0 WHERE sku_code = ?", SKU_VOUCHER_CODE);
        }
    }

    /**
     * 场景七：鉴权矩阵：能力停用、凭证吊销、密钥轮换（GRACE过渡）与项目暂停下的操作级放行策略，
     * 全部在签名通过后的凭证链阶段以统一 BUYER_AUTH_INVALID 拒绝，不区分具体失败阶段。
     */
    @Test
    void testProjectAuthMatrix() {
        String queryJson = "{\"buyerOrderNo\":\"AUTH-MATRIX\"}";

        // 1. 项目能力停用：签名与凭证链通过，requireCapability 阶段拒绝
        jdbc.update("UPDATE sc_project_capability SET status = 20 "
                + "WHERE project_id = ? AND capability_code = 'STANDARD_PRODUCT'", PROJECT_A_ID);
        try {
            assertQueryRejected(queryJson, "nonce-cap-" + UNIQUE_SEQ.incrementAndGet());
        } finally {
            jdbc.update("UPDATE sc_project_capability SET status = 10 "
                    + "WHERE project_id = ? AND capability_code = 'STANDARD_PRODUCT'", PROJECT_A_ID);
        }

        // 2. 凭证吊销：v1 置 REVOKED 后验签拒绝
        jdbc.update("UPDATE sc_project_app_credential_version SET status = 'REVOKED', active_slot = NULL "
                + "WHERE id = 9540001");
        try {
            assertQueryRejected(queryJson, "nonce-rev-" + UNIQUE_SEQ.incrementAndGet());
        } finally {
            jdbc.update("UPDATE sc_project_app_credential_version SET status = 'CURRENT', active_slot = 'CURRENT' "
                    + "WHERE id = 9540001");
        }

        // 3. 密钥轮换：按 uk_sc_project_app_credential_slot 唯一键约束，旧 v1 先降为 GRACE
        //    腾出 CURRENT 位后再插入 v2；GRACE 过渡期 v1 仍可用，吊销 v1 后仅 v2 可用
        jdbc.update("UPDATE sc_project_app_credential_version SET status = 'GRACE', active_slot = 'GRACE' "
                + "WHERE id = 9540001");
        insertCredential(9540003L, 9530001L, APP_A, 2, APP_KEY_V2);
        try {
            // v2（新密钥）验签通过
            ResponseEntity<CommonResult<Map<String, Object>>> v2Resp = postSigned(
                    QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                    new ProjectAppAuthCredentials(APP_A, APP_KEY_V2, 2, null, null),
                    System.currentTimeMillis(), "nonce-v2-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(v2Resp.getBody().getCode()).isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
            // v1（GRACE 过渡）验签仍通过
            ResponseEntity<CommonResult<Map<String, Object>>> v1Resp = postSigned(
                    QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-v1g-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(v1Resp.getBody().getCode()).isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
            // v1 吊销后仅 v2 可用
            jdbc.update("UPDATE sc_project_app_credential_version SET status = 'REVOKED', active_slot = NULL "
                    + "WHERE id = 9540001");
            assertQueryRejected(queryJson, "nonce-v1r-" + UNIQUE_SEQ.incrementAndGet());
        } finally {
            // 先删除 v2 腾出 CURRENT 位（uk_sc_project_app_credential_slot），再恢复 v1，保证后续测试用例凭证链可用
            jdbc.update("DELETE FROM sc_project_app_credential_version WHERE id = 9540003");
            jdbc.update("UPDATE sc_project_app_credential_version SET status = 'CURRENT', active_slot = 'CURRENT' "
                    + "WHERE id = 9540001");
        }

        // 4. 项目暂停：查询类操作按 ACTIVE_OR_SUSPENDED 策略放行，下单与资产查询仍拒绝
        jdbc.update("UPDATE sc_buyer_project SET status = 20 WHERE id = ?", PROJECT_A_ID);
        try {
            ResponseEntity<CommonResult<Map<String, Object>>> queryResp = postSigned(
                    QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-susp-q-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(queryResp.getBody().getCode()).isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
            // 下单（ACTIVE_ONLY）拒绝
            String createJson = """
                    {
                        "buyerOrderNo": "AUTH-SUSPEND-CREATE",
                        "buyerSkuCode": "%s",
                        "quantity": 1,
                        "target": {"deliveryMode": "BUYER_ASSET_QUERY"}
                    }
                    """.formatted(BUYER_SKU_VOUCHER);
            ResponseEntity<CommonResult<Object>> createResp = postSignedRaw(
                    ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-susp-c-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(createResp.getBody().getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
            // 资产查询（ACTIVE_ONLY）拒绝
            ResponseEntity<CommonResult<Object>> assetResp = postSignedRaw(
                    ASSETS_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-susp-a-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(assetResp.getBody().getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        } finally {
            jdbc.update("UPDATE sc_buyer_project SET status = 10 WHERE id = ?", PROJECT_A_ID);
        }
    }

    /**
     * 场景八：资产异常矩阵：等待/人工处理返回提示不泄露资产；终态与未受理返回明确业务错误；
     * 缺资产、解密失败与载荷解析失败按脱敏内部错误安全拒绝，且均落独立审计（审计写入故障
     * 分支依赖存储层故障注入，无法在真实链路中直接模拟，由审计服务单测覆盖）。
     */
    @Test
    void testProjectAssetQueryFailureMatrix() {
        // 1. 等待提示：卡券订单创建后未履约，资产查询返回 code 0 + 等待提示 + 空资产
        String waitingBuyerOrderNo = "ASSET-WAIT-" + UNIQUE_SEQ.incrementAndGet();
        createVoucherOrder(waitingBuyerOrderNo);
        String waitingJson = "{\"buyerOrderNo\":\"" + waitingBuyerOrderNo + "\"}";
        long auditBefore = countAudit();
        ResponseEntity<CommonResult<Map<String, Object>>> waitingResp = postSigned(
                ASSETS_PATH, waitingJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-aw-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(waitingResp.getBody().getCode()).isZero();
        assertThat(waitingResp.getBody().getData().get("hint")).isEqualTo("请等待，稍后重新获卡");
        assertThat((List<?>) waitingResp.getBody().getData().get("assets")).isEmpty();
        assertThat(countAudit()).isEqualTo(auditBefore + 1);

        // 2. 人工处理提示：订单与履约请求置 MANUAL
        mutateOrderState(waitingBuyerOrderNo, 90, 90);
        ResponseEntity<CommonResult<Map<String, Object>>> manualResp = postSigned(
                ASSETS_PATH, waitingJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-am-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(manualResp.getBody().getCode()).isZero();
        assertThat(manualResp.getBody().getData().get("hint"))
                .isEqualTo("订单正在人工处理中，请通过查单接口查看进度");

        // 3. 终态订单拒绝：FAILED 订单返回 ASSET_QUERY_ORDER_TERMINATED
        String terminatedBuyerOrderNo = "ASSET-TERM-" + UNIQUE_SEQ.incrementAndGet();
        createVoucherOrder(terminatedBuyerOrderNo);
        String terminatedJson = "{\"buyerOrderNo\":\"" + terminatedBuyerOrderNo + "\"}";
        mutateOrderState(terminatedBuyerOrderNo, 40, 50);
        ResponseEntity<CommonResult<Object>> terminatedResp = postSignedRaw(
                ASSETS_PATH, terminatedJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-at-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(terminatedResp.getBody().getCode()).isEqualTo(ASSET_QUERY_ORDER_TERMINATED.getCode());

        // 4. 未受理订单拒绝：路由不可用导致受理拒绝（回执 REJECTED_RETRYABLE 且无订单），
        //    资产查询返回 ASSET_QUERY_ORDER_NOT_ACCEPTED
        String rejectedOrderNo = "ASSET-REJ-" + UNIQUE_SEQ.incrementAndGet();
        issueQuote(PROJECT_A_CODE, rejectedOrderNo, SKU_VOUCHER_CODE);
        jdbc.update("UPDATE sc_supplier_routing_policy SET status = 20, active_flag = NULL "
                + "WHERE policy_code = 'CARD_PRIMARY'");
        String rejectedCreateJson = """
                {
                    "buyerOrderNo": "%s",
                    "buyerSkuCode": "%s",
                    "quantity": 1,
                    "target": {"deliveryMode": "BUYER_ASSET_QUERY"}
                }
                """.formatted(rejectedOrderNo, BUYER_SKU_VOUCHER);
        try {
            ResponseEntity<CommonResult<Map<String, Object>>> rejectedCreate = postSigned(
                    ORDERS_PATH, rejectedCreateJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-ar-c-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(rejectedCreate.getBody().getCode()).isZero();
            assertThat(rejectedCreate.getBody().getData().get("orderNo")).isNull();
            assertThat(rejectedCreate.getBody().getData().get("rejectionCode")).isEqualTo("NO_ROUTABLE_SUPPLIER");
            String rejectedJson = "{\"buyerOrderNo\":\"" + rejectedOrderNo + "\"}";
            ResponseEntity<CommonResult<Object>> rejectedAsset = postSignedRaw(
                    ASSETS_PATH, rejectedJson.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), "nonce-ar-a-" + UNIQUE_SEQ.incrementAndGet());
            assertThat(rejectedAsset.getBody().getCode()).isEqualTo(ASSET_QUERY_ORDER_NOT_ACCEPTED.getCode());
        } finally {
            jdbc.update("UPDATE sc_supplier_routing_policy SET status = 10, active_flag = 1 "
                    + "WHERE policy_code = 'CARD_PRIMARY'");
        }

        // 5. 成功订单缺资产：SUCCEEDED 但无任何资产行，按参考不一致安全拒绝（脱敏500）
        String missingBuyerOrderNo = "ASSET-MISS-" + UNIQUE_SEQ.incrementAndGet();
        createVoucherOrder(missingBuyerOrderNo);
        String missingJson = "{\"buyerOrderNo\":\"" + missingBuyerOrderNo + "\"}";
        mutateOrderState(missingBuyerOrderNo, 30, 40);
        auditBefore = countAudit();
        CommonResult<Object> missingResp = postSignedCapturingFailure(
                ASSETS_PATH, missingJson, "nonce-amiss-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(missingResp.getCode()).isEqualTo(500);
        assertThat(countAudit()).isEqualTo(auditBefore + 1);

        // 6. 解密失败：资产密文以错误 AAD 写入，GCM 校验失败，明文不落响应（脱敏500）
        String decryptBuyerOrderNo = "ASSET-DEC-" + UNIQUE_SEQ.incrementAndGet();
        createVoucherOrder(decryptBuyerOrderNo);
        String decryptJson = "{\"buyerOrderNo\":\"" + decryptBuyerOrderNo + "\"}";
        insertCorruptedAsset(decryptBuyerOrderNo, "wrong-aad");
        auditBefore = countAudit();
        CommonResult<Object> decryptResp = postSignedCapturingFailure(
                ASSETS_PATH, decryptJson, "nonce-adec-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(decryptResp.getCode()).isEqualTo(500);
        // 解密失败先落 ASSET_DECRYPT 失败事件、再落外层 ASSET_QUERY 失败事件，共 2 条审计
        assertThat(countAudit()).isEqualTo(auditBefore + 2);

        // 7. 载荷解析失败：密文可解密但载荷非对象，按 PAYLOAD_FAILED 安全拒绝（脱敏500）
        String parseBuyerOrderNo = "ASSET-PARSE-" + UNIQUE_SEQ.incrementAndGet();
        createVoucherOrder(parseBuyerOrderNo);
        String parseJson = "{\"buyerOrderNo\":\"" + parseBuyerOrderNo + "\"}";
        insertCorruptedAsset(parseBuyerOrderNo, "not-json-object");
        auditBefore = countAudit();
        CommonResult<Object> parseResp = postSignedCapturingFailure(
                ASSETS_PATH, parseJson, "nonce-ap-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(parseResp.getCode()).isEqualTo(500);
        assertThat(countAudit()).isEqualTo(auditBefore + 2);
    }

    /** 查单被 BUYER_AUTH_INVALID 拒绝的断言（能力/凭证/项目状态阶段统一错误）。 */
    private static void assertQueryRejected(String queryJson, String nonce) {
        ResponseEntity<CommonResult<Object>> resp = postSignedRaw(
                QUERY_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), nonce);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    /** 签发报价并完成卡券签名下单，返回平台订单号。 */
    private static String createVoucherOrder(String buyerOrderNo) {
        issueQuote(PROJECT_A_CODE, buyerOrderNo, SKU_VOUCHER_CODE);
        String createJson = """
                {
                    "buyerOrderNo": "%s",
                    "buyerSkuCode": "%s",
                    "quantity": 1,
                    "target": {"deliveryMode": "BUYER_ASSET_QUERY"}
                }
                """.formatted(buyerOrderNo, BUYER_SKU_VOUCHER);
        ResponseEntity<CommonResult<Map<String, Object>>> resp = postSigned(
                ORDERS_PATH, createJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-cvo-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isZero();
        return (String) resp.getBody().getData().get("orderNo");
    }

    /** 将订单与履约请求置为指定状态（P3 矩阵构造等待/人工/终态/缺资产场景）；入参为买家订单号。 */
    private static void mutateOrderState(String buyerOrderNo, int orderStatus, int requestStatus) {
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM sc_supply_chain_order WHERE buyer_order_no = ?", String.class, buyerOrderNo);
        jdbc.update("UPDATE sc_supply_chain_order SET order_status = ?, updated_at = UTC_TIMESTAMP(3) "
                + "WHERE order_no = ?", orderStatus, orderNo);
        jdbc.update("""
                UPDATE sc_fulfillment_request r
                JOIN sc_supply_chain_order o ON r.order_id = o.id
                SET r.status = ?, r.updated_at = UTC_TIMESTAMP(3)
                WHERE o.order_no = ?
                """, requestStatus, orderNo);
    }

    /** 向指定卡券订单写入一条损坏资产：AAD 错误（解密失败）或明文非法（载荷解析失败）；入参为买家订单号。 */
    private static void insertCorruptedAsset(String buyerOrderNo, String corruptionMode) {
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM sc_supply_chain_order WHERE buyer_order_no = ?", String.class, buyerOrderNo);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM sc_supply_chain_order WHERE order_no = ?", Long.class, orderNo);
        Long requestId = jdbc.queryForObject(
                "SELECT id FROM sc_fulfillment_request WHERE order_id = ?", Long.class, orderId);
        Long bOrderId = jdbc.queryForObject(
                "SELECT id FROM sc_b_order WHERE fulfillment_request_id = ?", Long.class, requestId);
        Long operationId = jdbc.queryForObject(
                "SELECT id FROM sc_fulfillment_operation WHERE b_order_id = ? ORDER BY sequence_no DESC LIMIT 1",
                Long.class, bOrderId);
        Long supplierId = jdbc.queryForObject(
                "SELECT supplier_id FROM sc_b_order WHERE id = ?", Long.class, bOrderId);
        byte[] plaintext = "not-json-object".equals(corruptionMode)
                ? "[\"not-an-object\"]".getBytes(StandardCharsets.UTF_8)
                : "{\"cardCode\":\"CARD_X\",\"cardSecret\":\"SECRET_X\"}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "not-json-object".equals(corruptionMode)
                ? (bOrderId + "|1|CARD_CODE_SECRET").getBytes(StandardCharsets.UTF_8)
                : "wrong|aad".getBytes(StandardCharsets.UTF_8);
        EnvelopeCryptoPort.EncryptedPayload encrypted = envelopeCrypto.encrypt(plaintext, aad);
        Long assetId = UNIQUE_SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO sc_fulfillment_asset(id, asset_no, fulfillment_request_id, b_order_id,
                    operation_id, supplier_id, buyer_id, sequence_no, unit_quantity,
                    asset_type, encrypted_payload, masked_value, fingerprint, key_version,
                    lifecycle_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 'CARD_CODE_SECRET', ?, 'CAR***',
                    UNHEX(REPEAT('07', 32)), ?, 10, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, assetId, "FA-" + assetId, requestId, bOrderId, operationId, supplierId, BUYER_ID,
                encrypted.ciphertext(), encrypted.keyVersion());
    }

    private static long countAudit() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Long.class);
    }

    /** 敏感路径内部错误可能以非2xx返回；捕获后解析脱敏响应体的统一错误码。 */
    private static CommonResult<Object> postSignedCapturingFailure(String path, String json, String nonce) {
        try {
            ResponseEntity<CommonResult<Object>> resp = postSignedRaw(
                    path, json.getBytes(StandardCharsets.UTF_8),
                    credentialsA(), System.currentTimeMillis(), nonce);
            return resp.getBody();
        } catch (HttpServerErrorException ex) {
            try {
                return objectMapper.readValue(ex.getResponseBodyAsString(),
                        new com.fasterxml.jackson.core.type.TypeReference<CommonResult<Object>>() {});
            } catch (JsonProcessingException parseFailure) {
                throw new IllegalStateException("无法解析脱敏错误响应体", parseFailure);
            }
        }
    }

    /** 签名查询并以 Map 形式返回 data 字段（供直充终态断言复用）。 */
    private static Map<String, Object> postSignedForBody(String path, String json, String nonce) {
        ResponseEntity<CommonResult<Map<String, Object>>> resp = postSigned(
                path, json.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), nonce);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isZero();
        return resp.getBody().getData();
    }

    /** 供应商挡板入队一个 QNM 响应报文。 */
    private static void enqueueQnmResponse(int status, String body) {
        MOCK_SUPPLIER.enqueue(new MockResponse().setResponseCode(status).setBody(body));
    }

    /** 与生产 Timer 同一入口驱动真实履约执行处理器，并校验返回的结局类。 */
    private static void driveOperationHandler(String operationNo, String expectedOutcomeClass) throws Exception {
        FulfillmentOperationExecuteHandler handler = server.getBean(FulfillmentOperationExecuteHandler.class);
        String outcome = handler.handle(
                new TimerTaskContext("TIMER-CLOSURE-IT", 9_900_001L, 1, 1L, "closure-it-worker"),
                objectMapper.readTree("{\"operationNo\":\"" + operationNo + "\"}"));
        assertThat(outcome).isEqualTo(expectedOutcomeClass);
    }

    /** 按总单号取最新履约操作号（受理事务内已创建）。 */
    private static String latestOperationNo(String orderNo) {
        return jdbc.queryForObject("""
                SELECT op.operation_no FROM sc_fulfillment_operation op
                JOIN sc_b_order b ON op.b_order_id = b.id
                JOIN sc_supply_chain_order o ON b.order_id = o.id
                WHERE o.order_no = ? ORDER BY op.sequence_no DESC LIMIT 1
                """, String.class, orderNo);
    }

    /** 恢复直充迁移发布门禁 READY（与 seedFixtures 相同的制品与密钥指纹）。 */
    private static void restoreMigrationGateReady() {
        byte[] gateFingerprint = server.getBean(RechargeFrequencyMigrationGateService.class).currentKeyFingerprint();
        jdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='READY', verified_artifact=?, "
                + "identity_key_fingerprint=?, verified_at=NOW(3) WHERE id=1",
                "a".repeat(40), gateFingerprint);
    }

    private static void issueQuote(String projectCode, String buyerOrderNo, String platformSkuCode) {
        StandardPricingQuoteReqDTO req = new StandardPricingQuoteReqDTO();
        req.setSourceSystem(SOURCE_SYSTEM);
        req.setExternalTenantId(EXTERNAL_TENANT_ID);
        req.setProjectCode(projectCode);
        req.setPlatformSkuCode(platformSkuCode);
        req.setBuyerOrderNo(buyerOrderNo);
        req.setQuantity(1L);
        quoteService.quote(req);
    }

    private static ProjectAppAuthCredentials credentialsA() {
        return new ProjectAppAuthCredentials(APP_A, APP_KEY, KEY_VERSION, null, null);
    }

    private static ProjectAppAuthCredentials credentialsB() {
        return new ProjectAppAuthCredentials(APP_B, APP_KEY, KEY_VERSION, null, null);
    }

    private static ResponseEntity<CommonResult<Map<String, Object>>> postSigned(
            String path, byte[] body, ProjectAppAuthCredentials credentials, long timestamp, String nonce) {
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                credentials, HttpMethod.POST.name(), path, body, timestamp, nonce);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::add);

        return REST.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, httpHeaders),
                new ParameterizedTypeReference<>() {});
    }

    private static ResponseEntity<CommonResult<Object>> postSignedRaw(
            String path, byte[] body, ProjectAppAuthCredentials credentials, long timestamp, String nonce) {
        // 签名 path 使用注册路径（不含 query string）
        String registeredPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                credentials, HttpMethod.POST.name(), registeredPath, body, timestamp, nonce);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::add);

        return REST.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, httpHeaders),
                new ParameterizedTypeReference<>() {});
    }

    private static HttpHeaders httpHeadersWithJsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static void seedFixtures() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        // 1. 采购商与项目
        BuyerCommandService buyers = server.getBean(BuyerCommandService.class);
        String assignedBuyerCode = buyers.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "闭环分销商", "闭环分销商", null, null,
                SOURCE_SYSTEM, EXTERNAL_TENANT_ID, now.minusMinutes(5), 1L));
        jdbc.update("UPDATE sc_buyer_tenant_binding SET buyer_id = ? WHERE buyer_id = (SELECT id FROM sc_buyer WHERE buyer_code = ?)",
                BUYER_ID, assignedBuyerCode);
        jdbc.update("UPDATE sc_buyer SET id = ?, buyer_code = ? WHERE buyer_code = ?",
                BUYER_ID, BUYER_CODE, assignedBuyerCode);

        BuyerProjectMapper projectMapper = server.getBean(BuyerProjectMapper.class);
        insertProject(projectMapper, PROJECT_A_ID, PROJECT_A_CODE, BUYER_ID, now);
        insertProject(projectMapper, PROJECT_B_ID, PROJECT_B_CODE, BUYER_ID, now);

        // 2. 项目能力开通 (STANDARD_PRODUCT)
        jdbc.update("""
                INSERT INTO sc_project_capability(
                    id, project_id, capability_code, status, version, created_at, updated_at)
                VALUES (9520001, ?, 'STANDARD_PRODUCT', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_A_ID);
        jdbc.update("""
                INSERT INTO sc_project_capability(
                    id, project_id, capability_code, status, version, created_at, updated_at)
                VALUES (9520002, ?, 'STANDARD_PRODUCT', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_B_ID);

        // 3. 项目应用与凭证
        jdbc.update("""
                INSERT INTO sc_project_application(
                    id, project_id, app_id, status, version, created_at, updated_at)
                VALUES (9530001, ?, ?, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_A_ID, APP_A);
        jdbc.update("""
                INSERT INTO sc_project_application(
                    id, project_id, app_id, status, version, created_at, updated_at)
                VALUES (9530002, ?, ?, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_B_ID, APP_B);

        insertCredential(9540001L, 9530001L, APP_A, KEY_VERSION, APP_KEY);
        insertCredential(9540002L, 9530002L, APP_B, KEY_VERSION, APP_KEY);

        // 4. 账户充值
        BuyerAccountOpeningService accounts = server.getBean(BuyerAccountOpeningService.class);
        accounts.open(new BuyerAccountOpeningService.OpeningBalanceCommand(
                BUYER_CODE, Money.cny(10_000_000L), 1L, "evidence://closure",
                new byte[32], "标准HTTP闭环测试充值", "trace-closure", null));

        // 5. 商品、SPU、SKU、Listing
        ProductCatalogCommandService catalog = server.getBean(ProductCatalogCommandService.class);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand("BRD_001", "闭环品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand("CAT_001", "闭环类目", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);

        // 卡券 SPU & SKU
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_001", "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "闭环卡券SPU", null, null, "测试卡券", FulfillmentType.VOUCHER_CODE.code(), 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                SKU_VOUCHER_CODE, "SPU_001", "闭环卡券100", FulfillmentType.VOUCHER_CODE.code(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus(SKU_VOUCHER_CODE, CatalogEntityStatus.ACTIVE, 1L);

        String listingVoucher = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                BUYER_CODE, SKU_VOUCHER_CODE, BUYER_SKU_VOUCHER, 1L));
        server.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(
                        listingVoucher, 0L, 9_800L, "闭环卡券100", now.minusMinutes(1), null,
                        "首次发布", 1L, "trace-v", null, null));
        setAvailability(BUYER_SKU_VOUCHER, ListingAvailability.AVAILABLE, "SUPPLY_READY");

        // 直充 SPU & SKU
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_002", "BRD_001", "CAT_001", "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "闭环直充SPU", null, null, "测试直充", FulfillmentType.DIRECT_RECHARGE.code(), 1L));
        catalog.changeSpuStatus("SPU_002", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                SKU_DIRECT_CODE, "SPU_002", "闭环直充100", FulfillmentType.DIRECT_RECHARGE.code(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus(SKU_DIRECT_CODE, CatalogEntityStatus.ACTIVE, 1L);

        String listingDirect = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                BUYER_CODE, SKU_DIRECT_CODE, BUYER_SKU_DIRECT, 1L));
        server.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(
                        listingDirect, 0L, 9_800L, "闭环直充100", now.minusMinutes(1), null,
                        "首次发布", 1L, "trace-d", null, null));
        setAvailability(BUYER_SKU_DIRECT, ListingAvailability.AVAILABLE, "SUPPLY_READY");

        // 6. 供应商与主路由
        PlatformSkuDO voucherSku = server.getBean(PlatformSkuMapper.class).selectByCode(SKU_VOUCHER_CODE);
        PlatformSkuDO directSku = server.getBean(PlatformSkuMapper.class).selectByCode(SKU_DIRECT_CODE);
        createSupplierOffer(voucherSku, now);
        createSupplierOffer(directSku, now);

        RoutingPolicyPresetFixture.deactivatePresetPolicy(jdbc);
        RoutingPolicyPublicationService routing = server.getBean(RoutingPolicyPublicationService.class);
        RoutingPolicyPublicationService.DraftResult draft = routing.createDraft(
                new RoutingPolicyPublicationService.CreateDraftCommand(
                        "CARD_PRIMARY", "卡券与直充主路由", "标准受理项目键路由",
                        now.minusHours(1), now.plusDays(1), List.of(
                        new RoutingPolicyPublicationService.RuleDraft(
                                "WS_TELCO", "吾盛话费路由", "FIXED", "QINAMENG",
                                "SPECIFIED", List.of(BUYER_CODE),
                                "ALL", List.of(),
                                "SPECIFIED", List.of("EQUITY_PRODUCT"),
                                "ALL", List.of(),
                                null, "STABLE", "PRICE", "COMPOSITE",
                                10, (short) 10, false, List.of())), 1L, 40, 30, 30));
        routing.publish(new RoutingPolicyPublicationService.PublishCommand(
                draft.policyVersionId(), 0L, "标准受理项目键主路由", 1L,
                "trace-routing", null, null));

        // 7. 直充限频门禁置 READY
        byte[] gateFingerprint = server.getBean(RechargeFrequencyMigrationGateService.class).currentKeyFingerprint();
        jdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='READY', verified_artifact=?, "
                + "identity_key_fingerprint=?, verified_at=NOW(3) WHERE id=1",
                "a".repeat(40), gateFingerprint);
    }

    private static void insertProject(BuyerProjectMapper mapper, Long id, String projectCode, Long buyerId, LocalDateTime now) {
        BuyerProjectDO project = BuyerProjectDO.builder()
                .id(id)
                .projectCode(projectCode)
                .projectName(projectCode)
                .buyerId(buyerId)
                .catalogMode("MANAGED")
                .status(BuyerProjectStatus.ACTIVE.getCode())
                .version(0L)
                .createdBy(1L)
                .createdAt(now)
                .updatedBy(1L)
                .updatedAt(now)
                .build();
        mapper.insert(project);
    }

    private static void insertCredential(long id, long applicationId, String appId, int keyVersion, String appKey) {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, appId, keyVersion, appKey);
        jdbc.update("""
                INSERT INTO sc_project_app_credential_version(
                    id, application_id, key_version, secret_ref, masked_hint, status,
                    valid_from, valid_to, active_slot, created_at)
                VALUES (?, ?, ?, ?, 'pro***key', 'CURRENT',
                    UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE, NULL, 'CURRENT', UTC_TIMESTAMP(3))
                """, id, applicationId, keyVersion, secretRef);
    }

    private static void setAvailability(String buyerSkuCode, ListingAvailability value, String reason) {
        BuyerListingMapper listingMapper = server.getBean(BuyerListingMapper.class);
        BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(BUYER_ID, buyerSkuCode);
        BuyerListingAvailabilityMapper mapper = server.getBean(BuyerListingAvailabilityMapper.class);
        BuyerListingAvailabilityDO availability = mapper.selectByListingId(listing.getId());
        availability.setAvailability(value.getCode());
        availability.setReasonCode(reason);
        availability.setEvaluatedAt(LocalDateTime.now(ZoneOffset.UTC));
        availability.setBasisVersion(availability.getBasisVersion() + 1);
        availability.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        mapper.updateById(availability);
    }

    private static void createSupplierOffer(PlatformSkuDO sku, LocalDateTime now) {
        SupplierMapper supplierMapper = server.getBean(SupplierMapper.class);
        SupplierDO supplier = supplierMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SupplierDO>()
                .eq(SupplierDO::getSupplierCode, "QINAMENG"));
        if (supplier == null) {
            supplier = SupplierDO.builder().supplierCode("QINAMENG").supplierName("奇那梦")
                    .shortName("奇那梦").supplierType("STANDARD_SUPPLIER")
                    .status(SupplierStatus.ACTIVE.getCode()).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            supplierMapper.insert(supplier);

            CredentialSlotDO credential = CredentialSlotDO.builder().slotNo("CRD_QNM")
                    .providerType("ENV").secretRef("env:QNM_CREDENTIAL")
                    .maskedHint("qnm-****").activeSecretVersion("v1").status((short) 10).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            server.getBean(CredentialSlotMapper.class).insert(credential);

            SupplierConnectorDO connector = SupplierConnectorDO.builder().connectorNo("CON_QNM")
                    .supplierId(supplier.getId()).connectorCode("GAME_ORDER")
                    .adapterType("QNM_GAME_ORDER_V1").status((short) 10).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            SupplierConnectorMapper connectorMapper = server.getBean(SupplierConnectorMapper.class);
            connectorMapper.insert(connector);

            SupplierConnectorVersionDO connectorVersion = SupplierConnectorVersionDO.builder()
                    .connectorId(connector.getId()).versionNo(1).baseUrl("https://qnm.invalid")
                    .merchantAccount("fixture").credentialSlotId(credential.getId()).protocolVersion("V1")
                    .callbackPath("/callback").connectTimeoutMs(1000).readTimeoutMs(3000)
                    .rateLimit(10).concurrencyLimit(2).adapterConfigSchemaVersion(1)
                    .adapterConfigJson("{}").validationStatus((short) 10).validatedAt(now)
                    .validationExpiresAt(now.plusDays(1)).publishedAt(now).configDigest(new byte[32])
                    .status(ConnectorVersionStatus.PUBLISHED.getCode()).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            server.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);

            connector.setCurrentVersionId(connectorVersion.getId());
            connector.setUpdatedAt(now);
            connectorMapper.updateById(connector);
        }

        Long connectorVersionId = server.getBean(SupplierConnectorVersionMapper.class)
                .selectList(null).get(0).getId();

        InventorySourceDO inventory = InventorySourceDO.builder().sourceNo("INV_QNM_" + sku.getSkuCode())
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .sourceType("REMOTE_ON_DEMAND").externalSourceCode("QNM_REMOTE_" + sku.getSkuCode())
                .ownerType("SUPPLIER").status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        server.getBean(InventorySourceMapper.class).insert(inventory);

        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OFR_QNM_" + sku.getSkuCode())
                .offerCode(sku.getSkuCode()).supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        SupplierOfferMapper offerMapper = server.getBean(SupplierOfferMapper.class);
        offerMapper.insert(offer);

        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId())
                .versionNo(1).externalSkuCode("QNM-" + sku.getSkuCode()).supplierSettlementUnitPrice(9_000L)
                .currency("CNY").connectorVersionId(connectorVersionId)
                .inventorySourceId(inventory.getId()).minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .primaryCapabilityCode("CREATE_RECHARGE").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusHours(1)).publishReason("标准受理项目键联调")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        server.getBean(SupplierOfferVersionMapper.class).insert(version);

        offer.setCurrentVersionId(version.getId());
        offer.setUpdatedAt(now);
        offerMapper.updateById(offer);
    }

    private static String[] commandLineArguments() {
        String masterKeyBase64 = Base64.getEncoder().encodeToString(MASTER_KEY);
        return new String[]{
                "--spring.profiles.active=project-http-closure-it",
                "--spring.config.import=optional:classpath:application-project-http-closure-it.yaml",
                "--server.port=0",
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.cloud.bootstrap.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.config.import-check.enabled=false",
                "--spring.boot.admin.client.enabled=false",
                "--aifuli.tenant.enable=false",
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
                "--aifuli.web.admin-ui.url=http://127.0.0.1/project-http-closure-it",
                "--aifuli.supply-chain.enabled=true",
                "--aifuli.supply-chain.datasource.url=" + jdbcUrl(),
                "--aifuli.supply-chain.datasource.username=" + jdbcUsername(),
                "--aifuli.supply-chain.datasource.password=" + jdbcPassword(),
                "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--aifuli.supply-chain.security.key-version=test-v1",
                "--aifuli.supply-chain.security.envelope-key-base64=" + masterKeyBase64,
                "--aifuli.supply-chain.security.fingerprint-key-base64=" + masterKeyBase64,
                "--aifuli.supply-chain.auth.enabled=true",
                "--aifuli.supply-chain.auth.master-key=" + masterKeyBase64,
                "--aifuli.supply-chain.auth.nonce-cleanup-interval=PT24H",
                "--QNM_CREDENTIAL=" + QNM_CREDENTIAL,
                "--logging.level.org.springframework.web=DEBUG",
                "--logging.level.com.aifuli=DEBUG"
        };
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String jdbcUsername() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String jdbcPassword() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    @Profile("project-http-closure-it")
    @Configuration(proxyBeanMethods = false)
    static class TestOverrides {
        @Bean
        @Primary
        GenericBatchPricingService genericBatchPricingService(PlatformSkuMapper skuMapper,
                                                              BuyerListingMapper listingMapper,
                                                              BuyerMapper buyerMapper,
                                                              ObjectMapper mapper) throws JsonProcessingException {
            GenericBatchPricingService service = mock(GenericBatchPricingService.class);
            FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
            PricingConfig config = new PricingConfig(direct, direct,
                    new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                    new HideRule(null, null),
                    new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            String configJson = mapper.writeValueAsString(config);
            when(service.ensureCurrentProjectVersion(anyString(), anyString())).thenAnswer(invocation -> {
                String platformSkuCode = invocation.getArgument(1);
                PlatformSkuDO sku = skuMapper.selectByCode(platformSkuCode);
                Long buyerId = buyerMapper.selectByBuyerCode(BUYER_CODE).getId();
                String buyerSku = SKU_VOUCHER_CODE.equals(platformSkuCode) ? BUYER_SKU_VOUCHER : BUYER_SKU_DIRECT;
                BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(buyerId, buyerSku);
                ProjectSkuPricingVersionDO version = ProjectSkuPricingVersionDO.builder()
                        .id(9L).pricingStatus(PricingStatus.READY.name())
                        .distributionPriceCents(9_800L).salesPriceCents(10_000L)
                        .effectiveConfigJson(configJson).build();
                return new CurrentProjectPricing(version, listing.getId(), sku.getId(), sku.getSkuCode());
            });
            return service;
        }

        @Bean
        RechargeFrequencyIdentityKeyProvider rechargeFrequencyIdentityKeyProvider() {
            byte[] key = new byte[32];
            return () -> key.clone();
        }

        /**
         * 履约执行链路的连接器元数据解析按既有集成测试模式打桩（同
         * RechargeFrequencyExecutionIntegrationTest）：capability/状态映射等发布元数据
         * 由发布流程保障，闭环测试关注凭证解析、QNM 协议外呼、状态机与加密资产落库，
         * 因此直接给出指向本地挡板的连接器配置与真实 credential_slot_id。
         */
        @Bean
        @Primary
        PublishedConnectorConfigurationResolver publishedConnectorConfigurationResolver(DataSource supplyChainDataSource) {
            var resolver = mock(PublishedConnectorConfigurationResolver.class);
            when(resolver.resolve(anyLong())).thenAnswer(invocation -> {
                Long connectorVersionId = invocation.getArgument(0, Long.class);
                Long credentialSlotId = new JdbcTemplate(supplyChainDataSource).queryForObject(
                        "SELECT credential_slot_id FROM sc_supplier_connector_version WHERE id = ?",
                        Long.class, connectorVersionId);
                String mockBaseUrl = MOCK_SUPPLIER.url("/").toString();
                return new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                        "CON_QNM", connectorVersionId, 1, "QNM_GAME_ORDER_V1",
                        mockBaseUrl.endsWith("/") ? mockBaseUrl.substring(0, mockBaseUrl.length() - 1) : mockBaseUrl,
                        "fixture", credentialSlotId,
                        null, Set.of(Capability.CREATE_RECHARGE, Capability.QUERY_ORDER));
            });
            return resolver;
        }
    }
}
