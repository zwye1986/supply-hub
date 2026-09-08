package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.ClaimedCredential;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.IssuedCredential;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证到店购入口凭证的冻结快照、摘要持久化、项目隔离与并发 CAS 语义。
 *
 * <p>同一项目会员允许同时存在多枚凭证，但同一 token 的两个并发请求必须恰好只有一个
 * 进入 PROCESSING；两个项目即使共享同一 SaaS 租户，凭证也不能跨项目消费；
 * 入口 URL 外呼失败撤销后 token 进入 REVOKED 终态。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机}。</p>
 */
class InstoreEntryCredentialIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.test.jdbc-url");

    private static final Long BUYER_ID = 1001L;
    private static final Long PROJECT_P1 = 101L;
    private static final Long PROJECT_P2 = 102L;
    private static final Long PROFILE_VERSION_P1 = 901L;
    private static final Long PROFILE_VERSION_P2 = 902L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_A = "COMPANY_A";
    private static final String COMPANY_B = "COMPANY_B";

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
    void freezesSnapshotAndAllowsExactlyOneConcurrentClaim() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            InstoreEntryCredentialService service =
                    context.getBean(InstoreEntryCredentialService.class);
            JdbcTemplate jdbc = new JdbcTemplate(
                    context.getBean("supplyChainDataSource", DataSource.class));
            insertFixtures(jdbc);

            IssuedCredential first = service.issueHome(
                    BUYER_ID, PROJECT_P1, BUYER_USER_ID, CHANNEL_CODE,
                    memberRoute(PROJECT_P1, PROFILE_VERSION_P1), COMPANY_A);
            IssuedCredential second = service.issueHome(
                    BUYER_ID, PROJECT_P1, BUYER_USER_ID, CHANNEL_CODE,
                    memberRoute(PROJECT_P1, PROFILE_VERSION_P1), COMPANY_A);
            assertThat(first.token()).isNotEqualTo(second.token());

            ConcurrentResults<ClaimedCredential> claims = concurrent(2, () ->
                    service.claimForMemberLookup(PROJECT_P1, CHANNEL_CODE, first.token()));

            assertThat(claims.successes()).hasSize(1);
            assertThat(claims.failures()).hasSize(1);
            assertThat(claims.failures().getFirst()).isInstanceOf(ServiceException.class);
            assertThat(((ServiceException) claims.failures().getFirst()).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());

            ClaimedCredential winner = claims.successes().getFirst();
            assertThat(winner.projectId()).isEqualTo(PROJECT_P1);
            assertThat(winner.memberProfileVersionId()).isEqualTo(PROFILE_VERSION_P1);
            assertThat(winner.companyCode()).isEqualTo(COMPANY_A);
            assertThat(winner.buyerUserId()).isEqualTo(BUYER_USER_ID);
            service.markLookupSucceeded(winner.credentialId(), winner.processingVersion());

            assertThatThrownBy(() -> service.claimForMemberLookup(
                    PROJECT_P1, CHANNEL_CODE, first.token()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED.getCode());

            ClaimedCredential independent = service.claimForMemberLookup(
                    PROJECT_P1, CHANNEL_CODE, second.token());
            assertThat(independent).isNotNull();

            IssuedCredential otherProject = service.issueHome(
                    BUYER_ID, PROJECT_P2, BUYER_USER_ID, CHANNEL_CODE,
                    memberRoute(PROJECT_P2, PROFILE_VERSION_P2), COMPANY_B);
            assertThatThrownBy(() -> service.claimForMemberLookup(
                    PROJECT_P1, CHANNEL_CODE, otherProject.token()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
            assertThatThrownBy(() -> service.claimForMemberLookup(
                    PROJECT_P2, CHANNEL_CODE, first.token()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
            ClaimedCredential p2Claim = service.claimForMemberLookup(
                    PROJECT_P2, CHANNEL_CODE, otherProject.token());
            assertThat(p2Claim.memberProfileVersionId()).isEqualTo(PROFILE_VERSION_P2);
            assertThat(p2Claim.companyCode()).isEqualTo(COMPANY_B);

            IssuedCredential revoked = service.issueHome(
                    BUYER_ID, PROJECT_P1, BUYER_USER_ID, CHANNEL_CODE,
                    memberRoute(PROJECT_P1, PROFILE_VERSION_P1), COMPANY_A);
            service.revoke(revoked.credentialId(), revoked.credentialVersion(),
                    InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);
            assertThatThrownBy(() -> service.claimForMemberLookup(
                    PROJECT_P1, CHANNEL_CODE, revoked.token()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getCode())
                    .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED.getCode());
            Map<String, Object> revokedRow = jdbc.queryForMap(
                    "SELECT status, revoke_reason FROM sc_instore_entry_credential "
                            + "WHERE credential_no = ?", revoked.credentialNo());
            assertThat(((Number) revokedRow.get("status")).intValue()).isEqualTo(40);
            assertThat(revokedRow.get("revoke_reason"))
                    .isEqualTo(InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED.name());

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA=DATABASE() "
                            + "AND TABLE_NAME='sc_instore_entry_credential' "
                            + "AND COLUMN_NAME='token'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT OCTET_LENGTH(token_digest) FROM sc_instore_entry_credential "
                            + "WHERE credential_no=?", Integer.class, first.credentialNo()))
                    .isEqualTo(32);
        }
    }

    private static void insertFixtures(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, 'BUYER_MEMBER_ENTRY', '会员入口采购商', '会员入口', 'SAAS_MALL',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, BUYER_ID);
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, 'INSTORE_MEMBER_ENTRY_P1', '会员入口项目一', ?, 'INDEPENDENT',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_P1, BUYER_ID);
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, 'INSTORE_MEMBER_ENTRY_P2', '会员入口项目二', ?, 'INDEPENDENT',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_P2, BUYER_ID);
    }

    private static BuyerIntegrationRoute memberRoute(Long projectId, Long profileVersionId) {
        return new BuyerIntegrationRoute(
                projectId, profileVersionId, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                "AIFULI_MALL", "tenant-a", "HTTP", "adapter-rtmart", "v1", null);
    }

    /** 让所有线程在同一道闸门后同时发起操作，避免串行调度造成假并发。 */
    private static <T> ConcurrentResults<T> concurrent(
            int taskCount, Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(taskCount)) {
            List<Future<T>> futures = new ArrayList<>(taskCount);
            for (int index = 0; index < taskCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();

            List<T> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    successes.add(future.get());
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            return new ConcurrentResults<>(successes, failures);
        }
    }

    private ConfigurableApplicationContext applicationContext() {
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
                ? System.getProperty("supply-chain.test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "")
                : MYSQL.getPassword();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            InstoreChannelUserBindingResolver.class, InstoreEntryCredentialService.class})
    static class TestConfiguration {
    }

    private record ConcurrentResults<T>(List<T> successes, List<Throwable> failures) {
    }
}
