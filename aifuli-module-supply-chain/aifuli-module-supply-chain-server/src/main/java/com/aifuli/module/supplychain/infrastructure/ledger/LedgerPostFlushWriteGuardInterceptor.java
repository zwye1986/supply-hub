package com.aifuli.module.supplychain.infrastructure.ledger;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.regex.Pattern;

/**
 * flush 后写守卫（R3-03 §7.2.2(7)）：仅装配于 supplyChainSqlSessionFactory。
 * <p>
 * 当活动同步集合存在状态 FLUSHED 的 {@link LocalLedgerIntentCollector.TransactionContext} 且其
 * holder 匹配当前事务连接时，{@link Executor#update}（INSERT/UPDATE/DELETE）及锁定 SELECT
 * （按冻结词法规则判别）在执行前抛 {@link SystemIntegrityException}。FLUSHING 窗口（flush 自身
 * 写入：分组 SQL＋scheduleOnce 写入＋测试路径底座写入，无领域写）放行；普通无锁 SELECT 放行；
 * 无收集上下文事务完全透明；同线程下一事务不拦截（afterCompletion 后上下文不再存在于同步集合）。
 * <p>
 * 锁定 SELECT 词法规则（rev2 冻结，大小写不敏感，语句级含子查询/CTE/括号）：
 * 单遍状态机剥离单/双引号与反引号字面量（含双写与反斜杠转义不结束）、{@code -- }/{@code #} 行注释、
 * {@code /* ... *}{@code /} 块注释后，仅顶层（NORMAL 状态）匹配
 * {@code FOR UPDATE}／{@code FOR SHARE}／{@code LOCK IN SHARE MODE}（任意空白 {@code [ \t\r\n]+}），
 * 其后可选 {@code OF 列}／{@code NOWAIT}／{@code SKIP LOCKED} 后缀不改变锁定判定。
 * 判别每执行单遍扫描（N11：动态 SQL 展开文本每次可能不同，禁止按静态 MappedStatement 签名缓存）。
 * N12：词法剥离不处理 {@code NO_BACKSLASH_ESCAPES} 语义（反斜杠转义按默认 sql_mode 处理；
 * 该模式为理论边界，当前语句面无影响）。
 * <p>
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#722-完整技术-worker-意图收集数据库-collation-确定性排序与单次-flush-协议r3-03
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
})
public class LedgerPostFlushWriteGuardInterceptor implements Interceptor {

    /** 顶层锁定模式（任意空白 [ \t\r\n]+；大小写不敏感；OF/NOWAIT/SKIP LOCKED 后缀不影响判定）。 */
    private static final Pattern LOCKING_PATTERN = Pattern.compile(
            "(?i)^(?:FOR[\\t\\r\\n ]+UPDATE|FOR[\\t\\r\\n ]+SHARE|LOCK[\\t\\r\\n ]+IN[\\t\\r\\n ]+SHARE[\\t\\r\\n ]+MODE)");

    private final DataSource supplyChainDataSource;

    public LedgerPostFlushWriteGuardInterceptor(DataSource supplyChainDataSource) {
        this.supplyChainDataSource = supplyChainDataSource;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement statement = (MappedStatement) args[0];
        LocalLedgerIntentCollector.TransactionContext flushed = findFlushedContext();
        if (flushed == null) {
            return invocation.proceed();
        }
        boolean query = "query".equals(invocation.getMethod().getName());
        if (!query) {
            throw new SystemIntegrityException("flush后写守卫拒绝执行写语句: " + statement.getId());
        }
        BoundSql boundSql = args.length >= 6 ? (BoundSql) args[5] : statement.getBoundSql(args[1]);
        if (isLockingStatement(boundSql.getSql())) {
            throw new SystemIntegrityException("flush后写守卫拒绝执行锁定SELECT: " + statement.getId());
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    /**
     * 存在 FLUSHED 上下文且 holder 匹配当前事务连接才拦截；FLUSHING 窗口与无上下文事务完全透明。
     */
    private LocalLedgerIntentCollector.TransactionContext findFlushedContext() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Object resource = TransactionSynchronizationManager.getResource(supplyChainDataSource);
        if (!(resource instanceof ConnectionHolder holder)) {
            return null;
        }
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof LocalLedgerIntentCollector.TransactionContext context
                    && !context.completed()
                    && context.state() == LocalLedgerIntentCollector.State.FLUSHED
                    && context.holder() == holder) {
                return context;
            }
        }
        return null;
    }

    /**
     * 冻结词法判别（包私有供负控制测试）：剥离字面量与注释后顶层匹配锁定模式。
     */
    static boolean isLockingStatement(String sql) {
        if (sql == null) {
            return false;
        }
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                i = skipQuoted(sql, i, '"');
                continue;
            }
            if (c == '`') {
                i = skipBacktick(sql, i);
                continue;
            }
            if (c == '#') {
                i = skipLineComment(sql, i + 1);
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-'
                    && (i + 2 >= length || Character.isWhitespace(sql.charAt(i + 2)))) {
                i = skipLineComment(sql, i + 2);
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i + 2);
                continue;
            }
            if (LOCKING_PATTERN.matcher(sql).region(i, length).lookingAt()) {
                return true;
            }
            i++;
        }
        return false;
    }

    /** 从引号字符处跳过单/双引号字面量；双写引号与反斜杠转义不结束。 */
    private static int skipQuoted(String sql, int start, char quote) {
        int length = sql.length();
        int j = start + 1;
        while (j < length) {
            char c = sql.charAt(j);
            if (c == '\\' && j + 1 < length) {
                j += 2;
                continue;
            }
            if (c == quote) {
                if (j + 1 < length && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return length;
    }

    /** 从反引号处跳过引用标识符；两个连续反引号不结束。 */
    private static int skipBacktick(String sql, int start) {
        int length = sql.length();
        int j = start + 1;
        while (j < length) {
            if (sql.charAt(j) == '`') {
                if (j + 1 < length && sql.charAt(j + 1) == '`') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return length;
    }

    private static int skipLineComment(String sql, int start) {
        int j = start;
        int length = sql.length();
        while (j < length && sql.charAt(j) != '\n') {
            j++;
        }
        return j;
    }

    private static int skipBlockComment(String sql, int start) {
        int end = sql.indexOf("*/", start);
        return end < 0 ? sql.length() : end + 2;
    }
}
