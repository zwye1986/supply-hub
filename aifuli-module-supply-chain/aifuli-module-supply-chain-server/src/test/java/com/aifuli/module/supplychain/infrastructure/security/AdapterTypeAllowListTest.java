package com.aifuli.module.supplychain.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * adapterType 白名单灰度（§6.8）：配置来源、fail-closed、逗号分隔 trim。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#68-adaptertype-白名单灰度j3-沿用}。
 * 空配置拒绝一切（与 {@link SupplierEndpointPolicy} 一致的 fail-closed 姿态），避免未配置时误放生产。</p>
 */
class AdapterTypeAllowListTest {

    private static final String KEY = "aifuli.supply-chain.connector.allowed-adapter-types";

    private AdapterTypeAllowList allowList(String configured) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(KEY, "")).thenReturn(configured);
        return new AdapterTypeAllowList(environment);
    }

    @Test
    void allowsConfiguredAdapterType() {
        assertThat(allowList("QNM_GAME_ORDER_V1").isAllowed("QNM_GAME_ORDER_V1")).isTrue();
    }

    @Test
    void rejectsAdapterTypeOutsideList() {
        assertThat(allowList("QNM_GAME_ORDER_V1").isAllowed("HUA_SHENG_ORDER_V1")).isFalse();
    }

    @Test
    void emptyConfigurationRejectsEverything() {
        AdapterTypeAllowList list = allowList("");
        assertThat(list.isAllowed("QNM_GAME_ORDER_V1")).isFalse();
        assertThat(list.isAllowed("HUA_SHENG_ORDER_V1")).isFalse();
    }

    @Test
    void trimsWhitespaceAroundCommaSeparatedEntries() {
        AdapterTypeAllowList list = allowList(" QNM_GAME_ORDER_V1 , HUA_SHENG_ORDER_V1 ");
        assertThat(list.isAllowed("QNM_GAME_ORDER_V1")).isTrue();
        assertThat(list.isAllowed("HUA_SHENG_ORDER_V1")).isTrue();
    }

    @Test
    void nullAdapterTypeIsRejected() {
        assertThat(allowList("QNM_GAME_ORDER_V1").isAllowed(null)).isFalse();
    }
}
