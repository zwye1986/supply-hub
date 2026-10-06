package com.aifuli.module.supplychain.infrastructure.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供应链事务管理器透明状态观测装饰器。
 * <p>
 * 依据 O-S07 方案 A 设计：
 * <ul>
 *   <li>委托底层真实 {@link JdbcTransactionManager}；</li>
 *   <li>拦截 {@link #getTransaction(TransactionDefinition)} 捕获原始 {@link DefaultTransactionStatus} 引用；</li>
 *   <li>依据 {@link ConnectionHolder} 在活动事务同步中注册/复用专属 {@link Scope}，登记在途未完成 status；</li>
 *   <li>在 {@link #commit(TransactionStatus)} 与 {@link #rollback(TransactionStatus)} 的 finally 块中精准注销 status 句柄；</li>
 *   <li>支持 {@code PROPAGATION_NESTED} 保存点隔离与 {@code PROPAGATION_REQUIRES_NEW} 挂起隔离；</li>
 *   <li>不包装 status，无 Spring 私有栈反射，不新建额外事务，不引入局部 retry。</li>
 * </ul>
 * 详细设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#2026-10-06-o-s07-祖先-localrollbackonly-观测机制与-manager-装饰器设计先行用户已批准方案-a
 */
public class SupplyChainTransactionObservationManager implements ResourceTransactionManager, InitializingBean {

    private final JdbcTransactionManager delegate;
    private final DataSource dataSource;
    private final Map<TransactionStatus, Scope> handles = new ConcurrentHashMap<>();

    public SupplyChainTransactionObservationManager(JdbcTransactionManager delegate, DataSource dataSource) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
    }

    public JdbcTransactionManager getDelegate() {
        return delegate;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public Object getResourceFactory() {
        return delegate.getResourceFactory();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        delegate.afterPropertiesSet();
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        TransactionStatus status = delegate.getTransaction(definition);
        if (status instanceof DefaultTransactionStatus d && d.hasTransaction()
                && d.getTransaction() instanceof JdbcTransactionObjectSupport jdbc
                && jdbc.hasConnectionHolder() && TransactionSynchronizationManager.isSynchronizationActive()) {
            ConnectionHolder holder = jdbc.getConnectionHolder();
            Scope scope = TransactionSynchronizationManager.getSynchronizations().stream()
                    .filter(s -> s instanceof Scope p && p.owner == this && p.holder == holder)
                    .map(s -> (Scope) s)
                    .findFirst()
                    .orElse(null);
            if (scope == null) {
                scope = new Scope(this, holder);
                TransactionSynchronizationManager.registerSynchronization(scope);
            }
            scope.statuses.put(status, Boolean.TRUE);
            handles.put(status, scope);
        }
        return status;
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        try {
            delegate.commit(status);
        } finally {
            release(status);
        }
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        try {
            delegate.rollback(status);
        } finally {
            release(status);
        }
    }

    private void release(TransactionStatus status) {
        Scope scope = handles.remove(status);
        if (scope != null) {
            scope.statuses.remove(status);
        }
    }

    public static Scope registerScope(SupplyChainTransactionObservationManager owner, ConnectionHolder holder) {
        Scope scope = new Scope(owner, holder);
        TransactionSynchronizationManager.registerSynchronization(scope);
        return scope;
    }

    /**
     * 单物理连接与生命周期专属观测 Scope。
     */
    public static final class Scope implements TransactionSynchronization {
        private final SupplyChainTransactionObservationManager owner;
        private final ConnectionHolder holder;
        private final Map<TransactionStatus, Boolean> statuses = new IdentityHashMap<>();
        private boolean completed;

        public Scope(SupplyChainTransactionObservationManager owner, ConnectionHolder holder) {
            this.owner = owner;
            this.holder = holder;
        }

        public SupplyChainTransactionObservationManager getOwner() {
            return owner;
        }

        public ConnectionHolder getHolder() {
            return holder;
        }

        public Map<TransactionStatus, Boolean> getStatuses() {
            return statuses;
        }

        public boolean isCompleted() {
            return completed;
        }

        @Override
        public void afterCompletion(int status) {
            completed = true;
            statuses.clear();
        }
    }
}
