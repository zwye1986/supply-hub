package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;

/**
 * 订单管理前端契约的枚举映射纯函数（前端 OrderBizType / PlatformOrderStatus /
 * SupplierOrderStatus / RouteFailReason）。
 *
 * <p>本类只做「后端封闭枚举 + 商品类型字典码 → 前端字符串枚举」的确定性翻译，
 * 不访问数据库、不依赖 Spring，便于单测覆盖映射表全部分支。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#4-枚举映射表}。</p>
 */
public final class OrderViewMappings {

    /** 前端业务类型枚举值；见设计 §4.1。 */
    public static final String BIZ_VIRTUAL_CARD = "VIRTUAL_CARD";
    public static final String BIZ_VIRTUAL_RECHARGE = "VIRTUAL_RECHARGE";
    public static final String BIZ_DINING = "DINING";
    public static final String BIZ_MOVIE = "MOVIE";
    public static final String BIZ_HEMA = "HEMA";
    public static final String BIZ_INSTORE = "INSTORE";

    /** 前端平台订单状态枚举值；见设计 §4.2。 */
    public static final String PLATFORM_WAIT_PAY = "WAIT_PAY";
    public static final String PLATFORM_PROCESSING = "PROCESSING";
    public static final String PLATFORM_MANUAL = "MANUAL";
    public static final String PLATFORM_SUCCESS = "SUCCESS";
    public static final String PLATFORM_FAILED = "FAILED";
    public static final String PLATFORM_CLOSED = "CLOSED";

    /** 前端供应商订单状态枚举值；见设计 §4.3。 */
    public static final String SUPPLIER_WAIT_SHIP = "WAIT_SHIP";
    public static final String SUPPLIER_SHIPPED = "SHIPPED";
    public static final String SUPPLIER_FINISHED = "FINISHED";
    public static final String SUPPLIER_FAILED = "FAILED";
    public static final String SUPPLIER_MANUAL = "MANUAL";
    public static final String SUPPLIER_CANCELLED = "CANCELLED";

    /** 前端路由失败原因枚举值；见设计 §4.4。 */
    public static final String FAIL_API_TIMEOUT = "API_TIMEOUT";
    public static final String FAIL_SUPPLIER_REJECT = "SUPPLIER_REJECT";
    public static final String FAIL_API_ERROR = "API_ERROR";

    /**
     * 电影票点餐类 CARD 订单依赖的商品类型字典码（sc_product_type.type_code）。
     * 决策依据：O2O 现网场景码仅 HEMA，电影票/点餐码订单实际走 CARD 券码履约，
     * 其 SPU 商品类型标记为 MOVIE / DINING（设计 §4.1 待确认 5 的查证结论）。
     */
    public static final String PRODUCT_TYPE_MOVIE = "MOVIE";
    public static final String PRODUCT_TYPE_DINING = "DINING";

    private OrderViewMappings() {
    }

    /**
     * 业务类型映射（设计 §4.1）：
     * CARD + 券码类履约 → 按 SPU 商品类型细分（MOVIE/DINING 优先，其余 VIRTUAL_CARD）；
     * CARD + 直充/账户权益 → VIRTUAL_RECHARGE；O2O → HEMA（现网唯一场景）；INSTORE → INSTORE。
     */
    public static String bizType(String orderType, String fulfillmentType, String spuProductType) {
        if (SupplyChainOrderType.O2O.name().equals(orderType)) {
            return BIZ_HEMA;
        }
        if (SupplyChainOrderType.INSTORE.name().equals(orderType)) {
            return BIZ_INSTORE;
        }
        // CARD 订单按履约类型分域
        if (isRechargeFulfillment(fulfillmentType)) {
            return BIZ_VIRTUAL_RECHARGE;
        }
        if (PRODUCT_TYPE_MOVIE.equals(spuProductType)) {
            return BIZ_MOVIE;
        }
        if (PRODUCT_TYPE_DINING.equals(spuProductType)) {
            return BIZ_DINING;
        }
        return BIZ_VIRTUAL_CARD;
    }

    /**
     * 平台订单状态映射；人工独立展示，见 2026-10-13 失败关单设计 §8.1。
     * WAIT_PAY 在供应链域永不出现（受理即已支付），保留给前端展示位。
     */
    public static String platformStatus(SupplyChainOrderStatus status) {
        return switch (status) {
            case SUCCEEDED -> PLATFORM_SUCCESS;
            case FAILED -> PLATFORM_FAILED;
            case MANUAL -> PLATFORM_MANUAL;
            case CANCELLED -> PLATFORM_CLOSED;
            // CREATED(0)/PROCESSING(10)/PARTIALLY_SUCCEEDED(20) 均视为处理中
            default -> PLATFORM_PROCESSING;
        };
    }

    /**
     * 供应商订单状态映射（设计 §4.3，评审决策：B 端 10 执行中 → SHIPPED，
     * 「已发货」语义化为「已提交供应商执行」）。WAIT_PAY/CLOSED 在 B 端域永不出现。
     */
    public static String supplierStatus(BOrderFulfillmentStatus status) {
        return switch (status) {
            case SUCCEEDED -> SUPPLIER_FINISHED;
            case FAILED -> SUPPLIER_FAILED;
            case MANUAL -> SUPPLIER_MANUAL;
            case CANCELLED -> SUPPLIER_CANCELLED;
            // PENDING(10 执行中)/PROCESSING(20 查询中) 视为已提交供应商执行
            case PENDING, PROCESSING -> SUPPLIER_SHIPPED;
            // WAITING(0 待执行) 视为待供应商执行
            default -> SUPPLIER_WAIT_SHIP;
        };
    }

    /**
     * 路由失败原因映射（设计 §4.4）：REJECTED → SUPPLIER_REJECT；
     * RESULT_UNKNOWN（超时收敛）→ API_TIMEOUT；其余兜底 API_ERROR。
     * PRICE_OVER_THRESHOLD 由路由淘汰阶段产生（候选价格阈值检查），
     * STOCK_SHORT 需供应商错误码映射支撑，本期 B 端尝试级不产生这两类值。
     */
    public static String routeFailReason(String operationResultClass) {
        if ("REJECTED".equals(operationResultClass)) {
            return FAIL_SUPPLIER_REJECT;
        }
        if ("RESULT_UNKNOWN".equals(operationResultClass)) {
            return FAIL_API_TIMEOUT;
        }
        return FAIL_API_ERROR;
    }

    /** 直充/账户权益类履约；与 PlatformSkuDO.fulfillmentType 封闭值对齐。 */
    public static boolean isRechargeFulfillment(String fulfillmentType) {
        return "DIRECT_RECHARGE".equals(fulfillmentType)
                || "ACCOUNT_BOUND_BENEFIT".equals(fulfillmentType);
    }

    /** 券码类履约（卡号卡密/券码/券链接）。 */
    public static boolean isVoucherFulfillment(String fulfillmentType) {
        return "CARD_CODE_SECRET".equals(fulfillmentType)
                || "VOUCHER_CODE".equals(fulfillmentType)
                || "VOUCHER_URL".equals(fulfillmentType);
    }
}
