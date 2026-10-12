package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 代码与数据库共同约束的供应商连接器能力，枚举名为持久化和接口使用的稳定编码。
 * 中文说明与编码统一维护，仅解释接口职责，不承载重试、退款或账本动作。
 * 适配器声明本枚举后，管理端目录同时返回编码与说明，前端无需维护中文能力字典。
 * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#84-能力子页。
 */
public enum Capability {
    CREATE_RECHARGE("创建充值：向供应商发起充值订单"),
    ISSUE_ASSET("发放资产：向供应商申请发放券码等权益资产"),
    BIND_BENEFIT("绑定权益：将权益绑定到指定用户账号"),
    QUERY_ORDER("查询订单：查询供应商订单状态与履约结果"),
    QUERY_ASSET("查询资产：查询券码等权益资产的状态"),
    VOID_ASSET("作废资产：将已发放的权益资产作废"),
    UNBIND_ASSET("解绑资产：解除权益资产与用户账号的绑定"),
    QUERY_BALANCE("查询余额：查询供应商账户的可用余额"),
    QUERY_INVENTORY("查询库存：查询供应商商品的可用库存"),
    RECEIVE_CALLBACK("接收回调：接收供应商推送的订单状态与履约结果通知");

    private final String description;

    Capability(String description) {
        this.description = description;
    }

    /** 管理端只读展示的中文名称与职责解释，编码仍使用 {@link #name()}。 */
    public String getDescription() {
        return description;
    }
}
