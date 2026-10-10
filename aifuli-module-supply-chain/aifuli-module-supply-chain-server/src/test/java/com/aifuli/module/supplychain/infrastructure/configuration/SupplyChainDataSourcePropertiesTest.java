package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyChainDataSourcePropertiesTest {
    @Test
    void defaultPoolProvidesCapacityForTenConcurrentOuterAndReaderTransactions() {
        assertThat(new SupplyChainDataSourceProperties().getMaximumPoolSize()).isEqualTo(40);
    }
}
