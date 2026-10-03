package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList;
import com.aifuli.module.supplychain.infrastructure.security.EnvironmentCredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.ExecutionProfileDefaults;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationRecordService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService.ListOnShelfCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService.PublishOfferCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService.PublishResult;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.OFFER_CAPABILITY_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 上架即发布全链路集成测试（真实 MySQL + Liquibase，profile {@code supply-chain-integration}）。
 *
 * <p>覆盖设计 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#11-测试与门禁}
 * 的 ③④⑤⑥：</p>
 * <ul>
 *   <li>③ 合作商品（未配置）→ 上架：自动开通库存、版本行冻结 {@code external_sku_code}、结算价=={@code draft_unit_price}、
 *       画像==适配器默认、{@code current_version_id} 非空、{@code version+1}、binding upsert、路由由跳过变命中；</li>
 *   <li>④ 能力缺口：SKU 推导能力未在连接器版本启用 → 上架拒绝 + {@code OFFER_CAPABILITY_MISSING}；</li>
 *   <li>⑤ 改身份列 → {@code fulfillmentStatus} READY→STALE → 重新上架回到 READY 且 {@code version+1}；</li>
 *   <li>⑥ 乐观锁：以陈旧 {@code expectedOfferVersion} 直接发布被拒（{@code IllegalStateException}）。</li>
 * </ul>
 */
class OfferListOnShelfIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String TEST_SECRET = "integration-secret-must-not-enter-database";
    private static final String DIRECT_SKU = "SKU_001_100";
    // SKU 编码父段必须等于所属 SPU 的内部短序号：BOUND_SKU 挂在第二个 SPU（SPU_002，短序号 002）下，
    // 故为 SKU_002_001（ScopedBusinessCodeFormat 强校验父段一致）。
    private static final String BOUND_SKU = "SKU_002_001";
    private static final String DIRECT_EXTERNAL_CODE = "QNM-TELCO-100";

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void listOnShelfPublishesFulfillableVersionRejectsCapabilityGapAndRelistsAfterDrift() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
            SupplierConfigurationCommandService configuration =
                    context.getBean(SupplierConfigurationCommandService.class);
            ConnectorValidationService validation = context.getBean(ConnectorValidationService.class);
            ConnectorPublicationService publication = context.getBean(ConnectorPublicationService.class);
            SupplierSupplyScopeService scopes = context.getBean(SupplierSupplyScopeService.class);
            SupplierCooperativeProductService offers = context.getBean(SupplierCooperativeProductService.class);
            SupplierOfferPublicationService offerPublication =
                    context.getBean(SupplierOfferPublicationService.class);
            AdapterMetadataRegistry registry = context.getBean(AdapterMetadataRegistry.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
            SupplierOfferVersionMapper offerVersionMapper = context.getBean(SupplierOfferVersionMapper.class);
            InventorySourceMapper inventorySourceMapper = context.getBean(InventorySourceMapper.class);
            PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                    "supplyChainDataSource", javax.sql.DataSource.class));

            ExecutionProfileDefaults qnmProfile =
                    registry.require(QnmGameOrderV1Metadata.TYPE).defaultExecutionProfile();

            // 目录：一个 SPU 下挂两条 SKU——直充（可履约）与账号绑定权益（QNM 未启用 BIND_BENEFIT，制造能力缺口）。
            seedCatalog(catalog);

            // 供应商 + 供货配置（EQUITY/EQUITY_PRODUCT/VIRTUAL_CARD，枚举名全等命中，无需类目树）。
            String supplierCode = configuration.createSupplier(
                    new SupplierConfigurationCommandService.CreateSupplierCommand(
                            null, "QNM", "QNM", "STANDARD_SUPPLIER", null, 1L));
            Long supplierId = supplierMapper.selectByCode(supplierCode).getId();
            scopes.replaceAll(supplierCode, List.of(new ScopeLine(
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", List.of("API_DIRECT"), 1_000L, 1L)), 1L);
            Long directSkuId = skuMapper.selectByCode(DIRECT_SKU).getId();

            // 技术菜单链路：凭据槽（ENV 引用）→ 连接器草稿 → 映射初始化 → 校验 → 发布（产出 C3 唯一已发布连接器版本）。
            String slotNo = configuration.createCredentialSlot(
                    new SupplierConfigurationCommandService.CreateCredentialSlotCommand(
                            "ENV", "env:QNM_TEST_USERKEY", "qnm-****", "v1", 1L));
            SupplierConfigurationCommandService.DraftConnectorResult draft =
                    configuration.createConnectorDraft(draft(
                            supplierCode, slotNo, QnmGameOrderV1Metadata.TYPE,
                            "https://api.qinameng.com", "{}", "PRIMARY"));
            configuration.initializeMappings(draft.connectorVersionId(), 1L);
            validation.validate(validate(draft.connectorVersionId()));
            publication.publish(new ConnectorPublicationService.PublishConnectorCommand(
                    draft.connectorNo(), draft.connectorVersionId(), 0L, 1L,
                    "B1 集成测试连接器发布", "trace-connector", null, null));

            // 业务菜单链路：选品（f1 必填 externalSkuCode）新增两条合作商品，状态 PUBLISHED 但 current_version_id 为空。
            List<String> offerNos = offers.createPublished(supplierCode, List.of(
                    new DraftLine(DIRECT_SKU, "FIXED", 9_700L, null, null, "API_DIRECT", 500L, 1L,
                            DIRECT_EXTERNAL_CODE),
                    new DraftLine(BOUND_SKU, "FIXED", 8_800L, null, null, "API_DIRECT", 300L, 1L,
                            "QNM-BOUND-100")), 1L);
            String directOfferNo = offerNos.get(0);
            String boundOfferNo = offerNos.get(1);

            // 上架前：路由跳过（current_version_id 为空），投影判 NOT_CONFIGURED。
            assertThat(offerMapper.selectPublishedBySupplierAndSku(supplierId, directSkuId)).isEmpty();
            OfferListItem before = findItem(offers.page(supplierCode, null, null, null, 1, 20).getList(), directOfferNo);
            assertThat(before.fulfillmentStatus()).isEqualTo("NOT_CONFIGURED");
            assertThat(before.currentVersionId()).isNull();
            assertThat(before.offerVersion()).isZero();
            assertThat(before.externalSkuCode()).isEqualTo(DIRECT_EXTERNAL_CODE);

            // ④ 能力缺口：账号绑定权益 SKU 推导 BIND_BENEFIT，QNM 连接器未启用 → 拒绝上架并返回明确错误码。
            assertThatThrownBy(() -> offerPublication.listOnShelf(new ListOnShelfCommand(
                    supplierCode, boundOfferNo, null, 1L, "trace-gap", null, null)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(OFFER_CAPABILITY_MISSING.getCode());
            // 被拒绝的合作商品不得写入当前版本，仍停留在未配置态。
            assertThat(offerMapper.selectByNo(boundOfferNo).getCurrentVersionId()).isNull();

            // ③ 上架即发布：一步产出可履约版本。
            PublishResult first = offerPublication.listOnShelf(new ListOnShelfCommand(
                    supplierCode, directOfferNo, null, 1L, "trace-list", null, null));
            assertThat(first.offerVersionNo()).isEqualTo(1);
            assertThat(first.aggregateVersion()).isEqualTo(1L);

            SupplierOfferDO listed = offerMapper.selectByNo(directOfferNo);
            assertThat(listed.getCurrentVersionId()).isNotNull();
            assertThat(listed.getVersion()).isEqualTo(1L);
            assertThat(listed.getStatus()).isEqualTo(OfferStatus.PUBLISHED.getCode());

            SupplierOfferVersionDO version = offerVersionMapper.selectById(listed.getCurrentVersionId());
            assertThat(version.getExternalSkuCode()).isEqualTo(DIRECT_EXTERNAL_CODE);
            assertThat(version.getSupplierSettlementUnitPrice()).isEqualTo(9_700L);
            assertThat(version.getCurrency()).isEqualTo("CNY");
            assertThat(version.getMinQuantity()).isEqualTo(1L);
            assertThat(version.getMaxQuantity()).isEqualTo(500L);
            assertThat(version.getPrimaryCapabilityCode()).isEqualTo(Capability.CREATE_RECHARGE.name());
            assertThat(version.getCompletionMode()).isEqualTo(qnmProfile.completionMode().code());
            assertThat(version.getCreateIdempotencyMode()).isEqualTo(qnmProfile.createIdempotencyMode().code());
            assertThat(version.getUnknownResultPolicy()).isEqualTo(qnmProfile.unknownResultPolicy().code());
            assertThat(version.getPartialDeliveryMode()).isEqualTo(qnmProfile.partialDeliveryMode().code());
            assertThat(version.getResultMultiplicity()).isEqualTo(qnmProfile.resultMultiplicity().code());
            assertThat(version.getFulfillmentConfigJson()).isEqualTo(qnmProfile.fulfillmentConfigJson());
            assertThat(version.getValidFrom()).isNotNull();
            assertThat(version.getValidTo()).isNull();

            // c3 库存自动开通：ACTIVE + REMOTE_ON_DEMAND，外部来源编码继承身份行 externalSkuCode。
            InventorySourceDO source = inventorySourceMapper.selectActiveBySupplierSkuAndType(
                    supplierId, directSkuId, "REMOTE_ON_DEMAND");
            assertThat(source).isNotNull();
            assertThat(source.getExternalSourceCode()).isEqualTo(DIRECT_EXTERNAL_CODE);

            // binding upsert：一条身份行对应一条绑定。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_external_sku_binding "
                    + "WHERE offer_id = ?", Long.class, listed.getId())).isEqualTo(1L);

            // 路由由跳过变命中（status==PUBLISHED 且 current_version_id 非空，§2 不变量 2）。
            assertThat(offerMapper.selectPublishedBySupplierAndSku(supplierId, directSkuId)).hasSize(1);
            OfferListItem ready = findItem(offers.page(supplierCode, null, null, null, 1, 20).getList(), directOfferNo);
            assertThat(ready.fulfillmentStatus()).isEqualTo("READY");
            assertThat(ready.currentVersionId()).isEqualTo(listed.getCurrentVersionId());
            assertThat(ready.offerVersion()).isEqualTo(1L);

            // ⑥ 乐观锁：以陈旧 expectedOfferVersion(0) 直接发布被拒（当前聚合版本已为 1）。
            assertThatThrownBy(() -> offerPublication.publish(stalePublish(
                    directOfferNo, 0L, draft.connectorVersionId(), source.getSourceNo(), qnmProfile)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not publishable at the expected version");

            // ⑤ 改身份列单价 → 与版本冻结值不一致 → STALE；重新上架 → READY 且聚合版本 +1、binding 仍为一条。
            offers.updateCommercial(supplierCode, directOfferNo,
                    new DraftLine(DIRECT_SKU, "FIXED", 9_500L, null, null, "API_DIRECT", 500L, 1L,
                            DIRECT_EXTERNAL_CODE), 1L);
            OfferListItem stale = findItem(offers.page(supplierCode, null, null, null, 1, 20).getList(), directOfferNo);
            assertThat(stale.fulfillmentStatus()).isEqualTo("STALE");

            // 重新上架＝新建不可变版本（version_no+1，§2 不变量 1）+ 乐观锁 version+1（publish:190，k1）。
            // 聚合 version 是通用乐观锁计数：createPublished(0)→首次上架(1)→改身份列 updateCommercial(2)→重新上架(3)。
            // updateCommercial 的 updateById 也占用一次 @Version 自增，故断言"重新上架在改条款后的版本上 +1"，
            // 而非硬编码绝对值——这才贴合设计不变量（publish 每次 version+1），不依赖中间写次数。
            long versionBeforeRelist = stale.offerVersion();
            PublishResult relist = offerPublication.listOnShelf(new ListOnShelfCommand(
                    supplierCode, directOfferNo, null, 1L, "trace-relist", null, null));
            assertThat(relist.offerVersionNo()).isEqualTo(2);
            assertThat(relist.aggregateVersion()).isEqualTo(versionBeforeRelist + 1);
            SupplierOfferDO afterRelist = offerMapper.selectByNo(directOfferNo);
            assertThat(afterRelist.getVersion()).isEqualTo(relist.aggregateVersion());
            SupplierOfferVersionDO secondVersion = offerVersionMapper.selectById(afterRelist.getCurrentVersionId());
            assertThat(secondVersion.getSupplierSettlementUnitPrice()).isEqualTo(9_500L);
            assertThat(secondVersion.getExternalSkuCode()).isEqualTo(DIRECT_EXTERNAL_CODE);
            OfferListItem readyAgain = findItem(offers.page(supplierCode, null, null, null, 1, 20).getList(), directOfferNo);
            assertThat(readyAgain.fulfillmentStatus()).isEqualTo("READY");
            assertThat(readyAgain.offerVersion()).isEqualTo(relist.aggregateVersion());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_external_sku_binding "
                    + "WHERE offer_id = ?", Long.class, listed.getId())).isEqualTo(1L);

            // 凭据密钥永不入库（F1-hybrid 护栏，§2 不变量 11）。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_credential_slot "
                    + "WHERE secret_ref LIKE ?", Long.class, "%" + TEST_SECRET + "%")).isZero();
        }
    }

    private static OfferListItem findItem(List<OfferListItem> items, String offerNo) {
        return items.stream().filter(item -> offerNo.equals(item.offerNo())).findFirst().orElseThrow();
    }

    private static void seedCatalog(ProductCatalogCommandService catalog) {
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "QNM 测试品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "QNM 直充", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        // SPU 履约类型决定其下 SKU 的履约类型（createSku 强校验 SKU.fulfillmentType 必须等于 SPU.fulfillmentType，
        // 见 ProductCatalogCommandService#createSku）。③ 直充 SKU 与 ④ 能力缺口 SKU 派生主能力不同
        // （DIRECT_RECHARGE→CREATE_RECHARGE 在 QNM 验证集内；ACCOUNT_BOUND_BENEFIT→BIND_BENEFIT 不在），
        // 故必须分属两个 fulfillmentType 不同的 SPU。设计见 §9 fulfillmentType→primaryCapability 单一映射。
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_001",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "QNM 话费直充", null, null, "中台受理后不可自动退款或撤销",
                FulfillmentType.DIRECT_RECHARGE.name(), 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                DIRECT_SKU, "SPU_001", "话费直充 100 元", FulfillmentType.DIRECT_RECHARGE.name(),
                10_000L, "CNY", 1L));
        catalog.changeSkuStatus(DIRECT_SKU, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_002", "BRD_001", "CAT_001",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "QNM 账号绑定权益", null, null, "中台受理后不可自动退款或撤销",
                FulfillmentType.ACCOUNT_BOUND_BENEFIT.name(), 1L));
        catalog.changeSpuStatus("SPU_002", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                BOUND_SKU, "SPU_002", "账号绑定权益 100 元", FulfillmentType.ACCOUNT_BOUND_BENEFIT.name(),
                10_000L, "CNY", 1L));
        catalog.changeSkuStatus(BOUND_SKU, CatalogEntityStatus.ACTIVE, 1L);
    }

    private static SupplierConfigurationCommandService.CreateConnectorDraftCommand draft(
            String supplierCode, String slotNo, String adapterType, String baseUrl, String config, String code) {
        return new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                supplierCode, code, adapterType, baseUrl, "qnm-user", slotNo, "V1",
                "/callbacks/qnm/game-order/v1", 3_000, 10_000, 100, 20, config, 1L);
    }

    private static ConnectorValidationService.ValidateConnectorCommand validate(Long versionId) {
        return new ConnectorValidationService.ValidateConnectorCommand(versionId,
                Set.of("ISSUE_ASSET", "CREATE_RECHARGE", "QUERY_ORDER",
                        "RECEIVE_CALLBACK", "QUERY_BALANCE"),
                Duration.ofMinutes(10), 1L, "验证 QNM 协议与凭证引用", "trace-validation");
    }

    /** ⑥ 陈旧版本直接发布：商业条款合法但 expectedOfferVersion 落后于当前聚合版本，应被乐观锁拒绝。 */
    private static PublishOfferCommand stalePublish(String offerNo, long expectedVersion, Long connectorVersionId,
                                                    String sourceNo, ExecutionProfileDefaults profile) {
        return new PublishOfferCommand(
                offerNo, expectedVersion, DIRECT_EXTERNAL_CODE, 9_500L, connectorVersionId, sourceNo,
                1L, 500L, 1L, Capability.CREATE_RECHARGE.name(),
                profile.completionMode().code(), profile.createIdempotencyMode().code(),
                profile.unknownResultPolicy().code(), profile.partialDeliveryMode().code(),
                profile.resultMultiplicity().code(), profile.fulfillmentConfigJson(),
                LocalDateTime.now(ZoneOffset.UTC), null, "陈旧版本重放", 1L,
                "trace-stale", null, null);
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.connector.allowed-hosts", "api.qinameng.com");
        // §6.8 adapterType 白名单灰度闸门：Phase 1 仅放行 QNM（dogfood/集成测试），上架步骤 2 的 C3 候选据此过滤。
        properties.put("aifuli.supply-chain.connector.allowed-adapter-types", QnmGameOrderV1Metadata.TYPE);
        properties.put("QNM_TEST_USERKEY", TEST_SECRET);
        return new SpringApplicationBuilder(TestConfiguration.class)
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
            OutboxEventRepository.class, ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class, ClosedJsonSchemaValidator.class,
            EnvironmentCredentialProvider.class, SupplierEndpointPolicy.class, AdapterTypeAllowList.class,
            SupplierConfigurationCommandService.class, ConnectorValidationRecordService.class,
            ConnectorValidationService.class, ConnectorPublicationService.class,
            SupplierOfferPublicationService.class, PublishedConnectorConfigurationResolver.class,
            SupplierSupplyScopeService.class, SupplierCooperativeProductService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
