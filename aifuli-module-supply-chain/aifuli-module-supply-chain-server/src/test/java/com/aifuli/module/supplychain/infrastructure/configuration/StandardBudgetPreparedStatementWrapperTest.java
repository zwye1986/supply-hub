package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 标准预算 PreparedStatement 包装器单元测试。
 */
class StandardBudgetPreparedStatementWrapperTest {

    @Test
    void executeInstallsGuardThenDelegates() throws Exception {
        PreparedStatement delegate = mock(PreparedStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetPreparedStatementWrapper wrapper = new StandardBudgetPreparedStatementWrapper(
                delegate, connectionWrapper, context, now + TimeUnit.SECONDS.toNanos(6));
        wrapper.executeUpdate();

        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        verify(delegate).executeUpdate();
    }

    @Test
    void expiredBudgetRejectsBeforeDelegate() throws Exception {
        PreparedStatement delegate = mock(PreparedStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetPreparedStatementWrapper wrapper = new StandardBudgetPreparedStatementWrapper(
                delegate, connectionWrapper, context, now - 1000);

        assertThatThrownBy(wrapper::execute).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).execute();
    }
}
