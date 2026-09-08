package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购短期一次性入口凭证 DO，对应 {@code sc_instore_entry_credential}。
 *
 * <p>数据库只保存原始 token 的 SHA-256 摘要，明文仅在签发时返回一次；
 * 凭证通过 ACTIVE→PROCESSING→USED 三阶段状态机防止并发重复消费，
 * 撤销为 REVOKED 终态并固化稳定撤销原因。凭证固化采购项目归属、
 * 签发时冻结的 MEMBER_PROFILE 档案版本和 companyCode 快照，历史在途凭证
 * 由 018 迁移失败关闭转为 REVOKED。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#7-Liquibase-018}。</p>
 */
@TableName("sc_instore_entry_credential")
@Data
@Builder
public class InstoreEntryCredentialDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 到店购入口凭证稳定业务编号。 */
    private String credentialNo;
    /** 稳定用户绑定主键，逻辑引用 sc_instore_channel_user_binding.id。 */
    private Long bindingId;
    /** 凭证所属采购项目内部主键。 */
    private Long projectId;
    /** 签发时冻结的MEMBER_PROFILE档案版本内部主键。 */
    private Long memberProfileVersionId;
    /** 接口①返回和旅通入口使用的企业组快照。 */
    private String companyCode;
    /** 入口 token 原文的 SHA-256 摘要；禁止保存原文。 */
    private byte[] tokenDigest;
    /** 凭证用途：HOME 首页，DETAIL 订单详情。 */
    private String purpose;
    /** DETAIL 凭证绑定的到店购详情主键；HOME 为空。 */
    private Long instoreOrderId;
    /** 凭证状态：10 ACTIVE，15 PROCESSING，20 USED，30 EXPIRED，40 REVOKED。 */
    private Short status;
    /** 凭证失效时间，Asia/Shanghai。 */
    private LocalDateTime expiresAt;
    /** 凭证被会员查询请求独占的时间，Asia/Shanghai。 */
    private LocalDateTime processingStartedAt;
    /** 会员资料成功返回后的凭证消费时间，Asia/Shanghai。 */
    private LocalDateTime usedAt;
    /** 凭证撤销时间，Asia/Shanghai。 */
    private LocalDateTime revokedAt;
    /** 稳定撤销原因。 */
    private String revokeReason;
    /** 凭证状态乐观锁版本号。 */
    @Version
    private Long version;
    /** 凭证创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 凭证最后更新时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
