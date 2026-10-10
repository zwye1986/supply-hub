package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyIdentityService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyQueryService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyTimerStartupCheck;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeHandler;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler;
import com.aifuli.module.supplychain.border.fulfillment.application.SkuPurchaseLimitService;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.testsupport.RoutingPolicyPresetFixture;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver;
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
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.IDEMPOTENCY_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「标准受理的项目永久键与身份清理」RED 集成测试（真实 MySQL + Liquibase，TDD 红灯阶段，
 * 只断言新语义，不改动生产代码）。
 *
 * <p>验证设计 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md：
 * <ul>
 *   <li>§2 首次身份与重放优先／§3 回执竞争：受理幂等键从 (buyer_id, buyer_order_no) 改为
 *       (project_id, buyer_order_no)——同采购商两个项目共用单号必须各自建单，不能串单；</li>
 *   <li>§3：同键不同参数（串行与并发）必须幂等冲突，不能捕获任意 DataIntegrityViolation
 *       后盲回放已有回执；</li>
 *   <li>§3：重放返回稳定 requestId（实际回执号）与不变回执版本；</li>
 *   <li>§3：REJECTED_FINAL 相同身份只回放不重开（带 CAS 也不行）。</li>
 * </ul>
 *
 * <p>报价按项目永久键语义准备：每个用例为各自 (projectCode, buyerOrderNo) 先经真实
 * {@link StandardPricingQuoteService} 签发 ISSUED 报价；受理请求不携带 token，服务端在供应链
 * 事务内按项目永久键查取唯一报价并一次性消费（Task3 语义）。请求级
 * buyerUserId/listingVersion/pricingQuoteToken/paidAt 已随身份清理删除，本类 fixture 已机械移除
 * 这些赋值，业务断言保持不变。</p>
 */
class ProjectStandardAcceptanceIntegrationTest {

    private static final String PROJECT_A = "PRJ_KEY_A";
    private static final String PROJECT_B = "PRJ_KEY_B";
    private static final String BUYER_CODE = "BYR_001";
    private static final String BUYER_SKU_CODE = "WS-TELCO-100";
    private static final String PLATFORM_SKU_CODE = "SKU_001_100";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "tenant-wusheng";
    /** 明显假号 fixture（大陆手机号格式）；按契约不写入日志或断言消息。 */
    private static final String FAKE_MOBILE = "13800138000";
    private static final String OTHER_FAKE_MOBILE = "13900139000";
    private static final long ACCEPT_QUANTITY = 1L;
    private static final long REJECTED_QUANTITY = 2L;

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach
    void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterEach
    void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    /**
     * 用例一（设计 §3）：同采购商的两个项目使用相同 buyerOrderNo，必须各自创建独立回执与订单。
     *
     * <p>当前实现受理幂等键仍是 (buyer_id, buyer_order_no)：第二笔在 claim 内因项目不同
     * 触发幂等冲突异常（若并发首创则被盲回放成第一笔回执），无法各自建单 → RED。</p>
     */
    @Test
    void sameBuyerTwoProjectsSharingOrderNumberCreateIndependentReceiptsAndOrders() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            String sharedOrderNo = "C-PROJECT-KEY-SHARED";
            issueQuote(context, PROJECT_A, sharedOrderNo);
            issueQuote(context, PROJECT_B, sharedOrderNo);

            FulfillmentAcceptanceReceiptRespDTO first =
                    facade.acceptFulfillment(acceptanceRequest(PROJECT_A, sharedOrderNo));
            FulfillmentAcceptanceReceiptRespDTO second =
                    facade.acceptFulfillment(acceptanceRequest(PROJECT_B, sharedOrderNo));

            assertThat(first.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(second.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(first.getOrderNo()).isNotBlank();
            assertThat(second.getOrderNo()).isNotBlank().isNotEqualTo(first.getOrderNo());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no=?",
                    Long.class, sharedOrderNo)).isEqualTo(2L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    Long.class, sharedOrderNo)).isEqualTo(2L);
            assertThat(jdbc.queryForList(
                    "SELECT project_id FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    Long.class, sharedOrderNo)).containsExactlyInAnyOrder(
                    projectId(jdbc, PROJECT_A), projectId(jdbc, PROJECT_B));
        }
    }

    /**
     * 用例二（设计 §3）：同项目同 buyerOrderNo、不同履约目标的串行重发必须幂等冲突。
     *
     * <p>新语义：同键异参（目标变更）不得回放成功，必须抛
     * {@link SupplyChainErrorCodeConstants#IDEMPOTENCY_CONFLICT} 对应 ServiceException。
     * 该串行路径当前已按指纹差异抛冲突，本用例作为身份清理改造期间的回归护栏保留。</p>
     */
    @Test
    void sameProjectOrderNumberWithDifferentTargetMustConflictOnSerialResend() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            String orderNo = "C-CONFLICT-TARGET";
            issueQuote(context, PROJECT_A, orderNo);

            FulfillmentAcceptanceReceiptRespDTO first =
                    facade.acceptFulfillment(acceptanceRequest(PROJECT_A, orderNo));
            assertThat(first.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            AcceptFulfillmentReqDTO differentTarget = acceptanceRequest(PROJECT_A, orderNo);
            differentTarget.getTarget().setValues(Map.of(
                    FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                    FulfillmentTargetFields.ACCOUNT_VALUE, OTHER_FAKE_MOBILE));
            assertThatThrownBy(() -> facade.acceptFulfillment(differentTarget))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(IDEMPOTENCY_CONFLICT.getCode());
        }
    }

    /**
     * 用例三（设计 §3，承接单测任务说明的 DataIntegrityViolation 语义）：同项目键异参并发首创竞争，
     * 落败一方必须重探测并幂等冲突，不能捕获唯一键冲突后盲回放获胜方的回执。
     *
     * <p>当前实现对 claim 抛出的 DataIntegrityViolationException 无条件按业务键盲回放：
     * 两笔异参请求都会拿到 200 回放响应（获胜方建单、落败方回放获胜方回执），
     * 断言「恰好一笔成功、另一笔冲突」失败 → RED。</p>
     */
    @Test
    void concurrentDifferentTargetRequestsOnSameProjectKeyMustConflictInsteadOfBlindReplay() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            String orderNo = "C-CONFLICT-RACE";
            issueQuote(context, PROJECT_A, orderNo);

            List<Object> outcomes = concurrentlyCollect(2, index -> {
                AcceptFulfillmentReqDTO request = acceptanceRequest(PROJECT_A, orderNo);
                if (index == 1) {
                    request.getTarget().setValues(Map.of(
                            FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                            FulfillmentTargetFields.ACCOUNT_VALUE, OTHER_FAKE_MOBILE));
                }
                try {
                    return facade.acceptFulfillment(request);
                } catch (ServiceException conflict) {
                    // 捕获仅作为断言输入（非吞异常）：新语义要求异参并发落败方必须幂等冲突。
                    return conflict;
                }
            });

            long conflictCount = outcomes.stream().filter(ServiceException.class::isInstance).count();
            assertThat(conflictCount).isEqualTo(1L);
            outcomes.stream().filter(ServiceException.class::isInstance).forEach(conflict ->
                    assertThat(((ServiceException) conflict).getCode()).isEqualTo(IDEMPOTENCY_CONFLICT.getCode()));
            Object winner = outcomes.stream()
                    .filter(outcome -> !(outcome instanceof ServiceException)).findFirst().orElseThrow();
            assertThat(winner).isInstanceOf(FulfillmentAcceptanceReceiptRespDTO.class);
            assertThat(((FulfillmentAcceptanceReceiptRespDTO) winner).getReceiptStatus())
                    .isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_order_no=?",
                    Long.class, orderNo)).isEqualTo(1L);
        }
    }

    /**
     * 用例四（设计 §3）：同一请求重放必须返回稳定 requestId（实际回执号）与不变回执版本。
     *
     * <p>当前实现每次调用生成新的 REQ 前缀号作为 requestId，首次与重放并不相等 → RED。</p>
     */
    @Test
    void identicalReplayKeepsStableRequestIdAndReceiptVersion() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            String orderNo = "C-REPLAY-STABLE";
            issueQuote(context, PROJECT_A, orderNo);
            AcceptFulfillmentReqDTO request = acceptanceRequest(PROJECT_A, orderNo);

            FulfillmentAcceptanceReceiptRespDTO first = facade.acceptFulfillment(request);
            assertThat(first.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);

            FulfillmentAcceptanceReceiptRespDTO replayed = facade.acceptFulfillment(request);

            assertThat(replayed.getIdempotentReplay()).isTrue();
            assertThat(replayed.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            assertThat(replayed.getReceiptVersion()).isEqualTo(first.getReceiptVersion());
            assertThat(replayed.getOrderNo()).isEqualTo(first.getOrderNo());
            String receiptNo = jdbc.queryForObject(
                    "SELECT receipt_no FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no=?",
                    String.class, orderNo);
            // 新语义：首次与重放的 requestId 都返回实际回执号，不是每次新生成的 REQ 业务号。
            assertThat(first.getRequestId()).isEqualTo(receiptNo);
            assertThat(replayed.getRequestId()).isEqualTo(receiptNo);
        }
    }

    /**
     * 用例五（设计 §3）：REJECTED_FINAL 相同身份重发（带与不带 CAS 各一）只回放、不重开。
     *
     * <p>数量不为一直接最终拒绝（QUANTITY_NOT_SUPPORTED，发生在报价消费之前，无需准备报价）。
     * 新语义：REJECTED_FINAL 即使携带当前正的 previousReceiptVersion 也不能 CAS 重开。
     * 当前串行路径已满足只回放语义，本用例作为回归护栏保留。</p>
     */
    @Test
    void rejectedFinalReceiptOnlyReplaysForSameIdentityWithAndWithoutCas() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            seed(context);
            BuyerFulfillmentCommandFacade facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            String plainOrderNo = "C-FINAL-PLAIN";
            String casOrderNo = "C-FINAL-CAS";

            AcceptFulfillmentReqDTO plain = quantityRejectedRequest(PROJECT_A, plainOrderNo);
            // 数量不为一在报价消费之前即被最终拒绝，无需为该键签发报价。
            FulfillmentAcceptanceReceiptRespDTO rejected = facade.acceptFulfillment(plain);
            assertThat(rejected.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(rejected.getRejectionCode())
                    .isEqualTo(AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name());

            long ordersBeforeReplay = count(jdbc, "sc_supply_chain_order");

            // 不带 CAS 的同身份重发：只回放，不重开。
            FulfillmentAcceptanceReceiptRespDTO plainReplay = facade.acceptFulfillment(copy(plain));
            assertThat(plainReplay.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(plainReplay.getIdempotentReplay()).isTrue();
            assertThat(plainReplay.getReceiptVersion()).isEqualTo(rejected.getReceiptVersion());
            assertThat(plainReplay.getRejectionCode())
                    .isEqualTo(AcceptanceRejectionCode.QUANTITY_NOT_SUPPORTED.name());

            // 带 CAS 的同身份重发：REJECTED_FINAL 携带当前版本也不能重开。
            AcceptFulfillmentReqDTO cas = quantityRejectedRequest(PROJECT_A, casOrderNo);
            FulfillmentAcceptanceReceiptRespDTO casRejected = facade.acceptFulfillment(cas);
            assertThat(casRejected.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            AcceptFulfillmentReqDTO casRetry = copy(cas);
            casRetry.setPreviousReceiptVersion(casRejected.getReceiptVersion());
            FulfillmentAcceptanceReceiptRespDTO casReplay = facade.acceptFulfillment(casRetry);
            assertThat(casReplay.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(casReplay.getIdempotentReplay()).isTrue();
            assertThat(casReplay.getReceiptVersion()).isEqualTo(casRejected.getReceiptVersion());

            // 重放不得开启新尝试：无新订单、无新回执。
            assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(ordersBeforeReplay);
            assertThat(count(jdbc, "sc_fulfillment_acceptance_receipt")).isEqualTo(2L);
        }
    }

    /**
     * 按项目永久键签发 ISSUED 报价（Task3 语义：永久键为 projectId+buyerOrderNo）。
     * 受理不携带 token：服务端在供应链事务内按项目键查取唯一报价并一次性消费。
     */
    private static void issueQuote(ConfigurableApplicationContext context,
                                   String projectCode, String buyerOrderNo) {
        StandardPricingQuoteReqDTO quoteRequest = new StandardPricingQuoteReqDTO();
        quoteRequest.setSourceSystem(SOURCE_SYSTEM);
        quoteRequest.setExternalTenantId(EXTERNAL_TENANT_ID);
        quoteRequest.setProjectCode(projectCode);
        quoteRequest.setPlatformSkuCode(PLATFORM_SKU_CODE);
        quoteRequest.setBuyerOrderNo(buyerOrderNo);
        quoteRequest.setQuantity(ACCEPT_QUANTITY);
        context.getBean(StandardPricingQuoteService.class).quote(quoteRequest);
    }

    private static AcceptFulfillmentReqDTO acceptanceRequest(String projectCode, String buyerOrderNo) {
        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        request.setSourceSystem(SOURCE_SYSTEM);
        request.setExternalTenantId(EXTERNAL_TENANT_ID);
        request.setProjectCode(projectCode);
        request.setBuyerOrderNo(buyerOrderNo);
        request.setBuyerSkuCode(BUYER_SKU_CODE);
        request.setQuantity(ACCEPT_QUANTITY);
        request.setTarget(mobileTarget(FAKE_MOBILE));
        return request;
    }

    /**
     * 数量不为一的最终拒绝请求：QUANTITY_NOT_SUPPORTED 拒绝发生在报价消费之前，
     * 报价服务一期只支持数量一，无法为该键签发可消费报价。
     */
    private static AcceptFulfillmentReqDTO quantityRejectedRequest(String projectCode, String buyerOrderNo) {
        AcceptFulfillmentReqDTO request = acceptanceRequest(projectCode, buyerOrderNo);
        request.setQuantity(REJECTED_QUANTITY);
        return request;
    }

    private static FulfillmentTargetDTO mobileTarget(String accountValue) {
        FulfillmentTargetDTO target = new FulfillmentTargetDTO();
        target.setValues(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, accountValue));
        return target;
    }

    private static AcceptFulfillmentReqDTO copy(AcceptFulfillmentReqDTO source) {
        AcceptFulfillmentReqDTO target = new AcceptFulfillmentReqDTO();
        target.setSourceSystem(source.getSourceSystem());
        target.setExternalTenantId(source.getExternalTenantId());
        target.setProjectCode(source.getProjectCode());
        target.setBuyerOrderNo(source.getBuyerOrderNo());
        target.setBuyerSkuCode(source.getBuyerSkuCode());
        target.setQuantity(source.getQuantity());
        target.setPreviousReceiptVersion(source.getPreviousReceiptVersion());
        FulfillmentTargetDTO fulfillmentTarget = new FulfillmentTargetDTO();
        fulfillmentTarget.setValues(Map.copyOf(source.getTarget().getValues()));
        target.setTarget(fulfillmentTarget);
        return target;
    }

    /**
     * 并发收集受理结果：返回值要么是成功响应，要么是被捕获待断言的 ServiceException。
     * 起跑线屏障保证两个请求几乎同时进入 claim 的首次探测，触发唯一键竞争。
     */
    private static List<Object> concurrentlyCollect(int count, IndexedCallable action) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.call(current);
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @FunctionalInterface
    private interface IndexedCallable {
        Object call(int index) throws Exception;
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static Long projectId(JdbcTemplate jdbc, String projectCode) {
        return jdbc.queryForObject("SELECT id FROM sc_buyer_project WHERE project_code=?",
                Long.class, projectCode);
    }

    /**
     * 受理全链路 fixtures：直充限频门禁置 READY、分销商/项目/商品/发布/可售、开户、
     * 奇那梦供应商报价与主路由。与既有 FulfillmentAcceptanceIntegrationTest.seed 等价。
     */
    private static Long seed(ConfigurableApplicationContext context) {
        // 普通流程fixture明确建立已核验READY（测试密钥端口仅返回测试专用32字节key），不改变生产初始CLOSED。
        var gateJdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        byte[] gateFingerprint = context.getBean(RechargeFrequencyMigrationGateService.class)
                .currentKeyFingerprint();
        gateJdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='READY',verified_artifact=?,"
                + "identity_key_fingerprint=?,verified_at=NOW(3) WHERE id=1",
                "a".repeat(40), gateFingerprint);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        BuyerCommandService buyers = context.getBean(BuyerCommandService.class);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        buyers.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "吾盛", "吾盛", null, null,
                SOURCE_SYSTEM, EXTERNAL_TENANT_ID, now.minusMinutes(5), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "中国移动", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "话费直充", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_001",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                PLATFORM_SKU_CODE, "SPU_001", "话费直充 100 元", FulfillmentType.DIRECT_RECHARGE.code(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus(PLATFORM_SKU_CODE, CatalogEntityStatus.ACTIVE, 1L);
        String listingNo = catalog.createBuyerListing(
                new ProductCatalogCommandService.CreateBuyerListingCommand(
                        BUYER_CODE, PLATFORM_SKU_CODE, BUYER_SKU_CODE, 1L));
        context.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(
                        listingNo, 0L, 9_800L, "话费直充 100 元", now.minusMinutes(1), null,
                        "首次发布", 1L, "trace-listing", null, null));
        setAvailability(context, ListingAvailability.AVAILABLE, "SUPPLY_READY");

        Long buyerId = context.getBean(BuyerMapper.class).selectByBuyerCode(BUYER_CODE).getId();
        insertActiveProject(context.getBean(BuyerProjectMapper.class), PROJECT_A, buyerId, now);
        insertActiveProject(context.getBean(BuyerProjectMapper.class), PROJECT_B, buyerId, now);
        context.getBean(BuyerAccountOpeningService.class).open(
                new BuyerAccountOpeningService.OpeningBalanceCommand(
                        BUYER_CODE, Money.cny(25_000L), 1L, "evidence://opening",
                        new byte[32], "标准受理项目键集成测试期初额",
                        "trace-account", null));

        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode(PLATFORM_SKU_CODE);
        createQnmOffer(context, sku, now);
        // 044 预置引导策略占用同类型唯一 active 位；受理链路种子发布自有 CARD_PRIMARY 策略，先停用预置。
        RoutingPolicyPresetFixture.deactivatePresetPolicy(new JdbcTemplate(
                context.getBean("supplyChainDataSource", DataSource.class)));
        RoutingPolicyPublicationService routing = context.getBean(RoutingPolicyPublicationService.class);
        // PRD 分销商+商品类型层级：两个项目共用 FIXED 主供应商 QINAMENG。
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
        return buyerId;
    }

    private static void createQnmOffer(ConfigurableApplicationContext context,
                                       PlatformSkuDO sku, LocalDateTime now) {
        SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
        SupplierDO supplier = SupplierDO.builder().supplierCode("QINAMENG").supplierName("奇那梦")
                .shortName("奇那梦").supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        supplierMapper.insert(supplier);
        CredentialSlotDO credential = CredentialSlotDO.builder().slotNo("CRD_QNM")
                .providerType("ENV").secretRef("env:QNM_CREDENTIAL")
                .maskedHint("qnm-****").activeSecretVersion("v1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder().connectorNo("CON_QNM")
                .supplierId(supplier.getId()).connectorCode("GAME_ORDER")
                .adapterType("QNM_GAME_ORDER_V1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        SupplierConnectorMapper connectorMapper = context.getBean(SupplierConnectorMapper.class);
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
        context.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);
        connector.setCurrentVersionId(connectorVersion.getId());
        connector.setUpdatedAt(now);
        connectorMapper.updateById(connector);
        InventorySourceDO inventory = InventorySourceDO.builder().sourceNo("INV_QNM")
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .sourceType("REMOTE_ON_DEMAND").externalSourceCode("QNM_REMOTE")
                .ownerType("SUPPLIER").status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OFR_QNM_TELCO")
                .offerCode(PLATFORM_SKU_CODE).supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
        offerMapper.insert(offer);
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId())
                .versionNo(1).externalSkuCode("QNM-TELCO-100").supplierSettlementUnitPrice(9_000L)
                .currency("CNY").connectorVersionId(connectorVersion.getId())
                .inventorySourceId(inventory.getId()).minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .primaryCapabilityCode("CREATE_RECHARGE").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusHours(1)).publishReason("标准受理项目键联调")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        offer.setCurrentVersionId(version.getId());
        offer.setUpdatedAt(now);
        offerMapper.updateById(offer);
    }

    private static void setAvailability(ConfigurableApplicationContext context,
                                        ListingAvailability value, String reason) {
        BuyerListingMapper listingMapper = context.getBean(BuyerListingMapper.class);
        BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(
                context.getBean(BuyerMapper.class).selectByBuyerCode(BUYER_CODE).getId(), BUYER_SKU_CODE);
        BuyerListingAvailabilityMapper mapper = context.getBean(BuyerListingAvailabilityMapper.class);
        BuyerListingAvailabilityDO availability = mapper.selectByListingId(listing.getId());
        availability.setAvailability(value.getCode());
        availability.setReasonCode(reason);
        availability.setEvaluatedAt(LocalDateTime.now(ZoneOffset.UTC));
        availability.setBasisVersion(availability.getBasisVersion() + 1);
        availability.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(mapper.updateById(availability)).isEqualTo(1);
    }

    private static void insertActiveProject(BuyerProjectMapper mapper, String projectCode,
                                            Long buyerId, LocalDateTime now) {
        BuyerProjectDO project = BuyerProjectDO.builder()
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
        assertThat(mapper.insert(project)).isEqualTo(1);
    }

    ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
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
            OutboxEventRepository.class, BuyerCommandService.class, BuyerResolver.class,
            BuyerProjectAccessResolver.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class, BuyerListingPublicationService.class,
            BuyerAccountOpeningService.class, FundReservationService.class, AccountLedgerWriter.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader.class,
            com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class,
            com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRefreshService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRefreshTransactionalWorker.class,
            ClosedJsonSchemaValidator.class, FulfillmentTargetSchemaRegistry.class,
            AesGcmEnvelopeCrypto.class, SkuPurchaseLimitService.class, FulfillmentAcceptanceLifecycle.class,
            RechargeFrequencyLifecycle.class,
            RechargeFrequencyMigrationGateService.class,
            RechargeFrequencyIdentityService.class,
            RechargeFrequencyAcceptanceCoordinator.class,
            RechargeFrequencyProductResolver.class,
            OrderSummaryPolicyRegistry.class, CardOrderSummaryPolicy.class,
            BuyerFulfillmentCommandFacade.class,
            RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class,
            RechargeFrequencyWakeScheduler.class,
            RechargeFrequencyTimerStartupCheck.class,
            RechargeFrequencyRecoveryService.class,
            RechargeFrequencyWakeHandler.class,
            RechargeFrequencyQueryService.class,
            DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class,
            StandardPricingQuoteService.class, com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectPricingAdmissionService.class})
    static class TestConfiguration {
        @Bean
        RechargeFrequencyIdentityKeyProvider frequencyKey() {
            return () -> new byte[32];
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        /**
         * 真实报价服务（项目永久键语义）依赖 GenericBatchPricingService 提供当前项目定价版本；
         * 这里固定返回与已发布 Listing 等价的 READY 定价快照，并绑定真实平台 SKU 与 Listing，
         * 使报价的 platformSkuId/listingId 与受理链路实际锁定的商品一致。
         * 写法参照 StandardPricingQuotePermanentKeyIntegrationTest 的定价 stub。
         */
        @Bean
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
                BuyerListingDO listing = listingMapper.selectByBuyerAndSkuCode(buyerId, BUYER_SKU_CODE);
                ProjectSkuPricingVersionDO version = ProjectSkuPricingVersionDO.builder()
                        .id(9L).pricingStatus(PricingStatus.READY.name())
                        .distributionPriceCents(9_800L).salesPriceCents(10_000L)
                        .effectiveConfigJson(configJson).build();
                return new CurrentProjectPricing(version, listing.getId(), sku.getId(), sku.getSkuCode());
            });
            return service;
        }
    }
}
