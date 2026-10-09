package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.*;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 独占 Testcontainers 空库，经真实 Liquibase／事务代理／MyBatis 路由证明新 RR 与锁隔离。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#3-应用边界与事务协议。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CurrentSupplyEligibilityReaderIntegrationTest {
    private static final String READER = "com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader";
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private LocalDateTime now;
    private BuyerDO buyer;
    private PlatformSkuDO sku;
    private BuyerProductDO product;
    private BuyerProjectDO autoProject;
    private long sequence;

    @BeforeAll void start() {
        mysql.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        transactions = new TransactionTemplate(context.getBean("supplyChainTransactionManager",
                PlatformTransactionManager.class));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @AfterAll void stop() {
        if (context != null) context.close();
        mysql.stop();
    }

    @BeforeEach void fixture() {
        // 仅清理本类独占测试库的路由夹具，不访问已有业务库。
        jdbc.update("DELETE FROM sc_std_routing_rule_scope");
        jdbc.update("DELETE FROM sc_std_routing_candidate");
        jdbc.update("DELETE FROM sc_std_routing_rule");
        jdbc.update("DELETE FROM sc_supplier_routing_policy_version");
        jdbc.update("DELETE FROM sc_supplier_routing_policy");
        now = context.getBean(SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        context.getBean(BuyerCommandService.class).createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "资格买家" + suffix, "资格买家" + suffix, null, null,
                "AIFULI_MALL", "eligibility-" + suffix, now.minusMinutes(1), 1L));
        buyer = context.getBean(BuyerMapper.class).selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getBuyerName, "资格买家" + suffix));
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String brand = "BRD_%03d".formatted(sequence), category = "CAT_%03d".formatted(sequence),
                spu = "SPU_%03d".formatted(sequence), skuCode = "SKU_%03d_100".formatted(sequence);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(brand, brand, null, null, 0, 1L));
        catalog.changeBrandStatus(brand, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(category, category, null, 0, 1L));
        catalog.changeCategoryStatus(category, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(spu, brand, category,
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", spu, null, null, "不可自动撤销", 1L));
        catalog.changeSpuStatus(spu, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spu,
                "资格SKU", "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(skuCode, CatalogEntityStatus.ACTIVE, 1L);
        sku = context.getBean(PlatformSkuMapper.class).selectByCode(skuCode);
        product = BuyerProductDO.builder().buyerProductCode("EP" + suffix).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProductMapper.class).insert(product);
    }

    @Test void realProxyUsesReadOnlyNewRepeatableReadConnectionAndDatabaseTime() {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        Object reader = reader();
        assertThat(AopUtils.isAopProxy(reader)).isTrue();
        transactions.executeWithoutResult(status -> {
            Long outerId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            Object result = readProject(null, Map.of());
            assertThat(property(result, "qualification").toString()).isEqualTo("CONFIRMED");
            assertThat(property(result, "evaluatedAt", LocalDateTime.class)).isAfter(now).isBefore(now.plusMinutes(1));
            // 每次实际路由查询的连接／事务属性由 MyBatis 测试拦截器采集。
            QueryObservation observation = context.getBean(QueryObservation.class);
            assertThat(observation.connectionId).isNotEqualTo(outerId);
            assertThat(observation.readOnly).isTrue();
            assertThat(observation.isolation).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        });
    }

    @Test void committedDisableAfterOuterOldViewIsVisibleAndOuterViewIsPreserved() throws Exception {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        reader();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch oldView = new CountDownLatch(1), committed = new CountDownLatch(1);
            Future<?> disable = executor.submit(() -> {
                await(oldView);
                transactions.executeWithoutResult(status -> jdbc.update(
                        "UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId()));
                committed.countDown();
            });
            transactions.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=?", Short.class,
                        supplier.getId())).isEqualTo((short) 10);
                oldView.countDown();
                await(committed);
                Object result = readProject(null, Map.of());
                assertThat(property(result, "qualification").toString()).isEqualTo("UNAVAILABLE");
                assertThat(property(result, "reasonCodes", List.class)).contains("SUPPLIER_NOT_ACTIVE");
                assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=?", Short.class,
                        supplier.getId())).isEqualTo((short) 10);
            });
            disable.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void readerDoesNotWaitForOuterListingProductProjectOrSupplyLocksOrWriteAnything() throws Exception {
        SupplierDO supplier = supply("A", 90);
        BuyerProjectDO project = project("P");
        ProjectListingDO listing = listing(project);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        reader();
        jdbc.update("INSERT INTO sc_buyer_listing (listing_no,buyer_id,platform_sku_id,buyer_sku_code,status,version,created_at,updated_at) VALUES (?,?,?, ?,0,0,?,?)",
                "EL" + sequence, buyer.getId(), sku.getId(), "CODE" + sequence, now, now);
        Long beforeOutbox = jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM sc_buyer_listing WHERE buyer_id=? FOR UPDATE", buyer.getId());
                jdbc.queryForList("SELECT id FROM sc_buyer_product WHERE id=? FOR UPDATE", product.getId());
                jdbc.queryForList("SELECT id FROM sc_project_listing WHERE id=? FOR UPDATE", listing.getId());
                jdbc.queryForList("SELECT id FROM sc_supplier WHERE id=? FOR UPDATE", supplier.getId());
                jdbc.update("UPDATE sc_buyer_product SET status=90 WHERE id=?", product.getId());
                Future<Object> result = executor.submit(() -> readBuyer());
                try {
                    assertThat(property(result.get(5, TimeUnit.SECONDS), "qualification").toString()).isEqualTo("CONFIRMED");
                } catch (Exception failure) { throw new AssertionError("Reader must not wait for outer row locks", failure); }
                status.setRollbackOnly();
            });
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class)).isEqualTo(beforeOutbox);
        assertThat(jdbc.queryForObject("SELECT status FROM sc_buyer_product WHERE id=?", Short.class, product.getId())).isEqualTo((short) 10);
        assertThat(context.getBean(QueryObservation.class).sql).noneMatch(sql ->
                sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE") || !sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"));
    }

    @Test void optionalUnknownPrimaryIsNotReplacedByConfirmedBackup() {
        SupplierDO high = supply("A", 90), low = supply("B", 60);
        publish(List.of(auto("GLOBAL", high, low, "OPTIONAL", null)));
        var fact = new RoutingInput.Fact(RoutingInput.FactStatus.AVAILABLE, null, now, now.plusMinutes(5));
        Object result = readProject(autoProject.getId(), Map.of(low.getSupplierCode(), new RoutingInput.CandidateFacts(null, null, fact)));
        assertThat(property(result, "qualification").toString()).isEqualTo("UNKNOWN");
        assertThat(snapshot(result).decision().selectedCandidate().supplierId()).isEqualTo(high.getId());
    }

    @Test void requiredMissingFactsAreUnknownWithOriginalReasons() {
        SupplierDO high = supply("A", 90), low = supply("B", 60);
        publish(List.of(auto("GLOBAL", high, low, "REQUIRED", null)));
        Object result = readProject(autoProject.getId(), Map.of());
        assertThat(property(result, "qualification").toString()).isEqualTo("UNKNOWN");
        assertThat(property(result, "reasonCodes", List.class)).containsExactly("HEALTH_FACT_REQUIRED");
        assertThat(snapshot(result).decision().selected()).isFalse();
    }

    @Test void buyerAggregateDoesNotAuthorizeAnUnavailableProject() {
        SupplierDO supplier = supply("A", 90);
        BuyerProjectDO allowed = project("YES"), blocked = project("NO");
        listing(allowed); listing(blocked);
        publish(List.of(fixed("YES", supplier.getSupplierCode(), allowed.getProjectCode())));
        Object result = readBuyer();
        assertThat(property(result, "qualification").toString()).isEqualTo("CONFIRMED");
        assertThat(property(result, "projects", List.class)).hasSize(2);
        assertThat(property(readProject(blocked.getId(), Map.of()), "qualification").toString()).isEqualTo("UNAVAILABLE");
    }

    @Test void noUsableProjectUsesNullDefaultAndRemovedProjectsDoNotLeak() {
        SupplierDO supplier = supply("A", 90);
        BuyerProjectDO removed = project("OLD");
        ProjectListingDO listing = listing(removed);
        jdbc.update("UPDATE sc_project_listing SET status=90 WHERE id=?", listing.getId());
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        Object result = readBuyer();
        List<?> projects = property(result, "projects", List.class);
        assertThat(projects).hasSize(1);
        assertThat(property(projects.getFirst(), "projectId")).isNull();
        assertThat(property(result, "qualification").toString()).isEqualTo("CONFIRMED");
    }

    @Test void missingPolicyIsUnavailableNotUnknownAndBrokenOfferPropagates() {
        Object missing = readProject(null, Map.of());
        assertThat(property(missing, "qualification").toString()).isEqualTo("UNAVAILABLE");
        assertThat(property(missing, "reasonCodes", List.class)).containsExactly("NO_ACTIVE_POLICY");
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        jdbc.update("UPDATE sc_supplier_offer SET current_version_id=999999 WHERE supplier_id=?", supplier.getId());
        assertThatThrownBy(() -> readProject(null, Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test void brokenProjectVersionAndCrossBuyerProjectAreTechnicalFailures() {
        BuyerProjectDO project = project("P");
        ProjectListingDO listing = listing(project);
        reader();
        jdbc.update("UPDATE sc_project_listing SET current_version_id=999999 WHERE id=?", listing.getId());
        assertThatThrownBy(this::readBuyer).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sc_project_listing SET status=90 WHERE id=?", listing.getId());
        jdbc.update("UPDATE sc_buyer_project SET buyer_id=999999 WHERE id=?", project.getId());
        assertThatThrownBy(() -> readProject(project.getId(), Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test void boundaryContainsOfferPolicyFactsAndQuotaNaturalDay() {
        SupplierDO high = supply("A", 90), low = supply("B", 60);
        publish(List.of(auto("GLOBAL", high, low, "OPTIONAL", null)));
        var fact = new RoutingInput.Fact(RoutingInput.FactStatus.AVAILABLE, null,
                now, now.plusSeconds(20));
        Object facts = readProject(autoProject.getId(), Map.of(high.getSupplierCode(), new RoutingInput.CandidateFacts(null, null, fact)));
        assertThat(property(facts, "nextFactBoundary")).isEqualTo(fact.validUntil());
        assertThat(property(facts, "sourceSummary", String.class)).contains("GLOBAL", high.getSupplierCode());
        jdbc.update("UPDATE sc_supplier_offer_version SET valid_to=? WHERE offer_id IN (SELECT id FROM sc_supplier_offer WHERE supplier_id=?)",
                now.plusSeconds(10), high.getId());
        assertThat(property(readProject(autoProject.getId(), Map.of()), "nextFactBoundary")).isEqualTo(now.plusSeconds(10));
        jdbc.update("UPDATE sc_supplier_offer_version SET valid_to=NULL WHERE offer_id IN (SELECT id FROM sc_supplier_offer WHERE supplier_id=?)", high.getId());
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_to=?", now.plusSeconds(15));
        assertThat(property(readProject(autoProject.getId(), Map.of()), "nextFactBoundary")).isEqualTo(now.plusSeconds(15));
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_to=NULL");
        jdbc.update("INSERT INTO sc_supplier_sku_daily_quota (supplier_id,platform_sku_id,daily_limit,created_at,updated_at) VALUES (?,?,100,?,?)",
                high.getId(), sku.getId(), now, now);
        assertThat(property(readProject(autoProject.getId(), Map.of()), "nextFactBoundary")).isEqualTo(now.toLocalDate().plusDays(1).atStartOfDay());
    }

    @Test void scheduledPolicyMissingNowStillReturnsItsNextStartBoundary() {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_from=?", now.plusMinutes(1));
        Object result = readProject(null, Map.of());
        assertThat(property(result, "qualification").toString()).isEqualTo("UNAVAILABLE");
        assertThat(property(result, "reasonCodes", List.class)).containsExactly("NO_EFFECTIVE_POLICY_VERSION");
        assertThat(property(result, "nextFactBoundary")).isEqualTo(now.plusMinutes(1));
    }

    @Test void emptyDynamicPoolStillReturnsPolicyEndBoundary() {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        jdbc.update("UPDATE sc_std_routing_rule SET route_mode='AUTO',primary_supplier_id=NULL");
        jdbc.update("UPDATE sc_supplier_offer SET status=20 WHERE supplier_id=?", supplier.getId());
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_to=?", now.plusMinutes(1));
        Object result = readProject(null, Map.of());
        assertThat(property(result, "reasonCodes", List.class)).containsExactly("NO_DYNAMIC_CANDIDATE");
        assertThat(property(result, "nextFactBoundary")).isEqualTo(now.plusMinutes(1));
    }

    @Test void factMaximumAgeIsBoundaryEvenWhenItsExplicitExpiryIsLater() {
        SupplierDO high = supply("A", 90), low = supply("B", 60);
        publish(List.of(auto("GLOBAL", high, low, "OPTIONAL", null)));
        var fact = new RoutingInput.Fact(RoutingInput.FactStatus.AVAILABLE, null,
                now, now.plusMinutes(5));
        assertThat(property(readProject(autoProject.getId(), Map.of(high.getSupplierCode(),
                new RoutingInput.CandidateFacts(null, null, fact))), "nextFactBoundary"))
                .isEqualTo(now.plusSeconds(60));
    }

    @Test void disableCommittedAfterFirstReaderEntityReadDoesNotRewriteThatView() throws Exception {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        QueryObservation observation = context.getBean(QueryObservation.class);
        CountDownLatch established = new CountDownLatch(1), committed = new CountDownLatch(1);
        observation.afterFirstBuyerRead = () -> { established.countDown(); await(committed); };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> disable = executor.submit(() -> {
                await(established);
                transactions.executeWithoutResult(status -> jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId()));
                committed.countDown();
            });
            assertThat(property(readProject(null, Map.of()), "qualification").toString()).isEqualTo("CONFIRMED");
            disable.get(10, TimeUnit.SECONDS);
            assertThat(property(readProject(null, Map.of()), "qualification").toString()).isEqualTo("UNAVAILABLE");
        } finally { observation.afterFirstBuyerRead = null; }
    }

    @Test void duplicatePublishedEffectiveVersionsAreNotConvertedToUnavailable() {
        SupplierDO supplier = supply("A", 90);
        var rules = List.of(fixed("GLOBAL", supplier.getSupplierCode(), null));
        publish(rules);
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_to=?", now.plusHours(1));
        RoutingPolicyPublicationService publication = context.getBean(RoutingPolicyPublicationService.class);
        var second = publication.createDraft(new RoutingPolicyPublicationService.CreateDraftCommand(
                "ELIGIBILITY" + sequence, "资格策略", "V2", now.plusHours(1), null, rules, 1L, 40, 30, 30));
        publication.publish(new RoutingPolicyPublicationService.PublishCommand(second.policyVersionId(), 0L,
                "资格夹具", 1L, "fixture", null, null));
        jdbc.update("UPDATE sc_supplier_routing_policy_version SET valid_from=? WHERE id=?",
                now.minusMinutes(1), second.policyVersionId());
        assertThatThrownBy(() -> readProject(null, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 2");
    }

    @Test void databaseFailureIsNotConvertedToBusinessWaiting() {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        jdbc.execute("RENAME TABLE sc_supplier_offer TO sc_supplier_offer_unavailable_fixture");
        try {
            assertThatThrownBy(() -> readProject(null, Map.of()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("RENAME TABLE sc_supplier_offer_unavailable_fixture TO sc_supplier_offer");
        }
    }

    @Test void brokenConnectorJsonFailsWithoutInventingUnknownFacts() {
        SupplierDO supplier = supply("A", 90);
        publish(List.of(fixed("GLOBAL", supplier.getSupplierCode(), null)));
        // MySQL JSON 列不允许非法语法，合法数组仍违反已发布配置的对象契约。
        jdbc.update("UPDATE sc_supplier_connector_version SET adapter_config_json='[]' WHERE connector_id IN (SELECT id FROM sc_supplier_connector WHERE supplier_id=?)", supplier.getId());
        assertThatThrownBy(() -> readProject(null, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a JSON object");
    }

    private Object reader() {
        assertThat(ClassUtils.isPresent(READER, getClass().getClassLoader()))
                .as("独立当前资格 Reader 应已实现并装配真实事务代理").isTrue();
        try { return context.getBean(Class.forName(READER)); }
        catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }

    private Object readProject(Long projectId, Map<String, RoutingInput.CandidateFacts> facts) {
        return call(reader(), "readProject", RoutingInput.class, new RoutingInput(buyer.getId(), projectId,
                sku.getId(), "DIRECT_RECHARGE", 1, now.minusYears(1), facts));
    }
    private Object readBuyer() { return call(reader(), "readBuyer", Long.class, product.getId()); }

    private static Object call(Object target, String name, Class<?> parameter, Object argument) {
        try { return target.getClass().getMethod(name, parameter).invoke(target, argument); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object property(Object target, String name) {
        assertThat(target).isNotNull();
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static <T> T property(Object target, String name, Class<T> type) { return type.cast(property(target, name)); }
    private static SupplierRoutingApplicationService.RoutingSnapshot snapshot(Object result) {
        return property(result, "routingSnapshot", SupplierRoutingApplicationService.RoutingSnapshot.class);
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private BuyerProjectDO project(String code) {
        BuyerProjectDO project = BuyerProjectDO.builder().projectCode(code + sequence).projectName(code)
                .buyerId(buyer.getId()).catalogMode("MANAGED").settlementPartyType("DISTRIBUTOR")
                .status((short) 10).version(0L).createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProjectMapper.class).insert(project);
        return project;
    }
    private ProjectListingDO listing(BuyerProjectDO project) {
        ProjectListingDO listing = ProjectListingDO.builder().listingCode("PL" + project.getProjectCode())
                .projectId(project.getId()).buyerProductId(product.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(ProjectListingMapper.class).insert(listing);
        ProjectListingVersionDO version = ProjectListingVersionDO.builder().listingId(listing.getId())
                .versionNo(1).buyerSettlementUnitPrice(100L).settlementPriceLevel("L2")
                .priceSource("INHERITED").l3AdjustMode("FOLLOW_L2").currency("CNY")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(ProjectListingVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_project_listing SET current_version_id=? WHERE id=?", version.getId(), listing.getId());
        return listing;
    }

    private SupplierDO supply(String code, int score) {
        String unique = code + sequence;
        SupplierDO supplier = SupplierDO.builder().supplierCode(unique).supplierName(unique).shortName(unique)
                .supplierType("STANDARD_SUPPLIER").status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        CredentialSlotDO credential = CredentialSlotDO.builder().slotNo("CR" + unique).providerType("ENV")
                .secretRef("env:FIXTURE").maskedHint("fixture").activeSecretVersion("v1")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder().connectorNo("CN" + unique)
                .supplierId(supplier.getId()).connectorCode("PRIMARY").adapterType("QNM_GAME_ORDER_V1")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorMapper.class).insert(connector);
        SupplierConnectorVersionDO connectorVersion = SupplierConnectorVersionDO.builder().connectorId(connector.getId())
                .versionNo(1).baseUrl("https://fixture.invalid").merchantAccount("fixture")
                .credentialSlotId(credential.getId()).protocolVersion("V1").callbackPath("/fixture")
                .connectTimeoutMs(1000).readTimeoutMs(3000).rateLimit(10).concurrencyLimit(2)
                .adapterConfigSchemaVersion(1).adapterConfigJson("{}").validationStatus((short) 10)
                .validatedAt(now).validationExpiresAt(now.plusDays(1)).publishedAt(now).configDigest(new byte[32])
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);
        jdbc.update("UPDATE sc_supplier_connector SET current_version_id=? WHERE id=?", connectorVersion.getId(), connector.getId());
        InventorySourceDO inventory = InventorySourceDO.builder().sourceNo("IN" + unique).supplierId(supplier.getId())
                .platformSkuId(sku.getId()).sourceType("REMOTE_ON_DEMAND").externalSourceCode(unique)
                .ownerType("SUPPLIER").status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OF" + unique).offerCode(unique)
                .supplierId(supplier.getId()).platformSkuId(sku.getId()).stabilityScore(score).timelinessScore(score)
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierOfferMapper.class).insert(offer);
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId()).versionNo(1)
                .externalSkuCode(unique).supplierSettlementUnitPrice(100L).currency("CNY")
                .connectorVersionId(connectorVersion.getId()).inventorySourceId(inventory.getId())
                .minQuantity(1L).maxQuantity(1L).quantityStep(1L).primaryCapabilityCode("CREATE_RECHARGE")
                .completionMode("QUERY_OR_CALLBACK").createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE").fulfillmentConfigSchemaVersion(1)
                .fulfillmentConfigJson("{}").validFrom(now.minusDays(1)).publishReason("fixture")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_supplier_offer SET current_version_id=? WHERE id=?", version.getId(), offer.getId());
        return supplier;
    }
    private RoutingPolicyPublicationService.RuleDraft fixed(String code, String supplier, String project) {
        return new RoutingPolicyPublicationService.RuleDraft(code, code, "FIXED", supplier,
                "ALL", List.of(), project == null ? "ALL" : "SPECIFIED", project == null ? List.of() : List.of(project),
                project == null ? "ALL" : "SPECIFIED", project == null ? List.of() : List.of("EQUITY_PRODUCT"),
                "ALL", List.of(), null, "STABLE", "PRICE", "COMPOSITE", 10, (short) 10, false, List.of());
    }
    private RoutingPolicyPublicationService.RuleDraft auto(String code, SupplierDO high, SupplierDO low, String mode, String project) {
        autoProject = project("AUTO");
        return new RoutingPolicyPublicationService.RuleDraft(code, code, "AUTO", null,
                "SPECIFIED", List.of(buyer.getBuyerCode()), "SPECIFIED", List.of(autoProject.getProjectCode()),
                "SPECIFIED", List.of("EQUITY_PRODUCT"), "SPECIFIED", List.of(high.getSupplierCode(), low.getSupplierCode()),
                null, "STABLE", "PRICE", "COMPOSITE", 10, (short) 10, false,
                List.of(new RoutingPolicyPublicationService.CandidateDraft(high.getSupplierCode(), 0,
                        "UNSUPPORTED", null, "UNSUPPORTED", null, mode, 60),
                        new RoutingPolicyPublicationService.CandidateDraft(low.getSupplierCode(), 1,
                                "UNSUPPORTED", null, "UNSUPPORTED", null, mode, 60)));
    }
    private void publish(List<RoutingPolicyPublicationService.RuleDraft> rules) {
        RoutingPolicyPublicationService publication = context.getBean(RoutingPolicyPublicationService.class);
        var draft = publication.createDraft(new RoutingPolicyPublicationService.CreateDraftCommand(
                "ELIGIBILITY" + sequence, "资格策略", "V1", now.minusMinutes(1), null, rules, 1L, 40, 30, 30));
        publication.publish(new RoutingPolicyPublicationService.PublishCommand(draft.policyVersionId(), 0L,
                "资格夹具", 1L, "fixture", null, null));
        context.getBean(QueryObservation.class).sql.clear();
    }

    /** 在实际 MyBatis 查询连接上取证；不替换路由、数据库时钟或事务管理器。 */
    @org.apache.ibatis.plugin.Intercepts({@org.apache.ibatis.plugin.Signature(type = org.apache.ibatis.executor.statement.StatementHandler.class,
            method = "prepare", args = {java.sql.Connection.class, Integer.class}),
            @org.apache.ibatis.plugin.Signature(type = org.apache.ibatis.executor.Executor.class,
                    method = "query", args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class,
                    org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class})})
    static class QueryObservation implements org.apache.ibatis.plugin.Interceptor {
        volatile Long connectionId;
        volatile boolean readOnly;
        volatile int isolation;
        final List<String> sql = new CopyOnWriteArrayList<>();
        volatile Runnable afterFirstBuyerRead;
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            if (invocation.getTarget() instanceof org.apache.ibatis.executor.Executor) {
                Object result = invocation.proceed();
                var mapped = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
                Runnable hook = afterFirstBuyerRead;
                if (hook != null && mapped.getId().equals(BuyerMapper.class.getName() + ".selectById")
                        && org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    afterFirstBuyerRead = null;
                    hook.run();
                }
                return result;
            }
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                java.sql.Connection connection = (java.sql.Connection) invocation.getArgs()[0];
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    result.next(); connectionId = result.getLong(1);
                }
                readOnly = connection.isReadOnly(); isolation = connection.getTransactionIsolation();
                sql.add(((org.apache.ibatis.executor.statement.StatementHandler) invocation.getTarget()).getBoundSql().getSql());
            }
            return invocation.proceed();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, ProductCatalogCommandService.class,
            ProductCenterTestConfiguration.class, RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class})
    @ComponentScan(basePackages = "com.aifuli.module.supplychain.tradecore.catalog.application",
            useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*CurrentSupplyEligibilityReader"))
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean QueryObservation observation(org.apache.ibatis.session.SqlSessionFactory factory) {
            QueryObservation observation = new QueryObservation();
            factory.getConfiguration().addInterceptor(observation);
            return observation;
        }
    }
}
