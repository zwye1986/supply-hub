package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.framework.datasource.core.filter.DruidAdRemoveFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证旧 Druid 深绑定类已原位桩化：@Deprecated(forRemoval=true) 且构造熔断。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#32-源码原位桩代码stub契约
 */
class DeprecatedDruidStubTest {

    private static final Class<?>[] STUBS = {
            BudgetRoutingDataSource.class,
            BudgetConnectionWrapper.class,
            BudgetStatementWrapper.class,
            BudgetPreparedStatementWrapper.class,
            MySqlSocketExtractor.class,
            DruidAdRemoveFilter.class,
    };

    @Test
    void allStubsAreDeprecatedForRemoval() {
        for (Class<?> stub : STUBS) {
            Deprecated annotation = stub.getAnnotation(Deprecated.class);
            assertThat(annotation).as(stub.getSimpleName() + " 必须标注 @Deprecated").isNotNull();
            assertThat(annotation.forRemoval()).as(stub.getSimpleName() + " 必须 forRemoval=true").isTrue();
        }
    }

    @Test
    void allStubsThrowOnInstantiation() throws Exception {
        for (Class<?> stub : STUBS) {
            Constructor<?> constructor = stub.getDeclaredConstructor();
            assertThatThrownBy(constructor::newInstance)
                    .as(stub.getSimpleName() + " 构造必须熔断")
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }
}
