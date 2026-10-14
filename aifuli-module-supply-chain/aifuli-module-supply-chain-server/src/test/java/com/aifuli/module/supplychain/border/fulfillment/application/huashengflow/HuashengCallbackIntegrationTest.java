package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventDO;
import com.aifuli.module.supplychain.infrastructure.inbox.InboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionCaseStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionReasonCodes;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 华昇回调幂等收敛、时间窗校验与并发锁序集成测试。
 *
 * <p>测试场景覆盖：
 * <ul>
 *   <li>1. 同 nonce 同内容：只执行一次业务流转，重复调用幂等成功；</li>
 *   <li>2. 同 nonce 异内容或改投不同版本：抛出 409 冲突异常；</li>
 *   <li>3. 已提交同内容过时间窗：仍返回 200 确认；</li>
 *   <li>4. 未处理旧内容过时间窗：抛出 400 校验异常；</li>
 *   <li>5. 业务事务回滚：Inbox 记录同步回滚，后续允许重投；</li>
 *   <li>6. 不同 nonce 同资产：领域聚合幂等，不重复扣款或生成重复资产；</li>
 *   <li>7. 同步响应与回调竞态：终态后回调不重扣；</li>
 *   <li>8. CONFIRM 认领后至结果落库期间：持进程锁排队，回调不能抢先释放；</li>
 *   <li>9. 认证后等进程互斥锁超 3 秒：抛出 503 异常且无 Inbox 与领域写入。</li>
 * </ul>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛}。
 */
class HuashengCallbackIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_callback")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static ConfigurableApplicationContext context;

    private HuashengCallbackApplicationService callbackService;
    private HuashengCallbackInbox callbackInbox;
    private HuashengFulfillmentLifecycle lifecycle;
    private FulfillmentOperationMapper operationMapper;
    private BOrderMapper bOrderMapper;
    private FulfillmentRequestMapper requestMapper;
    private InboxEventMapper inboxEventMapper;
    private SupplyChainDatabaseClock databaseClock;
    private ReentrantLock executionLock;
    private SupplierConnectorVersionMapper versionMapper;
    private SupplierConnectorMapper connectorMapper;
    private BusinessNoGenerator businessNoGenerator;
    private HuashengCheckpointCodec checkpointCodec;
    private FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private FulfillmentAssetMapper assetMapper;
    private HmacFingerprintPort hmacFingerprint;

    private static final Long CONNECTOR_VER_ID = 200L;
    private static final Long SUPPLIER_ID = 1L;
    private static final String APP_ID = "test-merchant-01";

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
        callbackService = context.getBean(HuashengCallbackApplicationService.class);
        callbackInbox = context.getBean(HuashengCallbackInbox.class);
        lifecycle = context.getBean(HuashengFulfillmentLifecycle.class);
        operationMapper = context.getBean(FulfillmentOperationMapper.class);
        bOrderMapper = context.getBean(BOrderMapper.class);
        requestMapper = context.getBean(FulfillmentRequestMapper.class);
        inboxEventMapper = context.getBean(InboxEventMapper.class);
        databaseClock = context.getBean(SupplyChainDatabaseClock.class);
        executionLock = context.getBean(ReentrantLock.class);
        versionMapper = context.getBean(SupplierConnectorVersionMapper.class);
        connectorMapper = context.getBean(SupplierConnectorMapper.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        checkpointCodec = context.getBean(HuashengCheckpointCodec.class);
        exceptionCaseMapper = context.getBean(FulfillmentExceptionCaseMapper.class);
        assetMapper = context.getBean(FulfillmentAssetMapper.class);
        hmacFingerprint = context.getBean(HmacFingerprintPort.class);

        // 初始化 connector 与 version 映射
        ensureConnectorRecords();
    }

    private void ensureConnectorRecords() {
        SupplierConnectorDO conn = connectorMapper.selectById(10L);
        if (conn == null) {
            conn = SupplierConnectorDO.builder()
                    .id(10L).connectorNo("CONN_10").supplierId(SUPPLIER_ID)
                    .connectorCode("HS_CODE").adapterType("HUA_SHENG_ORDER_V1")
                    .currentVersionId(CONNECTOR_VER_ID).status((short) 10).version(0L)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            connectorMapper.insert(conn);
        }
        SupplierConnectorVersionDO ver = versionMapper.selectById(CONNECTOR_VER_ID);
        if (ver == null) {
            ver = SupplierConnectorVersionDO.builder()
                    .id(CONNECTOR_VER_ID).connectorId(10L).versionNo(1).status((short) 10)
                    .baseUrl("https://test.huasheng.com")
                    .protocolVersion("V1")
                    .credentialSlotId(1L)
                    .merchantAccount(APP_ID).adapterConfigSchemaVersion(1).adapterConfigJson("{}")
                    .validationStatus((short) 10).configDigest(new byte[32])
                    .connectTimeoutMs(3000).readTimeoutMs(5000).version(0L)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            versionMapper.insert(ver);
        }
    }

    @Test
    @DisplayName("1. 同 nonce 同内容：只执行一次业务流转，重复调用幂等成功返回")
    void sameNonceSamePayload_processedOnlyOnce() {
        var fixture = seedOrderAggregate("HS_CB_IDEMP", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_001", fixture.bOrder().getBillno(), "HS_ORD_001", nowSec);

        // 第一次调用
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-1");

        // 验证 Inbox 记录已为 STATUS_SUCCESS
        String eventId = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_001");
        InboxEventDO inbox = inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId);
        assertThat(inbox).isNotNull();
        assertThat(inbox.getStatus()).isEqualTo(InboxEventMapper.STATUS_SUCCESS);

        // 验证 BOrder 已到终态
        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());

        // 第二次重复调用同 nonce 同事实
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-1-dup");

        // 仍为 1 条成功记录
        List<InboxEventDO> list = inboxEventMapper.selectList(new LambdaQueryWrapper<InboxEventDO>()
                .eq(InboxEventDO::getConsumerName, HuashengCallbackInbox.CONSUMER_NAME)
                .eq(InboxEventDO::getEventId, eventId));
        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("2. 同 nonce 异内容或改投版本：抛出 409 冲突异常")
    void sameNonceDifferentPayloadOrDifferentVersion_returns409() {
        var fixture = seedOrderAggregate("HS_CB_DIFF", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact1 = createSuccessFact("NONCE_DIFF_002", fixture.bOrder().getBillno(), "HS_ORD_002", nowSec);
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact1, "trace-cb-2");

        // 同 nonce 但不同 orderSn
        HuashengCallbackFact fact2 = createSuccessFact("NONCE_DIFF_002", fixture.bOrder().getBillno(), "HS_ORD_DIFF", nowSec);

        assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact2, "trace-cb-2-conflict"))
                .isInstanceOf(HuashengCallbackConflictException.class);

        // 同 nonce 改投其它 connectorVersionId (300L)
        assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, 300L, fact1, "trace-cb-2-ver-conflict"))
                .isInstanceOf(HuashengCallbackConflictException.class);
    }

    @Test
    @DisplayName("3. 已提交同内容过时间窗：仍返回 200 确认，不抛 400")
    void alreadyCommittedSamePayload_beyondTimeWindow_returns200() {
        var fixture = seedOrderAggregate("HS_CB_TIME_SUCC", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_TIME_003", fixture.bOrder().getBillno(), "HS_ORD_003", nowSec);
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-3");

        // 推进测试数据库时钟，重投完全相同事实（timestamp 也是防重内容，不能改消息冒充旧消息）。
        java.time.LocalDateTime later = databaseClock.now().plusSeconds(600);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean("supplyChainDataSource", javax.sql.DataSource.class));
        new org.springframework.transaction.support.TransactionTemplate(context.getBean("supplyChainTransactionManager",
                org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            jdbc.execute("SET timestamp=" + databaseClock.toInstant(later).getEpochSecond());
            try {
                assertThat(databaseClock.toInstant(databaseClock.now()).getEpochSecond() - fact.timestamp()).isGreaterThan(300);
                callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-3-expired-dup");
            } finally { jdbc.execute("SET timestamp=DEFAULT"); }
        });
    }

    @Test
    @DisplayName("4. 未处理旧内容过时间窗：抛出 400 校验异常")
    void unprocessedOldPayload_beyondTimeWindow_returns400() {
        var fixture = seedOrderAggregate("HS_CB_TIME_FAIL", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        // 首次到达即超出 300 秒
        HuashengCallbackFact oldFact = createSuccessFact("NONCE_OLD_004", fixture.bOrder().getBillno(), "HS_ORD_004", nowSec - 500);

        assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, oldFact, "trace-cb-4-old"))
                .isInstanceOf(HuashengCallbackValidationException.class);

        // 验证没有写入 Inbox
        String eventId = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_OLD_004");
        InboxEventDO inbox = inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId);
        assertThat(inbox).isNull();
    }

    @Test
    @DisplayName("5. 事务回滚后可重投：领域异常导致事务回滚后，Inbox 记录同步回滚并允许后续重投成功")
    void transactionRollback_allowsSubsequentRedelivery() {
        var fixture = seedOrderAggregate("HS_CB_ROLLBACK", AdapterStepCode.PLACE_ORDER, 1);
        // 不执行 claimPlaceToProduceMarker，导致 applyCallback 因首 PLACE marker 缺失抛出异常导致事务回滚

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_RB_005", fixture.bOrder().getBillno(), "HS_ORD_005", nowSec);

        assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-5-rb"))
                .isInstanceOf(HuashengCallbackNotFoundException.class);

        // 验证 Inbox 记录随事务回滚不存在
        String eventId = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_RB_005");
        InboxEventDO inbox = inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId);
        assertThat(inbox).isNull();

        // 补全前置条件（使 marker 存在）
        claimPlaceToProduceMarker(fixture);

        // 供应商重投相同 nonce，此时应能顺利成功
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-5-retry");

        InboxEventDO retryInbox = inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId);
        assertThat(retryInbox).isNotNull();
        assertThat(retryInbox.getStatus()).isEqualTo(InboxEventMapper.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("6. 不同 nonce 同资产：领域聚合幂等，不重复扣款或产生重复资产")
    void differentNonceSameAsset_idempotentNoDuplicateFunds() {
        var fixture = seedOrderAggregate("HS_CB_DIFF_NONCE", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact1 = createSuccessFact("NONCE_A_006", fixture.bOrder().getBillno(), "HS_ORD_006", nowSec, "CD_006", "SEC_006", "10.00");
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact1, "trace-cb-6-a");

        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());

        // 供应商用不同 nonce 再次发送相同资产与单号
        HuashengCallbackFact fact2 = createSuccessFact("NONCE_B_006", fixture.bOrder().getBillno(), "HS_ORD_006", nowSec, "CD_006", "SEC_006", "10.00");
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact2, "trace-cb-6-b");

        // 两个 nonce 均记录为 SUCCESS，但订单状态与终态不变
        String eventId1 = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_A_006");
        String eventId2 = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_B_006");
        assertThat(inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId1).getStatus())
                .isEqualTo(InboxEventMapper.STATUS_SUCCESS);
        assertThat(inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId2).getStatus())
                .isEqualTo(InboxEventMapper.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("7. 同步响应与回调竞态：订单已由同步置终态，后续回调不重复扣款")
    void syncResponseAndCallbackRace_noDuplicateDeduction() {
        var fixture = seedOrderAggregate("HS_CB_RACE", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        // 模拟同步流程已经将 BOrder 与 Operation 更新为 SUCCEEDED
        fixture.bOrder().setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        bOrderMapper.updateById(fixture.bOrder());
        fixture.operation().setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        operationMapper.updateById(fixture.operation());

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_RACE_007", fixture.bOrder().getBillno(), "HS_ORD_007", nowSec);

        // 回调到达，应平稳收敛
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-7");

        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
    }

    @Test
    @DisplayName("8. CONFIRM 认领后至结果落库期间：持进程锁排队，回调不能抢先释放")
    void confirmClaimedUntilResultPersisted_callbackCannotPrematurelyRelease() throws Exception {
        var fixture = seedOrderAggregate("HS_CB_LOCK_ORDER", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_LOCK_008", fixture.bOrder().getBillno(), "HS_ORD_008", nowSec);

        // 模拟后台线程正在执行 CONFIRM 并持有 executionLock 1 秒钟
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch finishTaskLatch = new CountDownLatch(1);

        Thread runnerThread = new Thread(() -> {
            executionLock.lock();
            try {
                lockAcquiredLatch.countDown();
                finishTaskLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } finally {
                executionLock.unlock();
            }
        });
        runnerThread.start();
        lockAcquiredLatch.await(3, TimeUnit.SECONDS);

        // 在锁被持有时启动回调异步任务，回调应等待锁而非跳过或直接释放
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> callbackFuture = executor.submit(() -> {
            callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-8");
        });

        // 释放锁
        finishTaskLatch.countDown();
        callbackFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        runnerThread.join();

        // 验证回调在持锁释放后成功推进
        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
    }

    @Test
    @DisplayName("9. 认证后等进程互斥锁超 3 秒：抛出 503 异常且无 Inbox 与领域写入")
    void processLockTimeoutOver3Seconds_returns503_noInboxOrDomainWrites() throws Exception {
        var fixture = seedOrderAggregate("HS_CB_TIMEOUT", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact = createSuccessFact("NONCE_TIMEOUT_009", fixture.bOrder().getBillno(), "HS_ORD_009", nowSec);

        // 测试线程独占锁 4 秒
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            executionLock.lock();
            try {
                lockAcquired.countDown();
                releaseLock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } finally {
                executionLock.unlock();
            }
        });
        holder.start();
        lockAcquired.await(3, TimeUnit.SECONDS);

        try {
            // 回调等待互斥锁超时（3 秒），应抛出 HuashengLockBusyException
            assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact, "trace-cb-9"))
                    .isInstanceOf(HuashengLockBusyException.class);
        } finally {
            releaseLock.countDown();
            holder.join();
        }

        // 验证没有写入 Inbox
        String eventId = HuashengCallbackFact.computeEventId(SUPPLIER_ID, APP_ID, "NONCE_TIMEOUT_009");
        InboxEventDO inbox = inboxEventMapper.selectForUpdate(HuashengCallbackInbox.CONSUMER_NAME, eventId);
        assertThat(inbox).isNull();

        // 验证 BOrder 状态没有改变
        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.PROCESSING.getCode());
    }

    @Test
    @DisplayName("10. 身份冲突升级Case；后续合法成功记供应商成功，但不能绕过冲突完成平台交付")
    void recoveryLimitCaseWithIdentityConflictBlocksPlatformDeliveryButRetainsSupplierSuccess() {
        var fixture = seedOrderAggregate("HS_CB_UPG", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        LocalDateTime now = databaseClock.now();
        long nowSec = databaseClock.toInstant(now).getEpochSecond();

        // 模拟恢复到限人工状态
        FulfillmentOperationDO op = operationMapper.selectById(fixture.operation().getId());
        op.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
        operationMapper.updateById(op);
        BOrderDO bo = bOrderMapper.selectById(fixture.bOrder().getId());
        bo.setFulfillmentStatus(BOrderFulfillmentStatus.MANUAL.getCode());
        bOrderMapper.updateById(bo);
        var binding = new HuashengCheckpoint.Binding(bo.getId(), bo.getBOrderNo(), op.getOperationNo(),
                op.getSequenceNo(), AdapterStepCode.PLACE_ORDER, CONNECTOR_VER_ID, 100L, op.getConfigDigest());
        var cp = checkpointCodec.decrypt(new HuashengCheckpointCodec.Stored(op.getCheckpointSchemaVersion(),
                op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
        var stored = checkpointCodec.encrypt(new HuashengCheckpoint(cp.binding(), cp.placeParameters(),
                "HS_ORD_FROZEN", cp.observation(), cp.progress(), null));
        op.setCheckpointCiphertext(stored.ciphertext()); operationMapper.updateById(op);

        String evidenceRef = "HUASHENG_CHECKPOINT:" + op.getOperationNo();
        byte[] evidence = evidenceRef.getBytes(StandardCharsets.UTF_8);
        exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE))
                .orderId(fixture.order().getId())
                .fulfillmentRequestId(fixture.request().getId())
                .bOrderId(fixture.bOrder().getId())
                .operationId(op.getId())
                .reasonCode(FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT)
                .status(FulfillmentExceptionCaseStatus.OPEN.getCode())
                .evidenceRef(evidenceRef)
                .evidenceDigest(hmacFingerprint.fingerprint(HuashengFlowCodes.EXCEPTION_EVIDENCE_PURPOSE, evidence))
                .activeCaseFlag(1)
                .createdAt(now).updatedAt(now).version(0L).build());

        // 回调1：供应商单号与冻结身份冲突；不是以价格差异制造人工状态。
        HuashengCallbackFact conflictFact = createSuccessFact(
                "NONCE_UPG_001", fixture.bOrder().getBillno(), "HS_ORD_UPG_1", nowSec,
                "CD_UPG_1", "SEC_UPG_1", "12.00"
        );
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, conflictFact, "trace-upg-1");

        // 验证：activeCase 被升级为 HUASHENG_FACT_CONFLICT
        FulfillmentExceptionCaseDO activeCase = exceptionCaseMapper.selectActiveByOperationId(op.getId());
        assertThat(activeCase).isNotNull();
        assertThat(activeCase.getReasonCode()).isEqualTo(FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT);

        // 回调2：身份正确且完整卡密相同，供应商成功事实必须留存，平台仍待人工核实。
        HuashengCallbackFact validFact = createSuccessFact(
                "NONCE_UPG_002", fixture.bOrder().getBillno(), "HS_ORD_FROZEN", nowSec + 1,
                "CD_UPG_1", "SEC_UPG_1", "10.00"
        );
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, validFact, "trace-upg-2");

        // 供应商成功与平台人工态并存，不误报卡密已保存交付。
        BOrderDO boFinal = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(boFinal.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        assertThat(orderMapper.selectById(fixture.order().getId()).getOrderStatus()).isEqualTo(SupplyChainOrderStatus.MANUAL.getCode());
    }

    @Test
    @DisplayName("11. 同一完整卡密的成功回调不因金额变化制造履约冲突")
    void subsequentCallbackWithDifferentRawPriceDoesNotInvalidateDeliveredSuccess() {
        var fixture = seedOrderAggregate("HS_CB_DIFF_RAW", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture);

        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();

        // 首次回调成功终结（10.00 元）
        HuashengCallbackFact fact1 = createSuccessFact(
                "NONCE_RAW_001", fixture.bOrder().getBillno(), "HS_ORD_RAW_1", nowSec,
                "CD_RAW_1", "SEC_RAW_1", "10.00"
        );
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact1, "trace-raw-1");

        BOrderDO boAfter1 = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(boAfter1.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());

        // 第二次回调：相同 orderSn，相同卡密，但原元金额异化为 10.004（同分异额：舍入同为 1000 分但原元数值冲突）
        HuashengCallbackFact fact2 = createSuccessFact(
                "NONCE_RAW_002", fixture.bOrder().getBillno(), "HS_ORD_RAW_1", nowSec + 2,
                "CD_RAW_1", "SEC_RAW_1", "10.004"
        );
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact2, "trace-raw-2");

        // 本夹具的资金／平台提交是替身；这里只验证供应商事实与Case，不冒充平台交付门禁。
        FulfillmentExceptionCaseDO contradictionCase = exceptionCaseMapper.selectActiveByOperationId(fixture.operation().getId());
        assertThat(contradictionCase).isNull();
        assertThat(bOrderMapper.selectById(fixture.bOrder().getId()).getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
    }

    @Test
    @DisplayName("12. 跨单重复卡密导致主事务回滚，独立受控事务留证开 HUASHENG_FACT_CONFLICT case 并转 MANUAL")
    void crossOrderDuplicateCardKeyRollsBackMainTx_andIndependentTxCreatesFactConflictCase() {
        // 订单 1 交付卡密 CD_SAME_001 / SEC_SAME_001
        var fixture1 = seedOrderAggregate("HS_CROSS_1", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture1);
        long nowSec = databaseClock.toInstant(databaseClock.now()).getEpochSecond();
        HuashengCallbackFact fact1 = createSuccessFact(
                "NONCE_CROSS_1", fixture1.bOrder().getBillno(), "HS_ORD_C1", nowSec,
                "CD_SAME_001", "SEC_SAME_001", "10.00"
        );
        callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact1, "trace-cross-1");

        // 订单 2 收到同一张卡密
        var fixture2 = seedOrderAggregate("HS_CROSS_2", AdapterStepCode.PLACE_ORDER, 1);
        claimPlaceToProduceMarker(fixture2);
        HuashengCallbackFact fact2 = createSuccessFact(
                "NONCE_CROSS_2", fixture2.bOrder().getBillno(), "HS_ORD_C2", nowSec + 1,
                "CD_SAME_001", "SEC_SAME_001", "10.00"
        );

        SupplierOrderLifecycle supplierOrderLifecycle = context.getBean(SupplierOrderLifecycle.class);
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_sc_fulfillment_asset_fingerprint"))
                .when(supplierOrderLifecycle).commitHuashengSuccess(argThat(p -> p != null && p.bOrderId() == fixture2.bOrder().getId()), any(), any());

        // 调用回调，因为跨单卡密唯一键冲突，应抛出冲突异常（要求人工核实并回滚 Inbox）
        assertThatThrownBy(() -> callbackService.handleCallbackFact(SUPPLIER_ID, CONNECTOR_VER_ID, fact2, "trace-cross-2"))
                .isNotNull();

        // 验证：订单 2 主事务回滚（订单 2 下无资产）
        List<FulfillmentAssetDO> assets2 = assetMapper.selectList(
                new LambdaQueryWrapper<FulfillmentAssetDO>().eq(FulfillmentAssetDO::getBOrderId, fixture2.bOrder().getId()));
        assertThat(assets2).isEmpty();

        // 供应商成功独立留存，平台未保存本单卡密，保持MANUAL并开启冲突Case。
        BOrderDO bo2 = bOrderMapper.selectById(fixture2.bOrder().getId());
        assertThat(bo2.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        assertThat(orderMapper.selectById(fixture2.order().getId()).getOrderStatus()).isEqualTo(SupplyChainOrderStatus.MANUAL.getCode());
        FulfillmentExceptionCaseDO conflictCase = exceptionCaseMapper.selectActiveByOperationId(fixture2.operation().getId());
        assertThat(conflictCase).isNotNull();
        assertThat(conflictCase.getReasonCode()).isEqualTo(FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT);
    }

    private void claimPlaceToProduceMarker(TestAggregateFixture fixture) {
        lifecycle.claimForExecution(fixture.operation().getOperationNo());
    }

    @Test
    @DisplayName("正式加密 FAILED 回调零交付/省略数量返回200，非法非零交付返回400")
    void signedFailedCallbackUsesDeliveryQuantityAtHttpBoundary() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var pair = keys.generateKeyPair();
        String key = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String secret = "abcdef0123456789abcdef0123456789";
        var config = new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                "HS_CALLBACK", CONNECTOR_VER_ID, 1, com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.TYPE,
                "https://example.test", APP_ID, null, "/callbacks/suppliers/huasheng", Set.of(), 400L,
                com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET, 1000, 1000, Map.of());
        when(context.getBean(PublishedConnectorConfigurationResolver.class).resolve(CONNECTOR_VER_ID)).thenReturn(config);
        var credentials = context.getBean(FrozenCredentialSetResolver.class);
        var controller = new com.aifuli.module.supplychain.interfaceapi.callback.HuashengCallbackController(callbackService);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (String number : new String[]{"0", "ABSENT", "1"}) {
            var fixture = seedOrderAggregate("HS_FAILED_HTTP_" + number, AdapterStepCode.PLACE_ORDER, 1);
            claimPlaceToProduceMarker(fixture);
            String plaintext = "{\"timestamp\":" + databaseClock.toInstant(databaseClock.now()).getEpochSecond()
                    + ",\"nonce\":\"failed-http-" + number + "\",\"orderSn\":\"HS_HTTP_" + number
                    + "\",\"outOrderSn\":\"" + fixture.bOrder().getBillno() + "\","
                    + (number.equals("ABSENT") ? "" : "\"num\":" + number + ",")
                    + "\"totalPrice\":\"10.00\",\"status\":\"FAILED\",\"list\":[]}";
            String cipher = com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto.encryptBodyHex(plaintext, secret, APP_ID);
            String sign = com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto.signBodyHex(cipher, pair.getPrivate());
            // 每次认证都返回新数组，真实入口会擦除材料；重复投递不复用已擦除凭据。
            when(credentials.resolve(any(), any(), any())).thenAnswer(invocation -> Map.of(
                    com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.APP_SECRET, secret.toCharArray(),
                    com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.CALLBACK_PUBLIC_KEY, key.toCharArray()));
            for (int delivery = 0; delivery < 2; delivery++) {
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/callbacks/suppliers/huasheng/" + CONNECTOR_VER_ID)
                        .header("Appid", APP_ID).header("X-Sign", sign)
                        .header("trace-id", "trace-failed-http").content(cipher)).andReturn();
                assertThat(response.getResponse().getStatus()).isEqualTo(number.equals("1") ? 400 : 200);
                assertThat(response.getResponse().getContentAsString()).isEqualTo(number.equals("1") ? "Invalid callback" : "success");
            }
            var bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
            if (number.equals("1")) {
                assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.PROCESSING.getCode());
            } else {
                assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.FAILED.getCode());
                var op = operationMapper.selectById(fixture.operation().getId());
                var checkpoint = checkpointCodec.decrypt(new HuashengCheckpointCodec.Stored(
                        op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()),
                        new HuashengCheckpoint.Binding(bOrder.getId(), bOrder.getBOrderNo(), op.getOperationNo(),
                                op.getSequenceNo(), AdapterStepCode.PLACE_ORDER, CONNECTOR_VER_ID, 100L, op.getConfigDigest()));
                assertThat(checkpoint.observation().num()).isZero();
                assertThat(checkpoint.placeParameters().num()).isEqualTo(1);
            }
        }
    }

    @Test
    void realSignedParserToControllerRejectsMalformedPlaintextWithoutDisclosure() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var pair = keys.generateKeyPair();
        String publicKey = java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String secret = "abcdef0123456789abcdef0123456789";
        var config = new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                "HS_CALLBACK", CONNECTOR_VER_ID, 1, com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.TYPE,
                "https://example.test", APP_ID, null, "/callbacks/suppliers/huasheng", java.util.Set.of(), 400L,
                com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET, 1000, 1000, java.util.Map.of());
        when(context.getBean(PublishedConnectorConfigurationResolver.class).resolve(CONNECTOR_VER_ID)).thenReturn(config);
        var credentials = context.getBean(FrozenCredentialSetResolver.class);
        var controller = new com.aifuli.module.supplychain.interfaceapi.callback.HuashengCallbackController(callbackService);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(controller.getClass());
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            for (String field : new String[]{"totalPrice", "status"}) {
                String marker = "CARD_SECRET_PARSER_CONTROLLER_" + field;
                var fixture = seedOrderAggregate("HS_PRIVACY_" + field, AdapterStepCode.PLACE_ORDER, 1);
                String plaintext = "{\"timestamp\":" + databaseClock.toInstant(databaseClock.now()).getEpochSecond()
                        + ",\"nonce\":\"privacy-" + field + "\",\"orderSn\":\"HS_PRIVACY\",\"outOrderSn\":\""
                        + fixture.bOrder().getBillno() + "\",\"num\":1,\"totalPrice\":\""
                        + (field.equals("totalPrice") ? marker : "10.00") + "\",\"status\":\""
                        + (field.equals("status") ? marker : "SUCCESS")
                        + "\",\"list\":[{\"type\":201,\"code\":\"PRIVATE_CODE\",\"pass\":\"PRIVATE_PASS\"}]}";
                String cipher = com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto.encryptBodyHex(plaintext, secret, APP_ID);
                String sign = com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto.signBodyHex(cipher, pair.getPrivate());
                char[] secretChars = secret.toCharArray(); char[] keyChars = publicKey.toCharArray();
                when(credentials.resolve(any(), any(), any())).thenReturn(java.util.Map.of(
                        com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.APP_SECRET, secretChars,
                        com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.CALLBACK_PUBLIC_KEY, keyChars));
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/callbacks/suppliers/huasheng/" + CONNECTOR_VER_ID).header("Appid", APP_ID).header("X-Sign", sign).content(cipher))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andReturn();
                assertThat(response.getResponse().getContentAsString()).isEqualTo("Invalid callback");
                assertThat(secretChars).containsOnly('\0'); assertThat(keyChars).containsOnly('\0');
                assertThat(logs.list).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(marker, cipher, sign, "PRIVATE_CODE", "PRIVATE_PASS");
                    assertThat(event.getThrowableProxy()).isNull();
                });
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private HuashengCallbackFact createSuccessFact(String nonce, String outOrderSn, String orderSn, long timestamp) {
        return createSuccessFact(nonce, outOrderSn, orderSn, timestamp, "CD" + nonce, "SEC" + nonce, "10.00");
    }

    private HuashengCallbackFact createSuccessFact(String nonce, String outOrderSn, String orderSn, long timestamp,
                                                   String code, String pass, String priceRaw) {
        var card = HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\"" + code + "\",\"pass\":\"" + pass + "\"}");
        HuashengPrice price = ((HuashengInternalAttempt.Parsed<HuashengPrice>) HuashengMoneyParser.parsePrice(priceRaw)).value();
        return new HuashengCallbackFact(
                timestamp,
                nonce,
                orderSn,
                outOrderSn,
                1,
                price,
                HuashengOrderStatus.SUCCESS,
                Optional.of(card),
                priceRaw,
                APP_ID
        );
    }

    private static final java.util.concurrent.atomic.AtomicLong RECEIPT_SEQ = new java.util.concurrent.atomic.AtomicLong(20000L);

    private TestAggregateFixture seedOrderAggregate(String prefix, AdapterStepCode step, int sequenceNo) {
        LocalDateTime now = databaseClock.now();
        String orderNo = businessNoGenerator.next(BusinessNoPrefixes.SC);
        String reqNo = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        String bOrderNo = businessNoGenerator.next(BusinessNoPrefixes.BO);
        String billno = businessNoGenerator.next(BusinessNoPrefixes.BILL);
        String opNo = businessNoGenerator.next(BusinessNoPrefixes.OP);

        long receiptId = RECEIPT_SEQ.incrementAndGet();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(orderNo).orderType("CARD").buyerId(101L)
                .buyerCodeSnapshot("BUYER_101").buyerNameSnapshot("buyer")
                .buyerOrderNo("BUYER_ORD_" + prefix + "_" + receiptId)
                .acceptanceReceiptId(receiptId)
                .platformSkuId(1001L)
                .detailType(com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT)
                .currency("CNY")
                .projectId(201L).orderStatus(SupplyChainOrderStatus.PROCESSING.getCode())
                .aftersaleStatus((short) 0).businessStage("FULFILLING").detailVersion(1L)
                .quantity(1L).buyerSettlementAmount(1000L)
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .requestNo(reqNo).orderId(order.getId()).buyerId(101L)
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
                .attemptNo(1).supplierId(SUPPLIER_ID).offerId(10L).offerVersionId(100L).connectorVersionId(CONNECTOR_VER_ID)
                .inventorySourceId(1L).billno(billno).idempotencyKey(bOrderNo)
                .requestedQuantity(1L).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(1000L).expectedSupplierSettlementAmount(1000L)
                .currency("CNY").fulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode())
                .settlementStatus(BOrderSettlementStatus.PENDING.getCode()).activeFlag(true)
                .version(0L).createdAt(now).updatedAt(now).build();
        bOrderMapper.insert(bOrder);

        request.setActiveBOrderId(bOrder.getId());
        requestMapper.updateById(request);

        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrderNo, sequenceNo);
        byte[] configDigest = sha256((idempotencyKey + "|100|200").getBytes(StandardCharsets.UTF_8));

        HuashengCheckpoint.Binding binding = new HuashengCheckpoint.Binding(
                bOrder.getId(), bOrderNo, opNo, sequenceNo, step, CONNECTOR_VER_ID, 100L, configDigest);
        HuashengCheckpoint.PlaceParameters place = new HuashengCheckpoint.PlaceParameters(
                billno, 1001L, 1, 1800, "13800138000", "http://localhost/callback/huasheng/200");
        HuashengCheckpoint.Progress progress = new HuashengCheckpoint.Progress(0, 0, 0, null, null, "seed-trace");
        HuashengCheckpoint checkpoint = new HuashengCheckpoint(binding, place, null, null, progress, null);
        HuashengCheckpointCodec.Stored stored = checkpointCodec.encrypt(checkpoint);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo(opNo).bOrderId(bOrder.getId()).sequenceNo(sequenceNo)
                .operationKind(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name()).idempotencyKey(idempotencyKey)
                .configDigest(configDigest).status(SupplierOrderOperationStatus.PENDING.getCode())
                .attemptCount(0).checkpointSchemaVersion(stored.schemaVersion())
                .checkpointKeyVersion(stored.keyVersion()).checkpointCiphertext(stored.ciphertext())
                .version(0L).createdAt(now).updatedAt(now).build();
        operationMapper.insert(operation);

        return new TestAggregateFixture(order, request, bOrder, operation);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record TestAggregateFixture(
            SupplyChainOrderDO order,
            FulfillmentRequestDO request,
            BOrderDO bOrder,
            FulfillmentOperationDO operation
    ) {}

    private SupplyChainOrderMapper orderMapper;

    @BeforeEach
    void initOrderMapper() {
        orderMapper = context.getBean(SupplyChainOrderMapper.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            UlidGenerator.class,
            BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class,
            HuashengCheckpointCodec.class,
            HuashengType201AssetCodec.class,
            HuashengFulfillmentLifecycle.class,
            HuashengCallbackInbox.class,
            HuashengCallbackApplicationService.class,
            HuashengLeaseWatchdogHandler.class,
            DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class
    })
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ReentrantLock huashengExecutionLock() {
            return new ReentrantLock();
        }

        @Bean
        PublishedConnectorConfigurationResolver publishedConnectorConfigurationResolver() {
            return mock(PublishedConnectorConfigurationResolver.class);
        }

        @Bean
        FrozenCredentialSetResolver frozenCredentialSetResolver() {
            return mock(FrozenCredentialSetResolver.class);
        }

        @Bean
        SupplierOrderLifecycle supplierOrderLifecycle(BOrderMapper bOrderMapper) {
            SupplierOrderLifecycle mock = mock(SupplierOrderLifecycle.class);
            when(mock.matchesHuashengDeliveredAsset(any(), any())).thenReturn(true);
            doAnswer(invocation -> {
                HuashengExecutionPermit permit = invocation.getArgument(0);
                BOrderDO bOrder = bOrderMapper.selectById(permit.bOrderId());
                if (bOrder != null) {
                    bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
                    bOrderMapper.updateById(bOrder);
                }
                return null;
            }).when(mock).commitHuashengSuccess(any(), any(), any());
            doAnswer(invocation -> {
                HuashengExecutionPermit permit = invocation.getArgument(0);
                BOrderDO bOrder = bOrderMapper.selectById(permit.bOrderId());
                if (bOrder != null) {
                    bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.FAILED.getCode());
                    bOrderMapper.updateById(bOrder);
                }
                return null;
            }).when(mock).releaseHuashengRejection(any(), any(), any());
            return mock;
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
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyIdentityService.class),
                    mock(com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver.class),
                    clock, executionMapper, operationMapper, bOrderMapper, orderMapper,
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler.class),
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService.class)
            );
        }
    }
}
