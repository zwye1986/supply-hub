package com.aifuli.framework.jdvop.core.order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 京东 VOP 订单详情摘要。
 *
 * @param jdOrderId 京东订单号
 * @param thirdOrderId 商城侧第三方订单号
 * @param parentType 京东父子单类型
 * @param parentJdOrderId 京东父订单号
 * @param childJdOrderIdList 京东子订单号
 * @param skuInfoList 京东订单商品
 * @param deliveryTime 京东出库或厂家确认发货时间
 * @param orderState 京东订单状态
 */
public record JdOrderDetail(
        Long jdOrderId,
        String thirdOrderId,
        Integer parentType,
        Long parentJdOrderId,
        List<Long> childJdOrderIdList,
        List<SkuInfo> skuInfoList,
        LocalDateTime deliveryTime,
        OrderState orderState) {

    public JdOrderDetail {
        childJdOrderIdList = childJdOrderIdList == null ? List.of() : List.copyOf(childJdOrderIdList);
        skuInfoList = skuInfoList == null ? List.of() : List.copyOf(skuInfoList);
    }

    /**
     * 京东订单商品摘要。
     *
     * @param skuId 京东 SKU 编号
     * @param skuNum 商品数量
     * @param type 商品类型：0 普通、1 附件、2 赠品、3 延保
     */
    public record SkuInfo(Long skuId, Integer skuNum, Integer type) {
    }

    /**
     * 京东订单状态摘要。
     *
     * @param confirmState 确认状态
     * @param cancelOrderState 取消状态
     * @param deliveryState 配送状态
     * @param jdOrderState 京东订单状态
     */
    public record OrderState(
            Integer confirmState,
            Integer cancelOrderState,
            Integer deliveryState,
            Integer jdOrderState) {
    }

}
