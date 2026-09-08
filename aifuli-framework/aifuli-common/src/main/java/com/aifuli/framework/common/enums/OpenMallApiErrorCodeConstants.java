package com.aifuli.framework.common.enums;

/**
 * 标准开放商城接口错误码常量。
 *
 * <p>与开放接口文档约定保持一致，供各业务模块开放接口复用。
 */
public interface OpenMallApiErrorCodeConstants {

    String SUCCESS = "0";

    // ========== 公共错误 100xx ==========
    String PARAM_ERROR = "10001";
    String PARAM_ERROR_MSG = "参数错误";

    String SIGN_ERROR = "10002";
    String SIGN_ERROR_MSG = "签名错误";

    String TIMESTAMP_EXPIRED = "10003";
    String TIMESTAMP_EXPIRED_MSG = "timestamp 已过期";

    String NONCE_DUPLICATE = "10004";
    String NONCE_DUPLICATE_MSG = "nonce 重复";

    String PLATFORM_CODE_INVALID = "10005";
    String PLATFORM_CODE_INVALID_MSG = "platformCode 无效";

    String MEMBER_BIND_FAIL = "10006";
    String MEMBER_BIND_FAIL_MSG = "会员绑定失败";

    // ========== 用户错误 200xx ==========
    String USER_NOT_EXISTS = "20001";
    String USER_NOT_EXISTS_MSG = "用户不存在";

    // ========== 商品错误 300xx ==========
    String PRODUCT_NOT_EXISTS = "30001";
    String PRODUCT_NOT_EXISTS_MSG = "商品不存在或已下架";

    String PRODUCT_STOCK_NOT_ENOUGH = "30002";
    String PRODUCT_STOCK_NOT_ENOUGH_MSG = "库存不足";

    String PRODUCT_MIN_BUY_COUNT = "30003";
    String PRODUCT_MIN_BUY_COUNT_MSG = "商品未达到起购量";

    // ========== 订单错误 400xx ==========
    String ORDER_NOT_EXISTS = "40001";
    String ORDER_NOT_EXISTS_MSG = "订单不存在";

    String ORDER_STATUS_INVALID = "40002";
    String ORDER_STATUS_INVALID_MSG = "订单状态不允许操作";

    // ========== 系统错误 500xx ==========
    String SYSTEM_ERROR = "50000";
    String SYSTEM_ERROR_MSG = "系统异常";

}
