package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 敏感数据访问审计持久化对象，对应数据库表 {@code sc_sensitive_access_audit}。
 *
 * <p>核心职责：追加记录履约目标与交付资产的查询、解密和导出行为。
 * 严禁在审计中持久化买家手机号或用户标识明文，仅保存 HMAC 摘要。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@TableName("sc_sensitive_access_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveAccessAuditDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感数据访问审计稳定业务编号，前缀 AUD_ */
    private String auditNo;

    /** 访问类型：TARGET_DECRYPT目标解密，ASSET_QUERY资产查询，ASSET_DECRYPT资产解密，ASSET_EXPORT资产导出 */
    private String accessType;

    /** 访问主体类型：BUYER_SERVICE采购商服务，ADMIN_USER运营人员，SYSTEM_WORKER系统任务 */
    private String principalType;

    /** 经过认证的访问主体稳定标识 */
    private String principalId;

    /** 相关采购商内部主键，无采购商上下文时为空 */
    private Long buyerId;

    /** 采购商用户标识HMAC摘要，不保存用户标识明文 */
    private byte[] buyerUserDigest;

    /** 资源类型：FULFILLMENT_REQUEST履约请求，FULFILLMENT_ASSET履约资产 */
    private String resourceType;

    /** 被访问资源内部主键 */
    private Long resourceId;

    /** 授权访问所使用的权限Scope编码，例如 FULFILLMENT_ASSET_READ */
    private String scopeCode;

    /** 访问结果：10允许，20拒绝，30失败 */
    private Short resultStatus;

    /** 允许、拒绝或失败的稳定原因码 */
    private String reasonCode;

    /** 访问链路追踪标识 */
    private String traceId;

    /** 访问发生时间，UTC */
    private LocalDateTime accessedAt;
}
