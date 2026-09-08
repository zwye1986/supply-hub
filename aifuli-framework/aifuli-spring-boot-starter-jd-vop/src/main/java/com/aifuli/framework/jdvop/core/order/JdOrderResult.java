package com.aifuli.framework.jdvop.core.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 京东 VOP 提交订单结果。
 *
 * @param jdOrderId 京东父订单号
 * @param thirdOrderId 商城侧第三方订单号
 * @param parentType 京东父子单类型
 * @param orderTotalPrice 京东订单总金额
 * @param orderTotalFreight 京东订单运费
 * @param skuInfoList 京东返回的商品明细
 */
public record JdOrderResult(
        Long jdOrderId,
        String thirdOrderId,
        Integer parentType,
        BigDecimal orderTotalPrice,
        BigDecimal orderTotalFreight,
        List<SkuInfo> skuInfoList) {

    public JdOrderResult {
        if (jdOrderId == null || jdOrderId <= 0) {
            throw new IllegalArgumentException("京东订单号必须大于 0");
        }
        skuInfoList = skuInfoList == null ? List.of() : List.copyOf(skuInfoList);
    }

    /**
     * 京东下单商品结果。
     *
     * @param skuId 京东 SKU 编号
     * @param skuNum 京东确认数量
     * @param skuPrice 京东采购单价
     */
    public record SkuInfo(Long skuId, Integer skuNum, BigDecimal skuPrice) {
    }

}
