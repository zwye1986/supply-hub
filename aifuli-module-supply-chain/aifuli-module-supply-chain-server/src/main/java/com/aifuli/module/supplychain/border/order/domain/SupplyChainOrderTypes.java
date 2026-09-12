package com.aifuli.module.supplychain.border.order.domain;

/**
 * 供应链总订单的类型与业务阶段稳定编码常量，对应 {@code sc_supply_chain_order} 的 DDL CHECK 约束。
 *
 * <p>详见详细设计 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7-订单模型}。</p>
 */
public final class SupplyChainOrderTypes {

    private SupplyChainOrderTypes() {
    }

    /** 订单类型：标准商品履约。 */
    public static final String ORDER_TYPE_CARD = SupplyChainOrderType.CARD.name();
    /** 订单类型：O2O 渠道场景（ele.me/盒马配送）。 */
    public static final String ORDER_TYPE_O2O = SupplyChainOrderType.O2O.name();
    /** 订单类型：到店购渠道场景（大润发/旅通到店扫码购）。 */
    public static final String ORDER_TYPE_INSTORE = SupplyChainOrderType.INSTORE.name();
    /** 场景详情类型：标准履约请求。 */
    public static final String DETAIL_TYPE_FULFILLMENT = "FULFILLMENT";
    /** 场景详情类型：O2O 渠道详情。 */
    public static final String DETAIL_TYPE_O2O_CHANNEL = "O2O_CHANNEL";
    /** 场景详情类型：到店购渠道订单头。 */
    public static final String DETAIL_TYPE_INSTORE_CHANNEL = "INSTORE_CHANNEL";

    /** 业务阶段：CARD 受理后等待供应商履约结果。 */
    public static final String STAGE_CARD_AWAITING_RESULT = "CARD_AWAITING_RESULT";
    /** 业务阶段：O2O 受理后等待采购商支付。 */
    public static final String STAGE_O2O_AWAITING_PAYMENT = "O2O_AWAITING_PAYMENT";
    /** 业务阶段：O2O 支付已确认，资金已提交。 */
    public static final String STAGE_O2O_PAID = "O2O_PAID";
    /** 业务阶段：O2O 已取消。 */
    public static final String STAGE_O2O_CANCELLED = "O2O_CANCELLED";
    /** 业务阶段：O2O 失败。 */
    public static final String STAGE_O2O_FAILED = "O2O_FAILED";
    /** 业务阶段：到店购受理后等待支付。 */
    public static final String STAGE_INSTORE_AWAITING_PAYMENT = "INSTORE_AWAITING_PAYMENT";
    /** 业务阶段：到店购支付已确认，到店履约完成。 */
    public static final String STAGE_INSTORE_PAID = "INSTORE_PAID";
    /** 业务阶段：到店购已取消。 */
    public static final String STAGE_INSTORE_CANCELLED = "INSTORE_CANCELLED";
    /** 业务阶段：到店购失败。 */
    public static final String STAGE_INSTORE_FAILED = "INSTORE_FAILED";
}
