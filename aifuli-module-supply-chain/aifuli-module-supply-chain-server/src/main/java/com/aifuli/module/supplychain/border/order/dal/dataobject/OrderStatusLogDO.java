package com.aifuli.module.supplychain.border.order.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_order_status_log")
@Data
@Builder
public class OrderStatusLogDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long detailVersion;
    private Short fromOrderStatus;
    private Short toOrderStatus;
    private Short fromAftersaleStatus;
    private Short toAftersaleStatus;
    private String businessStage;
    private String sourceEventType;
    private String sourceEventNo;
    private String reasonCode;
    private String traceId;
    private LocalDateTime createdAt;
}
