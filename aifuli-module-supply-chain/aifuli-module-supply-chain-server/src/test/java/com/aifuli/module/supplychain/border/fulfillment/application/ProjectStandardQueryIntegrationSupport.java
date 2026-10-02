package com.aifuli.module.supplychain.border.fulfillment.application;

import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/**
 * 真实MySQL、生产Mapper和Spring事务代理；仅信封加密外部端口替身。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#6-自动化门禁与完成边界。
 */
class ProjectStandardQueryIntegrationSupport implements AutoCloseable {
    final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("project_query")
                    .withUsername("query")
                    .withPassword("query")
                    .withCommand("--log-bin-trust-function-creators=1");
    final ConfigurableApplicationContext context;
    final JdbcTemplate jdbc;

    ProjectStandardQueryIntegrationSupport() {
        mysql.start();
        context =
                new SpringApplicationBuilder(Config.class)
                        .profiles("project-standard-query-test")
                        .web(WebApplicationType.NONE)
                        .properties(
                                Map.of(
                                        "aifuli.supply-chain.enabled",
                                        true,
                                        "aifuli.supply-chain.datasource.url",
                                        mysql.getJdbcUrl(),
                                        "aifuli.supply-chain.datasource.username",
                                        mysql.getUsername(),
                                        "aifuli.supply-chain.datasource.password",
                                        mysql.getPassword(),
                                        "aifuli.supply-chain.datasource.driver-class-name",
                                        "com.mysql.cj.jdbc.Driver"))
                        .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
    }

    @Override
    public void close() {
        context.close();
        mysql.stop();
    }

    StandardProductContext queryContext(long project, long buyer) {
        return new StandardProductContext(
                project, buyer, "project-app", 7, ProjectAuthOperations.STANDARD_ORDER_QUERY);
    }

    StandardProductContext assetContext(long project, long buyer) {
        return new StandardProductContext(
                project, buyer, "project-app", 7, ProjectAuthOperations.STANDARD_ORDER_ASSET_QUERY);
    }

    ProjectStandardOrderQueryService queries() {
        return context.getBean(ProjectStandardOrderQueryService.class);
    }

    ProjectStandardAssetQueryService assets() {
        return context.getBean(ProjectStandardAssetQueryService.class);
    }

    EnvelopeCryptoPort crypto() {
        return context.getBean(EnvelopeCryptoPort.class);
    }

    TransactionTemplate transaction() {
        return new TransactionTemplate(
                context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }

    void resetData() {
        for (String table :
                new String[] {
                    "sc_sensitive_access_audit",
                    "sc_fulfillment_asset",
                    "sc_fulfillment_operation",
                    "sc_b_order",
                    "sc_fulfillment_request",
                    "sc_supply_chain_order",
                    "sc_fulfillment_acceptance_receipt"
                }) jdbc.update("DELETE FROM " + table);
        reset(crypto());
        ReceiptPause.enabled.set(false);
    }

    void receipt(long id, Long project, long buyer, String buyerNo, String schema) {
        jdbc.update(
                "INSERT INTO"
                    + " sc_fulfillment_acceptance_receipt(id,receipt_no,project_id,buyer_id,buyer_order_no,buyer_user_id,buyer_sku_code,quantity,target_schema_code,target_digest,invariant_fingerprint,request_fingerprint,listing_version_no,status,created_at,updated_at,version)"
                    + " VALUES(?,?,?,?,?,'old-user','sku',1,?,UNHEX(REPEAT('01',32)),UNHEX(REPEAT('02',32)),UNHEX(REPEAT('03',32)),1,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),1)",
                id,
                "AR_" + id,
                project,
                buyer,
                buyerNo,
                schema);
    }

    void accepted(long id, Long project, long buyer, String buyerNo, String schema) {
        receipt(id, project, buyer, buyerNo, schema);
        buildOrder(id, project, buyer, buyerNo, schema);
    }

    void buildOrder(long id, Long project, long buyer, String buyerNo, String schema) {
        jdbc.update(
                "INSERT INTO"
                    + " sc_supply_chain_order(id,order_no,order_type,project_id,buyer_id,buyer_code_snapshot,buyer_name_snapshot,buyer_user_id,buyer_order_no,acceptance_receipt_id,buyer_settlement_amount,currency,platform_sku_id,quantity,order_status,aftersale_status,business_stage,detail_type,detail_id,detail_version,request_fingerprint,created_at,updated_at)"
                    + " VALUES(?,?,'CARD',?,?,'buyer','Buyer','different-old-user',?,?,100,'CNY',1,1,10,0,'CARD_AWAITING_RESULT','FULFILLMENT',?,1,UNHEX(REPEAT('03',32)),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                id,
                "SC_" + id,
                project,
                buyer,
                buyerNo,
                id,
                id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_fulfillment_request(id,request_no,order_id,buyer_id,listing_id,listing_version_id,platform_sku_id,target_schema_code,target_schema_version,target_ciphertext,target_digest,target_key_version,requested_quantity,fulfilled_quantity,status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,1,1,1,?,1,X'01',UNHEX(REPEAT('01',32)),'k1',1,0,20,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                id,
                "FR_" + id,
                id,
                buyer,
                schema);
        jdbc.update(
                "UPDATE sc_fulfillment_acceptance_receipt SET"
                    + " status=10,listing_version_id=1,result_order_id=?,result_order_no=?,result_fulfillment_request_no=?,accepted_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE id=?",
                id,
                "SC_" + id,
                "FR_" + id,
                id);
    }

    void asset(long id, long request, long buyer, String type, short status) {
        // 每资产独立BOrder和operation，便于校验跨尝试与第二资产失败；不依赖供应商调用。
        jdbc.update(
                "INSERT INTO"
                    + " sc_b_order(id,b_order_no,fulfillment_request_id,order_id,attempt_no,supplier_id,offer_id,offer_version_id,connector_version_id,inventory_source_id,billno,idempotency_key,requested_quantity,fulfilled_quantity,supplier_settlement_unit_price,expected_supplier_settlement_amount,currency,fulfillment_status,settlement_status,created_at,updated_at)"
                    + " VALUES(?,?,?,"
                    + " ?,?,9,1,1,1,1,?,?,1,1,100,100,'CNY',40,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                id,
                "BO_" + id,
                request,
                request,
                (int) id,
                "bill_" + id,
                "key_" + id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_fulfillment_operation(id,operation_no,b_order_id,operation_kind,adapter_step_code,sequence_no,idempotency_key,config_digest,status,created_at,updated_at)"
                    + " VALUES(?,"
                    + " ?,?,'FULFILL','SEND_KM',1,?,UNHEX(REPEAT('04',32)),40,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                id,
                "OP_" + id,
                id,
                "op_key_" + id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_fulfillment_asset(id,asset_no,fulfillment_request_id,b_order_id,operation_id,supplier_id,buyer_id,buyer_user_id,sequence_no,unit_quantity,asset_type,encrypted_payload,masked_value,fingerprint,key_version,lifecycle_status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,9,?,'third-old-user',1,1,?,?,'****',?,'k1',?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                id,
                "FA_" + id,
                request,
                id,
                id,
                buyer,
                type,
                new byte[] {(byte) id},
                java.nio.ByteBuffer.allocate(32).putLong(id).array(),
                status);
    }

    @Profile("project-standard-query-test")
    @Configuration(proxyBeanMethods = false)
    @Import({
        SupplyChainDataSourceConfiguration.class,
        ProjectStandardOrderQueryService.class,
        ProjectStandardAssetQueryService.class,
        ProjectStandardAssetAuditService.class,
        ProjectStandardAssetPayloadDecoder.class
    })
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BusinessNoGenerator numbers() {
            return new BusinessNoGenerator(new UlidGenerator());
        }

        @Bean
        EnvelopeCryptoPort crypto() {
            return mock(EnvelopeCryptoPort.class);
        }

        @Bean
        static BeanPostProcessor receiptPause() {
            return new ReceiptPause();
        }
    }

    static class ReceiptPause implements BeanPostProcessor {
        static final AtomicBoolean enabled = new AtomicBoolean();
        static CountDownLatch read;
        static CountDownLatch proceed;

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof FulfillmentAcceptanceReceiptMapper)) return bean;
            return Proxy.newProxyInstance(
                    bean.getClass().getClassLoader(),
                    new Class<?>[] {FulfillmentAcceptanceReceiptMapper.class},
                    (proxy, method, args) -> {
                        Object value;
                        try {
                            value = method.invoke(bean, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (method.getName().equals("selectList")
                                && enabled.compareAndSet(true, false)) {
                            read.countDown();
                            if (!proceed.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("快照测试等待超时");
                        }
                        return value;
                    });
        }
    }
}
