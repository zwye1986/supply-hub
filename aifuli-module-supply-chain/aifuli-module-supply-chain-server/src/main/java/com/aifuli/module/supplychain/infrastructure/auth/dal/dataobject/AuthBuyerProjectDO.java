package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 认证链路使用的采购项目只读投影，对应 {@code sc_buyer_project}。
 *
 * <p>该最小投影避免 {@code infrastructure.auth} 依赖 {@code tradecore}；写路径仍归
 * {@code tradecore.buyer} 所有。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_buyer_project")
@Data
public class AuthBuyerProjectDO {

    /** 采购项目内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 项目状态：0草稿，10启用，20暂停，90归档 */
    private Short status;
}
