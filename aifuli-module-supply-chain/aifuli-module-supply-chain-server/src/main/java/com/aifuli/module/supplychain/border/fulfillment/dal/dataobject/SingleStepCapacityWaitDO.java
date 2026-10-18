package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/** 独立于供应商事实的容量等待；序次永久递增，不能重复使用已完成Timer键。设计见共享限额设计§7。 */
@TableName("sc_single_step_capacity_wait") @Data @Builder
public class SingleStepCapacityWaitDO {
    @TableId(type = IdType.INPUT) private Long operationId;
    private String action;
    private Long expectedGeneration;
    private Long wakeSequence;
    private LocalDateTime nextWakeAt;
    private Boolean activeFlag;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
