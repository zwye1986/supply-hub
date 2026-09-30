package com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成功创建后永久保留的三位流水号占用记录。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Data
@TableName("sc_business_code_claim")
public class BusinessCodeClaimDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务编码作用域内部主键，逻辑引用sc_business_code_scope.id。 */
    private Long scopeId;
    /** 三位流水号数值，范围1至999。 */
    private Short serialNo;
    /** 占号时间。 */
    private LocalDateTime createdAt;
}
