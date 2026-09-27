package com.aifuli.module.supplychain.infrastructure.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;

/**
 * Liquibase 建列后、服务接单前给历史父对象补不可变短序号。迁移可重入，任何错误使启动失败。
 * 先锁各全局作用域，再按稳定 ID 处理；符合新根格式的旧对象优先保留自己的数字段。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BusinessCodeLegacyBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ScopedBusinessCodeService codes;

    public BusinessCodeLegacyBackfill(@Qualifier("supplyChainDataSource") DataSource dataSource,
                                      @Qualifier("supplyChainTransactionManager") PlatformTransactionManager txManager,
                                      ScopedBusinessCodeService codes) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(txManager);
        this.codes = codes;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillNow();
    }

    /** 也供发布前迁移验证和真实 MySQL 集成测试调用。 */
    public void backfillNow() {
        transaction.executeWithoutResult(status -> {
            backfillParent("sc_platform_spu", "spu_code", "code_scope_no",
                    ScopedBusinessCodeService.Kind.SPU, "SPU");
            backfillParent("sc_buyer", "buyer_code", "code_scope_no",
                    ScopedBusinessCodeService.Kind.BYR, "BYR");
            backfillCategories();
        });
    }

    private void backfillParent(String table, String codeColumn, String noColumn,
                                ScopedBusinessCodeService.Kind kind, String prefix) {
        codes.lockRootScope(kind);
        List<LegacyRow> rows = rows(table, codeColumn, noColumn);
        checkCapacity(table, rows);
        rows.stream().filter(row -> row.number() != null)
                .forEach(row -> codes.ensureExistingRootClaim(kind, row.number()));
        List<LegacyRow> missing = rows.stream().filter(row -> row.number() == null).toList();
        List<LegacyRow> normalized = missing.stream()
                .filter(row -> preferred(prefix, row.code()) != null).toList();
        List<LegacyRow> other = missing.stream()
                .filter(row -> preferred(prefix, row.code()) == null).toList();
        for (LegacyRow row : normalized) {
            assign(table, noColumn, row, codes.claimLegacyParent(kind, preferred(prefix, row.code())));
        }
        for (LegacyRow row : other) {
            assign(table, noColumn, row, codes.claimLegacyParent(kind, null));
        }
        log.info("历史业务编号补号 table={} total={} assigned={}", table, rows.size(), missing.size());
    }

    private void backfillCategories() {
        codes.lockRootScope(ScopedBusinessCodeService.Kind.CAT_NODE);
        List<LegacyRow> rows = rows("sc_product_category", "category_code", "category_node_no");
        checkCapacity("sc_product_category", rows);
        rows.stream().filter(row -> row.number() != null)
                .forEach(row -> codes.ensureExistingRootClaim(ScopedBusinessCodeService.Kind.CAT_NODE, row.number()));
        int assigned = 0;
        for (LegacyRow row : rows) {
            if (row.number() == null) {
                assign("sc_product_category", "category_node_no", row, codes.allocateCategoryNodeNo());
                assigned++;
            }
        }
        log.info("历史分类节点补号 total={} assigned={}", rows.size(), assigned);
    }

    private List<LegacyRow> rows(String table, String codeColumn, String noColumn) {
        // 三组表名和列名仅来自本类固定常量，绝不拼接请求参数。
        return jdbc.query("SELECT id, " + codeColumn + ", " + noColumn + " FROM " + table + " ORDER BY id",
                (rs, index) -> new LegacyRow(rs.getLong("id"), rs.getString(codeColumn),
                        rs.getObject(noColumn, Short.class)));
    }

    private void assign(String table, String noColumn, LegacyRow row, int number) {
        int updated = jdbc.update("UPDATE " + table + " SET " + noColumn + " = ? WHERE id = ? AND "
                + noColumn + " IS NULL", number, row.id());
        if (updated != 1) {
            throw new IllegalStateException("历史短序号补号并发冲突: table=" + table + ", id=" + row.id());
        }
    }

    private static void checkCapacity(String table, List<LegacyRow> rows) {
        if (rows.size() > ScopedBusinessCodeFormat.MAX_NO) {
            List<Long> overflow = rows.stream().sorted(Comparator.comparingLong(LegacyRow::id))
                    .skip(ScopedBusinessCodeFormat.MAX_NO).limit(5).map(LegacyRow::id).toList();
            throw new IllegalStateException("历史父对象超过三位短序号容量：table=" + table
                    + "，总数=" + rows.size() + "，超出对象ID示例=" + overflow);
        }
    }

    private static Integer preferred(String prefix, String code) {
        try {
            return ScopedBusinessCodeFormat.parseRoot(prefix, code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record LegacyRow(long id, String code, Short number) {
    }
}
