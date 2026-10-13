package com.aifuli.module.system.service.dict;

import com.aifuli.module.system.controller.admin.dict.DictDataController;
import com.aifuli.module.system.dal.mysql.dict.DictDataMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.FastCheckService;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在真实 MySQL 上验证 system 管理端表单字典：首次全量写入、二次幂等。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 * 路由模式与状态契约见
 * {@code docs/superpowers/specs/2026-10-12-routing-rule-dictionaries-design.md#6-验证与验收}。
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminFormDictionaryMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("system_test")
            .withUsername("system_test")
            .withPassword("system_test");

    @BeforeAll
    static void createYudaoDictionaryTables() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE system_dict_type (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      name VARCHAR(100) NOT NULL DEFAULT '',
                      type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      deleted_time DATETIME NULL DEFAULT NULL,
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_dict_data (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      sort INT NOT NULL DEFAULT 0,
                      label VARCHAR(100) NOT NULL DEFAULT '',
                      value VARCHAR(100) NOT NULL DEFAULT '',
                      dict_type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      color_type VARCHAR(100) NULL DEFAULT '',
                      css_class VARCHAR(100) NULL DEFAULT '',
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            // 完整 system 主清单还包含菜单迁移；此处只提供其真实前置结构与供应链根目录。
            statement.execute("""
                    CREATE TABLE system_menu (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      application_code VARCHAR(64) NOT NULL DEFAULT '',
                      name VARCHAR(100) NOT NULL DEFAULT '',
                      permission VARCHAR(100) NOT NULL DEFAULT '',
                      type TINYINT NOT NULL DEFAULT 1,
                      sort INT NOT NULL DEFAULT 0,
                      parent_id BIGINT NOT NULL DEFAULT 0,
                      path VARCHAR(200) NOT NULL DEFAULT '',
                      icon VARCHAR(100) NOT NULL DEFAULT '',
                      component VARCHAR(200) NULL,
                      component_name VARCHAR(100) NULL,
                      status TINYINT NOT NULL DEFAULT 0,
                      visible BIT(1) NOT NULL DEFAULT b'1',
                      keep_alive BIT(1) NOT NULL DEFAULT b'0',
                      always_show BIT(1) NOT NULL DEFAULT b'1',
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0'
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    INSERT INTO system_menu (application_code, name, permission, path)
                    VALUES ('supply-chain-admin', '供应链管理', 'supply-chain:admin', '/supplier')
                    """);
        }
    }

    @Test
    void migrationWritesDictionariesOnlyOnce() throws Exception {
        updateLiquibase();
        assertEquals(14, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode', 'supply_chain_invoice_title_type',
                    'supply_chain_project_type', 'supply_chain_project_status',
                    'supply_chain_fulfillment_type', 'supply_chain_sku_status')
                  AND deleted = b'0'
                """));
        assertEquals(54, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode', 'supply_chain_invoice_title_type',
                    'supply_chain_project_type', 'supply_chain_project_status',
                    'supply_chain_fulfillment_type', 'supply_chain_sku_status')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals("暂停分销", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_buyer_status' AND value = 'SUSPENDED' AND deleted = b'0'
                """));
        assertEquals(5, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_supply_mode' AND deleted = b'0'
                """));
        assertEquals("一件代发", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_supply_mode' AND value = 'DROP_SHIP' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '003-supply-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(2, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_invoice_title_type' AND deleted = b'0'
                """));
        assertEquals("企业", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_invoice_title_type' AND value = 'ENTERPRISE' AND deleted = b'0'
                """));
        assertEquals("个人", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_invoice_title_type' AND value = 'PERSONAL' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '004-invoice-title-type-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(6, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_project_type' AND deleted = b'0'
                """));
        assertEquals("积分兑换", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_project_type'
                  AND value = 'POINTS_REDEMPTION' AND deleted = b'0'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_project_status' AND deleted = b'0'
                """));
        assertEquals("筹备中", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_project_status' AND value = 'DRAFT' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '005-project-list-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(2, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_fulfillment_type' AND deleted = b'0'
                """));
        assertEquals("卡号卡密", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_fulfillment_type'
                  AND value = 'CARD_CODE_SECRET' AND deleted = b'0'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_sku_status' AND deleted = b'0'
                """));
        assertEquals("上架", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_sku_status' AND value = '10' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '006-product-list-filter-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_mode' AND deleted = b'0'
                """));
        assertEquals("等于市场价", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_mode'
                  AND value = 'EQUAL_MARKET_PRICE' AND deleted = b'0'
                """));
        assertEquals("自定义设置", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_mode'
                  AND value = 'CUSTOM' AND deleted = b'0'
                """));
        assertEquals("固定值", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_mode'
                  AND value = 'FIXED' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '010-suggested-sale-price-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type = 'supply_chain_suggested_sale_price_operator' AND deleted = b'0'
                """));
        assertEquals(4, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator' AND deleted = b'0'
                """));
        assertEquals("加", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator'
                  AND value = 'ADD' AND deleted = b'0'
                """));
        assertEquals("减", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator'
                  AND value = 'SUBTRACT' AND deleted = b'0'
                """));
        assertEquals("乘", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator'
                  AND value = 'MULTIPLY' AND deleted = b'0'
                """));
        assertEquals("除", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator'
                  AND value = 'DIVIDE' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '011-suggested-sale-price-operator-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type = 'supply_chain_offer_price_mode' AND deleted = b'0'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_offer_price_mode' AND deleted = b'0'
                """));
        assertEquals("固定值", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_offer_price_mode'
                  AND value = 'FIXED' AND deleted = b'0'
                """));
        assertEquals("实时更新", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_offer_price_mode'
                  AND value = 'REALTIME' AND deleted = b'0'
                """));
        assertEquals("按供货折扣", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_offer_price_mode'
                  AND value = 'DISCOUNT' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '012-offer-price-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));

        assertRoutingDictionaries();
        updateLiquibase();
        assertEquals(14, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode', 'supply_chain_invoice_title_type',
                    'supply_chain_project_type', 'supply_chain_project_status',
                    'supply_chain_fulfillment_type', 'supply_chain_sku_status')
                  AND deleted = b'0'
                """));
        assertEquals(54, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode', 'supply_chain_invoice_title_type',
                    'supply_chain_project_type', 'supply_chain_project_status',
                    'supply_chain_fulfillment_type', 'supply_chain_sku_status')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '003-supply-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '004-invoice-title-type-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '005-project-list-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '006-product-list-filter-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_mode' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '010-suggested-sale-price-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type = 'supply_chain_suggested_sale_price_operator' AND deleted = b'0'
                """));
        assertEquals(4, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_suggested_sale_price_operator' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '011-suggested-sale-price-operator-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type = 'supply_chain_offer_price_mode' AND deleted = b'0'
                """));
        assertEquals(3, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_offer_price_mode' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '012-offer-price-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
        assertRoutingDictionaries();
        assertRoutingDictionaryResponse(false);

        // 重放必须保留管理员修改；已禁用项不被重新启用，逻辑删除项可补齐但不复活旧行。
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE system_dict_type SET name = '人工路由模式', remark = '保留类型配置'
                    WHERE type = 'supply_chain_routing_mode' AND deleted = b'0'
                    """);
            statement.execute("""
                    UPDATE system_dict_data
                    SET label = '人工自动', sort = 9, color_type = 'warning', css_class = 'custom',
                        remark = '保留数据配置', updater = 'manual'
                    WHERE dict_type = 'supply_chain_routing_mode' AND value = 'AUTO' AND deleted = b'0'
                    """);
            statement.execute("""
                    UPDATE system_dict_data SET status = 1
                    WHERE dict_type = 'supply_chain_routing_mode' AND value = 'FIXED' AND deleted = b'0'
                    """);
            statement.execute("""
                    UPDATE system_dict_data SET deleted = b'1'
                    WHERE dict_type = 'supply_chain_routing_rule_status' AND value = 'DISABLED'
                    """);
            for (int replay = 0; replay < 2; replay++) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                        "db/changelog/releases/1.0.0/015-routing-rule-dictionaries.sql"), StandardCharsets.UTF_8));
            }
            // 仅在隔离测试库移除本变更集记录，模拟先人工热更、后由正式迁移登记的存量环境。
            statement.execute("""
                    DELETE FROM DATABASECHANGELOG
                    WHERE ID = '015-routing-rule-dictionaries' AND AUTHOR = 'supply-chain'
                    """);
        }
        // 模拟新的正式进程启动，不能沿用上一轮 Liquibase 的已执行记录内存缓存。
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '015-routing-rule-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(4, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN ('supply_chain_routing_mode', 'supply_chain_routing_rule_status')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_routing_rule_status' AND value = 'DISABLED' AND deleted = b'1'
                """));
        assertEquals("人工路由模式:保留类型配置", queryText("""
                SELECT CONCAT(name, ':', remark) FROM system_dict_type
                WHERE type = 'supply_chain_routing_mode' AND deleted = b'0'
                """));
        assertEquals("人工自动:9:0:warning:custom:保留数据配置:manual", queryText("""
                SELECT CONCAT(label, ':', sort, ':', status, ':', color_type, ':', css_class, ':', remark, ':', updater)
                FROM system_dict_data
                WHERE dict_type = 'supply_chain_routing_mode' AND value = 'AUTO' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT status FROM system_dict_data
                WHERE dict_type = 'supply_chain_routing_mode' AND value = 'FIXED' AND deleted = b'0'
                """));
        assertEquals("暂停分销", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_buyer_status' AND value = 'SUSPENDED' AND deleted = b'0'
                """));
        assertRoutingDictionaryResponse(true);
    }

    /** 精简列表经真实 Mapper/Service 和 MVC 序列化，不用 mock 数据冒充字典接口契约。 */
    private static void assertRoutingDictionaryResponse(boolean fixedDisabled) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("dictionary-test", new JdbcTransactionFactory(),
                new UnpooledDataSource(MYSQL.getDriverClassName(), MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(), MYSQL.getPassword())));
        configuration.addMapper(DictDataMapper.class);
        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            DictDataServiceImpl service = new DictDataServiceImpl();
            ReflectionTestUtils.setField(service, "dictDataMapper", session.getMapper(DictDataMapper.class));
            DictDataController controller = new DictDataController();
            ReflectionTestUtils.setField(controller, "dictDataService", service);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/system/dict-data/simple-list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[?(@.dictType == 'supply_chain_routing_mode')].value")
                            .value(fixedDisabled ? contains("AUTO") : contains("AUTO", "FIXED")))
                    .andExpect(jsonPath("$.data[?(@.dictType == 'supply_chain_routing_mode')].label")
                            .value(fixedDisabled ? contains("人工自动") : contains("自动路由", "固定供应商")))
                    .andExpect(jsonPath("$.data[?(@.dictType == 'supply_chain_routing_rule_status')].value")
                            .value(contains("ENABLED", "DISABLED")))
                    .andExpect(jsonPath("$.data[?(@.dictType == 'supply_chain_routing_rule_status')].label")
                            .value(contains("启用", "停用")))
                    .andExpect(jsonPath("$.data[?(@.dictType == 'supply_chain_routing_rule_status')].colorType")
                            .value(contains("success", "info")));
        }
    }

    private static void assertRoutingDictionaries() throws SQLException {
        assertEquals(2, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN ('supply_chain_routing_mode', 'supply_chain_routing_rule_status')
                  AND status = 0 AND deleted = b'0'
                """));
        assertEquals("AUTO:自动路由:1:0:,FIXED:固定供应商:2:0:", queryText("""
                SELECT GROUP_CONCAT(CONCAT(value, ':', label, ':', sort, ':', status, ':', color_type)
                                    ORDER BY sort SEPARATOR ',')
                FROM system_dict_data
                WHERE dict_type = 'supply_chain_routing_mode' AND deleted = b'0'
                """));
        assertEquals("ENABLED:启用:1:0:success,DISABLED:停用:2:0:info", queryText("""
                SELECT GROUP_CONCAT(CONCAT(value, ':', label, ':', sort, ':', status, ':', color_type)
                                    ORDER BY sort SEPARATOR ',')
                FROM system_dict_data
                WHERE dict_type = 'supply_chain_routing_rule_status' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '015-routing-rule-dictionaries' AND AUTHOR = 'supply-chain'
                """));
    }

    private static void updateLiquibase() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String queryText(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
