package com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个对象类型和父对象对应一个可锁定的编号分配作用域。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Data
@TableName("sc_business_code_scope")
public class BusinessCodeScopeDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务编码类型。 */
    private String codeKind;
    /** 父对象内部主键；根作用域为0。 */
    private Long ownerId;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
