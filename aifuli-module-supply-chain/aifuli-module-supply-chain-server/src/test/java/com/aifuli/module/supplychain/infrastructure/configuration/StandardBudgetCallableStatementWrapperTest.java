package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 标准预算 CallableStatement 包装器单元测试。
 */
class StandardBudgetCallableStatementWrapperTest {

    @Test
    void executeAppliesGuardAndDelegates() throws Exception {
        CallableStatement delegate = mock(CallableStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetCallableStatementWrapper wrapper = new StandardBudgetCallableStatementWrapper(
                delegate, connectionWrapper, context, now + TimeUnit.SECONDS.toNanos(6));

        wrapper.execute();
        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        verify(delegate).execute();

        wrapper.registerOutParameter(1, java.sql.Types.INTEGER);
        verify(delegate).registerOutParameter(1, java.sql.Types.INTEGER);
    }
}
