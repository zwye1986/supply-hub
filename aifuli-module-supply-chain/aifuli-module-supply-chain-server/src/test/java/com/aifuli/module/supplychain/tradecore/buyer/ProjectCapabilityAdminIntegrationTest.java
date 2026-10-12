package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 项目能力管理真实数据库门禁：不通过 SQL 授权，使用生产事务服务并验证状态和追加审计。
 * 设计见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#5-验证与交付。
 */
class ProjectCapabilityAdminIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ProjectCapabilityAdminService service;

    @BeforeAll
    static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
                "--spring.config.location=optional:classpath:/no-application.yaml",
                "--spring.cloud.nacos.config.import-check.enabled=false",
                "--spring.cloud.nacos.config.enabled=false", "--spring.cloud.nacos.discovery.enabled=false",
                "--aifuli.supply-chain.enabled=true", "--spring.main.banner-mode=off",
                "--aifuli.supply-chain.datasource.url=" + MYSQL.getJdbcUrl(),
                "--aifuli.supply-chain.datasource.username=" + MYSQL.getUsername(),
                "--aifuli.supply-chain.datasource.password=" + MYSQL.getPassword());
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        service = context.getBean(ProjectCapabilityAdminService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM sc_project_capability_audit");
        jdbc.update("DELETE FROM sc_project_capability");
        jdbc.update("DELETE FROM sc_buyer_project");
        BuyerProjectDO project = BuyerProjectDO.builder().projectCode("PRJ_CAP").projectName("标准商品授权测试")
                .buyerId(42L).catalogMode("MANAGED").status((short) 10).version(0L)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        context.getBean(BuyerProjectMapper.class).insert(project);
    }

    @Test
    void enableDisableAndReenableKeepIdentityAndAppendAuditsOnlyOnChanges() {
        assertThat(service.list("PRJ_CAP")).extracting("capabilityCode")
                .containsExactly("STANDARD_PRODUCT", "O2O", "INSTORE");
        assertThat(service.list("PRJ_CAP")).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("NOT_ENABLED");
            assertThat(row.version()).isNull();
        });
        assertThat(service.enable("PRJ_CAP", null, 71L).changed()).isTrue();
        Long id = jdbc.queryForObject("SELECT id FROM sc_project_capability", Long.class);
        LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM sc_project_capability", LocalDateTime.class);
        assertThat(service.enable("PRJ_CAP", 0L, 72L).changed()).isFalse();
        assertThat(service.disable("PRJ_CAP", 0L, true, "  暂停交易  ", 72L).changed()).isTrue();
        assertThat(service.list("PRJ_CAP").getFirst()).satisfies(row -> {
            assertThat(row.status()).isEqualTo("DISABLED");
            assertThat(row.version()).isEqualTo(1L);
            assertThat(row.lastOperatorId()).isEqualTo(72L);
            assertThat(row.lastChangedAt()).isNotNull();
        });
        assertThat(service.disable("PRJ_CAP", 1L, true, "重复关闭", 73L).changed()).isFalse();
        assertThat(service.enable("PRJ_CAP", 1L, 74L).changed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT id FROM sc_project_capability", Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT created_at FROM sc_project_capability", LocalDateTime.class)).isEqualTo(created);
        assertThat(jdbc.queryForList("SELECT operator_id FROM sc_project_capability_audit ORDER BY id", Long.class))
                .containsExactly(71L, 72L, 74L);
        assertThat(jdbc.queryForObject("SELECT reason FROM sc_project_capability_audit WHERE after_status=20", String.class))
                .isEqualTo("暂停交易");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_project_application", Integer.class)).isZero();
    }

    @Test
    void staleVersionStateAndConfirmationCannotChangeAuthorization() {
        assertThat(service.disable("PRJ_CAP", null, true, "没有开通过", 71L).changed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_project_capability", Integer.class)).isZero();
        assertThatThrownBy(() -> service.disable("PRJ_CAP", null, false, "原因", 71L))
                .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", PROJECT_CAPABILITY_CONFIRMATION_REQUIRED.getCode());
        assertThatThrownBy(() -> service.disable("PRJ_CAP", null, true, " ", 71L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.disable("PRJ_CAP", null, true, "长".repeat(257), 71L)).isInstanceOf(ServiceException.class);
        service.enable("PRJ_CAP", null, 71L);
        assertThatThrownBy(() -> service.disable("PRJ_CAP", null, true, "过期页面", 71L))
                .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", PROJECT_CAPABILITY_VERSION_CONFLICT.getCode());
        jdbc.update("UPDATE sc_buyer_project SET status=20");
        service.disable("PRJ_CAP", 0L, true, "暂停项目仍可关闭", 71L);
        assertThatThrownBy(() -> service.enable("PRJ_CAP", 1L, 71L))
                .isInstanceOf(ServiceException.class).hasFieldOrPropertyWithValue("code", PROJECT_STATUS_INVALID.getCode());
        assertThat(service.list("PRJ_CAP").getFirst().status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> service.list("MISSING")).isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", PROJECT_NOT_FOUND.getCode());
    }

    @Test
    void rollbackDoesNotLeaveAuthorizationOrAudit() {
        var tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            service.enable("PRJ_CAP", null, 71L);
            status.setRollbackOnly();
        });
        assertThat(service.list("PRJ_CAP").getFirst().status()).isEqualTo("NOT_ENABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_project_capability_audit", Integer.class)).isZero();
    }

    @Test
    void concurrentFirstEnableCreatesOneRowAndOneAuditAndRejectsStaleWriter() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> write = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                service.enable("PRJ_CAP", null, 71L);
                return "ENABLED";
            } catch (ServiceException failure) {
                assertThat(failure.getCode()).isEqualTo(PROJECT_CAPABILITY_VERSION_CONFLICT.getCode());
                return "CONFLICT";
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(write);
            var b = pool.submit(write);
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ENABLED", "CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_project_capability", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_project_capability_audit", Integer.class)).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, ProjectCapabilityAdminService.class})
    static class Config { }
}
