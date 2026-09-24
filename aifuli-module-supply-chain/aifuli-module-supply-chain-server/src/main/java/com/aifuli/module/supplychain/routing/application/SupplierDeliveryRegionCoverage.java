package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.ip.core.Area;
import com.aifuli.framework.ip.core.utils.AreaUtils;
import com.aifuli.module.supplychain.tradecore.supply.domain.DeliveryRegionType;

import java.util.List;
import java.util.Set;

/**
 * 供应商配送行政区划覆盖判定：精确命中或配置节点为收货节点的祖先。
 * 未知行政区划只接受精确命中，避免以编码前缀猜测真实层级。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#5-配送区域}。
 */
public final class SupplierDeliveryRegionCoverage {

    private SupplierDeliveryRegionCoverage() {
    }

    public static boolean covers(String deliveryRegionType, List<String> configuredAdcodes,
                                 String deliveryAdcode) {
        if (deliveryAdcode == null) {
            return true;
        }
        if (DeliveryRegionType.NATIONWIDE.name().equals(deliveryRegionType)) {
            return true;
        }
        if (!DeliveryRegionType.DESIGNATED.name().equals(deliveryRegionType)
                || configuredAdcodes == null || configuredAdcodes.isEmpty()) {
            return false;
        }
        Set<String> configured = Set.copyOf(configuredAdcodes);
        if (configured.contains(deliveryAdcode)) {
            return true;
        }
        Area area = AreaUtils.getArea(Integer.valueOf(deliveryAdcode));
        while (area != null) {
            area = area.getParent();
            if (area != null && configured.contains(String.valueOf(area.getId()))) {
                return true;
            }
        }
        return false;
    }
}
