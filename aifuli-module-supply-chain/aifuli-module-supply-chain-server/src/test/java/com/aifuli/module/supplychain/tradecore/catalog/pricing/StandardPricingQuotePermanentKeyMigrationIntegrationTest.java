package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.PreconditionFailedException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 在真实 MySQL 上验证 `051-standard-quote-permanent-key` 的分阶段升级：
 * 非法历史（同项目键多条报价、归属链断裂、ISSUED 却带消费引用、CONSUMED 指向不存在的订单）在破坏性 DDL 前
 * 触发 Liquibase HALT 且旧数据/旧列不被修改；合法历史升级后用户列删除、项目订单唯一键生效、
 * 历史行原样保留。空库全量升级与二次幂等由 {@code SupplyChainLiquibaseIntegrationTest} 覆盖。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#4-增量DDL与历史阻断检查。
 */
@Testcontainers(disabledWithoutDocker = true)
class StandardPricingQuotePermanentKeyMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    @Test
    void haltsOnIllegalHistoryThenUpgradesLegalHistory() throws Exception {
        // 1. 先迁移到 050（051 之前共 52 个变更集），保持旧 schema 供历史数据构造。
        liquibaseUpdate(52);
        assertThat(columnExists("buyer_user_id")).isTrue();

        insertOwnershipChain();

        // 2. 场景A：同一项目键存在两条报价（旧 15 分钟重签语义可能产生），预条件必须 HALT。
        insertLegacyQuote(1L, "token-dup-1", "ORD-DUP", "ISSUED", null, 801L);
        insertLegacyQuote(2L, "token-dup-2", "ORD-DUP", "ISSUED", null, 801L);
        assertNextChangesetHalts();
        assertThat(queryCount("SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE buyer_order_no = 'ORD-DUP'"))
                .isEqualTo(2);
        assertThat(columnExists("buyer_user_id")).isTrue();
        deleteAllQuotes();

        // 3. 场景B：ISSUED 状态却已带消费引用，状态与消费字段矛盾，HALT。
        insertLegacyQuote(1L, "token-st-incons", "ORD-ST", "ISSUED", 999L, 801L);
        assertNextChangesetHalts();
        assertThat(queryCount("SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE buyer_order_no = 'ORD-ST'"))
                .isEqualTo(1);
        assertThat(columnExists("buyer_user_id")).isTrue();
        deleteAllQuotes();

        // 4. 场景C：CONSUMED 指向不存在的订单，消费引用与订单事实不一致，HALT。
        insertLegacyQuote(1L, "token-orphan", "ORD-ORPHAN", "CONSUMED", 999L, 801L);
        assertNextChangesetHalts();
        assertThat(queryCount("SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE buyer_order_no = 'ORD-ORPHAN'"))
                .isEqualTo(1);
        assertThat(columnExists("buyer_user_id")).isTrue();
        deleteAllQuotes();

        // 5. 场景D：报价归属链断裂（项目不存在）触发第2类归属预条件 HALT。
        insertOrphanProjectQuote(3L, "token-orphan-prj", "ORD-ORPHAN-PRJ");
        assertNextChangesetHalts();
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE buyer_order_no = 'ORD-ORPHAN-PRJ'"))
                .isEqualTo(1);
        assertThat(columnExists("buyer_user_id")).isTrue();
        deleteAllQuotes();

        // 6. 清空非法历史后插入合法历史（ISSUED、归属链完整、旧失效时间已过），升级成功。
        insertLegacyQuote(1L, "token-legal", "ORD-LEGAL", "ISSUED", null, 801L);
        liquibaseUpdate(1);

        // 7. 升级结果：用户列删除、失效时间可空、项目订单唯一键存在、历史行与冻结金额原样保留。
        assertThat(columnExists("buyer_user_id")).isFalse();
        assertThat(isNullable("expires_at")).isTrue();
        assertThat(queryCount("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sc_standard_pricing_quote'
                  AND INDEX_NAME = 'uk_sc_standard_quote_project_order'
                """)).isEqualTo(2);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_standard_pricing_quote
                WHERE buyer_order_no = 'ORD-LEGAL' AND quote_token = 'token-legal'
                  AND user_payable_amount_cents = 10100 AND expires_at < NOW(3)
                """)).isEqualTo(1);

        // 8. 已应用后重复执行必须幂等，不再重复改动。
        liquibaseUpdate(1);
        assertThat(columnExists("buyer_user_id")).isFalse();
    }

    /** 破坏性 DDL 前的 HALT 预条件失败必须能从异常因果链定位，且 051 未被执行。 */
    private static void assertNextChangesetHalts() {
        Throwable error = catchThrowable(() -> liquibaseUpdate(1));
        assertThat(error).as("预条件冲突必须 HALT").isNotNull();
        assertThat(hasPreconditionFailure(error)).isTrue();
    }

    private static boolean hasPreconditionFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PreconditionFailedException) {
                return true;
            }
        }
        return false;
    }

    /** 预条件核对所需的完整归属链：平台SKU → 分销商品 → 项目 → 项目商品。 */
    private static void insertOwnershipChain() throws SQLException {
        execute("""
                INSERT INTO sc_platform_sku(id, sku_code, spu_id, sku_name, fulfillment_type, face_value,
                    currency, result_schema_code, result_schema_version, status, version,
                    suggested_sale_price, suggested_sale_price_rule_json, created_at, updated_at)
                VALUES (901, 'MOVIE_051', 1, '051迁移验证SKU', 'VOUCHER_CODE', 10000, 'CNY',
                    'STD_RESULT', 1, 10, 0, 10000,
                    '{"mode":"FIXED","fixedPriceCents":10000}', NOW(3), NOW(3))
                """);
        execute("""
                INSERT INTO sc_buyer_product(id, buyer_product_code, buyer_id, platform_sku_id, status, version,
                    created_at, updated_at)
                VALUES (911, 'BP_051', 1, 901, 10, 0, NOW(3), NOW(3))
                """);
        execute("""
                INSERT INTO sc_buyer_project(id, project_code, project_name, buyer_id, catalog_mode, status, version,
                    created_at, updated_at)
                VALUES (801, 'PRJ_051', '051迁移验证项目', 1, 'MANAGED', 10, 0, NOW(3), NOW(3))
                """);
        execute("""
                INSERT INTO sc_project_listing(id, listing_code, project_id, buyer_product_id, status, version,
                    created_at, updated_at)
                VALUES (851, 'PL_051', 801, 911, 10, 0, NOW(3), NOW(3))
                """);
    }

    /** 在 035 旧 schema（用户列、失效时间均非空）上构造一条历史报价；projectId 指定归属项目。 */
    private static void insertLegacyQuote(long id, String token, String orderNo, String status, Long consumedOrderId,
                                          long projectId)
            throws SQLException {
        Timestamp consumedAt = "CONSUMED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO sc_standard_pricing_quote(id, quote_token, source_system, external_tenant_id,
                         buyer_id, project_id, listing_id, platform_sku_id, platform_sku_code,
                         project_pricing_version_id, buyer_user_id, buyer_order_no, quantity,
                         distribution_unit_price_cents, sales_unit_price_cents, product_subtotal_cents,
                         service_fee_cents, user_payable_amount_cents, tax_metadata_json, status,
                         expires_at, consumed_order_id, consumed_at, created_at)
                     VALUES (?, ?, 'AIFULI_MALL', 'tenant-legacy', 1, ?, 851, 901, 'MOVIE_051',
                         9, '13800138000', ?, 1, 9800, 10000, 10000, 100, 10100,
                         '{"taxIncluded":false,"taxRateCode":"NON_TAXABLE","taxRateLabel":"不征税"}', ?,
                         DATE_SUB(NOW(3), INTERVAL 15 MINUTE), ?, ?, NOW(3))
                     """)) {
            statement.setLong(1, id);
            statement.setString(2, token);
            statement.setLong(3, projectId);
            statement.setString(4, orderNo);
            statement.setString(5, status);
            if (consumedOrderId == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, consumedOrderId);
            }
            if (consumedAt == null) {
                statement.setNull(7, java.sql.Types.TIMESTAMP);
            } else {
                statement.setTimestamp(7, consumedAt);
            }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** 归属链断裂的历史报价：project_id 指向不存在的项目，供第2类归属预条件 HALT 验证。 */
    private static void insertOrphanProjectQuote(long id, String token, String orderNo) throws SQLException {
        insertLegacyQuote(id, token, orderNo, "ISSUED", null, 999L);
    }

    private static void deleteAllQuotes() throws SQLException {
        execute("DELETE FROM sc_standard_pricing_quote");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(sql)).isNotNegative();
        }
    }

    private static void liquibaseUpdate(int maxCount) throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(maxCount, new Contexts(), new LabelExpression());
            }
        }
    }

    private static boolean columnExists(String column) throws SQLException {
        return queryCount("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sc_standard_pricing_quote'
                  AND COLUMN_NAME = '%s'
                """.formatted(column)) == 1;
    }

    private static boolean isNullable(String column) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT IS_NULLABLE FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sc_standard_pricing_quote'
                       AND COLUMN_NAME = ?
                     """)) {
            statement.setString(1, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return "YES".equalsIgnoreCase(resultSet.getString(1));
            }
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
