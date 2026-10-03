package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.security.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.application.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import java.util.*;
import java.time.Duration;
import java.security.KeyPairGenerator;
import static org.assertj.core.api.Assertions.*;

/** 真实 MySQL 验证显式集绑定，假适配器只提供隔离元数据，绝不提前注册 HUA_SHENG 执行器。 */
class ConnectorCredentialSetIntegrationTest {
    static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("credential_binding")
            .withUsername("supply_chain").withPassword("supply_chain");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    @BeforeAll static void start() {
        MYSQL.start();
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled",true);
        properties.put("aifuli.supply-chain.datasource.url",MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username",MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password",MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name","com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.connector.allowed-hosts","api.qinameng.com");
        properties.put("aifuli.supply-chain.security.key-version","v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64",Base64.getEncoder().encodeToString(new byte[32]));
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64",Base64.getEncoder().encodeToString(new byte[32]));
        properties.put("TEST_CALLBACK","callback-material");
        context=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).properties(properties).run();
        jdbc=new JdbcTemplate(context.getBean("supplyChainDataSource",javax.sql.DataSource.class));
    }
    @AfterAll static void stop() { if(context!=null)context.close(); MYSQL.stop(); }

    @Test void longPemStoresWithoutTruncationAndSetFreezesThroughRealPublication() throws Exception {
        String supplier=supplier(); String pem=privatePem();
        Long set=createCompleteSet(supplier,"COMPLETE",pem);
        var draft=draft(supplier,"COMPLETE","{\"outMobile\":false,\"expireTime\":60}");
        var version=context.getBean(SupplierConnectorVersionMapper.class).selectById(draft.connectorVersionId());
        assertThat(version.getCredentialSlotId()).isNull(); assertThat(version.getCredentialSetId()).isEqualTo(set);
        assertThat(version.getCredentialItemSnapshot()).contains("activeSecretVersion", "callbackPublicKey").doesNotContain(pem);
        assertThat(jdbc.queryForObject("SELECT LENGTH(secret_ref) FROM sc_credential_item WHERE credential_set_id=? AND item_key='privateKey'",Long.class,set)).isGreaterThan(512);
        var resolver=context.getBean(CredentialSetResolver.class);
        var values=resolver.resolveSet(set);
        try { assertThat(new String(values.get("privateKey"))).isEqualTo(pem); }
        finally { values.values().forEach(value->Arrays.fill(value,'\0')); }
        var moved=context.getBean(CredentialItemMapper.class).selectBySetAndKey(set,"privateKey").toBuilder().itemKey("appSecret").build();
        assertThatThrownBy(()->context.getBean(EnvelopeCredentialItemResolver.class).resolve(moved)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->context.getBean(SupplierCredentialCommandService.class).addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(supplier,"COMPLETE","secretKey","ENV","env:TEST_CALLBACK",null,"masked",1,1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound");
        validate(draft);
        context.getBean(ConnectorPublicationService.class).publish(new ConnectorPublicationService.PublishConnectorCommand(
                draft.connectorNo(),draft.connectorVersionId(),0L,1L,"test","trace",null,null));
        var runtime=context.getBean(PublishedConnectorConfigurationResolver.class).resolve(draft.connectorVersionId());
        assertThat(runtime.credentialSlotId()).isNull();assertThat(runtime.credentialSetId()).isEqualTo(set);
        assertThat(runtime.connectTimeoutMs()).isEqualTo(1500);assertThat(runtime.readTimeoutMs()).isEqualTo(2501);
        assertThat(runtime.extraParameters()).containsEntry("expireTime","60").containsEntry("outMobile","false");
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE credential_set_id=? AND item_key='privateKey'",set);
        assertThatThrownBy(()->context.getBean(PublishedConnectorConfigurationResolver.class).resolve(draft.connectorVersionId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingExtraAndCrossSupplierReferencesFailBeforeDraft() throws Exception {
        String supplier=supplier(); Long incomplete=context.getBean(SupplierCredentialCommandService.class).createCredentialSet(
                new SupplierCredentialCommandService.CreateCredentialSetCommand(supplier,"INCOMPLETE",1L));
        assertThatThrownBy(()->draft(supplier,"INCOMPLETE","{}")).isInstanceOf(IllegalArgumentException.class);
        String other=supplier(); createCompleteSet(other,"OTHER",privatePem());
        assertThatThrownBy(()->draft(supplier,"OTHER","{}")).isInstanceOf(IllegalArgumentException.class);
        createCompleteSet(supplier,"EXTRA",privatePem());
        context.getBean(SupplierCredentialCommandService.class).addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                supplier,"EXTRA","secretKey","ENV","env:TEST_CALLBACK",null,"masked",1,1L));
        assertThatThrownBy(()->draft(supplier,"EXTRA","{}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(incomplete).isPositive();
    }
    @Test void itemVersionDriftAndDisabledSetRejectPublicationWithRollback() throws Exception {
        String supplier=supplier(); Long set=createCompleteSet(supplier,"DRIFT",privatePem());
        var draft=draft(supplier,"DRIFT","{}"); validate(draft);
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE credential_set_id=? AND item_key='privateKey'",set);
        assertThatThrownBy(()->publish(draft)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=1 WHERE credential_set_id=?",set);
        jdbc.update("UPDATE sc_credential_set SET status=20 WHERE id=?",set);
        assertThatThrownBy(()->publish(draft)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector WHERE connector_no=?",Long.class,draft.connectorNo())).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier_connector_version WHERE id=?",Integer.class,draft.connectorVersionId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_config_publish_record WHERE target_version_id=? AND action_type='PUBLISH'",Long.class,draft.connectorVersionId())).isZero();
    }
    @Test void oldRepeatableReadSnapshotCannotAppendAfterBindingCommits() throws Exception {
        String supplier=supplier(); Long set=createCompleteSet(supplier,"RACE",privatePem());
        var locked=new java.util.concurrent.CountDownLatch(1); var snapshot=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var manager=context.getBean("supplyChainTransactionManager",org.springframework.transaction.PlatformTransactionManager.class);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            var binding=executor.submit(()->transaction.execute(status->{
                context.getBean(CredentialSetMapper.class).selectByIdForUpdate(set); locked.countDown(); await(snapshot);
                try {return draft(supplier,"RACE","{}");} catch(Exception failure){throw new IllegalStateException(failure);}
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var adding=executor.submit(()->{
                try { transaction.executeWithoutResult(status->{
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_connector_version WHERE credential_set_id=?",Long.class,set)).isZero();
                    snapshot.countDown();
                    context.getBean(SupplierCredentialCommandService.class).addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                            supplier,"RACE","secretKey","ENV","env:TEST_CALLBACK",null,"masked",1,1L));
                }); return (Throwable)null;} catch(RuntimeException failure){return failure;}
            });
            assertThat(binding.get(15,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            assertThat(adding.get(15,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("bound");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_credential_item WHERE credential_set_id=?",Long.class,set)).isEqualTo(3L);
        } finally { snapshot.countDown(); executor.shutdownNow(); }
    }
    @Test void publicationWithOldSnapshotRejectsSetDisabledWhileAwaitingSetLock() throws Exception {
        String supplier=supplier(); Long set=createCompleteSet(supplier,"DISABLE_RACE",privatePem());
        var draft=draft(supplier,"DISABLE_RACE","{}"); validate(draft);
        var locked=new java.util.concurrent.CountDownLatch(1);var snapshot=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                context.getBean("supplyChainTransactionManager",org.springframework.transaction.PlatformTransactionManager.class));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            var disabling=executor.submit(()->transaction.execute(status->{
                context.getBean(CredentialSetMapper.class).selectByIdForUpdate(set);locked.countDown();await(snapshot);
                jdbc.update("UPDATE sc_credential_set SET status=20 WHERE id=?",set);return true;
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var publishing=executor.submit(()->{
                try {transaction.executeWithoutResult(status->{
                    assertThat(jdbc.queryForObject("SELECT status FROM sc_credential_set WHERE id=?",Integer.class,set)).isEqualTo(10);
                    snapshot.countDown();publish(draft);
                });return (Throwable)null;}catch(RuntimeException failure){return failure;}
            });
            assertThat(disabling.get(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(publishing.get(15,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector WHERE connector_no=?",Long.class,draft.connectorNo())).isNull();
        } finally {snapshot.countDown();executor.shutdownNow();}
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try {if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("concurrency handshake timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("concurrency interrupted",failure);}
    }
    @Test void extraSchemaIsAuthoritativeAtCreationValidationAndRuntime() throws Exception {
        String supplier=supplier();createCompleteSet(supplier,"EXTRA_SCHEMA",privatePem());
        for(String invalid:List.of("{\"token\":\"synthetic-secret\"}","{\"expireTime\":\"60\"}","{\"nested\":{}}")) {
            assertThatThrownBy(()->draft(supplier,"EXTRA_SCHEMA",invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        var draft=draft(supplier,"EXTRA_SCHEMA","{}");
        context.getBean(SupplierConfigurationCommandService.class).initializeMappings(draft.connectorVersionId(),1L);
        jdbc.update("UPDATE sc_supplier_connector_version SET extra_config='{\"token\":\"synthetic\"}' WHERE id=?",draft.connectorVersionId());
        var validation=new ConnectorValidationService.ValidateConnectorCommand(draft.connectorVersionId(),
                new QnmGameOrderV1Metadata().capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),Duration.ofMinutes(10),1L,"test","trace");
        assertThatThrownBy(()->context.getBean(ConnectorValidationService.class).validate(validation)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE sc_supplier_connector_version SET extra_config='{}' WHERE id=?",draft.connectorVersionId());
        context.getBean(ConnectorValidationService.class).validate(validation);publish(draft);
        jdbc.update("UPDATE sc_supplier_connector_version SET extra_config='{\"token\":\"synthetic\"}' WHERE id=?",draft.connectorVersionId());
        assertThatThrownBy(()->context.getBean(PublishedConnectorConfigurationResolver.class).resolve(draft.connectorVersionId())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void plaintextLimitsRejectBeforeMySqlWriteAndExactUtf8BoundarySurvives() throws Exception {
        String supplier=supplier();var service=context.getBean(SupplierCredentialCommandService.class);
        Long set=service.createCredentialSet(new SupplierCredentialCommandService.CreateCredentialSetCommand(supplier,"LIMIT",1L));
        for(String tooLarge:List.of("A".repeat(8193),"密".repeat(6000))) {
            assertThatThrownBy(()->service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                    supplier,"LIMIT","privateKey","ENVELOPE",null,tooLarge,"masked",1,1L))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        }
        assertThatThrownBy(()->service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                supplier,"LIMIT","privateKey","ENV","env:"+"A".repeat(509),null,"masked",1,1L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_credential_item WHERE credential_set_id=?",Long.class,set)).isZero();
        String boundary="密".repeat(5461)+"a";
        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(supplier,"LIMIT","privateKey","ENVELOPE",null,boundary,"masked",1,1L));
        var item=context.getBean(CredentialItemMapper.class).selectBySetAndKey(set,"privateKey");
        char[] resolved=context.getBean(EnvelopeCredentialItemResolver.class).resolve(item);
        try {assertThat(new String(resolved)).isEqualTo(boundary);}finally{Arrays.fill(resolved,'\0');}
    }
    static void publish(SupplierConfigurationCommandService.DraftConnectorResult draft) {
        context.getBean(ConnectorPublicationService.class).publish(new ConnectorPublicationService.PublishConnectorCommand(
                draft.connectorNo(),draft.connectorVersionId(),0,1L,"test","trace",null,null));
    }
    static void validate(SupplierConfigurationCommandService.DraftConnectorResult draft) {
        context.getBean(SupplierConfigurationCommandService.class).initializeMappings(draft.connectorVersionId(),1L);
        context.getBean(ConnectorValidationService.class).validate(new ConnectorValidationService.ValidateConnectorCommand(
                draft.connectorVersionId(),new QnmGameOrderV1Metadata().capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),Duration.ofMinutes(10),1L,"test","trace"));
    }
    static SupplierConfigurationCommandService.DraftConnectorResult draft(String supplier,String set,String extra) throws Exception {
        var command=new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                supplier,"CON_"+UUID.randomUUID(),"TEST_SET","https://api.qinameng.com","merchant",null,"V1",null,
                1500,2501,null,null,"{}",1L,set,extra);
        return context.getBean(SupplierConfigurationCommandService.class).createConnectorDraft(command);
    }
    static String supplier() {
        return context.getBean(SupplierConfigurationCommandService.class).createSupplier(new SupplierConfigurationCommandService.CreateSupplierCommand(
                null,"供应商","供应商","STANDARD_SUPPLIER",null,1L));
    }
    static Long createCompleteSet(String supplier,String code,String pem) {
        var service=context.getBean(SupplierCredentialCommandService.class);
        Long set=service.createCredentialSet(new SupplierCredentialCommandService.CreateCredentialSetCommand(supplier,code,1L));
        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(supplier,code,"appSecret","ENVELOPE",null,"1234567890123456","masked",1,1L));
        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(supplier,code,"privateKey","ENVELOPE",null,pem,"masked",1,1L));
        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(supplier,code,"callbackPublicKey","ENV","env:TEST_CALLBACK",null,"masked",1,1L));
        return set;
    }
    static String privatePem() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        return "-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----";
    }
    @Configuration(proxyBeanMethods=false)
    @Import({com.aifuli.module.supplychain.tradecore.supply.application.ConnectorCredentialBindingValidator.class,ConnectorPublicationIntegrationTest.TestConfiguration.class, SupplierCredentialCommandService.class,
            AesGcmEnvelopeCrypto.class,EnvelopeCredentialItemResolver.class,EnvCredentialItemResolver.class,DefaultCredentialSetResolver.class})
    static class Config { @Bean AdapterMetadata testMetadata() { return new TestMetadata(); } }
    static class TestMetadata implements AdapterMetadata {
        final AdapterMetadata qnm=new QnmGameOrderV1Metadata();
        public String adapterType(){return "TEST_SET";}
        public CredentialBindingMode credentialBindingMode(){return CredentialBindingMode.CREDENTIAL_SET;}
        public Set<String> credentialKeys(){return Set.of("appSecret","privateKey","callbackPublicKey");}
        public Set<Capability> capabilities(){return qnm.capabilities();}
        public String connectorSchemaResource(){return qnm.connectorSchemaResource();}
        public String offerSchemaResource(){return qnm.offerSchemaResource();}
        public String extraConfigSchemaResource(){return "schema/credential-extra-test.json";}
        public int connectorSchemaVersion(){return 1;} public int offerSchemaVersion(){return 1;}
        public void validateConnectorConfig(int version,JsonNode config){qnm.validateConnectorConfig(version,config);}
        public void validateOfferConfig(int version,JsonNode config){qnm.validateOfferConfig(version,config);}
        public ExecutionProfileDefaults defaultExecutionProfile(){return qnm.defaultExecutionProfile();}
        public MappingTemplates mappingTemplates(){return qnm.mappingTemplates();}
    }
}
