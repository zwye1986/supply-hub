package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 直充限频运营追加审计；仅保存供应链自身数据，不含手机号明文或跨模块表引用。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Data
@Builder
@TableName("sc_recharge_frequency_action_audit")
public class RechargeFrequencyActionAuditDO {
    /** 直充限频运营审计内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 管理操作幂等号。 */
    private String actionNo;
    /** 管理操作请求指纹。 */
    private byte[] requestFingerprint;
    /** 管理操作类型：RETRY触发检查，RELEASE释放占用。 */
    private String actionType;
    /** 直充限频队列内部主键；资源不存在时可空。 */
    private Long queueId;
    /** 直充限频队列项内部主键；资源不存在时可空。 */
    private Long entryId;
    /** 供应链总订单号。 */
    private String orderNo;
    /** 请求预期的占用代次。 */
    private Long expectedGeneration;
    /** 请求预期的乐观锁版本。 */
    private Long expectedVersion;
    /** 真实内部操作用户ID。 */
    private Long operatorUserId;
    /** 管理操作原因。 */
    private String reason;
    /** 管理操作结果码。 */
    private String resultCode;
    /** 管理操作前状态快照。 */
    private String beforeStateJson;
    /** 管理操作后状态快照。 */
    private String afterStateJson;
    /** 请求链路追踪ID。 */
    private String traceId;
    /** 审计创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
