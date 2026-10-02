package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.StandardOrderIdentityMigrationService;
import com.aifuli.module.supplychain.border.fulfillment.application.StandardOrderIdentityMigrationService.Code;
import com.aifuli.module.supplychain.border.fulfillment.application.StandardOrderIdentityMigrationService.IdentityReport;
import com.aifuli.module.supplychain.border.fulfillment.application.StandardOrderIdentityMigrationService.MigrationException;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy.RequestIdentity;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingQuoteStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 上验证标准订单身份离线迁移服务（052 后、门禁 READY 前）的安全与幂等行为：
 * 可核验历史订单重建格式 2 指纹/冻结绑定/手机快照并记录 SYSTEM_WORKER 审计；卡券目标排除每人限购
 * 手机快照；解密失败或快照矛盾阻断全批（无部分更新）；未建单回执与历史 NULL 项目订单不重建不阻断；
 * 幂等重跑只校验绑定后跳过；无事务调用被 MANDATORY 拒绝。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#51-离线应用迁移与实际手机号覆盖。
 */
class StandardOrderIdentityMigrationIntegrationTest {

    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static final long BUYER_ID = 1L;
    private static final long PLATFORM_SKU_ID = 901L;
    private static final String PROJECT_CODE = "PRJ_MIG";
    private static final String BUYER_SKU_CODE = "WS-MIG-100";
    private static final String MOBILE = "13800138000";
    private static final String SCHEMA_DIRECT = "DIRECT_RECHARGE_TARGET";
    private static final String SCHEMA_VOUCHER = "VOUCHER_DELIVERY_TARGET";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    private ConfigurableApplicationContext context;
    private FulfillmentAcceptanceReceiptMapper receipts;
    private FulfillmentRequestMapper requests;
    private SupplyChainOrderMapper orders;
    private StandardPricingQuoteMapper quotes;
    private BuyerProjectMapper projects;
    private SensitiveAccessAuditMapper audits;
    private StandardOrderIdentityMigrationService migration;
    private EnvelopeCryptoPort crypto;
    private HmacFingerprintPort hmac;
    private SupplyChainDatabaseClock clock;
    private ObjectMapper json;
    private TransactionTemplate transactions;

    @BeforeEach
    void startContainerAndContext() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        context = applicationContext();
        receipts = context.getBean(FulfillmentAcceptanceReceiptMapper.class);
        requests = context.getBean(FulfillmentRequestMapper.class);
        orders = context.getBean(SupplyChainOrderMapper.class);
        quotes = context.getBean(StandardPricingQuoteMapper.class);
        projects = context.getBean(BuyerProjectMapper.class);
        audits = context.getBean(SensitiveAccessAuditMapper.class);
        migration = context.getBean(StandardOrderIdentityMigrationService.class);
        crypto = context.getBean(EnvelopeCryptoPort.class);
        hmac = context.getBean(HmacFingerprintPort.class);
        clock = context.getBean(SupplyChainDatabaseClock.class);
        json = new ObjectMapper();
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @AfterEach
    void stopContextAndContainer() {
        if (context != null) {
            context.close();
        }
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void rebuildsFormatZeroReceiptAndCoversMobileSnapshotIdempotently() {
        insertProject();
        HistoricalOrder order = insertAcceptedOrderChain(101L, "SC_MIG_101", "ORD_MIG_A",
                encryptedDirectTarget("ORD_MIG_A", MOBILE), SCHEMA_DIRECT);

        IdentityReport first = transactions.execute(status -> migration.migrateIdentitySnapshots());

        assertThat(first.rebuiltCount()).isEqualTo(1);
        FulfillmentAcceptanceReceiptDO rebuiltReceipt = receipts.selectById(order.receiptId());
        // 回执格式 2 与冻结绑定回填：与真实请求/订单一致，供查单与重放比较使用。
        assertThat(rebuiltReceipt.getFingerprintVersion())
                .isEqualTo((short) StandardAcceptanceFingerprintPolicy.FORMAT_VERSION);
        assertThat(rebuiltReceipt.getPlatformSkuId()).isEqualTo(PLATFORM_SKU_ID);
        assertThat(rebuiltReceipt.getTargetSchemaVersion()).isEqualTo(1);
        assertThat(rebuiltReceipt.getPricingQuoteId()).isEqualTo(order.quoteId());
        // 指纹与受理期同源：Java 端按同一规范化策略复算必须逐字节一致。
        byte[] expectedFingerprint = sha256(StandardAcceptanceFingerprintPolicy.canonicalRequest(
                new RequestIdentity(BUYER_ID, PROJECT_CODE, "ORD_MIG_A", BUYER_SKU_CODE,
                        PLATFORM_SKU_ID, 1L, SCHEMA_DIRECT, 1,
                        expectedDigestHex(MOBILE))));
        assertThat(rebuiltReceipt.getRequestFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(rebuiltReceipt.getInvariantFingerprint()).isEqualTo(expectedFingerprint);
        SupplyChainOrderDO rebuiltOrder = orders.selectById(101L);
        assertThat(rebuiltOrder.getRequestFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(rebuiltOrder.getBuyerOrderDigest()).isEqualTo(sha256(
                StandardAcceptanceFingerprintPolicy.canonicalBuyerOrderDigestInput(
                        PROJECT_CODE, "ORD_MIG_A")));
        // 实际履约目标恢复：MOBILE 类型与规范化手机快照落库，供每人限购统计使用。
        assertThat(rebuiltOrder.getFulfillmentAccountType())
                .isEqualTo(FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE);
        assertThat(rebuiltOrder.getFulfillmentAccountValue()).isEqualTo(MOBILE);
        assertThat(audits.selectCount(new LambdaQueryWrapper<SensitiveAccessAuditDO>()
                .eq(SensitiveAccessAuditDO::getAccessType, "TARGET_DECRYPT")
                .eq(SensitiveAccessAuditDO::getPrincipalType, "SYSTEM_WORKER")
                .eq(SensitiveAccessAuditDO::getPrincipalId, "STANDARD_ORDER_IDENTITY_MIGRATION")
                .eq(SensitiveAccessAuditDO::getResultStatus, (short) 10)
                .eq(SensitiveAccessAuditDO::getReasonCode, "IDENTITY_REBUILT"))).isEqualTo(1L);

        // 幂等重跑：已重建订单只校验绑定一致后跳过，不产生新指纹/审计/改动。
        IdentityReport second = transactions.execute(status -> migration.migrateIdentitySnapshots());
        assertThat(second.rebuiltCount()).isZero();
        assertThat(second.rebuiltSkipped()).isEqualTo(1);
        assertThat(receipts.selectById(order.receiptId()).getRequestFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(audits.selectCount(new LambdaQueryWrapper<SensitiveAccessAuditDO>()
                .eq(SensitiveAccessAuditDO::getPrincipalId, "STANDARD_ORDER_IDENTITY_MIGRATION"))).isEqualTo(1L);
    }

    @Test
    void voucherTargetRebuildsFingerprintWithoutMobileSnapshot() {
        insertProject();
        HistoricalOrder order = insertAcceptedOrderChain(102L, "SC_MIG_102", "ORD_MIG_V",
                encryptedVoucherTarget("ORD_MIG_V"), SCHEMA_VOUCHER);

        IdentityReport report = transactions.execute(status -> migration.migrateIdentitySnapshots());

        assertThat(report.rebuiltCount()).isEqualTo(1);
        SupplyChainOrderDO rebuiltOrder = orders.selectById(102L);
        // 卡券目标（无 accountType 字段）不进入每人限购手机快照：类型与值保持空，非以 NULL 误认已验证；
        // 格式 2 指纹与冻结绑定照常重建（重放比较不区分手机号/卡券目标）。
        assertThat(rebuiltOrder.getFulfillmentAccountType()).isNull();
        assertThat(rebuiltOrder.getFulfillmentAccountValue()).isNull();
        assertThat(receipts.selectById(order.receiptId()).getFingerprintVersion())
                .isEqualTo((short) StandardAcceptanceFingerprintPolicy.FORMAT_VERSION);
    }

    @Test
    void decryptFailureBlocksWholeBatchWithoutPartialUpdate() {
        insertProject();
        HistoricalOrder healthy = insertAcceptedOrderChain(103L, "SC_MIG_103", "ORD_MIG_OK",
                encryptedDirectTarget("ORD_MIG_OK", MOBILE), SCHEMA_DIRECT);
        HistoricalOrder broken = insertAcceptedOrderChain(104L, "SC_MIG_104", "ORD_MIG_BAD",
                encryptedDirectTarget("ORD_MIG_BAD", MOBILE), SCHEMA_DIRECT);
        corruptCiphertext(broken.requestId());

        // 全批验证失败：异常经事务模板回滚，审计与任何回执/订单更新一并回滚，无部分更新。
        assertThatThrownBy(() -> transactions.execute(status -> migration.migrateIdentitySnapshots()))
                .isInstanceOf(MigrationException.class)
                .extracting(exception -> ((MigrationException) exception).code())
                .isEqualTo(Code.TARGET_UNRECOVERABLE);
        assertThat(receipts.selectById(healthy.receiptId()).getFingerprintVersion()).isEqualTo((short) 0);
        assertThat(receipts.selectById(broken.receiptId()).getFingerprintVersion()).isEqualTo((short) 0);
        assertThat(orders.selectById(103L).getFulfillmentAccountType()).isNull();
        assertThat(orders.selectById(104L).getFulfillmentAccountType()).isNull();
        assertThat(audits.selectCount(new LambdaQueryWrapper<SensitiveAccessAuditDO>()
                .eq(SensitiveAccessAuditDO::getPrincipalId, "STANDARD_ORDER_IDENTITY_MIGRATION"))).isZero();
    }

    @Test
    void mobileSnapshotConflictWithHistoricalValueBlocksRebuild() {
        insertProject();
        // 历史订单已固化的履约账号与解密出的真实目标矛盾：不可信历史阻断覆盖，不静默改写。
        HistoricalOrder order = insertAcceptedOrderChain(105L, "SC_MIG_105", "ORD_MIG_C",
                encryptedDirectTarget("ORD_MIG_C", MOBILE), SCHEMA_DIRECT);
        SupplyChainOrderDO conflicting = orders.selectById(105L);
        conflicting.setFulfillmentAccountValue("13900139000");
        orders.updateById(conflicting);

        assertThatThrownBy(() -> transactions.execute(status -> migration.migrateIdentitySnapshots()))
                .isInstanceOf(MigrationException.class)
                .extracting(exception -> ((MigrationException) exception).code())
                .isEqualTo(Code.ACCOUNT_CONFLICT);
        assertThat(receipts.selectById(order.receiptId()).getFingerprintVersion()).isEqualTo((short) 0);
    }

    @Test
    void unbuiltReceiptAndNullProjectOrderAreSkippedWithoutRebuild() {
        insertProject();
        // 未建单的 REJECTED_FINAL 历史回执：无可信目标/报价关联，保持格式 0，仅保留查询进度/拒绝语义。
        LocalDateTime now = clock.now();
        FulfillmentAcceptanceReceiptDO unbuilt = FulfillmentAcceptanceReceiptDO.builder()
                .receiptNo("RCP_MIG_UNBUILT").buyerId(BUYER_ID).projectId(801L)
                .buyerOrderNo("ORD_MIG_REJ").buyerSkuCode(BUYER_SKU_CODE).quantity(1L)
                .targetSchemaCode(SCHEMA_DIRECT).targetDigest(new byte[32])
                .invariantFingerprint(new byte[32]).requestFingerprint(new byte[32])
                .listingVersionNo(3).attemptNo(1)
                .status(AcceptanceReceiptStatus.REJECTED_FINAL.getCode())
                .rejectionCode(AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name())
                .rejectedAt(now).createdAt(now).updatedAt(now).version(0L).build();
        receipts.insert(unbuilt);
        // 历史 NULL 项目 CARD 订单：不从 source 推测归属，跳过且不阻断（回执关联由限频迁移孤儿检查兜底）。
        // 004/013 CHECK 要求 CARD 必带回执/平台SKU/数量1：配一条 NULL 项目的 REJECTED_FINAL 历史回执。
        FulfillmentAcceptanceReceiptDO legacyReceipt = FulfillmentAcceptanceReceiptDO.builder()
                .receiptNo("RCP_MIG_NULLPRJ").buyerId(BUYER_ID).projectId(null)
                .buyerOrderNo("ORD_MIG_NULLPRJ").buyerSkuCode(BUYER_SKU_CODE).quantity(1L)
                .targetSchemaCode(SCHEMA_DIRECT).targetDigest(new byte[32])
                .invariantFingerprint(new byte[32]).requestFingerprint(new byte[32])
                .listingVersionNo(3).attemptNo(1)
                .status(AcceptanceReceiptStatus.REJECTED_FINAL.getCode())
                .rejectionCode(AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER.name())
                .rejectedAt(now).createdAt(now).updatedAt(now).version(0L).build();
        receipts.insert(legacyReceipt);
        LocalDateTime orderTime = clock.now();
        SupplyChainOrderDO nullProject = SupplyChainOrderDO.builder()
                .orderNo("SC_MIG_106").orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(BUYER_ID).projectId(null)
                .buyerCodeSnapshot("B_MIG").buyerNameSnapshot("迁移采购商")
                .buyerOrderNo("ORD_MIG_NULLPRJ").acceptanceReceiptId(legacyReceipt.getId())
                .buyerSettlementAmount(10_100L).currency("CNY")
                .platformSkuId(PLATFORM_SKU_ID).quantity(1L)
                .orderStatus((short) 10).aftersaleStatus((short) 0).businessStage("ACCEPTED")
                .detailType(SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT).detailVersion(0L)
                .version(0L).createdAt(orderTime).updatedAt(orderTime).build();
        orders.insert(nullProject);

        IdentityReport report = transactions.execute(status -> migration.migrateIdentitySnapshots());

        // 报告以总单为扫描维度：仅 NULL 项目订单计入不可核验跳过；孤立未建单回执不经任何重建路径。
        assertThat(report.rebuiltCount()).isZero();
        assertThat(report.rebuiltSkipped()).isZero();
        assertThat(report.unverifiableSkipped()).isEqualTo(1);
        assertThat(receipts.selectById(unbuilt.getId()).getFingerprintVersion()).isEqualTo((short) 0);
        assertThat(receipts.selectById(legacyReceipt.getId()).getFingerprintVersion()).isEqualTo((short) 0);
    }

    @Test
    void missingSurroundingTransactionIsRejected() {
        // MANDATORY：离线覆盖必须与门禁/限频迁移同事务提交，独立调用一律拒绝。
        assertThatThrownBy(() -> migration.migrateIdentitySnapshots())
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * 一条 052 前的可核验历史订单链：项目 + CONSUMED 报价 + 履约请求（真实加密目标）+
     * 格式 0 ACCEPTED 回执 + CARD 总单。所有跨表 ID/单号互相一致，供迁移服务核验重建。
     */
    private HistoricalOrder insertAcceptedOrderChain(long orderId, String orderNo, String buyerOrderNo,
                                                      EncryptedTarget target, String schemaCode) {
        LocalDateTime now = clock.now();
        StandardPricingQuoteDO quote = StandardPricingQuoteDO.builder()
                .quoteToken("QT_" + orderNo).sourceSystem("AIFULI_MALL").externalTenantId("tenant-mig")
                .buyerId(BUYER_ID).projectId(801L).listingId(851L).platformSkuId(PLATFORM_SKU_ID)
                .platformSkuCode("SKU_MIG").projectPricingVersionId(9L).buyerOrderNo(buyerOrderNo)
                .quantity(1L).distributionUnitPriceCents(9_800L).salesUnitPriceCents(10_000L)
                .productSubtotalCents(10_000L).serviceFeeCents(100L).userPayableAmountCents(10_100L)
                .taxMetadataJson("{\"taxable\":false}")
                .status(PricingQuoteStatus.CONSUMED.name()).consumedOrderId(orderId).consumedAt(now)
                .createdAt(now).build();
        assertThat(quotes.insert(quote)).isEqualTo(1);

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .requestNo("FR_" + orderNo).orderId(orderId).buyerId(BUYER_ID).listingId(851L)
                .listingVersionId(951L).platformSkuId(PLATFORM_SKU_ID)
                .targetSchemaCode(schemaCode).targetSchemaVersion(1)
                .targetCiphertext(target.ciphertext()).targetDigest(new byte[32])
                .targetKeyVersion(target.keyVersion()).requestedQuantity(1L).fulfilledQuantity(0L)
                .status((short) 10).aggregateVersion(0L).createdAt(now).updatedAt(now).build();
        assertThat(requests.insert(request)).isEqualTo(1);

        FulfillmentAcceptanceReceiptDO receipt = FulfillmentAcceptanceReceiptDO.builder()
                .receiptNo("RCP_" + orderNo).buyerId(BUYER_ID).projectId(801L)
                .buyerOrderNo(buyerOrderNo).buyerSkuCode(BUYER_SKU_CODE).quantity(1L)
                .targetSchemaCode(schemaCode).targetDigest(new byte[32])
                .invariantFingerprint(new byte[32]).requestFingerprint(new byte[32])
                .listingVersionNo(3).listingVersionId(951L).attemptNo(1)
                .status(AcceptanceReceiptStatus.ACCEPTED.getCode())
                .resultOrderId(orderId).resultOrderNo(orderNo).resultFulfillmentRequestNo(request.getRequestNo())
                .acceptedAt(now).createdAt(now).updatedAt(now).version(0L).build();
        assertThat(receipts.insert(receipt)).isEqualTo(1);

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(orderId).orderNo(orderNo).orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(BUYER_ID).projectId(801L)
                .pricingQuoteId(quote.getId()).pricingVersionId(9L)
                .buyerCodeSnapshot("B_MIG").buyerNameSnapshot("迁移采购商")
                .buyerOrderNo(buyerOrderNo).acceptanceReceiptId(receipt.getId())
                .buyerSettlementAmount(10_100L).currency("CNY").platformSkuId(PLATFORM_SKU_ID)
                .quantity(1L).orderStatus((short) 10).aftersaleStatus((short) 0).businessStage("ACCEPTED")
                .detailType(SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT).detailId(request.getId())
                .detailVersion(1L).version(0L).createdAt(now).updatedAt(now).build();
        assertThat(orders.insert(order)).isEqualTo(1);
        return new HistoricalOrder(orderId, receipt.getId(), request.getId(), quote.getId());
    }

    private void insertProject() {
        LocalDateTime now = clock.now();
        BuyerProjectDO project = BuyerProjectDO.builder()
                .id(801L).projectCode(PROJECT_CODE).projectName("身份迁移项目").buyerId(BUYER_ID)
                .catalogMode("MANAGED").status(BuyerProjectStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        assertThat(projects.insert(project)).isEqualTo(1);
    }

    private record EncryptedTarget(byte[] ciphertext, String keyVersion) {
    }

    /** 与受理期同口径的直充目标加密：canonical JSON + AAD=buyerId|buyerOrderNo|schemaCode|schemaVersion。 */
    private EncryptedTarget encryptedDirectTarget(String buyerOrderNo, String mobile) {
        Map<String, String> values = new TreeMap<>(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, mobile));
        return encryptTarget(SCHEMA_DIRECT, buyerOrderNo, values);
    }

    private EncryptedTarget encryptedVoucherTarget(String buyerOrderNo) {
        Map<String, String> values = new TreeMap<>(Map.of(
                FulfillmentTargetFields.DELIVERY_MODE, FulfillmentTargetFields.DELIVERY_MODE_BUYER_ASSET_QUERY));
        return encryptTarget(SCHEMA_VOUCHER, buyerOrderNo, values);
    }

    private EncryptedTarget encryptTarget(String schemaCode, String buyerOrderNo, Map<String, String> values) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("schemaCode", schemaCode);
            canonical.put("schemaVersion", 1);
            canonical.put("values", values);
            byte[] plaintext = json.writeValueAsBytes(canonical);
            EnvelopeCryptoPort.EncryptedPayload encrypted = crypto.encrypt(plaintext,
                    (BUYER_ID + "|" + buyerOrderNo + "|" + schemaCode + "|1").getBytes(StandardCharsets.UTF_8));
            return new EncryptedTarget(encrypted.ciphertext(), encrypted.keyVersion());
        } catch (Exception exception) {
            throw new IllegalStateException("fixture target encryption failed", exception);
        }
    }

    /** Java 端按与受理/迁移同一规范化策略复算目标摘要（HMAC），断言重建指纹同源。 */
    private String expectedDigestHex(String mobile) {
        Map<String, String> values = new TreeMap<>(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, mobile));
        byte[] digest = hmac.fingerprint(FulfillmentTargetFields.FINGERPRINT_PURPOSE,
                canonicalTargetBytes(SCHEMA_DIRECT, values));
        return HexFormat.of().formatHex(digest);
    }

    private byte[] canonicalTargetBytes(String schemaCode, Map<String, String> values) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("schemaCode", schemaCode);
            canonical.put("schemaVersion", 1);
            canonical.put("values", values);
            return json.writeValueAsBytes(canonical);
        } catch (Exception exception) {
            throw new IllegalStateException("fixture target canonicalization failed", exception);
        }
    }

    private void corruptCiphertext(long requestId) {
        FulfillmentRequestDO request = requests.selectById(requestId);
        request.setTargetCiphertext("corrupted-ciphertext-bytes".getBytes(StandardCharsets.UTF_8));
        requests.updateById(request);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
        return new SpringApplicationBuilder(MigrationTestConfiguration.class)
                .web(WebApplicationType.NONE).properties(properties).run();
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

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class, FulfillmentTargetSchemaRegistry.class,
            StandardOrderIdentityMigrationService.class})
    static class MigrationTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    /** 历史链关键内部 ID（总单/回执/请求/报价），供用例断言与破坏操作定位。 */
    private record HistoricalOrder(long orderId, long receiptId, long requestId, long quoteId) {
    }
}
