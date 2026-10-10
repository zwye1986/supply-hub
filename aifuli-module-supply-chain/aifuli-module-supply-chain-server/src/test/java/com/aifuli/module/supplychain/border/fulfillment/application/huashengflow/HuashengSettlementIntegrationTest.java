package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.FundReservationDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.FundReservationMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 上的华昇资金受控提交、释放与 Guard 校验门禁集成测试。
 *
 * <p>测试覆盖：
 * 1. 无 permit 的通用入口（transition / confirmTrustedOutcome）拒绝推进华昇操作；
 * 2. 受控 commitHuashengSuccess：合法 permit、完整 type201 资产与金额匹配时实扣资金并落地资产；
 * 3. 受控 releaseHuashengRejection：合法 permit、可信失败且无资产时释放预占资金；
 * 4. 金额不匹配时 Guard 拦截并回滚资金；
 * 5. 资产非法或缺失时 Guard 拦截；
 * 6. 代次不匹配（stale permit）时 Guard 拦截；
 * 7. 已存在交付资产时 release 拦截拒绝。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#63-take与最终资金}。</p>
 */
class HuashengSettlementIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_settlement")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static ConfigurableApplicationContext context;

    private SupplierOrderLifecycle lifecycle;
    private FundReservationService fundReservationService;
    private BuyerAccountOpeningService accountOpeningService;
    private BuyerCommandService buyerCommandService;
    private BuyerMapper buyerMapper;
    private BuyerAccountMapper accountMapper;
    private FundReservationMapper reservationMapper;
    private FulfillmentOperationMapper operationMapper;
    private BOrderMapper bOrderMapper;
    private FulfillmentRequestMapper requestMapper;
    private SupplyChainOrderMapper orderMapper;
    private FulfillmentAssetMapper assetMapper;
    private BusinessNoGenerator businessNoGenerator;
    private SupplyChainDatabaseClock databaseClock;
    private JdbcTemplate jdbcTemplate;

    private static Long testBuyerId;
    private static String testBuyerCode;

    @BeforeAll
    static void startAll() {
        if (EXTERNAL_URL == null) {
            MYSQL.start();
        }
        String keyBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        context = new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", EXTERNAL_URL == null ? MYSQL.getJdbcUrl() : EXTERNAL_URL,
                        "aifuli.supply-chain.datasource.username", EXTERNAL_URL == null ? MYSQL.getUsername()
                                : System.getProperty("supply-chain.test.jdbc-username", "root"),
                        "aifuli.supply-chain.datasource.password", EXTERNAL_URL == null ? MYSQL.getPassword()
                                : System.getProperty("supply-chain.test.jdbc-password", ""),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.security.key-version", "test-v1",
                        "aifuli.supply-chain.security.envelope-key-base64", keyBase64,
                        "aifuli.supply-chain.security.fingerprint-key-base64", keyBase64
                )).run();
    }

    @AfterAll
    static void stopAll() {
        if (context != null) {
            context.close();
        }
        if (EXTERNAL_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void setUp() {
        lifecycle = context.getBean(SupplierOrderLifecycle.class);
        fundReservationService = context.getBean(FundReservationService.class);
        accountOpeningService = context.getBean(BuyerAccountOpeningService.class);
        buyerCommandService = context.getBean(BuyerCommandService.class);
        buyerMapper = context.getBean(BuyerMapper.class);
        accountMapper = context.getBean(BuyerAccountMapper.class);
        reservationMapper = context.getBean(FundReservationMapper.class);
        operationMapper = context.getBean(FulfillmentOperationMapper.class);
        bOrderMapper = context.getBean(BOrderMapper.class);
        requestMapper = context.getBean(FulfillmentRequestMapper.class);
        orderMapper = context.getBean(SupplyChainOrderMapper.class);
        assetMapper = context.getBean(FulfillmentAssetMapper.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        databaseClock = context.getBean(SupplyChainDatabaseClock.class);
        jdbcTemplate = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));

        // 准备采购商与期初账户
        if (testBuyerId == null) {
            testBuyerCode = buyerCommandService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                    null, "华昇测试采购商", "华昇测试采购商",
                    null, null, "AIFULI_MALL", "tenant-hs-settle",
                    LocalDateTime.now(ZoneOffset.UTC), 1L));
            BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(testBuyerCode);
            testBuyerId = buyer.getId();
            accountOpeningService.open(new BuyerAccountOpeningService.OpeningBalanceCommand(
                    testBuyerCode, Money.cny(100_000L), 1L,
                    "evidence://opening", new byte[32], "期初余额",
                    "trace-init", "causation-init"));
        }
    }

    @Test
    @DisplayName("1. 通用 transition 入口拒绝推进华昇操作")
    void genericTransitionRejectsHuashengOperation() {
        var fixture = seedAggregate("HS_GEN_01", AdapterStepCode.PLACE_ORDER, 1000L);
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .externalOrderNo("HS_ORD_GEN")
                .reportedSettlementAmount(1000L)
                .build();

        assertThatThrownBy(() -> lifecycle.transition(fixture.operation().getOperationNo(), outcome, "trace-gen"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("华昇操作必须使用专用受控 permit 入口推进");
    }

    @Test
    @DisplayName("2. 通用 confirmTrustedOutcome 入口拒绝推进华昇操作")
    void genericConfirmTrustedOutcomeRejectsHuashengOperation() {
        var fixture = seedAggregate("HS_GEN_02", AdapterStepCode.TAKE_CARD, 1000L);
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .externalOrderNo("HS_ORD_GEN")
                .reportedSettlementAmount(1000L)
                .build();

        assertThatThrownBy(() -> lifecycle.confirmTrustedOutcome(fixture.operation().getOperationNo(), outcome, "trace-gen", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("华昇操作必须使用专用受控 permit 入口推进");
    }

    @Test
    @DisplayName("3. 受控 commitHuashengSuccess：合法 permit、type201 资产与匹配金额完成实扣与交付")
    void commitHuashengSuccessCommitsFundsAndPersistsAsset() {
        var fixture = seedAggregate("HS_COMMIT_01", AdapterStepCode.TAKE_CARD, 1000L);
        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD);

        byte[] payload = type201Payload("CARD_CODE_001", "CARD_PASS_001");
        OperationAssetFact assetFact = OperationAssetFact.huashengType201("CARD_CODE_001", "CARD_PASS_001", payload);

        HuashengStepFact stepFact = new HuashengStepFact(
                HuashengStepFact.Endpoint.TAKE,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_SUCC",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                payload,
                List.of()
        );

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .protocolFact(stepFact)
                .assets(List.of(assetFact))
                .externalOrderNo("HS_ORD_SUCC")
                .reportedSettlementAmount(1000L)
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.commitHuashengSuccess(permit, outcome, "trace-commit");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        assertThat(result.bOrderStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        assertThat(result.orderStatus()).isEqualTo(SupplyChainOrderStatus.SUCCEEDED.getCode());

        // 验证资金预占推进至 COMMITTED (30)
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(
                testBuyerId, FundReferenceType.CARD_ORDER.code(), fixture.request().getRequestNo());
        assertThat(reservation.getStatus()).isEqualTo((short) 30);
        assertThat(reservation.getCommittedAmount()).isEqualTo(1000L);
        assertThat(reservation.getRemainingAmount()).isZero();

        // 验证资产入库
        List<FulfillmentAssetDO> assets = assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()));
        assertThat(assets).hasSize(1);
        assertThat(assets.getFirst().getMaskedValue()).isEqualTo("****");
        assertThat(assets.getFirst().getFingerprint()).isNotNull();

        // 验证台账写 COMMIT
        Long commitLedgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT' AND reference_no=?",
                Long.class, fixture.request().getRequestNo());
        assertThat(commitLedgerCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("4. 受控 releaseHuashengRejection：合法 permit 与可信失败释放资金且无资产")
    void releaseHuashengRejectionReleasesFundsAndDeliversNoAsset() {
        var fixture = seedAggregate("HS_RELEASE_01", AdapterStepCode.PLACE_ORDER, 1000L);
        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.PLACE_ORDER);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.REJECTED)
                .externalErrorCode("REFUND_SUCCESS")
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.releaseHuashengRejection(permit, outcome, "trace-release");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo(SupplierOrderOperationStatus.FAILED.getCode());
        assertThat(result.bOrderStatus()).isEqualTo(BOrderFulfillmentStatus.FAILED.getCode());
        assertThat(result.orderStatus()).isEqualTo(SupplyChainOrderStatus.FAILED.getCode());

        // 验证资金预占释放
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(
                testBuyerId, FundReferenceType.CARD_ORDER.code(), fixture.request().getRequestNo());
        assertThat(reservation.getStatus()).isEqualTo((short) 40);
        assertThat(reservation.getRemainingAmount()).isZero();

        // 验证无资产
        List<FulfillmentAssetDO> assets = assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()));
        assertThat(assets).isEmpty();

        // 验证台账写 RELEASE
        Long releaseLedgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='RELEASE' AND reference_no=?",
                Long.class, fixture.request().getRequestNo());
        assertThat(releaseLedgerCount).isEqualTo(1L);
    }

    @Test
    void executorTakeWithoutPriceMergesTrustedCheckpointAndCommitsRealLedger() throws Exception {
        var fixture = seedAggregate("HS_TAKE_MERGE", AdapterStepCode.TAKE_CARD, 1000L);
        storeCheckpoint(fixture, "10.0000");
        var facade = org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade.class);
        var factory = org.mockito.Mockito.mock(HuashengCallContextFactory.class);
        var card = HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\" TAKE_CODE \",\"pass\":\" TAKE_PASS \",\"validTime\":\"长期有效\"}");
        var ctor = com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse.class
                .getDeclaredConstructor(String.class, int.class, List.class);
        ctor.setAccessible(true);
        var response = ctor.newInstance(fixture.bOrder().getBillno(), 1, List.of(card));
        org.mockito.Mockito.when(facade.take(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCallResult.Parsed<>(response, List.of(), true));
        var ticket = com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket.builder()
                .operationNo(fixture.operation().getOperationNo()).billno(fixture.bOrder().getBillno())
                .adapterType(HuashengType201AssetCodec.ADAPTER_TYPE).adapterStepCode(AdapterStepCode.TAKE_CARD)
                .externalOrderNo(fixture.bOrder().getBillno()).quantity(1).timeoutSeconds(120).build();
        var outcome = new HuashengOperationExecutor(facade, factory).execute(ticket);
        assertThat(outcome.reportedSettlementAmount()).isNull();
        assertThat(outcome.outcomeClass()).isEqualTo(OperationOutcomeClass.ACCEPTED);
        var result = context.getBean(HuashengFulfillmentLifecycle.class)
                .apply(createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD), outcome, "trace-take");
        assertThat(result.decision()).isEqualTo(HuashengFlowDecision.FINAL_SUCCESS);
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getReportedSupplierSettlementAmount()).isEqualTo(1000L);
        assertThat(operationMapper.selectById(fixture.operation().getId()).getResultClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED.name());
        assertFunds(fixture, "COMMIT", 1);
        assertThat(assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()))).hasSize(1);
    }

    @Test
    void callbackPersistsCompleteAssetAndCommitsOnlyOnceAcrossDifferentNonces() {
        var fixture = seedAggregate("HS_CALLBACK_REAL", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        callback(fixture, "real-1", "10.00", "CB_REAL", "长期有效", true);
        callback(fixture, "real-2", "10.00", "CB_REAL", "长期有效", true);
        assertFunds(fixture, "COMMIT", 1);
        assertThat(assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()))).hasSize(1);
        assertThat(caseCount(fixture)).isZero();
    }

    @Test
    void mismatchedAmountRetainsEncryptedAssetEvidenceAndUniqueManualCase() {
        var fixture = seedAggregate("HS_MANUAL_EVIDENCE", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        callback(fixture, "manual-1", "11.00", "CB_HOLD", "长期有效", true);
        var operation = operationMapper.selectById(fixture.operation().getId());
        assertThat(operation.getStatus()).isEqualTo(SupplierOrderOperationStatus.MANUAL.getCode());
        assertThat(operation.getCompletedAt()).isNull();
        assertThat(readCheckpoint(fixture).pendingAssetPayload()).isNotNull();
        assertThat(new String(operation.getCheckpointCiphertext(), StandardCharsets.UTF_8)).doesNotContain("CB_HOLD");
        assertThat(caseCount(fixture)).isEqualTo(1);
        assertFunds(fixture, "COMMIT", 0);
        assertFunds(fixture, "RELEASE", 0);
        // 金额修正的新回调不能无视已有冲突自动扣款；旧证据保持不变。
        byte[] evidence = readCheckpoint(fixture).pendingAssetPayload();
        callback(fixture, "manual-2", "10.00", "CB_HOLD", "长期有效", true);
        assertThat(readCheckpoint(fixture).pendingAssetPayload()).isEqualTo(evidence);
        assertThat(caseCount(fixture)).isEqualTo(1);
        assertFunds(fixture, "COMMIT", 0);
    }

    @Test
    void manualCaseInsertFailureRollsBackCheckpointAndInboxTogether() {
        var fixture = seedAggregate("HS_MANUAL_ATOMIC", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        byte[] checkpoint = operationMapper.selectById(fixture.operation().getId()).getCheckpointCiphertext();
        // 仅此 Testcontainers 库添加拒绝约束，避免依赖创建触发器的 SUPER 权限。
        jdbcTemplate.execute("ALTER TABLE sc_fulfillment_exception_case ADD CONSTRAINT ck_hs_case_test_failure CHECK (operation_id <> "
                + fixture.operation().getId() + ")");
        try {
            assertThatThrownBy(() -> callback(fixture, "case-atomic", "11.00", "ATOMIC_CARD", "长期有效", true))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(operationMapper.selectById(fixture.operation().getId()).getCheckpointCiphertext()).isEqualTo(checkpoint);
            assertThat(caseCount(fixture)).isZero();
            String eventId = com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallbackFact
                    .computeEventId(1L, "0123456789abcdef", "case-atomic");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sc_inbox_event WHERE event_id=?", Long.class, eventId)).isZero();
            assertFunds(fixture, "COMMIT", 0); assertFunds(fixture, "RELEASE", 0);
        } finally { jdbcTemplate.execute("ALTER TABLE sc_fulfillment_exception_case DROP CHECK ck_hs_case_test_failure"); }
    }

    @Test
    void failedThenSuccessRegistersConflictWithoutReversingReleasedFunds() {
        var fixture = seedAggregate("HS_FAIL_CONFLICT", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        callback(fixture, "failed-first", "10.00", "", "", false);
        callback(fixture, "success-late", "10.00", "LATE_CODE", "长期有效", true);
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.FAILED.getCode());
        assertThat(caseCount(fixture)).isEqualTo(1);
        assertThat(readCheckpoint(fixture).pendingAssetPayload()).isNotNull();
        assertFunds(fixture, "RELEASE", 1);
        assertFunds(fixture, "COMMIT", 0);
    }

    @Test
    void differentPendingAssetCannotOverwriteEvidenceAndInboxRollsBack() {
        var fixture = seedAggregate("HS_EVIDENCE_SLOT", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        callback(fixture, "slot-first", "11.00", "SLOT_FIRST", "长期有效", true);
        byte[] evidence = readCheckpoint(fixture).pendingAssetPayload();
        assertThatThrownBy(() -> callback(fixture, "slot-second", "11.00", "SLOT_SECOND", "长期有效", true))
                .isInstanceOf(HuashengCallbackConflictException.class);
        assertThat(readCheckpoint(fixture).pendingAssetPayload()).isEqualTo(evidence);
        assertThat(caseCount(fixture)).isEqualTo(1);
        String eventId = com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallbackFact
                .computeEventId(1L, "0123456789abcdef", "slot-second");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sc_inbox_event WHERE event_id=?", Long.class, eventId)).isZero();
    }

    @Test
    void takeWithoutHistoricalAmountQueriesAndRetainsAssetInsteadOfInventingPrice() {
        var fixture = seedAggregate("HS_NO_PRICE", AdapterStepCode.TAKE_CARD, 1000L);
        storeCheckpoint(fixture, null);
        var asset = new HuashengType201AssetCodec().toFact(HuashengType201AssetCodecTest.card(
                "{\"type\":201,\"code\":\"NO_PRICE_CODE\",\"pass\":\"NO_PRICE_PASS\"}"));
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.TAKE, HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(), fixture.bOrder().getBillno(), 1, null, null, HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT, HuashengStepFact.IsTake.Y, asset.protectedPayload(), List.of());
        var outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).assets(List.of(asset))
                .externalOrderNo(fixture.bOrder().getBillno()).protocolFact(fact).build();
        var result = context.getBean(HuashengFulfillmentLifecycle.class)
                .apply(createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD), outcome, "trace-no-price");
        assertThat(result.decision()).isEqualTo(HuashengFlowDecision.WAIT_QUERY);
        assertThat(readCheckpoint(fixture).pendingAssetPayload()).isEqualTo(asset.protectedPayload());
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getReportedSupplierSettlementAmount()).isNull();
        assertFunds(fixture, "COMMIT", 0);
        assertFunds(fixture, "RELEASE", 0);
        assertThatThrownBy(() -> callback(fixture, "pending-drift", "10.00", "OTHER_CARD", "长期有效", true))
                .isInstanceOf(HuashengCallbackConflictException.class);
        assertFunds(fixture, "COMMIT", 0);
        callback(fixture, "pending-failed", "10.00", "", "", false);
        assertFunds(fixture, "RELEASE", 0);
        assertThat(caseCount(fixture)).isEqualTo(1);
    }

    @Test
    void recoveryClaimPersistsQuotaBeforeResultAndStopsNextClaimAtLimitButAcceptsCallback() {
        var fixture = seedAggregate("HS_CLAIM_QUOTA", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        for (int i = 1; i <= HuashengFlowPolicy.MAX_QUERY_COUNT; i++) {
            var op = operationMapper.selectById(fixture.operation().getId());
            op.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
            operationMapper.updateById(op);
            // 每次认领后模拟宕机，不调用 apply。
            assertThat(flow.claimForRecovery(op.getOperationNo())).isNotNull();
            assertThat(readCheckpoint(fixture).progress().queryCount()).isEqualTo(i);
            assertThat(readCheckpoint(fixture).progress().firstSendAt()).isNotNull();
            assertThat(operationMapper.selectById(op.getId()).getRecoveryDeadlineAt())
                    .isEqualTo(readCheckpoint(fixture).progress().recoveryDeadlineAt());
        }
        var op = operationMapper.selectById(fixture.operation().getId());
        op.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode()); operationMapper.updateById(op);
        assertThat(flow.claimForRecovery(op.getOperationNo())).isNull();
        assertThat(caseCount(fixture)).isEqualTo(1);
        callback(fixture, "quota-callback", "10.00", "QUOTA_CARD", "长期有效", true);
        assertFunds(fixture, "COMMIT", 1);
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
    }

    @Test
    void finalAllowedTakeClaimCanStillApplySuccessfulResult() {
        var fixture = seedAggregate("HS_LAST_TAKE", AdapterStepCode.TAKE_CARD, 1000L);
        storeCheckpoint(fixture, "10.00");
        var cp = readCheckpoint(fixture);
        var p = cp.progress();
        var limited = new HuashengCheckpoint(cp.binding(), cp.placeParameters(), cp.orderSn(), cp.observation(),
                new HuashengCheckpoint.Progress(20, 0, 4, p.firstSendAt(), p.recoveryDeadlineAt(), p.originTraceId()), null);
        var stored = context.getBean(HuashengCheckpointCodec.class).encrypt(limited);
        var op = fixture.operation(); op.setCheckpointCiphertext(stored.ciphertext());
        op.setStatus(SupplierOrderOperationStatus.PENDING.getCode()); operationMapper.updateById(op);
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        var permit = flow.claimForExecution(op.getOperationNo());
        assertThat(permit).isNotNull();
        assertThat(readCheckpoint(fixture).progress().takeCount()).isEqualTo(5);
        var asset = new HuashengType201AssetCodec().toFact(HuashengType201AssetCodecTest.card(
                "{\"type\":201,\"code\":\"LAST_TAKE\",\"pass\":\"LAST_PASS\"}"));
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.TAKE, HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(), cp.orderSn(), 1, null, null, HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT, HuashengStepFact.IsTake.Y, asset.protectedPayload(), List.of());
        assertThat(flow.apply(permit, OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED)
                .assets(List.of(asset)).protocolFact(fact).externalOrderNo(cp.orderSn()).build(), "trace-last").decision())
                .isEqualTo(HuashengFlowDecision.FINAL_SUCCESS);
        assertFunds(fixture, "COMMIT", 1);
    }

    @Test
    void successQueryAtTakeLimitIsRecoverableByCallbackButMoneyConflictIsNot() {
        for (boolean mismatch : new boolean[]{false, true}) {
            var fixture = seedAggregate(mismatch ? "HS_LIMIT_MONEY" : "HS_LIMIT_SUCCESS", AdapterStepCode.PLACE_ORDER, 1000L);
            storeCheckpoint(fixture, null);
            var cp = readCheckpoint(fixture);
            var stored = context.getBean(HuashengCheckpointCodec.class).encrypt(new HuashengCheckpoint(cp.binding(), cp.placeParameters(),
                    null, null, new HuashengCheckpoint.Progress(20, 0, 5, null, null, "origin"), null));
            fixture.operation().setCheckpointCiphertext(stored.ciphertext()); operationMapper.updateById(fixture.operation());
            var fact = new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                    fixture.bOrder().getBillno(), fixture.bOrder().getBillno(), 1, mismatch ? "11.00" : "10.00",
                    mismatch ? 1100L : 1000L, HuashengStepFact.Status.SUCCESS, HuashengStepFact.RefundStatus.NONE,
                    HuashengStepFact.IsTake.N, null, List.of());
            context.getBean(HuashengFulfillmentLifecycle.class).apply(createPermit(fixture.operation(), 1L, AdapterStepCode.QUERY_ORDER),
                    OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).protocolFact(fact).build(), "trace-limit");
            callback(fixture, mismatch ? "limit-money" : "limit-success", "10.00", mismatch ? "CONFLICT" : "LIMIT_OK", "长期有效", true);
            assertFunds(fixture, "COMMIT", mismatch ? 0 : 1);
        }
    }

    @Test
    void successThenMetadataDriftOrFailureRegistersConflictWithoutOverwritingDeliveredAsset() {
        var fixture = seedAggregate("HS_SUCC_CONFLICT", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        callback(fixture, "success-first", "10.00", "STABLE_CODE", "长期有效", true);
        var asset = assetMapper.selectOne(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()));
        callback(fixture, "metadata-drift", "10.00", "STABLE_CODE", "限本月使用", true);
        callback(fixture, "failed-late", "10.00", "", "", false);
        assertThat(caseCount(fixture)).isEqualTo(1);
        assertThat(assetMapper.selectById(asset.getId()).getEncryptedPayload()).isEqualTo(asset.getEncryptedPayload());
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        assertFunds(fixture, "COMMIT", 1);
        assertFunds(fixture, "RELEASE", 0);
    }

    private void callback(AggregateFixture fixture, String nonce, String price, String code, String validTime, boolean success) {
        var card = success ? java.util.Optional.of(HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\"" + code
                + "\",\"pass\":\" " + code + "_PASS \",\"validTime\":\"" + validTime + "\"}")) : java.util.Optional.<com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengProtocolCard>empty();
        var fact = new com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallbackFact(
                databaseClock.toInstant(databaseClock.now()).getEpochSecond(), nonce, fixture.bOrder().getBillno(), fixture.bOrder().getBillno(), 1,
                ((com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt.Parsed<com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPrice>)
                        com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengMoneyParser.parsePrice(price)).value(),
                success ? com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS
                        : com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.FAILED,
                card, price, "0123456789abcdef");
        context.getBean(HuashengCallbackApplicationService.class).handleCallbackFact(1L, 200L, fact, "trace-callback");
    }

    @Test
    void rollbackDoesNotLogCallbackOrFundsAsCompleted() {
        var fixture = seedAggregate("HS_LOG_ROLLBACK", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        var supplierLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SupplierOrderLifecycle.class);
        var callbackLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HuashengCallbackApplicationService.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); supplierLogger.addAppender(logs); callbackLogger.addAppender(logs);
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(context.getBean("supplyChainTransactionManager",
                    org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                callback(fixture, "log-rollback", "10.00", "LOG_ROLLBACK", "长期有效", true);
                status.setRollbackOnly();
            });
            assertFunds(fixture, "COMMIT", 0);
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("处理完成")
                    || event.getFormattedMessage().contains("commitHuashengSuccess 完成"));
            logs.list.clear();
            callback(fixture, "log-commit", "10.00", "LOG_ROLLBACK", "长期有效", true);
            assertFunds(fixture, "COMMIT", 1);
            assertThat(logs.list.stream().filter(event -> event.getFormattedMessage().contains("处理完成")).count()).isEqualTo(1);
            assertThat(logs.list.stream().filter(event -> event.getFormattedMessage().contains("commitHuashengSuccess 完成")).count()).isEqualTo(1);
        } finally { supplierLogger.detachAppender(logs); callbackLogger.detachAppender(logs); logs.stop(); }
    }

    private void assertFunds(AggregateFixture fixture, String entryType, long count) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type=? AND reference_no=?",
                Long.class, entryType, fixture.request().getRequestNo())).isEqualTo(count);
    }

    @Test
    void preparationWatchdogRetainsAggregateAndCreatesCaseWithoutCompletionTime() {
        var fixture = seedAggregate("HS_PREP_CASE", AdapterStepCode.PLACE_ORDER, 1000L);
        storeCheckpoint(fixture, null);
        operationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getId, fixture.operation().getId())
                .set(FulfillmentOperationDO::getStatus, SupplierOrderOperationStatus.PENDING.getCode())
                .set(FulfillmentOperationDO::getBusinessSendPossibleAt, null));
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        assertThat(flow.handlePreparationWatchdog(fixture.operation().getOperationNo())).isTrue();
        assertThat(operationMapper.selectById(fixture.operation().getId()).getCompletedAt()).isNull();
        assertThat(caseCount(fixture)).isEqualTo(1);
        assertThat(flow.handlePreparationWatchdog(fixture.operation().getOperationNo())).isFalse();
        assertFunds(fixture, "COMMIT", 0); assertFunds(fixture, "RELEASE", 0);
    }

    private long caseCount(AggregateFixture fixture) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case WHERE operation_id=? AND active_case_flag=1",
                Long.class, fixture.operation().getId());
    }

    private HuashengCheckpoint.Binding binding(AggregateFixture fixture) {
        var op = fixture.operation();
        return new HuashengCheckpoint.Binding(fixture.bOrder().getId(), fixture.bOrder().getBOrderNo(), op.getOperationNo(),
                op.getSequenceNo(), AdapterStepCode.valueOf(op.getAdapterStepCode()), 200L, 100L, op.getConfigDigest());
    }

    private void storeCheckpoint(AggregateFixture fixture, String price) {
        var observation = price == null ? null : new HuashengCheckpoint.Observation(
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint.QUERY, fixture.bOrder().getBillno(), fixture.bOrder().getBillno(),
                1, price, 1000L, com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS,
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent(),
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent());
        var checkpoint = new HuashengCheckpoint(binding(fixture), new HuashengCheckpoint.PlaceParameters(
                fixture.bOrder().getBillno(), 1001L, 1, 300, null, "https://example.test/callback"),
                price == null ? null : fixture.bOrder().getBillno(), observation, new HuashengCheckpoint.Progress(0, 0, 0, null, null, "origin"), null);
        var stored = context.getBean(HuashengCheckpointCodec.class).encrypt(checkpoint);
        var op = fixture.operation();
        op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion());
        op.setCheckpointCiphertext(stored.ciphertext()); op.setBusinessSendPossibleAt(databaseClock.now());
        operationMapper.updateById(op);
    }

    private HuashengCheckpoint readCheckpoint(AggregateFixture fixture) {
        var op = operationMapper.selectById(fixture.operation().getId());
        return context.getBean(HuashengCheckpointCodec.class).decrypt(new HuashengCheckpointCodec.Stored(
                op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding(fixture));
    }

    @Test
    @DisplayName("5. 金额不匹配时 Guard 拦截并回滚资金")
    void commitHuashengSuccessRollsBackOnAmountMismatch() {
        var fixture = seedAggregate("HS_MISMATCH_01", AdapterStepCode.TAKE_CARD, 1000L);
        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD);

        byte[] payload = type201Payload("CODE_BAD_AMT", "PASS_BAD_AMT");
        OperationAssetFact assetFact = OperationAssetFact.huashengType201("CODE_BAD_AMT", "PASS_BAD_AMT", payload);

        // 报告金额与期望金额 1000L 不符 (传入 2000L)
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .assets(List.of(assetFact))
                .reportedSettlementAmount(2000L)
                .build();

        assertThatThrownBy(() -> lifecycle.commitHuashengSuccess(permit, outcome, "trace-mismatch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reported settlement amount mismatch");

        // 验证资金仍保持预占 RESERVED，未被实扣
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(
                testBuyerId, FundReferenceType.CARD_ORDER.code(), fixture.request().getRequestNo());
        assertThat(reservation.getStatus()).isEqualTo((short) 10);
        assertThat(reservation.getRemainingAmount()).isEqualTo(1000L);

        // 验证无资产落库
        List<FulfillmentAssetDO> assets = assetMapper.selectList(new LambdaQueryWrapper<FulfillmentAssetDO>()
                .eq(FulfillmentAssetDO::getBOrderId, fixture.bOrder().getId()));
        assertThat(assets).isEmpty();
    }

    @Test
    @DisplayName("6. 资产载荷非法时 Guard 拦截并回滚")
    void commitHuashengSuccessRollsBackOnInvalidAssetPayload() {
        var fixture = seedAggregate("HS_BAD_ASSET_01", AdapterStepCode.TAKE_CARD, 1000L);
        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD);

        // 传入非法 JSON 或非 type201 载荷
        byte[] badPayload = "{\"bad\":\"payload\"}".getBytes(StandardCharsets.UTF_8);
        OperationAssetFact assetFact = OperationAssetFact.huashengType201("CODE", "PASS", badPayload);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .assets(List.of(assetFact))
                .reportedSettlementAmount(1000L)
                .build();

        assertThatThrownBy(() -> lifecycle.commitHuashengSuccess(permit, outcome, "trace-bad-asset"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Huasheng asset payload is not valid type 201");

        // 资金仍保留
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(
                testBuyerId, FundReferenceType.CARD_ORDER.code(), fixture.request().getRequestNo());
        assertThat(reservation.getStatus()).isEqualTo((short) 10);
    }

    @Test
    @DisplayName("7. 代次不匹配（stale permit）时 Guard 拦截并拒绝推进")
    void commitHuashengSuccessRollsBackOnGenerationMismatch() {
        var fixture = seedAggregate("HS_STALE_01", AdapterStepCode.TAKE_CARD, 1000L);
        // permit代次为 1，但数据库中代次更新为 2
        fixture.operation().setExecutionGeneration(2L);
        operationMapper.updateById(fixture.operation());

        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD);

        byte[] payload = type201Payload("CODE_STALE", "PASS_STALE");
        OperationAssetFact assetFact = OperationAssetFact.huashengType201("CODE_STALE", "PASS_STALE", payload);
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .assets(List.of(assetFact))
                .reportedSettlementAmount(1000L)
                .build();

        assertThatThrownBy(() -> lifecycle.commitHuashengSuccess(permit, outcome, "trace-stale"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Permit generation mismatch");
    }

    @Test
    @DisplayName("8. 存在已交付资产时 release 拦截拒绝")
    void releaseHuashengRejectionFailsIfDeliveredAssetsAlreadyExist() {
        var fixture = seedAggregate("HS_EXIST_ASSET_01", AdapterStepCode.TAKE_CARD, 1000L);
        HuashengExecutionPermit permit = createPermit(fixture.operation(), 1L, AdapterStepCode.TAKE_CARD);

        // 先人工插入一条已交付资产
        assetMapper.insert(FulfillmentAssetDO.builder()
                .assetNo(businessNoGenerator.next(BusinessNoPrefixes.FA))
                .fulfillmentRequestId(fixture.request().getId())
                .bOrderId(fixture.bOrder().getId())
                .operationId(fixture.operation().getId())
                .supplierId(fixture.bOrder().getSupplierId())
                .buyerId(testBuyerId)
                .sequenceNo(1)
                .unitQuantity(1L)
                .assetType("CARD_CODE_SECRET")
                .encryptedPayload(new byte[16])
                .maskedValue("****")
                .fingerprint(new byte[32])
                .keyVersion("test-v1")
                .lifecycleStatus((short) 10)
                .version(0L)
                .createdAt(databaseClock.now())
                .updatedAt(databaseClock.now())
                .build());

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.REJECTED)
                .externalErrorCode("FAILED")
                .build();

        assertThatThrownBy(() -> lifecycle.releaseHuashengRejection(permit, outcome, "trace-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Delivered assets already exist for BOrder");
    }

    private HuashengExecutionPermit createPermit(FulfillmentOperationDO operation, long generation, AdapterStepCode step) {
        return new HuashengExecutionPermit(
                operation.getOperationNo(),
                operation.getBOrderId(),
                generation,
                step,
                HuashengFlowTrigger.EXECUTE,
                databaseClock.now().plusSeconds(150)
        );
    }

    private byte[] type201Payload(String code, String pass) {
        return ("{\"payloadSchemaVersion\":1,\"assetType\":\"CARD_CODE_SECRET\",\"cardCode\":\"" + code + "\",\"cardSecret\":\"" + pass + "\","
                + "\"supplierPayload\":{\"adapterType\":\"HUA_SHENG_ORDER_V1\",\"type\":201,\"code\":\"" + code + "\",\"pass\":\"" + pass + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final java.util.concurrent.atomic.AtomicLong RECEIPT_SEQ = new java.util.concurrent.atomic.AtomicLong(20000L);

    private AggregateFixture seedAggregate(String prefix, AdapterStepCode step, long amount) {
        LocalDateTime now = databaseClock.now();
        String orderNo = businessNoGenerator.next(BusinessNoPrefixes.SC);
        String reqNo = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        String bOrderNo = businessNoGenerator.next(BusinessNoPrefixes.BO);
        String billno = businessNoGenerator.next(BusinessNoPrefixes.BILL);
        String opNo = businessNoGenerator.next(BusinessNoPrefixes.OP);

        long receiptId = RECEIPT_SEQ.incrementAndGet();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(orderNo).orderType("CARD").buyerId(testBuyerId)
                .buyerCodeSnapshot(testBuyerCode).buyerNameSnapshot("buyer")
                .buyerOrderNo("BUYER_ORD_" + prefix + "_" + receiptId)
                .acceptanceReceiptId(receiptId)
                .platformSkuId(1001L)
                .detailType(com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT)
                .currency("CNY")
                .projectId(201L).orderStatus(SupplyChainOrderStatus.PROCESSING.getCode())
                .aftersaleStatus((short) 0).businessStage("FULFILLING").detailVersion(1L)
                .quantity(1L).buyerSettlementAmount(amount)
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .requestNo(reqNo).orderId(order.getId()).buyerId(testBuyerId)
                .listingId(10L).listingVersionId(100L).platformSkuId(1001L)
                .targetSchemaCode("MOBILE_RECHARGE").targetSchemaVersion(1)
                .targetCiphertext(new byte[]{1, 2, 3}).targetDigest(new byte[32])
                .targetKeyVersion("v1")
                .status(SupplierOrderOperationStatus.EXECUTING.getCode()).requestedQuantity(1L).fulfilledQuantity(0L)
                .aggregateVersion(0L).createdAt(now).updatedAt(now).build();
        requestMapper.insert(request);

        order.setDetailId(request.getId());
        orderMapper.updateById(order);

        BOrderDO bOrder = BOrderDO.builder()
                .bOrderNo(bOrderNo).fulfillmentRequestId(request.getId()).orderId(order.getId())
                .attemptNo(1).supplierId(1L).offerId(10L).offerVersionId(100L).connectorVersionId(200L)
                .inventorySourceId(1L).billno(billno).idempotencyKey(bOrderNo)
                .requestedQuantity(1L).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(amount).expectedSupplierSettlementAmount(amount)
                .currency("CNY").fulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode())
                .settlementStatus(BOrderSettlementStatus.PENDING.getCode()).activeFlag(true)
                .version(0L).createdAt(now).updatedAt(now).build();
        bOrderMapper.insert(bOrder);

        request.setActiveBOrderId(bOrder.getId());
        requestMapper.updateById(request);

        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrderNo, 1);
        byte[] configDigest = sha256((idempotencyKey + "|100|200").getBytes(StandardCharsets.UTF_8));

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo(opNo).bOrderId(bOrder.getId()).operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name()).sequenceNo(1).idempotencyKey(idempotencyKey)
                .configDigest(configDigest).status(SupplierOrderOperationStatus.EXECUTING.getCode())
                .attemptCount(0).executionGeneration(1L)
                .executionLeaseUntil(now.plusSeconds(150))
                .version(0L).createdAt(now).updatedAt(now).build();
        operationMapper.insert(operation);

        // 冻结资金预占
        fundReservationService.freeze(new FundReservationService.FreezeCommand(
                testBuyerId, FundReferenceType.CARD_ORDER.code(), reqNo,
                Money.cny(amount), orderNo, "trace-freeze", "rcpt-01"));

        return new AggregateFixture(order, request, bOrder, operation);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record AggregateFixture(
            SupplyChainOrderDO order,
            FulfillmentRequestDO request,
            BOrderDO bOrder,
            FulfillmentOperationDO operation
    ) {}

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            UlidGenerator.class,
            BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class,
            BuyerCommandService.class,
            BuyerAccountOpeningService.class,
            FundReservationService.class,
            AccountLedgerWriter.class,
            CardOrderSummaryPolicy.class,
            SupplierOrderLifecycle.class,
            HuashengFulfillmentLifecycle.class,
            HuashengCheckpointCodec.class,
            HuashengCallbackApplicationService.class,
            HuashengCallbackInbox.class
    })
    static class TestConfig {
        @Bean
        com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer persistentTimer() {
            return org.mockito.Mockito.mock(com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer.class);
        }
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        RechargeFrequencyLifecycle rechargeFrequencyLifecycle(
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper executionMapper,
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper entryMapper,
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper queueMapper,
                SupplyChainDatabaseClock clock,
                SupplyChainOrderMapper orderMapper,
                BOrderMapper bOrderMapper,
                FulfillmentOperationMapper operationMapper) {
            return new RechargeFrequencyLifecycle(
                    queueMapper, entryMapper,
                    org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyIdentityService.class),
                    org.mockito.Mockito.mock(com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver.class),
                    clock, executionMapper, operationMapper, bOrderMapper, orderMapper,
                    org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler.class),
                    org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService.class)
            );
        }
    }
}
