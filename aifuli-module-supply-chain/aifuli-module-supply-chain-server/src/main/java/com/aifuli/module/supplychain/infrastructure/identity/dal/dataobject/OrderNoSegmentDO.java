package com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;

/** 永久预留号段记录，不修改或回收。设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#41-mysql永久预留与所有权}。 */
@Data
@TableName("sc_order_no_segment")
public class OrderNoSegmentDO {
    /** 订单编号号段所属日期，Asia/Shanghai */
    private LocalDate sequenceDate;
    /** 已提交预留号段的起始流水，含边界 */
    private Integer startValue;
    /** 已提交预留号段的结束流水，含边界 */
    private Integer endValue;
    /** 号段独占分配器本次启动标识 */
    private String ownerId;
    /** 订单编号号段预留记录创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
