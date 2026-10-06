package com.aifuli.module.supplychain.testsupport;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 只在真实SQL前后设置确定性屏障，从不替换SQL结果；见桥接设计§13.2。 */
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
public final class DeterministicSqlInterceptor implements Interceptor {
    public interface Hook {
        default void before(String statement, String sql, Object parameters) throws Exception { }
        default void after(String statement, String sql, Object parameters) throws Exception { }
    }
    private volatile Hook hook = new Hook() { };
    public void use(Hook value) { hook = value; }
    public void reset() { hook = new Hook() { }; }
    @Override public Object intercept(Invocation invocation) throws Throwable {
        var statement = (MappedStatement) invocation.getArgs()[0];
        Object parameters = invocation.getArgs()[1];
        String sql = statement.getBoundSql(parameters).getSql();
        Hook current = hook;
        current.before(statement.getId(), sql, parameters);
        Object result = invocation.proceed();
        current.after(statement.getId(), sql, parameters);
        return result;
    }
    public static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(20, TimeUnit.SECONDS)) { throw new AssertionError("确定性SQL屏障超时"); }
    }
}
