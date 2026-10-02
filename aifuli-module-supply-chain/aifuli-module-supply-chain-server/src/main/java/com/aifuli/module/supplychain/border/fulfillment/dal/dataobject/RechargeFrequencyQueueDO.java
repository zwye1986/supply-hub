package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 直充限频队列控制行；仅保存供应链自身数据，不含手机号明文或跨模块表引用。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Data
@Builder
@TableName("sc_recharge_frequency_queue")
public class RechargeFrequencyQueueDO {
    /** 直充限频队列内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 规范化充值手机号的专用 HMAC 摘要。 */
    private byte[] mobileDigest;
    /** 充值手机号脱敏展示值。 */
    private String mobileMasked;
    /** 入队时冻结的平台 SPU 内部主键。 */
    private Long platformSpuId;
    /** 下一次入队可分配的队列序号。 */
    private Long nextEnqueueSequence;
    /** 当前持有占用的队列项内部主键。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long holderEntryId;
    /** 每次取得新占用递增的占用代次。 */
    private Long occupancyGeneration;
    /** 当前占用取得时间，Asia/Shanghai。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime acquiredAt;
    /** 本次成功最短占用截止时间，Asia/Shanghai。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime minimumHoldUntil;
    /** 下一次队列补偿检查时间，Asia/Shanghai。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextCheckAt;
    /** 每次安排新的唤醒意图递增的任务代次。 */
    private Long wakeGeneration;
    /** 队列阻塞原因。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String blockedReason;
    /** 占用超时告警是否处于活跃状态。 */
    private Boolean occupiedAlertActive;
    /** 积压超阈值告警是否处于活跃状态。 */
    private Boolean backlogAlertActive;
    /** 最近占用超时告警时间，Asia/Shanghai。 */
    private LocalDateTime lastOccupiedAlertAt;
    /** 最近积压超阈值告警时间，Asia/Shanghai。 */
    private LocalDateTime lastBacklogAlertAt;
    /** 乐观锁版本。 */
    @Version
    private Long version;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 更新时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
