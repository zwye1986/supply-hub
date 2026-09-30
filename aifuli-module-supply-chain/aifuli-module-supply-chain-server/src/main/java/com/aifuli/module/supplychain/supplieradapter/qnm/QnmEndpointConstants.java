package com.aifuli.module.supplychain.supplieradapter.qnm;

/**
 * 奇那梦（QNM）GameOrder V1 协议端点与常量。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#101-奇那梦qnmgameorder-v1-协议实现规约}。</p>
 */
public final class QnmEndpointConstants {

    private QnmEndpointConstants() {
    }

    /** 直充创建端点路径。 */
    public static final String PATH_SEND = "/GameOrder/Send";

    /** 卡密/券码发放端点路径。 */
    public static final String PATH_SEND_KM = "/GameOrder/SendKm";

    /** 主动查单端点路径。 */
    public static final String PATH_QUERY = "/GameOrder/Query";

    /** 直充端点编码（写入 CallLog）。 */
    public static final String ENDPOINT_CODE_SEND = "QNM_SEND";

    /** 卡密发券端点编码（写入 CallLog）。 */
    public static final String ENDPOINT_CODE_SEND_KM = "QNM_SEND_KM";

    /** 查单端点编码（写入 CallLog）。 */
    public static final String ENDPOINT_CODE_QUERY = "QNM_QUERY";

    /** 协议字段名：商户号/客户编号。 */
    public static final String PARAM_CUSTOMER_ID = "customerid";

    /** 协议字段名：外部商品编号。 */
    public static final String PARAM_PRODUCT_ID = "productid";

    /** 协议字段名：客户端业务流水号。 */
    public static final String PARAM_BILLNO = "billno";

    /** 协议字段名：充值账号/手机号。 */
    public static final String PARAM_ACCOUNT = "account";

    /** 协议字段名：购买数量。 */
    public static final String PARAM_NUMBER = "number";

    /** 协议字段名：回调通知地址。 */
    public static final String PARAM_NOTIFY_URL = "notifyurl";

    /** 协议字段名：秒级时间戳。 */
    public static final String PARAM_TIMESTAMP = "timestamp";

    /** 协议字段名：MD5 签名串。 */
    public static final String PARAM_SIGN = "sign";

    /** 协议字段名：供应商订单号。 */
    public static final String PARAM_ORDER_SN = "orderSn";

    /** 响应字段名：结果响应码。 */
    public static final String FIELD_RES_CODE = "ResCode";

    /** 响应字段名：结果描述。 */
    public static final String FIELD_RES_MSG = "ResMsg";

    /** 响应字段名：订单状态（查单使用）。 */
    public static final String FIELD_ORDER_STATUS = "orderStatus";

    /** 响应字段名：供应商报告结算金额（单位：分）。 */
    public static final String FIELD_SUM_PRICE = "sumPrice";

    /** 响应字段名：卡密数组。 */
    public static final String FIELD_CARDS = "cards";

    /** 成功响应码：数字 1。 */
    public static final String RES_CODE_SUCCESS = "1";

    /** 成功响应码：字符串 OK。 */
    public static final String RES_CODE_OK = "OK";

    /** 查单订单状态：0 - 处理中。 */
    public static final int QUERY_STATUS_PROCESSING = 0;

    /** 查单订单状态：1 - 成功。 */
    public static final int QUERY_STATUS_SUCCESS = 1;

    /** 查单订单状态：2 - 失败。 */
    public static final int QUERY_STATUS_FAILED = 2;

    /** 回调状态：1 - 成功。 */
    public static final int CALLBACK_STATUS_SUCCESS = 1;

    /** 回调状态：2 - 失败。 */
    public static final int CALLBACK_STATUS_FAILED = 2;
}
