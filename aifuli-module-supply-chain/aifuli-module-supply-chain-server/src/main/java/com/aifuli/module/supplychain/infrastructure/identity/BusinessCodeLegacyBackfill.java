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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        log.info("历史业务编号补号预检及执行入口");
        transaction.executeWithoutResult(status -> {
            backfillParent("sc_platform_spu", "spu_code", "code_scope_no",
                    ScopedBusinessCodeService.Kind.SPU);
            backfillParent("sc_buyer", "buyer_code", "code_scope_no",
                    ScopedBusinessCodeService.Kind.BYR);
            backfillCategories();
        });
    }

    private void backfillParent(String table, String codeColumn, String noColumn,
                                ScopedBusinessCodeService.Kind kind) {
        codes.lockRootScope(kind);
        List<LegacyRow> rows = rows(table, codeColumn, noColumn);
        ParentPlan plan = preflightParent(table, kind, rows);
        rows.stream().filter(row -> row.number() != null)
                .forEach(row -> codes.ensureExistingRootClaim(kind, row.number()));
        for (LegacyCandidate candidate : plan.normalized()) {
            assign(table, noColumn, candidate.row(), codes.claimLegacyParent(kind, candidate.preferred()));
        }
        for (LegacyRow row : plan.other()) {
            assign(table, noColumn, row, codes.claimLegacyParent(kind, null));
        }
        log.info("历史业务编号补号 table={} total={} assigned={}", table, rows.size(),
                plan.normalized().size() + plan.other().size());
    }

    private void backfillCategories() {
        codes.lockRootScope(ScopedBusinessCodeService.Kind.CAT_NODE);
        List<LegacyRow> rows = rows("sc_product_category", "category_code", "category_node_no");
        preflightCategories(rows);
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

    /**
     * 写入任何短序号前，对历史可见父段、已有内部号和占号记录做全量交叉检查。
     * 失败信息直接指向冲突对象，整个补号事务随异常回滚。
     * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
     */
    private ParentPlan preflightParent(String table, ScopedBusinessCodeService.Kind kind,
                                       List<LegacyRow> rows) {
        checkCapacity(table, rows);
        Map<Integer, LegacyRow> assignedByNumber = new HashMap<>();
        Map<Integer, LegacyRow> preferredByNumber = new HashMap<>();
        List<LegacyCandidate> normalized = new ArrayList<>();
        List<LegacyRow> other = new ArrayList<>();
        for (LegacyRow row : rows) {
            if (row.number() != null) {
                LegacyRow previous = assignedByNumber.putIfAbsent((int) row.number(), row);
                if (previous != null) {
                    throw conflict(table, row.number(), previous, row);
                }
            }
            Integer preferred = ScopedBusinessCodeFormat.preferredRoot(kind.prefix(), row.code());
            if (preferred != null) {
                LegacyRow previous = preferredByNumber.putIfAbsent(preferred, row);
                if (previous != null) {
                    throw conflict(table, preferred, previous, row);
                }
            }
            if (row.number() == null) {
                if (preferred == null) {
                    other.add(row);
                } else {
                    normalized.add(new LegacyCandidate(row, preferred));
                }
            }
        }
        Set<Integer> claimed = claimedNumbers(kind);
        for (Map.Entry<Integer, LegacyRow> entry : preferredByNumber.entrySet()) {
            int number = entry.getKey();
            LegacyRow row = entry.getValue();
            LegacyRow assigned = assignedByNumber.get(number);
            if (assigned != null && assigned.id() != row.id()) {
                throw conflict(table, number, assigned, row);
            }
            if (row.number() == null && claimed.contains(number) && assigned == null) {
                throw new IllegalStateException("历史父段被占号记录占用: table=" + table
                        + "，" + describe(row) + "，号码=" + number);
            }
        }
        Set<Integer> reserved = new HashSet<>(claimed);
        reserved.addAll(assignedByNumber.keySet());
        normalized.forEach(candidate -> reserved.add(candidate.preferred()));
        if (other.size() > ScopedBusinessCodeFormat.MAX_NO - reserved.size()) {
            throw new IllegalStateException("历史父段无法唯一分配: table=" + table
                    + "，剩余号码=" + (ScopedBusinessCodeFormat.MAX_NO - reserved.size())
                    + "，待分配对象=" + other.stream().limit(5).map(BusinessCodeLegacyBackfill::describe).toList());
        }
        return new ParentPlan(normalized, other);
    }

    private void preflightCategories(List<LegacyRow> rows) {
        String table = "sc_product_category";
        checkCapacity(table, rows);
        Map<Integer, LegacyRow> assignedByNumber = new HashMap<>();
        for (LegacyRow row : rows) {
            if (row.number() != null) {
                LegacyRow previous = assignedByNumber.putIfAbsent((int) row.number(), row);
                if (previous != null) {
                    throw conflict(table, row.number(), previous, row);
                }
            }
        }
        Set<Integer> reserved = claimedNumbers(ScopedBusinessCodeService.Kind.CAT_NODE);
        reserved.addAll(assignedByNumber.keySet());
        List<LegacyRow> missing = rows.stream().filter(row -> row.number() == null).toList();
        if (missing.size() > ScopedBusinessCodeFormat.MAX_NO - reserved.size()) {
            throw new IllegalStateException("历史分类节点无法唯一分配: table=" + table
                    + "，剩余号码=" + (ScopedBusinessCodeFormat.MAX_NO - reserved.size())
                    + "，待分配对象=" + missing.stream().limit(5).map(BusinessCodeLegacyBackfill::describe).toList());
        }
    }

    private Set<Integer> claimedNumbers(ScopedBusinessCodeService.Kind kind) {
        return new HashSet<>(jdbc.queryForList("SELECT c.serial_no FROM sc_business_code_claim c "
                        + "JOIN sc_business_code_scope s ON s.id = c.scope_id "
                        + "WHERE s.code_kind = ? AND s.owner_id = 0", Integer.class, kind.name()));
    }

    private static IllegalStateException conflict(String table, int number, LegacyRow first, LegacyRow second) {
        return new IllegalStateException("历史父段重复或冲突: table=" + table + "，号码=" + number
                + "，" + describe(first) + "，" + describe(second));
    }

    private static String describe(LegacyRow row) {
        return "id=" + row.id() + ", code=" + row.code() + ", internalNo=" + row.number();
    }

    private record LegacyCandidate(LegacyRow row, Integer preferred) {
    }

    private record ParentPlan(List<LegacyCandidate> normalized, List<LegacyRow> other) {
    }

    private record LegacyRow(long id, String code, Short number) {
    }
}
