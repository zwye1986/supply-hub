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
 * 直充限频队列项；仅保存供应链自身数据，不含手机号明文或跨模块表引用。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Data
@Builder
@TableName("sc_recharge_frequency_entry")
public class RechargeFrequencyEntryDO {
    /** 直充限频队列项内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 直充限频队列内部主键。 */
    private Long queueId;
    /** 供应链总订单内部主键。 */
    private Long orderId;
    /** 供应链总订单号。 */
    private String orderNo;
    /** 履约请求内部主键。 */
    private Long fulfillmentRequestId;
    /** 本订单首次入队分配的队列序号。 */
    private Long enqueueSequence;
    /** 队列项状态：0等待，10占用，20已结束。 */
    private Short status;
    /** 当前合法履约操作号。 */
    private String activeOperationNo;
    /** 本队列项取得占用的代次。 */
    private Long occupancyGeneration;
    /** 外呼阶段：0已取得资格尚未发送，10外部可能已受理。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Short dispatchPhase;
    /** 分销商内部主键。 */
    private Long buyerId;
    /** 采购项目内部主键；存量订单可空。 */
    private Long projectId;
    /** 首次入队时间，Asia/Shanghai。 */
    private LocalDateTime enqueuedAt;
    /** 本订单排队流程结束时间，Asia/Shanghai。 */
    private LocalDateTime completedAt;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 更新时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
    /** 乐观锁版本。 */
    @Version
    private Long version;
}
