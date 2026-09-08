package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAuthNonceMapper;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NonceStore 真实 MySQL 持久化契约门禁：复合主键并发、命名事务回滚与 DELETE LIMIT 边界。
 *
 * <p>测试复用供应链独立数据源、真实 Liquibase 与 MyBatis MapperScan；每个场景使用唯一 appId 并在
 * finally 清理；Testcontainers 发布基线为 MySQL 8.4，外部数据库模式必须传入本测试类独占的
 * 空真实 MySQL 库并记录实际版本。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
class ProjectAuthNonceStoreIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static ConfigurableApplicationContext context;
    private static ProjectAuthNonceMapper nonceMapper;
    private static NonceStore nonceStore;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabaseAndApplication() throws SQLException {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        context = applicationContext();
        nonceMapper = context.getBean(ProjectAuthNonceMapper.class);
        nonceStore = context.getBean(NonceStore.class);
        DataSource dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).isNotBlank();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    @AfterAll
    static void stopDatabaseAndApplication() {
        if (context != null) {
            context.close();
        }
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void concurrentSameAppAndNonceAllowsExactlyOneInsert() throws Exception {
        String appId = uniqueAppId("concurrent");
        String nonce = "same-nonce";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> insertAfterBarrier(appId, nonce, ready, start));
            Future<Throwable> second = executor.submit(() -> insertAfterBarrier(appId, nonce, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> outcomes = Arrays.asList(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
            List<Throwable> failures = outcomes.stream().filter(Objects::nonNull).toList();
            assertThat(failures).singleElement().satisfies(failure -> {
                assertThat(failure).isInstanceOf(ServiceException.class);
                assertThat(((ServiceException) failure).getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
                assertThat(failure.getCause()).isInstanceOf(DuplicateKeyException.class);
            });
            assertThat(countRows(appId)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                deleteRows(appId);
            }
        }
    }

    @Test
    void mapperInsertParticipatesInNamedSupplyChainTransactionRollback() {
        String appId = uniqueAppId("rollback");
        PlatformTransactionManager transactionManager =
                context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                nonceStore.ensureUnique(appId, "rollback-nonce");
                assertThat(countRows(appId)).isEqualTo(1L);
                status.setRollbackOnly();
            });

            assertThat(countRows(appId)).isZero();
        } finally {
            deleteRows(appId);
        }
    }

    @Test
    void deleteExpiredBeforeAppliesMysqlLimitAndStrictCutoff() {
        String appId = uniqueAppId("cleanup");
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 24, 12, 0);
        try {
            insertRow(appId, "expired-1", cutoff.minusSeconds(2));
            insertRow(appId, "expired-2", cutoff.minusSeconds(1));
            insertRow(appId, "equal", cutoff);
            insertRow(appId, "future", cutoff.plusSeconds(1));

            assertThat(nonceMapper.deleteExpiredBefore(cutoff, 1)).isEqualTo(1);
            assertThat(countRows(appId)).isEqualTo(3L);
            assertThat(nonceMapper.deleteExpiredBefore(cutoff, 1)).isEqualTo(1);
            assertThat(remainingNonces(appId)).containsExactlyInAnyOrder("equal", "future");
        } finally {
            deleteRows(appId);
        }
    }

    private static Throwable insertAfterBarrier(String appId, String nonce, CountDownLatch ready,
                                                CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return new IllegalStateException("concurrent insert start barrier timed out");
        }
        try {
            nonceStore.ensureUnique(appId, nonce);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void insertRow(String appId, String nonce, LocalDateTime expireAt) {
        ProjectAuthNonceDO row = new ProjectAuthNonceDO();
        row.setAppId(appId);
        row.setNonce(nonce);
        row.setCreatedAt(expireAt.minusHours(1));
        row.setExpireAt(expireAt);
        assertThat(nonceMapper.insert(row)).isEqualTo(1);
    }

    private static long countRows(String appId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_project_auth_nonce WHERE app_id = ?", Long.class, appId);
    }

    private static List<String> remainingNonces(String appId) {
        return jdbc.queryForList(
                "SELECT nonce FROM sc_project_auth_nonce WHERE app_id = ? ORDER BY nonce",
                String.class, appId);
    }

    private static void deleteRows(String appId) {
        if (jdbc != null) {
            jdbc.update("DELETE FROM sc_project_auth_nonce WHERE app_id = ?", appId);
        }
    }

    private static String uniqueAppId(String scenario) {
        return "nonce-it-" + scenario + "-" + UUID.randomUUID();
    }

    private static ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
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
    @EnableConfigurationProperties(ProjectAppAuthProperties.class)
    @Import({SupplyChainDataSourceConfiguration.class, NonceStore.class})
    static class TestConfiguration {
    }
}
