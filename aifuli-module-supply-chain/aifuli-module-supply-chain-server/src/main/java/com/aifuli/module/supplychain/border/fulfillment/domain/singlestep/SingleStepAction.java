package com.aifuli.module.supplychain.border.fulfillment.domain.singlestep;

/** 单步外呼动作，CREATE越过marker后不可再次认领；见三家详细设计§4.7。 */
public enum SingleStepAction {
    CREATE, QUERY
}
