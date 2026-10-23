package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分销商开票信息。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#7-表}。
 */
@TableName("sc_buyer_invoice")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerInvoiceDO {

    /** 分销商开票信息内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 抬头类型：ENTERPRISE企业，PERSONAL个人 */
    private String titleType;
    /** 开票抬头 */
    private String invoiceTitle;
    /** 统一社会信用代码。个人抬头保存时须清空，更新策略 ALWAYS。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String socialCreditCode;
    /**
     * 电话。选填，空值更新须覆盖旧值。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#61-校验}。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String phone;
    /** 开户行。两种抬头均按提交值保存，空值更新须覆盖旧值。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bankName;
    /** 开户账号。两种抬头均按提交值保存，空值更新须覆盖旧值。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bankAccount;
    /** 开票地址 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String invoiceAddress;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 创建人用户ID */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
