package com.aifuli.module.supplychain.infrastructure.configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 只读视图边界：metadata / ResultSet 不得通过连接、语句或 unwrap 泄漏驱动对象。
 * 每个可能发包的调用共享连接前后守卫，关闭不受业务期限阻断。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#2026-10-09-代码评审修复协议优先于下文早期示意代码。
 */
final class StandardBudgetJdbcViews {
    private StandardBudgetJdbcViews() { }

    static DatabaseMetaData metadata(DatabaseMetaData raw, StandardBudgetConnectionWrapper connection) {
        return view(DatabaseMetaData.class, raw, connection, null);
    }

    static ResultSet resultSet(ResultSet raw, StandardBudgetConnectionWrapper connection, Statement statement) {
        return raw == null ? null : view(ResultSet.class, raw, connection, statement);
    }

    private static <T> T view(Class<T> type, T raw, StandardBudgetConnectionWrapper connection, Statement statement) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "BudgetJdbcView[" + type.getSimpleName() + "]";
                    default -> throw new SQLException("不支持的对象方法");
                };
            }
            if (name.equals("unwrap")) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) return iface.cast(proxy);
                throw new SQLException("预算 JDBC 视图禁止解包到底层驱动");
            }
            if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
            if (name.equals("getConnection")) return connection;
            // metadata ResultSet 的原始 Statement 不具备包装身份，返回 null 而不是泄漏驱动。
            if (name.equals("getStatement")) return statement;
            if (name.equals("close") || name.equals("isClosed")) {
                try { return invoke(raw, method, args); }
                catch (SQLException | RuntimeException failure) {
                    connection.recordCleanupFailure(failure);
                    throw failure;
                }
            }
            connection.requireBusinessMode();
            Object value = connection.guarded(() -> invoke(raw, method, args));
            if (value instanceof ResultSet result) return resultSet(result, connection, statement);
            return value;
        }));
    }

    private static Object invoke(Object raw, Method method, Object[] args) throws SQLException {
        try {
            return method.invoke(raw, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sql) throw sql;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new SQLException("JDBC 视图委派失败", cause);
        } catch (IllegalAccessException failure) {
            throw new SQLException("JDBC 接口不可访问", failure);
        }
    }
}
