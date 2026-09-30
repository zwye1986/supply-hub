package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyActionAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyActionAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 校验限频身份、FIFO 序号和状态不变量；不以内存数据库替代锁与约束。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
class RechargeFrequencyPersistenceIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("frequency_persistence").withUsername("frequency").withPassword("frequency");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        MYSQL.start();
        context = new SpringApplicationBuilder(SupplyChainDataSourceConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                        "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
        dataSource = context.getBean("supplyChainDataSource", DataSource.class);
    }

    @AfterAll
    static void stopDatabase() {
        if (context != null) context.close();
        if (MYSQL.isRunning()) MYSQL.stop();
    }

    @Test
    void durableTablesExistWithUniqueQueueIdentityAndNoPhysicalForeignKeys() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME IN ('sc_recharge_frequency_queue','sc_recharge_frequency_entry',"
                    + "'sc_recharge_frequency_action_audit')")).isEqualTo(3);
            insertQueue(connection, 100, 1);
            assertThatThrownBy(() -> insertQueue(connection, 101, 1)).isInstanceOf(SQLException.class);
            insertQueue(connection, 102, 2);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM sc_recharge_frequency_queue WHERE id IN (100,102)"))
                    .isEqualTo(2);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                    + "WHERE CONSTRAINT_SCHEMA = DATABASE()" )).isZero();
        }
    }


    @Test
    void rejectsDuplicateOrdersAndSequencesButKeepsFifoIndependentOfPrimaryKey() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertQueue(connection, 200, 200);
            insertEntry(connection, 205, 200, 2001, 1);
            insertEntry(connection, 201, 200, 2002, 2);
            assertThatThrownBy(() -> insertEntry(connection, 206, 200, 2001, 3))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertEntry(connection, 207, 200, 2003, 2))
                    .isInstanceOf(SQLException.class);
        }
        RechargeFrequencyEntryMapper mapper = context.getBean(RechargeFrequencyEntryMapper.class);
        TransactionTemplate transaction = transaction();
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.selectFirstWaitingForUpdate(200L, RechargeFrequencyEntryStatus.WAITING.getCode())
                    .getId()).isEqualTo(205L);
            assertThat(mapper.selectByIdForUpdate(201L).getEnqueueSequence()).isEqualTo(2L);
        });
        assertThat(mapper.selectByOrderId(2001L).getEnqueueSequence()).isEqualTo(1L);
    }

    @Test
    void checkConstraintsRejectInvalidIdentityGenerationStatusAndTimestamps() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertQueue(connection, 300, 300);
            insertEntry(connection, 301, 300, 3001, 1);
            for (String assignment : new String[]{"platform_spu_id=0", "next_enqueue_sequence=0",
                    "occupancy_generation=-1", "wake_generation=-1", "version=-1",
                    "holder_entry_id=301", "acquired_at=CURRENT_TIMESTAMP(3)"}) {
                assertThatThrownBy(() -> execute(connection,
                        "UPDATE sc_recharge_frequency_queue SET " + assignment + " WHERE id=300"))
                        .as(assignment).isInstanceOf(SQLException.class);
            }
            for (String assignment : new String[]{"enqueue_sequence=0", "queue_id=0", "buyer_id=0",
                    "project_id=0", "version=-1", "occupancy_generation=-1", "status=99", "status=10",
                    "status=20", "dispatch_phase=10", "completed_at=CURRENT_TIMESTAMP(3)"}) {
                assertThatThrownBy(() -> execute(connection,
                        "UPDATE sc_recharge_frequency_entry SET " + assignment + " WHERE id=301"))
                        .as(assignment).isInstanceOf(SQLException.class);
            }
            execute(connection, "UPDATE sc_recharge_frequency_entry SET status=10,active_operation_no='OP301',"
                    + "occupancy_generation=1,dispatch_phase=0 WHERE id=301");
            execute(connection, "UPDATE sc_recharge_frequency_entry SET dispatch_phase=10 WHERE id=301");
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE sc_recharge_frequency_entry SET dispatch_phase=99 WHERE id=301"))
                    .isInstanceOf(SQLException.class);
            execute(connection, "UPDATE sc_recharge_frequency_entry SET status=0,dispatch_phase=NULL WHERE id=301");
            execute(connection, "UPDATE sc_recharge_frequency_entry SET status=20,"
                    + "completed_at=CURRENT_TIMESTAMP(3) WHERE id=301");
        }
    }

    @Test
    void actualMapperUsesOptimisticVersionAndPreservesFieldsOnStaleWrite() {
        RechargeFrequencyQueueMapper mapper = context.getBean(RechargeFrequencyQueueMapper.class);
        LocalDateTime now = LocalDateTime.now();
        byte[] digest = new byte[32]; digest[0] = 4;
        RechargeFrequencyQueueDO queue = RechargeFrequencyQueueDO.builder().mobileDigest(digest)
                .mobileMasked("139****0000").platformSpuId(400L).createdAt(now).updatedAt(now).build();
        assertThat(mapper.insert(queue)).isEqualTo(1);
        RechargeFrequencyQueueDO current = mapper.selectByIdentity(digest, 400L);
        RechargeFrequencyQueueDO stale = mapper.selectById(queue.getId());
        assertThat(current.getVersion()).isZero();
        assertThat(current.getOccupiedAlertActive()).isFalse();
        assertThat(current.getBacklogAlertActive()).isFalse();
        current.setBlockedReason("MIGRATION_CONFLICT");
        assertThat(mapper.updateById(current)).isEqualTo(1);
        assertThat(current.getVersion()).isEqualTo(1L);
        stale.setMobileMasked("stale-value");
        assertThat(mapper.updateById(stale)).isZero();
        RechargeFrequencyQueueDO stored = mapper.selectById(queue.getId());
        assertThat(stored.getVersion()).isEqualTo(1L);
        assertThat(stored.getMobileMasked()).isEqualTo("139****0000");
        assertThat(stored.getBlockedReason()).isEqualTo("MIGRATION_CONFLICT");
    }

    @Test
    void entryMapperOptimisticVersionPreventsStaleOrderStateChanges() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertQueue(connection, 450, 450);
            insertEntry(connection, 451, 450, 4501, 1);
        }
        RechargeFrequencyEntryMapper mapper = context.getBean(RechargeFrequencyEntryMapper.class);
        RechargeFrequencyEntryDO first = mapper.selectById(451L);
        RechargeFrequencyEntryDO stale = mapper.selectById(451L);
        first.setActiveOperationNo("OP4501");
        assertThat(mapper.updateById(first)).isEqualTo(1);
        stale.setOrderNo("stale-order");
        assertThat(mapper.updateById(stale)).isZero();
        assertThat(mapper.selectById(451L).getOrderNo()).isEqualTo("ORDER4501");
        assertThat(mapper.selectById(451L).getVersion()).isEqualTo(1L);
    }

    @Test
    void appendAuditAllowsUnknownResourceAndEnforcesActionIdempotency() {
        RechargeFrequencyActionAuditMapper mapper = context.getBean(RechargeFrequencyActionAuditMapper.class);
        RechargeFrequencyActionAuditDO audit = RechargeFrequencyActionAuditDO.builder()
                .actionNo("ACTION_UNKNOWN").requestFingerprint(new byte[32]).actionType("RELEASE")
                .operatorUserId(1L).reason("核实不存在的队列").resultCode("NOT_FOUND")
                .traceId("TRACE_UNKNOWN").createdAt(LocalDateTime.now()).build();
        assertThat(mapper.insert(audit)).isEqualTo(1);
        RechargeFrequencyActionAuditDO saved = mapper.selectByActionNo("ACTION_UNKNOWN");
        assertThat(saved.getQueueId()).isNull();
        assertThat(saved.getRequestFingerprint()).hasSize(32);
        assertThat(saved.getResultCode()).isEqualTo("NOT_FOUND");
        audit.setId(null);
        assertThatThrownBy(() -> mapper.insert(audit)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        audit.setActionNo("ACTION_INVALID"); audit.setActionType("FORCE_RELEASE");
        assertThatThrownBy(() -> mapper.insert(audit)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        audit.setActionType("RELEASE"); audit.setReason("  ");
        assertThatThrownBy(() -> mapper.insert(audit)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void queueAndEntryForUpdateSerializeCompetingTransactions() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertQueue(connection, 500, 500);
            insertEntry(connection, 501, 500, 5001, 1);
        }
        RechargeFrequencyQueueMapper queues = context.getBean(RechargeFrequencyQueueMapper.class);
        RechargeFrequencyEntryMapper entries = context.getBean(RechargeFrequencyEntryMapper.class);
        assertRowLockBlocks(() -> queues.selectByIdForUpdate(500L));
        assertRowLockBlocks(() -> entries.selectByIdForUpdate(501L));
        assertRowLockBlocks(() -> entries.selectFirstWaitingForUpdate(500L,
                RechargeFrequencyEntryStatus.WAITING.getCode()));
    }

    private static void assertRowLockBlocks(Runnable lockingQuery) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            AtomicReference<Future<?>> pending = new AtomicReference<>();
            transaction().executeWithoutResult(status -> {
                lockingQuery.run();
                CountDownLatch started = new CountDownLatch(1);
                var competing = executor.submit(() -> transaction().executeWithoutResult(other -> {
                    started.countDown();
                    lockingQuery.run();
                }));
                pending.set(competing);
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> competing.get(250, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("行锁验证线程被中断", interrupted);
                }
            });
            pending.get().get(5, TimeUnit.SECONDS);
        }
    }

    /** 不存在控制行时依赖数据库唯一键决定创建者，不能依赖空查询的锁。 */
    @Test
    void concurrentCreationOfSameIdentityHasExactlyOneCommittedWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createCompetingQueue(600, ready, start));
            var second = executor.submit(() -> createCompetingQueue(601, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(5,TimeUnit.SECONDS), second.get(5,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1,1062);
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM sc_recharge_frequency_queue WHERE platform_spu_id=600"))
                    .isEqualTo(1);
        }
    }

    private static int createCompetingQueue(long id, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发创建启动超时");
            try {
                insertQueue(connection, id, 600);
                return 1;
            } catch (SQLException failure) {
                if (failure.getErrorCode() != 1062) throw failure;
                return failure.getErrorCode();
            }
        }
    }

    private static TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }

    private static void insertEntry(Connection connection, long id, long queueId, long orderId,
                                    long sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sc_recharge_frequency_entry
                    (id,queue_id,order_id,order_no,fulfillment_request_id,enqueue_sequence,buyer_id,
                     enqueued_at,created_at,updated_at)
                VALUES (?,?,?, ?,?, ?,1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                """)) {
            statement.setLong(1,id); statement.setLong(2,queueId); statement.setLong(3,orderId);
            statement.setString(4,"ORDER" + orderId); statement.setLong(5,orderId); statement.setLong(6,sequence);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.executeUpdate(); }
    }

    private static void insertQueue(Connection connection, long id, long spuId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sc_recharge_frequency_queue
                    (id,mobile_digest,mobile_masked,platform_spu_id,created_at,updated_at)
                VALUES (?, ?, '138****0000', ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """)) {
            statement.setLong(1, id);
            statement.setBytes(2, new byte[32]);
            statement.setLong(3, spuId);
            statement.executeUpdate();
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
