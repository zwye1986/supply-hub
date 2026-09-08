package com.aifuli.module.supplychain.supplieradapter.metadata;

/** 代码与数据库共同约束的供应商连接器能力。 */
public enum Capability {
    CREATE_RECHARGE,
    ISSUE_ASSET,
    BIND_BENEFIT,
    QUERY_ORDER,
    QUERY_ASSET,
    VOID_ASSET,
    UNBIND_ASSET,
    QUERY_BALANCE,
    QUERY_INVENTORY,
    RECEIVE_CALLBACK
}
