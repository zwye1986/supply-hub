package com.aifuli.module.supplychain.infrastructure;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 设计 §7：真实 Liquibase 从既有 065 升级，保留历史未知事实并验证命令唯一约束。 */
class FulfillmentCaseSchemaIntegrationTest {
    @Test
    void evidenceKeyVersionSupportsLocalKeyAndActiveIntentUniqueness() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("case_schema")) {
            mysql.start();
            // 既有 065 基线含两个额外 changeset；只在一次性容器建立旧库，不触及共享库。
            try (var connection = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var liquibase = new liquibase.Liquibase("db/changelog/db.changelog-master.yaml",
                         new liquibase.resource.ClassLoaderResourceAccessor(),
                         new liquibase.database.jvm.JdbcConnection(connection))) {
                liquibase.update(67, new liquibase.Contexts(), new liquibase.LabelExpression());
                var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='065-order-number-segments'", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sc_fulfillment_case_action'", Long.class)).isZero();
                jdbc.update("""
                        INSERT INTO sc_fulfillment_call_log (call_no,operation_id,b_order_id,attempt_no,direction,
                        connector_version_id,endpoint_code,started_at,duration_ms,result_class,request_digest,redaction_version,trace_id,created_at)
                        VALUES ('legacy-call',1,1,1,'OUTBOUND',1,'PLACE',CURRENT_TIMESTAMP(3),0,'RESULT_UNKNOWN',UNHEX(REPEAT('00',32)),1,'fixture',CURRENT_TIMESTAMP(3))
                        """);
            }
            try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE).run(
                    "--aifuli.supply-chain.enabled=true",
                    "--aifuli.supply-chain.datasource.url=" + mysql.getJdbcUrl(),
                    "--aifuli.supply-chain.datasource.username=" + mysql.getUsername(),
                    "--aifuli.supply-chain.datasource.password=" + mysql.getPassword(),
                    "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver")) {
                var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
                assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sc_fulfillment_case_action' AND column_name='evidence_key_version'", String.class)).isEqualTo("varchar");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_call_log WHERE call_no='legacy-call' AND remote_code IS NULL AND business_send_started IS NULL AND evidence_ciphertext IS NULL", Long.class)).isEqualTo(1L);
                String insert = """
                        INSERT INTO sc_fulfillment_case_action (action_no,case_no,case_id,b_order_id,operation_no,command_type,
                        active_intent_flag,request_fingerprint,actor_id,expected_case_version,expected_generation,execution_status,
                        evidence_schema_version,evidence_key_version,evidence_ciphertext,created_at,updated_at)
                        VALUES (?,'fixture-case',1,1,'fixture-operation','VERIFY_ORIGIN',?,UNHEX(REPEAT('00',32)),7,0,1,?,1,'local-v1',X'01',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                        """;
                jdbc.update(insert, "active-one", 1, "RECEIVED");
                assertThatThrownBy(() -> jdbc.update(insert, "active-two", 1, "STARTED"))
                        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
                jdbc.update("UPDATE sc_fulfillment_case_action SET active_intent_flag=NULL,execution_status='UNKNOWN' WHERE action_no='active-one'");
                jdbc.update(insert, "active-two", 1, "STARTED");
                jdbc.update(insert, "completed-one", null, "SUCCEEDED");
                jdbc.update(insert, "completed-two", null, "UNKNOWN");
                assertThatThrownBy(() -> jdbc.update(insert, "completed-one", null, "UNKNOWN"))
                        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
                assertThatThrownBy(() -> jdbc.update(insert, "invalid-combination", 1, "SUCCEEDED"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .hasMessageContaining("ck_sc_case_action_active");
                assertThatThrownBy(() -> jdbc.update(insert, "missing-active-intent", null, "STARTED"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .hasMessageContaining("ck_sc_case_action_active");
                assertThat(jdbc.queryForObject("SELECT evidence_key_version FROM sc_fulfillment_case_action WHERE action_no='completed-one'", String.class)).isEqualTo("local-v1");
            }
        }
    }
    @Configuration(proxyBeanMethods = false)
    @Import(SupplyChainDataSourceConfiguration.class)
    static class Fixture { }
}
