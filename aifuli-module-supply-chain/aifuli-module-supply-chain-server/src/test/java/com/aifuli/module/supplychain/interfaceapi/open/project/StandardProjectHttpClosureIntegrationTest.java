package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.SupplyChainServerApplication;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService;
import com.aifuli.module.supplychain.infrastructure.auth.LocalEnvelopeAppKeyCodec;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
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

    private static final String ORDERS_PATH = ProjectAuthOperations.STANDARD_ORDERS_PATH;
    private static final String QUERY_PATH = ORDERS_PATH + "/query";
    private static final String ASSETS_PATH = ORDERS_PATH + "/assets/query";

    private static final AtomicLong UNIQUE_SEQ = new AtomicLong(1000);
    private static final RestTemplate REST = new RestTemplate();

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
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    /**
     * 场景一：虚拟卡券标准订单全流程闭环：
     * 内部报价 -> 签名下单成功 -> 同参新nonce重放返回相同orderNo且不重扣 -> 签名查单返回PROCESSING+未就绪 ->
     * 模拟履约写入加密卡券资产 -> 查单返回SUCCEEDED+就绪 -> 资产查询返回解密明文卡密与独立审计。
     */
    @Test
    void testVoucherOrderFullClosure() {
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

        // 5. 模拟供应商履约成功：更新订单终态，并写入信封加密卡券资产
        Long orderId = jdbc.queryForObject("SELECT id FROM sc_supply_chain_order WHERE order_no = ?", Long.class, orderNo);
        Long fulfillmentRequestId = jdbc.queryForObject(
                "SELECT id FROM sc_fulfillment_request WHERE order_id = ?", Long.class, orderId);
        List<Long> bOrderIds = jdbc.queryForList(
                "SELECT id FROM sc_b_order WHERE fulfillment_request_id = ?", Long.class, fulfillmentRequestId);
        Long bOrderId;
        Long supplierId;
        if (bOrderIds.isEmpty()) {
            bOrderId = UNIQUE_SEQ.incrementAndGet();
            supplierId = jdbc.queryForObject("SELECT id FROM sc_supplier WHERE supplier_code = 'QINAMENG'", Long.class);
            jdbc.update("""
                    INSERT INTO sc_b_order(id, b_order_no, fulfillment_request_id, order_id, attempt_no,
                        supplier_id, offer_id, offer_version_id, connector_version_id, inventory_source_id,
                        billno, idempotency_key, requested_quantity, fulfilled_quantity,
                        supplier_settlement_unit_price, expected_supplier_settlement_amount, currency,
                        fulfillment_status, settlement_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 1, ?, 1, 1, 1, 1, 'BILL-1', 'IDEMP-1', 1, 1, 100, 100, 'CNY',
                        40, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, bOrderId, "BO-" + bOrderId, fulfillmentRequestId, orderId, supplierId);
        } else {
            bOrderId = bOrderIds.get(0);
            supplierId = jdbc.queryForObject("SELECT supplier_id FROM sc_b_order WHERE id = ?", Long.class, bOrderId);
        }
        List<Long> existingOpIds = jdbc.queryForList(
                "SELECT id FROM sc_fulfillment_operation WHERE b_order_id = ? ORDER BY sequence_no DESC LIMIT 1", Long.class, bOrderId);
        Long operationId;
        if (existingOpIds.isEmpty()) {
            operationId = UNIQUE_SEQ.incrementAndGet();
            jdbc.update("""
                    INSERT INTO sc_fulfillment_operation(id, operation_no, b_order_id, operation_kind,
                        adapter_step_code, sequence_no, idempotency_key, config_digest, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'FULFILL', 'SEND_KM', 1, 'OP-KEY-1', UNHEX(REPEAT('04', 32)), 40,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                    """, operationId, "OP-" + operationId, bOrderId);
        } else {
            operationId = existingOpIds.get(0);
        }

        // 加密卡密载荷
        String plainPayload = "{\"cardCode\":\"CARD_CLOSURE_123\",\"cardSecret\":\"SECRET_CLOSURE_999\"}";
        byte[] aad = (bOrderId + "|1|CARD_CODE_SECRET").getBytes(StandardCharsets.UTF_8);
        EnvelopeCryptoPort.EncryptedPayload encrypted = envelopeCrypto.encrypt(
                plainPayload.getBytes(StandardCharsets.UTF_8), aad);
        byte[] ciphertext = encrypted.ciphertext();
        String keyVersion = encrypted.keyVersion();

        Long assetId = UNIQUE_SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO sc_fulfillment_asset(id, asset_no, fulfillment_request_id, b_order_id,
                    operation_id, supplier_id, buyer_id, sequence_no, unit_quantity,
                    asset_type, encrypted_payload, masked_value, fingerprint, key_version,
                    lifecycle_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 'CARD_CODE_SECRET', ?, 'CARD***',
                    UNHEX(REPEAT('05', 32)), ?, 10, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, assetId, "FA-" + assetId, fulfillmentRequestId, bOrderId, operationId, supplierId, BUYER_ID,
                ciphertext, keyVersion);

        jdbc.update("""
                UPDATE sc_fulfillment_request
                SET status = 40, fulfilled_quantity = 1, updated_at = UTC_TIMESTAMP(3)
                WHERE id = ?
                """, fulfillmentRequestId);

        jdbc.update("""
                UPDATE sc_supply_chain_order
                SET order_status = 30, business_stage = 'CARD_DELIVERED', updated_at = UTC_TIMESTAMP(3)
                WHERE id = ?
                """, orderId);

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
     * 报价 -> 签名下单成功 -> 查单成功 -> 资产查询明确返回不支持提示。
     */
    @Test
    void testDirectRechargeOrderClosure() {
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

        // 4. 资产查询：直充不支持卡券资产，返回错误或提示
        ResponseEntity<CommonResult<Object>> assetResp = postSignedRaw(
                ASSETS_PATH, queryJson.getBytes(StandardCharsets.UTF_8),
                credentialsA(), System.currentTimeMillis(), "nonce-da-" + UNIQUE_SEQ.incrementAndGet());
        assertThat(assetResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetResp.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        // 直充订单查询资产契约：返回业务错误或不支持提示
        assertThat(assetResp.getBody()).isNotNull();
        assertThat(assetResp.getBody().getCode()).isNotZero();
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
    }
}
