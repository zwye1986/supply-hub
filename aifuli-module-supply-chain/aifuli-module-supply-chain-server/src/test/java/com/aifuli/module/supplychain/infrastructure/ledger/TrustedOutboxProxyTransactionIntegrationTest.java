package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.lang.invoke.MethodHandles;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * O-S01/O-S07真实REQUIRED代理技术夹具：只反射进入Repository实际私有底座，不复制追加算法。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md
 * #2026-10-06-o-s01o-s07有界代理检查点实现细化非验收。
 * 技术身份仅在src/test；领域表、源、槽及CAS共用生产manager/MyBatis/DataSource资源键。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrustedOutboxProxyTransactionIntegrationTest {
    private static final String FIXTURE_TYPE = "R3_02O_TECH_FIXTURE";
    private static final String FIXTURE_EVENT = "TECH_APPEND";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_proxy_transaction")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Worker worker;
    private static SqlProbe probe;

    @BeforeAll
    static void start() {
        MYSQL.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.datasource.connect-timeout-millis", 1500);
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);
        context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new));
        dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sc_r3_02o_domain_fixture (command_key VARCHAR(64) PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
        probe = context.getBean(SqlProbe.class);
        context.getBean("supplyChainSqlSessionFactory", SqlSessionFactory.class).getConfiguration().addInterceptor(probe);
        worker = context.getBean(Worker.class);
        assertThat(AopUtils.isAopProxy(worker)).as("必须调用真实Spring事务代理").isTrue();
        assertThat(context.getBean("supplyChainSqlSessionFactory", SqlSessionFactory.class)
                .getConfiguration().getEnvironment().getDataSource()).isSameAs(dataSource);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void requiredProxyCommitsDomainSourceSlotAndReturnsPersistentEventId() throws Exception {
        String eventId = worker.append("first");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        assertReleased();
        assertThat(probe.calls.stream().filter(c -> c.id.endsWith("OutboxEventMapper.insert"))).hasSize(1)
                .allSatisfy(c -> assertThat(c.result).isEqualTo(1));
        assertThat(probe.calls.stream().filter(c -> c.id.endsWith("OutboxEventMapper.selectList") && c.sql.contains("FOR UPDATE"))).hasSize(1);
        assertThat(probe.calls.stream().filter(c -> c.id.endsWith("OutboxAppendIdentityMapper.bindEventCas"))).hasSize(1)
                .allSatisfy(c -> assertThat(c.result).isEqualTo(1));
        assertThat(probe.calls).hasSize(5).allSatisfy(c -> assertThat(c.connection).isSameAs(worker.observed().domainConnection));
        try (Connection observer = independent()) {
            assertThat(observer).isNotSameAs(worker.observed().domainConnection);
            JdbcTemplate observed = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(observer, true));
            assertThat(observed.queryForObject("SELECT COUNT(*) FROM sc_r3_02o_domain_fixture WHERE command_key='first'", Integer.class)).isEqualTo(1);
            assertThat(observed.queryForObject("SELECT event_id FROM sc_outbox_event WHERE aggregate_type=? AND aggregate_id='first'", String.class, FIXTURE_TYPE)).isEqualTo(eventId);
            assertThat(observed.queryForObject("SELECT event_id FROM sc_outbox_append_identity WHERE aggregate_type=? AND aggregate_id='first' AND binding_status=20", String.class, FIXTURE_TYPE)).isEqualTo(eventId);
        }
    }

    @Test @Order(2)
    void proxyLocalRollbackOnlyIsRejectedBeforeLedgerOrSourceSql() throws Exception {
        assertThatThrownBy(() -> worker.localRollbackOnly("local-rollback"))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("已标记回滚");
        assertThat(worker.observed().localRollbackOnlyObserved).isTrue();
        assertThat(worker.observed().holderRollbackOnlyObserved).as("代理localRollbackOnly不能误写成holder已回滚").isFalse();
        assertThat(probe.calls).isEmpty();
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertReleased();
        assertNoOrphans("local-rollback");
    }

    @Test @Order(3)
    void otherActiveManagerCannotUsePreexistingSupplyChainResource() throws Exception {
        WrongWorker other = context.getBean(WrongWorker.class);
        assertThat(AopUtils.isAopProxy(other)).isTrue();
        assertThatThrownBy(() -> worker.crossManager("cross-manager", other))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("事务资源归属");
        assertThat(probe.calls).isEmpty();
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertReleased();
        assertNoOrphans("cross-manager");
    }

    @Test @Order(4)
    void unverifiedMarkBoundCannotCommitRealReservedSlot() throws Exception {
        assertThatThrownBy(() -> worker.unverifiedMark("unverified"))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("未验证");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertReleased();
        assertNoOrphans("unverified");
    }

    @Test @Order(5)
    void absentTransactionWrongManagerReadOnlyHolderRollbackAndMissingSyncAreRejected() throws Exception {
        assertThatThrownBy(() -> worker.noTransaction("absent")).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("必须在有效写事务");
        assertThat(probe.calls).isEmpty(); assertReleased(); assertNoOrphans("absent");
        assertThatThrownBy(() -> context.getBean(WrongWorker.class).append("other-only"))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("未绑定供应链事务连接资源");
        assertThat(probe.calls).isEmpty(); assertReleased(); assertNoOrphans("other-only");
        assertThatThrownBy(() -> worker.readOnly("readonly")).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("只读事务");
        assertThat(probe.calls).isEmpty(); assertReleased(); assertNoOrphans("readonly");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(() -> worker.holderRollback("holder")).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("已标记回滚");
        assertThat(probe.calls).isEmpty(); assertReleased(); assertNoOrphans("holder");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(() -> worker.missingSynchronization("no-sync")).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("活动事务同步");
        assertThat(probe.calls).isEmpty(); assertReleased(); assertNoOrphans("no-sync");
    }

    @Test @Order(6)
    void reservedAndCaughtUnverifiedMarkFailBeforeCommitThenSameThreadCanReuse() throws Exception {
        for (boolean mark : new boolean[]{false,true}) {
            String key = mark ? "caught-unverified" : "reserved";
            assertThatThrownBy(() -> worker.residual(key,mark)).isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("事务提交前守卫拒绝残留RESERVED");
            assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertReleased(); assertNoOrphans(key);
            String event = worker.append(key);
            assertThat(event).startsWith("EVT_");
            assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            assertReleased();
        }
    }

    @Test @Order(7)
    void unfinishedReentryRollsBackDomainAndSlotThenNewTransactionSucceeds() throws Exception {
        assertThatThrownBy(() -> worker.reentry("reentry")).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("拒绝重入未完成账本槽");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(probe.calls).noneMatch(c -> c.id.contains("OutboxEventMapper"));
        assertReleased(); assertNoOrphans("reentry");
        assertThat(worker.append("reentry")).startsWith("EVT_"); assertReleased();
    }

    @Test @Order(8)
    void realSourceSqlAndBindingCheckFailuresEscapeProxyWithFullRollback() throws Exception {
        String[][] faults = {
                {"r3_source_failure", "BEFORE INSERT ON sc_outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,MESSAGE_TEXT='R3_02O_SOURCE_SQL_FAULT'", "source-failure", "1644", "45000"},
                {"r3_binding_failure", "BEFORE UPDATE ON sc_outbox_append_identity FOR EACH ROW SET NEW.binding_status=99", "binding-failure", "3819", "HY000"}
        };
        for (String[] fault : faults) {
            jdbc.execute("CREATE TRIGGER " + fault[0] + " " + fault[1]);
            try {
                Throwable failure = catchThrowable(() -> worker.append(fault[2]));
                assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
                java.sql.SQLException cause = sqlCause(failure);
                assertThat(cause.getErrorCode()).isEqualTo(Integer.parseInt(fault[3]));
                assertThat(cause.getSQLState()).isEqualTo(fault[4]);
                assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
                assertReleased(); assertNoOrphans(fault[2]);
                if (fault[2].equals("binding-failure")) {
                    assertThat(probe.calls).anyMatch(c -> c.id.endsWith("OutboxEventMapper.insert") && c.result.equals(1));
                    assertThat(probe.calls).anyMatch(c -> c.id.endsWith("OutboxEventMapper.selectList") && c.sql.contains("FOR UPDATE"));
                }
            } finally { jdbc.execute("DROP TRIGGER " + fault[0]); }
            assertThat(worker.append(fault[2])).startsWith("EVT_"); assertReleased();
        }
    }

    @Test @Order(9)
    void requiredNestedProxySharesHolderAndOnlyOuterCommitPublishesResult() throws Exception {
        String event = worker.requiredNested("required-nested",worker);
        assertThat(event).startsWith("EVT_");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(probe.calls).hasSize(5).allSatisfy(c -> assertThat(c.connection).isSameAs(worker.observed().domainConnection));
        assertReleased();
    }

    @Test @Order(10)
    void suspendedGuardCannotPolluteAnotherTransactionOnSameThread() throws Exception {
        assertThatThrownBy(() -> worker.suspendGuard("suspended",worker))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("事务提交前守卫拒绝残留RESERVED");
        assertThat(worker.observed().completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertReleased(); assertNoOrphans("suspended-outer");
        try (Connection observer = independent()) {
            JdbcTemplate read = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(observer,true));
            assertThat(read.queryForObject("SELECT COUNT(*) FROM sc_r3_02o_domain_fixture WHERE command_key='suspended'",Integer.class)).isEqualTo(1);
            assertThat(read.queryForObject("SELECT event_id FROM sc_outbox_event WHERE aggregate_type=? AND aggregate_id='suspended'",String.class,FIXTURE_TYPE))
                    .isEqualTo(worker.innerEvent());
        }
    }

    @Test @Order(11)
    void ancestorLocalRollbackOnlyMustBeRejectedAfterJoiningAnotherRequiredProxy() throws Exception {
        assertThatThrownBy(() -> worker.ancestorLocalRollbackOnly("ancestor-local", worker))
                .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("已标记回滚");
        assertThat(worker.observed().ancestorLocalRollbackOnlyObserved).isTrue();
        assertThat(worker.observed().holderRollbackOnlyObserved).as("外层代理localRollbackOnly在进入内层前holder仍非回滚").isFalse();
        assertThat(probe.calls).isEmpty();
        assertReleased();
        assertNoOrphans("ancestor-local");
    }

    @Test @Order(12)
    void publicApiProgrammaticAncestorIsObservedBeforeLedgerDal() throws Exception {
        var tm = context.getBean("supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class);
        var template = new org.springframework.transaction.support.TransactionTemplate(tm);
        assertThatThrownBy(() -> template.execute(status -> {
            status.setRollbackOnly();
            return worker.append("programmatic-ancestor");
        })).isInstanceOf(SystemIntegrityException.class).hasMessageContaining("已标记回滚");
        assertThat(probe.calls).isEmpty();
        assertReleased();
        assertNoOrphans("programmatic-ancestor");
    }

    @Test @Order(13)
    void markedSuspendedOuterDoesNotPoisonRequiresNewAndRestoresOuterScope() throws Exception {
        var tm = context.getBean("supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class);
        var template = new org.springframework.transaction.support.TransactionTemplate(tm);
        template.execute(status -> {
            status.setRollbackOnly();
            assertThat(worker.isolated("public-api-isolated")).startsWith("EVT_");
            assertThatThrownBy(() -> worker.append("restored-outer"))
                    .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("已标记回滚");
            return null;
        });
        assertReleased();
        assertNoOrphans("restored-outer");
        try (Connection observer = independent()) {
            JdbcTemplate observed = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(observer, true));
            assertThat(observed.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id='public-api-isolated'", Integer.class)).isEqualTo(1);
        }
    }

    @Test @Order(14)
    void completedNestedLocalRollbackDoesNotPromoteToGlobalAndThreadReuses() throws Exception {
        var tm = context.getBean("supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class);
        var outer = new org.springframework.transaction.support.TransactionTemplate(tm);
        outer.execute(status -> {
            var nested = new org.springframework.transaction.support.TransactionTemplate(tm);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            nested.execute(inner -> {
                inner.setRollbackOnly();
                return null;
            });
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource)).isRollbackOnly()).isFalse();
            assertThat(worker.append("after-savepoint")).startsWith("EVT_");
            return null;
        });
        assertReleased();
        assertThat(worker.append("public-api-thread-reuse")).startsWith("EVT_");
        assertReleased();
    }

    private static java.sql.SQLException sqlCause(Throwable failure) {
        for (Throwable current=failure; current!=null; current=current.getCause()) {
            if (current instanceof java.sql.SQLException sql) return sql;
        }
        throw new AssertionError("真实SQL cause缺失",failure);
    }

    private static void assertReleased() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    private static void assertNoOrphans(String key) throws Exception {
        try (Connection observer = independent()) {
            JdbcTemplate observed = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(observer, true));
            assertThat(observed.queryForObject("SELECT COUNT(*) FROM sc_r3_02o_domain_fixture WHERE command_key=?", Integer.class, key)).isZero();
            assertThat(observed.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type=? AND aggregate_id=?", Integer.class, FIXTURE_TYPE, key)).isZero();
            assertThat(observed.queryForObject("SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_type=? AND aggregate_id=?", Integer.class, FIXTURE_TYPE, key)).isZero();
        }
    }

    private static Connection independent() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 仅测试读取实际Repository底座字段；业务命令不注入eventId/slotId/nonce/证明。 */
    private static Object repositoryCore(OutboxEventRepository repository) {
        try {
            Class<?> coreType = Class.forName(OutboxEventRepository.class.getName() + "$TrustedOutboxAppendCore");
            return MethodHandles.privateLookupIn(OutboxEventRepository.class, MethodHandles.lookup())
                    .findGetter(OutboxEventRepository.class, "trustedCore", coreType).invoke(repository);
        } catch (Throwable failure) {
            throw new IllegalStateException("测试私有底座访问失败", failure);
        }
    }

    /** 仅测试访问private guard/mark边界，不提供映射证明，不复制guard算法。 */
    private static void guardOperation(Object core, String method, String key) {
        try {
            MethodHandles.privateLookupIn(OutboxEventRepository.class, MethodHandles.lookup())
                    .findVirtual(core.getClass(), method, java.lang.invoke.MethodType.methodType(void.class, String.class))
                    .invoke(core, key);
        } catch (Throwable failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("测试guard边界访问失败", failure);
        }
    }

    private static String guardKey(String key) { return FIXTURE_TYPE + "|" + key + "|1|" + FIXTURE_EVENT; }

    public static class Worker {
        final Object core;
        final DataSource source;
        final JdbcTemplate domain;
        final SqlProbe sql;
        final List<Integer> completions = new ArrayList<>();
        Connection domainConnection;
        boolean localRollbackOnlyObserved;
        boolean holderRollbackOnlyObserved;
        boolean ancestorLocalRollbackOnlyObserved;
        String innerEventId;

        Worker(OutboxEventRepository repository, DataSource source, SqlProbe sql) {
            this.core = repositoryCore(repository);
            this.source = source;
            this.domain = new JdbcTemplate(source);
            this.sql = sql;
        }

        public Observation observed() {
            return new Observation(completions, domainConnection, localRollbackOnlyObserved, holderRollbackOnlyObserved, ancestorLocalRollbackOnlyObserved);
        }

        void observe() {
            sql.calls.clear();
            completions.clear();
            domainConnection = ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completions.add(status); }
            });
        }

        void begin(String key) {
            observe();
            domain.update("INSERT INTO sc_r3_02o_domain_fixture(command_key,value) VALUES (?,1)", key);
        }

        String appendCore(String key) {
            return TrustedOutboxAppendCoreBridge.append(core, TrustedOutboxAppendCoreBridge.newCommand(
                    FIXTURE_TYPE, key, 1L, FIXTURE_EVENT, "LOCAL", "tech-handler", 1, "LEGACY_JSON_V1",
                    "{\"value\":1}", "trace-" + key, null, null, LocalDateTime.now()));
        }

        @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.REQUIRED)
        public String append(String key) {
            begin(key);
            return appendCore(key);
        }

        public String noTransaction(String key) { sql.calls.clear(); return appendCore(key); }
        public String innerEvent() { return innerEventId; }

        @Transactional(transactionManager="supplyChainTransactionManager",readOnly=true)
        public String readOnly(String key) { observe(); return appendCore(key); }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public String holderRollback(String key) {
            begin(key);
            ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
            return appendCore(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public String missingSynchronization(String key) {
            begin(key);
            var saved = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return appendCore(key);
            } finally {
                // 仅恢复manager实际清理所需的原同步，不伪造活动事务状态。
                TransactionSynchronizationManager.initSynchronization();
                saved.forEach(TransactionSynchronizationManager::registerSynchronization);
            }
        }

        void reserveSql(String key) {
            domain.update("INSERT INTO sc_outbox_append_identity(aggregate_type,aggregate_id,aggregate_version,event_type,binding_status,reservation_nonce,target_type,target_name,payload_version,comparison_protocol,payload_json,created_at) "
                    + "VALUES (?,?,1,?,10,RANDOM_BYTES(16),'LOCAL','tech-handler',1,'LEGACY_JSON_V1','{}',CURRENT_TIMESTAMP(3))",FIXTURE_TYPE,key,FIXTURE_EVENT);
            guardOperation(core,"guardReservation",guardKey(key));
        }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public void residual(String key,boolean mark) {
            begin(key); reserveSql(key);
            if (mark) assertThatThrownBy(() -> guardOperation(core,"markBound",guardKey(key)))
                    .isInstanceOf(SystemIntegrityException.class).hasMessageContaining("未验证");
        }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public String reentry(String key) {
            begin(key); guardOperation(core,"guardReservation",guardKey(key)); return appendCore(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public String requiredNested(String key,Worker proxy) {
            begin(key); return proxy.joined(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager",propagation=Propagation.REQUIRED)
        public String joined(String key) {
            assertThat(((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).getConnection()).isSameAs(domainConnection);
            return appendCore(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager")
        public void suspendGuard(String key,Worker proxy) {
            begin(key+"-outer"); guardOperation(core,"guardReservation",guardKey(key));
            innerEventId=proxy.isolated(key);
        }

        // 仅测试挂起边界负控制；生产底座绝不创建REQUIRES_NEW或retry。
        @Transactional(transactionManager="supplyChainTransactionManager",propagation=Propagation.REQUIRES_NEW)
        public String isolated(String key) {
            domain.update("INSERT INTO sc_r3_02o_domain_fixture(command_key,value) VALUES (?,1)",key);
            return appendCore(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.REQUIRED)
        public void unverifiedMark(String key) {
            begin(key);
            domain.update("INSERT INTO sc_outbox_append_identity(aggregate_type,aggregate_id,aggregate_version,event_type,binding_status,reservation_nonce,target_type,target_name,payload_version,comparison_protocol,payload_json,created_at) "
                    + "VALUES (?,?,1,?,10,RANDOM_BYTES(16),'LOCAL','tech-handler',1,'LEGACY_JSON_V1','{}',CURRENT_TIMESTAMP(3))", FIXTURE_TYPE,key,FIXTURE_EVENT);
            guardOperation(core,"guardReservation",guardKey(key));
            guardOperation(core,"markBound",guardKey(key));
        }

        @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.REQUIRED)
        public String crossManager(String key, WrongWorker other) {
            begin(key);
            return other.append(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.REQUIRED)
        public String localRollbackOnly(String key) {
            begin(key);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            localRollbackOnlyObserved = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
            holderRollbackOnlyObserved = ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly();
            return appendCore(key);
        }

        @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.REQUIRED)
        public String ancestorLocalRollbackOnly(String key, Worker proxy) {
            begin(key);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            ancestorLocalRollbackOnlyObserved = TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
            holderRollbackOnlyObserved = ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).isRollbackOnly();
            return proxy.joined(key);
        }
    }

    /** 错manager持独立物理连接，外层已有供应链holder；不得另开供应链事务取巧。 */
    public static class WrongWorker {
        final Object core;
        final DataSource source;
        WrongWorker(OutboxEventRepository repository, DataSource source) {
            this.core = repositoryCore(repository);
            this.source = source;
        }
        @Transactional(transactionManager="otherTransactionManager", propagation=Propagation.REQUIRED)
        public String append(String key) {
            assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
            new JdbcTemplate(source).queryForObject("SELECT CONNECTION_ID()", Long.class);
            return TrustedOutboxAppendCoreBridge.append(core, TrustedOutboxAppendCoreBridge.newCommand(
                    FIXTURE_TYPE, key, 1L, FIXTURE_EVENT, "LOCAL", "tech-handler", 1, "LEGACY_JSON_V1",
                    "{\"value\":1}", "trace-" + key, null, null, LocalDateTime.now()));
        }
    }

    record Observation(List<Integer> completions, Connection domainConnection,
                       boolean localRollbackOnlyObserved, boolean holderRollbackOnlyObserved,
                       boolean ancestorLocalRollbackOnlyObserved) {}

    record Call(String id, Object result, Connection connection, String sql) {}

    /** Executor真实SQL执行后取结果和事务连接，不替换结果、参数或源读回。 */
    @Intercepts({@Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}),
            @Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    public static class SqlProbe implements Interceptor {
        final List<Call> calls = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Connection connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            calls.add(new Call(statement.getId(), result, connection, statement.getBoundSql(invocation.getArgs()[1]).getSql()));
            return result;
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Import({SupplyChainDataSourceConfiguration.class, BusinessNoGenerator.class, UlidGenerator.class, OutboxEventRepository.class})
    static class Fixture {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SqlProbe sqlProbe() { return new SqlProbe(); }
        @Bean("otherDataSource") DataSource otherDataSource() {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }
        @Bean("otherTransactionManager") org.springframework.jdbc.support.JdbcTransactionManager otherTransactionManager(
                @org.springframework.beans.factory.annotation.Qualifier("otherDataSource") DataSource source) {
            return new org.springframework.jdbc.support.JdbcTransactionManager(source);
        }
        @Bean WrongWorker wrongWorker(OutboxEventRepository repository,
                @org.springframework.beans.factory.annotation.Qualifier("otherDataSource") DataSource source) {
            return new WrongWorker(repository, source);
        }
        @Bean Worker worker(OutboxEventRepository repository,
                            @org.springframework.beans.factory.annotation.Qualifier("supplyChainDataSource") DataSource source,
                            SqlProbe sql) { return new Worker(repository, source, sql); }
    }
}
