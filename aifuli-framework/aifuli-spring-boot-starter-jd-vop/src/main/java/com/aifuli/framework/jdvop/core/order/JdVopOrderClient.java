package com.aifuli.framework.jdvop.core.order;

import java.util.List;

/**
 * 京东 VOP 订单客户端。
 */
public interface JdVopOrderClient {

    /**
     * 提交京东订单，使用请求中的 thirdOrderId 保证供应商侧幂等。
     */
    JdOrderResult submitOrder(JdOrderSubmitRequest request);

    /**
     * 按商城侧第三方订单号反查京东订单。
     */
    List<JdOrderDetail> queryOrderByThirdOrderId(String thirdOrderId);

    /**
     * 按京东订单号查询订单详情。
     */
    List<JdOrderDetail> queryOrderByJdOrderId(Long jdOrderId);

    /**
     * 按京东订单号查询配送包裹和配送轨迹。
     */
    JdDeliveryInfo queryDeliveryInfo(Long jdOrderId);

    /**
     * 取消京东订单，供商城取消及退款编排使用。
     */
    boolean cancelOrder(Long jdOrderId, String cancelReason);

}
