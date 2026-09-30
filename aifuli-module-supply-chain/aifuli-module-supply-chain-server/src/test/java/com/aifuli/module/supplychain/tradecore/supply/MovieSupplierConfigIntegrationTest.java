package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.api.movie.MovieSupplierChannel;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.security.EnvironmentCredentialProvider;
import com.aifuli.module.supplychain.tradecore.supply.application.MovieSupplierConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 真实MySQL与现有Liquibase四表，验证启用过滤、当前版本选择及跨连接器引用拒绝。 */
class MovieSupplierConfigIntegrationTest {

    @Test
    void readsOnlyPublishedCurrentVersionFromExistingSchema() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("movie_config_test").withUsername("test").withPassword("test")) {
            mysql.start();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfiguration.class)
                    .web(WebApplicationType.NONE).run(
                            "--spring.config.name=movie-config-integration",
                            "--aifuli.supply-chain.enabled=true",
                            "--aifuli.supply-chain.datasource.url=" + mysql.getJdbcUrl(),
                            "--aifuli.supply-chain.datasource.username=" + mysql.getUsername(),
                            "--aifuli.supply-chain.datasource.password=" + mysql.getPassword(),
                            "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                            "--MOVIE_TEST_SECRET=test-only-secret")) {
                JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
                jdbc.update("""
                        INSERT INTO sc_supplier(id,supplier_code,supplier_name,short_name,supplier_type,status,version,created_at,updated_at)
                        VALUES(1,'SPL_001','测试电影供应商','测试','STANDARD_SUPPLIER',10,0,NOW(3),NOW(3))
                        """);
                jdbc.update("""
                        INSERT INTO sc_credential_slot(id,slot_no,provider_type,secret_ref,active_secret_version,status,version,created_at,updated_at)
                        VALUES(1,'CRD_MOVIE_TEST','ENV','env:MOVIE_TEST_SECRET','v1',10,0,NOW(3),NOW(3))
                        """);
                jdbc.update("""
                        INSERT INTO sc_supplier_connector(id,connector_no,supplier_id,connector_code,adapter_type,current_version_id,status,version,created_at,updated_at)
                        VALUES(1,'CON_MOVIE_TEST',1,'jc-default','MOVIE_JC_V1',1,10,0,NOW(3),NOW(3))
                        """);
                String config = "{\"appId\":\"test-app\",\"channel\":\"JC\",\"accountRef\":\"jc-default\","
                        + "\"deliverMinutes\":\"8,15,30\",\"minRequestIntervalMs\":1100}";
                for (int versionNo = 1; versionNo <= 2; versionNo++) {
                    jdbc.update("""
                            INSERT INTO sc_supplier_connector_version(id,connector_id,version_no,base_url,credential_slot_id,
                              protocol_version,connect_timeout_ms,read_timeout_ms,adapter_config_schema_version,adapter_config_json,
                              validation_status,config_digest,status,version,created_at,updated_at)
                            VALUES(?,1,?,'http://supplier.example/api',1,'v1',5000,30000,1,?,0,UNHEX(SHA2('test',256)),10,0,NOW(3),NOW(3))
                            """, versionNo, versionNo, config);
                }
                MovieSupplierConfigService service = context.getBean(MovieSupplierConfigService.class);
                var first = service.accounts(List.of(MovieSupplierChannel.JC)).getFirst();
                assertThat(first.getConfigVersion()).isEqualTo("v1");
                assertThat(first.getAppSecret()).isEqualTo("test-only-secret");
                // 更新当前指针后下一次请求才读新版本，不自动选最新版本。
                jdbc.update("UPDATE sc_supplier_connector SET current_version_id=2 WHERE id=1");
                assertThat(service.accounts(List.of(MovieSupplierChannel.JC)).getFirst().getConfigVersion()).isEqualTo("v2");
                assertThat(first.getConfigVersion()).isEqualTo("v1");
                jdbc.update("UPDATE sc_supplier_connector_version SET status=0 WHERE id=2");
                assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("status=10");
                jdbc.update("UPDATE sc_supplier_connector_version SET status=10,connector_id=999 WHERE id=2");
                assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("属于本账号");
                jdbc.update("UPDATE sc_supplier_connector SET status=20 WHERE id=1");
                assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("没有启用");
            }
        }
    }

    /** 只装配供应链独立数据源及本次读取链路，避免测试启动无关进程或任务。 */
    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, MovieSupplierConfigService.class, EnvironmentCredentialProvider.class})
    static class TestConfiguration {
        /** 解析器与生产使用相同的Jackson对象模型。 */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
