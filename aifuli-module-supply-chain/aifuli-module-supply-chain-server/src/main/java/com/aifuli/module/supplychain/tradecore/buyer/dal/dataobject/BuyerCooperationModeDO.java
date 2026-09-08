package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分销商合作模式多选。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer_cooperation_mode")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerCooperationModeDO {

    /** 分销商合作模式行主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 合作模式 */
    private String cooperationMode;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
