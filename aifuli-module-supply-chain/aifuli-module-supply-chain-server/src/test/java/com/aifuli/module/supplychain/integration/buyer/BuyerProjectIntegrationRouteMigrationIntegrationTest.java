package com.aifuli.module.supplychain.integration.buyer;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证项目业务身份与 SaaS 外部租户路由的数据库边界。
 *
 * <p>业务事实按 projectId 隔离，多个项目可以路由到同一 externalTenantId；
 * MEMBER_PROFILE/CASHIER 能力则必须显式发布完整的 SaaS 路由坐标。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
class BuyerProjectIntegrationRouteMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_project_route")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.project-route-test.jdbc-url");

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
    void isolatesProjectsWhileAllowingSharedExternalTenantRoutes() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            DataSource dataSource = context.getBean("supplyChainDataSource", DataSource.class);
            BuyerProjectMapper projectMapper = context.getBean(BuyerProjectMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertThat(tableNames(jdbc)).contains(
                    "sc_buyer_project", "sc_project_capability",
                    "sc_project_application", "sc_project_app_credential_version");
            assertThat(columnNames(jdbc, "sc_buyer_integration_profile"))
                    .contains("project_id");
            assertThat(columnNames(jdbc, "sc_buyer_integration_capability"))
                    .contains("source_system", "external_tenant_id");

            insertBuyer(jdbc);
            insertProject(projectMapper, 101L, "PROJECT_A");
            insertProject(projectMapper, 102L, "PROJECT_B");
            assertThat(projectMapper.selectByProjectCode("PROJECT_A"))
                    .extracting(BuyerProjectDO::getId, BuyerProjectDO::getBuyerId,
                            BuyerProjectDO::getStatus)
                    .containsExactly(101L, 1L, BuyerProjectStatus.ACTIVE.getCode());
            insertPublishedCashierRoute(jdbc, 801L, 901L, 101L, "PROFILE_A");
            insertPublishedCashierRoute(jdbc, 802L, 902L, 102L, "PROFILE_B");

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM sc_buyer_integration_capability
                    WHERE capability_code = 'CASHIER'
                      AND source_system = 'AIFULI_MALL'
                      AND external_tenant_id = 'tenant-shared'
                    """, Long.class)).isEqualTo(2L);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO sc_buyer_integration_capability(
                        profile_version_id, capability_code, transport_mode,
                        adapter_code, contract_version, source_system, external_tenant_id,
                        http_base_url, credential_set_id, connect_timeout_ms,
                        read_timeout_ms, total_timeout_ms, max_response_bytes,
                        bulkhead_concurrency, rate_limit_per_second,
                        circuit_breaker_policy, retry_policy_code, status,
                        created_at)
                    VALUES (901, 'MEMBER_PROFILE', 'RPC', 'SAAS_MEMBER_RPC', 'v1',
                            NULL, NULL, NULL, NULL, 500, 1500, 2000, 65536,
                            20, 100, 'DEFAULT', 'NONE', 10, UTC_TIMESTAMP(3))
                    """))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);

            assertThatThrownBy(() -> insertProfile(jdbc, 803L, 101L, "PROFILE_A_DUP"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT TABLE_NAME
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                """, String.class);
    }

    private static List<String> columnNames(JdbcTemplate jdbc, String tableName) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, String.class, tableName);
    }

    private static void insertBuyer(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (1, 'BUYER_PROJECT_ROUTE', '项目路由采购商', '路由采购商', 'SAAS_MALL',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
    }

    private static void insertProject(
            BuyerProjectMapper projectMapper, long projectId, String projectCode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(projectMapper.insert(BuyerProjectDO.builder()
                .id(projectId)
                .projectCode(projectCode)
                .projectName(projectCode)
                .buyerId(1L)
                .catalogMode("INDEPENDENT")
                .status(BuyerProjectStatus.ACTIVE.getCode())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build())).isEqualTo(1);
    }

    private static void insertPublishedCashierRoute(
            JdbcTemplate jdbc, long profileId, long profileVersionId,
            long projectId, String profileNo) {
        insertProfile(jdbc, profileId, projectId, profileNo);
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile_version(
                    id, profile_id, version_no, status, effective_at,
                    published_at, change_reason, content_digest, current_flag,
                    version, created_at, updated_at)
                VALUES (?, ?, 1, 10, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),
                        '项目收银台路由首发', ?, 1, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, profileVersionId, profileId, new byte[32]);
        jdbc.update("""
                UPDATE sc_buyer_integration_profile
                SET current_version_id = ?, updated_at = UTC_TIMESTAMP(3)
                WHERE id = ?
                """, profileVersionId, profileId);
        jdbc.update("""
                INSERT INTO sc_buyer_integration_capability(
                    profile_version_id, capability_code, transport_mode,
                    adapter_code, contract_version, source_system, external_tenant_id,
                    http_base_url, credential_set_id, connect_timeout_ms,
                    read_timeout_ms, total_timeout_ms, max_response_bytes,
                    bulkhead_concurrency, rate_limit_per_second,
                    circuit_breaker_policy, retry_policy_code, status,
                    created_at)
                VALUES (?, 'CASHIER', 'RPC', 'SAAS_CASHIER_RPC', 'v1',
                        'AIFULI_MALL', 'tenant-shared', NULL, NULL,
                        500, 1500, 2000, 65536, 20, 100,
                        'DEFAULT', 'QUERY_SAME_KEY', 10, UTC_TIMESTAMP(3))
                """, profileVersionId);
    }

    private static void insertProfile(
            JdbcTemplate jdbc, long profileId, long projectId, String profileNo) {
        jdbc.update("""
                INSERT INTO sc_buyer_integration_profile(
                    id, profile_no, buyer_id, project_id, profile_name,
                    current_version_id, status, version, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, NULL, 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, profileId, profileNo, projectId, profileNo);
    }

    private static ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(SupplyChainDataSourceConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.project-route-test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.project-route-test.jdbc-password", "")
                : MYSQL.getPassword();
    }
}
