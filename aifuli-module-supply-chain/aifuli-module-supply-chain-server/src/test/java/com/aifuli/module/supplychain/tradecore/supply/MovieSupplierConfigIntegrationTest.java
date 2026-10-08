package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.api.movie.MovieSupplierChannel;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCredentialItemResolver;
import com.aifuli.module.supplychain.infrastructure.security.DefaultCredentialSetResolver;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/** 真实MySQL与现有Liquibase表，验证加密凭据读取、当前版本选择及错误绑定拒绝。 */
class MovieSupplierConfigIntegrationTest {

    @Test
    void readsOnlyPublishedCurrentVersionFromExistingSchema() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("movie_config_test").withUsername("test").withPassword("test")) {
            mysql.start();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfiguration.class)
                    .web(WebApplicationType.NONE).run(
                            "--spring.config.name=movie-config-integration",
                            // 本类仅验证本地已发布配置读取，不启用远程配置／注册中心。
                            // 设计见B0「九测试／夹具限定修复设计」。
                            "--spring.cloud.bootstrap.enabled=false",
                            "--spring.cloud.nacos.discovery.enabled=false",
                            "--spring.cloud.nacos.config.enabled=false",
                            "--spring.cloud.nacos.config.import-check.enabled=false",
                            "--aifuli.supply-chain.enabled=true",
                            "--aifuli.supply-chain.datasource.url=" + mysql.getJdbcUrl(),
                            "--aifuli.supply-chain.datasource.username=" + mysql.getUsername(),
                            "--aifuli.supply-chain.datasource.password=" + mysql.getPassword(),
                            "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver")) {
                JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
                jdbc.update("""
                        INSERT INTO sc_supplier(id,supplier_code,supplier_name,short_name,supplier_type,status,version,created_at,updated_at)
                        VALUES(1,'SPL_001','测试电影供应商','测试','STANDARD_SUPPLIER',10,0,NOW(3),NOW(3))
                        """);
                // 仅向临时测试库写入真实 AES-GCM 密文，不改变管理端录入或发布流程。
                byte[] plaintext = "test-only-secret".getBytes(StandardCharsets.UTF_8);
                String encrypted;
                try {
                    encrypted = EnvelopeCredentialItemResolver.encodeSecretRef(context.getBean(EnvelopeCryptoPort.class)
                            .encrypt(plaintext, EnvelopeCredentialItemResolver.aad(1L, "appSecret")));
                } finally {
                    java.util.Arrays.fill(plaintext, (byte) 0);
                }
                jdbc.update("""
                        INSERT INTO sc_credential_set(id,supplier_id,set_code,status,created_at,updated_at)
                        VALUES(1,1,'MOVIE_TEST',10,NOW(3),NOW(3))
                        """);
                jdbc.update("""
                        INSERT INTO sc_credential_item(id,credential_set_id,item_key,provider_type,secret_ref,
                          active_secret_version,status,created_at,updated_at)
                        VALUES(1,1,'appSecret','ENVELOPE',?,1,10,NOW(3),NOW(3))
                        """, encrypted);
                String snapshot = "[{\"itemId\":1,\"itemKey\":\"appSecret\",\"providerType\":\"ENVELOPE\",\"activeSecretVersion\":1}]";
                jdbc.update("""
                        INSERT INTO sc_supplier_connector(id,connector_no,supplier_id,connector_code,adapter_type,current_version_id,status,version,created_at,updated_at)
                        VALUES(1,'CON_MOVIE_TEST',1,'jc-default','MOVIE_JC_V1',1,10,0,NOW(3),NOW(3))
                        """);
                String config = "{\"appId\":\"test-app\",\"channel\":\"JC\",\"accountRef\":\"jc-default\","
                        + "\"deliverMinutes\":\"8,15,30\",\"minRequestIntervalMs\":1100}";
                for (int versionNo = 1; versionNo <= 2; versionNo++) {
                    jdbc.update("""
                            INSERT INTO sc_supplier_connector_version(id,connector_id,version_no,base_url,credential_set_id,credential_item_snapshot,
                              protocol_version,connect_timeout_ms,read_timeout_ms,adapter_config_schema_version,adapter_config_json,
                              validation_status,config_digest,status,version,created_at,updated_at)
                            VALUES(?,1,?,'http://supplier.example/api',1,?,'v1',5000,30000,1,?,0,UNHEX(SHA2('test',256)),10,0,NOW(3),NOW(3))
                            """, versionNo, versionNo, snapshot, config);
                }
                MovieSupplierConfigService service = context.getBean(MovieSupplierConfigService.class);
                var first = service.accounts(List.of(MovieSupplierChannel.JC)).getFirst();
                assertThat(first.getConfigVersion()).isEqualTo("v1");
                assertThat(first.getAppSecret()).isEqualTo("test-only-secret");
                assertThat(encrypted).doesNotContain("test-only-secret");
                // 只读服务须拒绝发布之后的秘密身份版本变化和 ENV 类型，不进行自动兼容。
                jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE id=1");
                assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("发布快照");
                jdbc.update("UPDATE sc_credential_item SET active_secret_version=1,provider_type='ENV' WHERE id=1");
                assertThatThrownBy(() -> service.accounts(List.of(MovieSupplierChannel.JC))).hasMessageContaining("ENVELOPE");
                jdbc.update("UPDATE sc_credential_item SET provider_type='ENVELOPE' WHERE id=1");
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
    @Import({SupplyChainDataSourceConfiguration.class, MovieSupplierConfigService.class,
            DefaultCredentialSetResolver.class, EnvelopeCredentialItemResolver.class})
    static class TestConfiguration {
        /** 测试专用主密钥，真实加解密但不依赖业务环境。 */
        @Bean
        EnvelopeCryptoPort envelopeCryptoPort() {
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            return new AesGcmEnvelopeCrypto("test-v1", key, key);
        }

        /** 解析器与生产使用相同的Jackson对象模型。 */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
