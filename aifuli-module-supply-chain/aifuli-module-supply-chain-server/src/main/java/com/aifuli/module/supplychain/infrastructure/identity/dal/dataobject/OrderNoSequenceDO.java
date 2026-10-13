package com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.*;

/** 永久预留高水位。设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#41-mysql永久预留与所有权}。 */
@Data
@TableName("sc_order_no_sequence")
public class OrderNoSequenceDO {
    /** 订单编号流水所属日期，Asia/Shanghai */
    @TableId(type = IdType.INPUT)
    private LocalDate sequenceDate;
    /** 已提交预留号段的当日最大流水，初始为0 */
    private Integer lastReservedValue;
    /** 订单编号流水行创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 订单编号流水行最后更新时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
