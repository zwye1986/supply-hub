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
import liquibase.exception.LiquibaseException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在真实 MySQL 上验证 system 管理端表单字典：首次全量写入、二次幂等。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 * 路由模式与状态契约见
 * {@code docs/superpowers/specs/2026-10-12-routing-rule-dictionaries-design.md#6-验证与验收}。
 * 完整主清单的录入菜单与admin授权边界见
 * {@code docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#5-数据与交付}。
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
            statement.execute("""
                    INSERT INTO system_menu (id, application_code, name, permission, type, parent_id, path)
                    VALUES (810, 'supply-chain-admin', '订单管理', 'supply-chain:order:query', 2, 1, 'orders'),
                           (820, 'mall-admin', '其它应用录入', 'supply-chain:order:create', 3, 0, ''),
                           (5, 'supply-chain-admin', '错误父目录订单页', 'supply-chain:order:query', 2, 820, 'orders')
                    """);
            // 只建迁移读取/写入的真实字段；无额外唯一键，不能靠INSERT IGNORE掩盖重复授权。
            statement.execute("""
                    CREATE TABLE system_users (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      username VARCHAR(30) NOT NULL,
                      status TINYINT NOT NULL DEFAULT 0,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      tenant_id BIGINT NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_role (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      code VARCHAR(100) NOT NULL,
                      status TINYINT NOT NULL DEFAULT 0,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      tenant_id BIGINT NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_user_role (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      role_id BIGINT NOT NULL,
                      deleted BIT(1) NULL DEFAULT b'0',
                      tenant_id BIGINT NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_role_menu (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      role_id BIGINT NOT NULL,
                      menu_id BIGINT NOT NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      tenant_id BIGINT NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            // admin/super_admin只是精确编码；普通同名用户、大小写近似、停用/删除和错租户都不是目标。
            statement.execute("""
                    INSERT INTO system_users (id, username, status, deleted, tenant_id) VALUES
                    (70, 'admin', 0, b'0', 9), (71, 'ADMIN', 0, b'0', 19),
                    (72, 'other', 0, b'0', 29), (73, 'admin', 1, b'0', 39),
                    (74, 'admin', 0, b'1', 49), (75, 'admin', 0, b'0', 59),
                    (76, 'admin', 0, b'0', 69), (77, 'admin', 0, b'0', 79),
                    (78, 'admin', 0, b'0', 89), (79, 'admin', 0, b'0', 99),
                    (80, 'admin', 0, b'0', 109), (81, 'admin', 0, b'0', 119)
                    """);
            statement.execute("""
                    INSERT INTO system_role (id, code, status, deleted, tenant_id) VALUES
                    (509, 'super_admin', 0, b'0', 9), (510, 'super_admin', 0, b'0', 19),
                    (511, 'super_admin', 0, b'0', 29), (512, 'super_admin', 0, b'0', 39),
                    (513, 'super_admin', 0, b'0', 49), (514, 'super_admin', 0, b'1', 59),
                    (515, 'super_admin', 1, b'0', 69), (516, 'super_admin', 0, b'0', 79),
                    (517, 'super_admin', 0, b'0', 89), (518, 'SUPER_ADMIN', 0, b'0', 99),
                    (519, 'super_admin', 0, b'0', 129), (520, 'common', 0, b'0', 119),
                    (521, 'super_admin', 0, b'0', 139), (602, 'common', 0, b'0', 9)
                    """);
            statement.execute("""
                    INSERT INTO system_user_role (user_id, role_id, deleted, tenant_id) VALUES
                    (70, 509, b'0', 9), (70, 509, b'0', 9), (70, 602, b'0', 9),
                    (71, 510, b'0', 19), (72, 511, b'0', 29), (73, 512, b'0', 39),
                    (74, 513, b'0', 49), (75, 514, b'0', 59), (76, 515, b'0', 69),
                    (77, 516, b'1', 79), (78, 517, b'0', 189), (79, 518, b'0', 99),
                    (80, 519, b'0', 109), (81, 520, b'0', 119)
                    """);
            statement.execute("""
                    INSERT INTO system_role_menu (role_id, menu_id, creator, tenant_id)
                    VALUES (602, 820, '保留既有授权', 9)
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

    /** 只授权已有admin超管，幂等且不污染其它应用、普通角色或租户。设计见录入设计 §5.1、§5.2。 */
    @Test
    void orderEntryMenuAndAdminGrantAreIdempotentAndScoped() throws Exception {
        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM system_menu
                WHERE CAST(application_code AS BINARY) = CAST('supply-chain-admin' AS BINARY)
                  AND CAST(permission AS BINARY) = CAST('supply-chain:order:create' AS BINARY)
                  AND name = '录入订单' AND parent_id = 810 AND type = 3 AND status = 0
                  AND visible = b'1' AND keep_alive = b'0' AND deleted = b'0'
                """));
        long menuId = Long.parseLong(queryText("""
                SELECT id FROM system_menu
                WHERE CAST(application_code AS BINARY) = CAST('supply-chain-admin' AS BINARY)
                  AND permission = 'supply-chain:order:create' AND deleted = b'0'
                """));
        assertEquals("509:9", queryText("SELECT GROUP_CONCAT(CONCAT(role_id, ':', tenant_id) ORDER BY role_id)"
                + " FROM system_role_menu WHERE menu_id=" + menuId + " AND deleted=b'0'"));
        assertEquals("其它应用录入", queryText("SELECT name FROM system_menu WHERE id=820"));
        assertEquals("保留既有授权", queryText("SELECT creator FROM system_role_menu WHERE role_id=602 AND menu_id=820"));

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE system_menu SET name='旧录入', parent_id=0, status=1, keep_alive=b'1' WHERE id=" + menuId);
            statement.execute("UPDATE system_role_menu SET deleted=b'1' WHERE menu_id=" + menuId + " AND tenant_id=9");
            // 错误租户的既有关系不能替代正确租户授权，也不应被本迁移擅自修正。
            statement.execute("INSERT INTO system_role_menu(role_id,menu_id,tenant_id) VALUES (509," + menuId + ",0)");
            for (int replay = 0; replay < 2; replay++) {
                for (String script : new String[]{"016-supply-chain-order-entry-menu.sql", "017-supply-chain-order-entry-admin-grant.sql"}) {
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                            "db/changelog/releases/1.0.0/" + script), StandardCharsets.UTF_8));
                }
            }
            statement.execute("DELETE FROM DATABASECHANGELOG WHERE ID IN ('016-supply-chain-order-entry-menu','017-supply-chain-order-entry-admin-grant')");
        }
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        updateLiquibase();
        updateLiquibase();
        assertEquals("录入订单:810:0:0", queryText("SELECT CONCAT(name,':',parent_id,':',status,':',keep_alive+0) FROM system_menu WHERE id=" + menuId));
        assertEquals("509:0,509:9", queryText("SELECT GROUP_CONCAT(CONCAT(role_id,':',tenant_id) ORDER BY tenant_id)"
                + " FROM system_role_menu WHERE menu_id=" + menuId + " AND deleted=b'0'"));
        assertEquals(1, queryCount("SELECT COUNT(*) FROM system_role_menu WHERE menu_id=" + menuId + " AND deleted=b'1'"));
        assertEquals(1, queryCount("SELECT COUNT(*) FROM system_menu WHERE application_code='supply-chain-admin' AND permission='supply-chain:order:create' AND deleted=b'0'"));
        assertEquals(2, queryCount("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID IN ('016-supply-chain-order-entry-menu','017-supply-chain-order-entry-admin-grant') AND EXECTYPE='EXECUTED'"));
    }

    /** 缺页面或菜单歧义不得静默登记完成；失败后修复前置数据可以继续迁移。 */
    @Test
    void orderEntryMenuHaltsWhenPrerequisitesAreMissingOrAmbiguous() throws Exception {
        updateLiquibase();
        String[] invalidFixtures = {
                "UPDATE system_menu SET deleted=b'1' WHERE id=810",
                """
                INSERT INTO system_menu(id,application_code,permission,type,parent_id)
                VALUES (9000,'supply-chain-admin','supply-chain:order:query',2,1)
                """,
                """
                INSERT INTO system_menu(id,application_code,permission,type,parent_id)
                VALUES (9000,'supply-chain-admin','supply-chain:order:create',3,810)
                """
        };
        for (String invalidFixture : invalidFixtures) {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute(invalidFixture);
                statement.execute("DELETE FROM DATABASECHANGELOG WHERE ID IN ('016-supply-chain-order-entry-menu','017-supply-chain-order-entry-admin-grant')");
                Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
                try {
                    LiquibaseException failure = assertThrows(LiquibaseException.class,
                            AdminFormDictionaryMigrationIntegrationTest::updateLiquibase);
                    assertTrue(failure.getMessage().contains("016-supply-chain-order-entry-menu"));
                    assertEquals(0, queryCount("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID IN ('016-supply-chain-order-entry-menu','017-supply-chain-order-entry-admin-grant')"));
                } finally {
                    // 仅清理本用例在disposable库制造的歧义；不影响其它迁移回归。
                    statement.execute("UPDATE system_menu SET deleted=b'0' WHERE id=810");
                    statement.execute("DELETE FROM system_menu WHERE id=9000");
                    Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
                    updateLiquibase();
                }
            }
        }
    }

    /** 其它近似admin/超管不能替代用户指定的有效绑定，也不能绕过HALT。 */
    @Test
    void orderEntryGrantHaltsWithoutEligibleAdmin() throws Exception {
        updateLiquibase();
        int grantsBefore = queryCount("SELECT COUNT(*) FROM system_role_menu");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE system_users SET status=1 WHERE id=70");
            statement.execute("DELETE FROM DATABASECHANGELOG WHERE ID='017-supply-chain-order-entry-admin-grant'");
            Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
            try {
                LiquibaseException failure = assertThrows(LiquibaseException.class,
                        AdminFormDictionaryMigrationIntegrationTest::updateLiquibase);
                assertTrue(failure.getMessage().contains("017-supply-chain-order-entry-admin-grant"));
                assertEquals(0, queryCount("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='017-supply-chain-order-entry-admin-grant'"));
                assertEquals(grantsBefore, queryCount("SELECT COUNT(*) FROM system_role_menu"));
            } finally {
                statement.execute("UPDATE system_users SET status=0 WHERE id=70");
                Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
                updateLiquibase();
            }
        }
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
