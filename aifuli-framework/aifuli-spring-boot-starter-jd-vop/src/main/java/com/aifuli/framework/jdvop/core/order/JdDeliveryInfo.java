package com.aifuli.framework.jdvop.core.order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 京东订单配送信息。
 *
 * @param jdOrderId 京东订单号
 * @param packages 配送包裹
 * @param tracks 配送轨迹
 */
public record JdDeliveryInfo(
        Long jdOrderId,
        List<PackageInfo> packages,
        List<TrackInfo> tracks) {

    public JdDeliveryInfo {
        packages = packages == null ? List.of() : List.copyOf(packages);
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    /**
     * 京东配送包裹。
     *
     * @param jdOrderId 京东子订单号
     * @param parentJdOrderId 京东父订单号
     * @param deliveryOrderId 京东配送单号，也是稳定包裹号和物流单号
     * @param deliveryCarrier 承运商名称
     */
    public record PackageInfo(
            Long jdOrderId,
            Long parentJdOrderId,
            String deliveryOrderId,
            String deliveryCarrier) {
    }

    /**
     * 京东配送轨迹。
     *
     * @param time 轨迹时间
     * @param content 轨迹内容
     * @param operator 操作方
     */
    public record TrackInfo(LocalDateTime time, String content, String operator) {
    }

}
