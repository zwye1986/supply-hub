package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 履约异常 Case 人工核实与失败关单的命令指纹、代次与证据审计，对应 {@code sc_fulfillment_case_action}。
 *
 * <p>职责：保存人工核实原单与失败关单两步命令的 actionNo 幂等、请求指纹、真实操作人、
 * 预期与实际代次、执行状态、受保护核实证据与完成摘要；同一 Case 同时只允许一个活动意图
 * （数据库唯一约束 uk_sc_case_action_active）。
 *
 * <p>设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#62-核实不是恢复购买、#63-失败确认的证据与幂等。
 */
@TableName("sc_fulfillment_case_action")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentCaseActionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 命令稳定审计编号，运营提交并用于幂等重放。 */
    private String actionNo;
    private String caseNo;
    private Long caseId;
    private Long bOrderId;
    private String operationNo;
    /** 命令类型：VERIFY_ORIGIN 核实原单，CONFIRM_FAILURE 确认失败关单。 */
    private String commandType;
    /** 活动意图标记：1 受理或执行中，NULL 已终结；同一 Case 同时只允许一个活动意图。 */
    private Boolean activeIntentFlag;
    private byte[] requestFingerprint;
    /** 提交命令的真实管理端操作人用户ID，取自登录态不可由客户端传入。 */
    private Long actorId;
    /** 失败确认引用的核实命令编号，仅 CONFIRM_FAILURE 使用。 */
    private String verifyActionNo;
    private Long expectedCaseVersion;
    private Long expectedGeneration;
    private Long actualCaseVersion;
    private Long actualGeneration;
    /** 执行状态：RECEIVED/STARTED/SUCCEEDED/FAILED/UNKNOWN。 */
    private String executionStatus;
    /** 查询已开始标记：1 已开始外呼；Timer 重投或进程恢复见此标记后不得再次外呼。 */
    private Boolean queryStartedFlag;
    /** 命令执行租约截止时间；到期无完整结果则终结为 UNKNOWN。 */
    private LocalDateTime leaseUntil;
    /** 受保护核实证据的显式schema版本。 */
    private Integer evidenceSchemaVersion;
    /** 受保护核实证据的密钥版本；沿用现有字符串标识，不转换为数字。 */
    private String evidenceKeyVersion;
    /** 受保护核实证据密文，AAD绑定actionNo、caseNo、operationNo、连接器与供给版本、generation。 */
    private byte[] evidenceCiphertext;
    /** 核实结论稳定原因码，如 HUASHENG_NOT_CREATED、HUASHENG_SUPPLIER_CANCELLED。 */
    private String verifyConclusion;
    /** 核实观察到的供应商原始主状态，脱敏保真。 */
    private String externalSupplierStatus;
    /** 本命令累计的未建单有效查询次数。 */
    private Integer validNotCreatedCount;
    /** 核实结果可用于失败确认的截止时间。 */
    private LocalDateTime freshUntil;
    private String completionSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
