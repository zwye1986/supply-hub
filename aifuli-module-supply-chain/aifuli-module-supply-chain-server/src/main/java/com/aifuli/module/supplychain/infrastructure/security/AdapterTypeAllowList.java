package com.aifuli.module.supplychain.infrastructure.security;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上架即发布（B1）步骤 2 连接器候选的 adapterType 白名单灰度闸门。
 *
 * <p>配置来源（非硬编码字面量，AGENTS.md §7），属性 {@code aifuli.supply-chain.connector.allowed-adapter-types}，
 * 逗号分隔。Phase 1 含 {@code QNM_GAME_ORDER_V1}（dogfood/集成测试）；{@code HUA_SHENG_ORDER_V1} 待其适配器与
 * 种子（057+）落地后翻牌。fail-closed：空配置拒绝一切，避免未配置时误放生产。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#68-adaptertype-白名单灰度j3-沿用}。</p>
 */
@Component
public class AdapterTypeAllowList {

    private final Set<String> allowedAdapterTypes;

    public AdapterTypeAllowList(Environment environment) {
        String configured = environment.getProperty(
                "aifuli.supply-chain.connector.allowed-adapter-types", "");
        this.allowedAdapterTypes = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String adapterType) {
        return adapterType != null && allowedAdapterTypes.contains(adapterType);
    }
}
