package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_DELIVERY_TYPE_INVALID;

/**
 * 平台 SPU 配送履约模式，对应 {@code sc_platform_spu.delivery_type} DDL COMMENT
 * 「配送履约模式：VIRTUAL虚拟发货, EXPRESS物流快递, PICKUP自提核销」。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#21-平台-spu-扩展-sc_platform_spu}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum DeliveryType {
    VIRTUAL("虚拟发货"),
    EXPRESS("物流快递"),
    PICKUP("自提核销");

    /** 管理端与 DDL COMMENT 共用的中文名称。 */
    private final String chineseName;

    public String code() {
        return name();
    }

    /**
     * 解析配送模式；空值按 DDL 默认值回落到 {@link #VIRTUAL}，未知取值抛业务错误码而非裸 IllegalArgumentException。
     */
    public static DeliveryType parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return VIRTUAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DeliveryType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw exception(PRODUCT_DELIVERY_TYPE_INVALID);
    }
}
