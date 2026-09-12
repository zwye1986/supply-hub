package com.aifuli.module.supplychain.error;

import com.aifuli.framework.common.exception.ErrorCode;

import java.util.Map;

/**
 * 供应链中台稳定错误目录。
 *
 * <p>错误码占用 1-050-001-000 段。RPC/HTTP 均使用同一错误码，调用方通过
 * {@link #retryDirective(int)} 获取稳定处置语义。</p>
 */
public interface SupplyChainErrorCodeConstants {

    ErrorCode BUYER_AUTH_INVALID = error(1_050_001_001, "采购商调用身份无效");
    ErrorCode BUYER_NOT_FOUND = error(1_050_001_002, "未找到采购商绑定");
    ErrorCode BUYER_DISABLED = error(1_050_001_003, "采购商已停用");
    ErrorCode MULTI_ITEM_ORDER_NOT_SUPPORTED = error(1_050_001_004, "一期不支持多商品订单");
    ErrorCode QUANTITY_NOT_SUPPORTED = error(1_050_001_005, "一期履约数量必须为一");
    ErrorCode INVALID_TARGET = error(1_050_001_006, "履约目标不符合已发布 Schema");
    ErrorCode LISTING_VERSION_EXPIRED = error(1_050_001_007, "采购商商品版本已失效");
    ErrorCode SKU_NOT_SALEABLE = error(1_050_001_008, "标准商品当前不可售");
    ErrorCode INVENTORY_INSUFFICIENT = error(1_050_001_009, "可兑现供给不足");
    ErrorCode NO_ROUTABLE_SUPPLIER = error(1_050_001_010, "没有可路由的供应商");
    ErrorCode BUYER_BALANCE_INSUFFICIENT = error(1_050_001_011, "采购商可用余额不足");
    ErrorCode IDEMPOTENCY_CONFLICT = error(1_050_001_012, "永久业务键对应的请求内容冲突");
    ErrorCode CANCEL_NOT_SUPPORTED_AFTER_ACCEPTANCE =
            error(1_050_001_013, "中台受理后不可自动退款、取消或撤销");
    ErrorCode DOWNSTREAM_RESULT_UNKNOWN = error(1_050_001_014, "下游调用结果未知，请按原业务键查询");
    ErrorCode MANUAL_REQUIRED = error(1_050_001_015, "业务需要受控人工处置");
    ErrorCode REQUEST_EXPIRED = error(1_050_001_016, "请求截止时间已过");
    ErrorCode TEMPORARILY_UNAVAILABLE = error(1_050_001_017, "服务暂时不可用，请使用原业务键退避重试");
    ErrorCode ORDER_STATE_CONFLICT = error(1_050_001_018, "订单状态与当前操作冲突");
    ErrorCode BUYER_EVENT_PAYLOAD_CONFLICT = error(1_050_001_019, "同一事件号对应的载荷摘要冲突");
    ErrorCode O2O_CHANNEL_USER_BINDING_MISSING = error(1_050_001_020, "O2O渠道用户绑定缺失或停用");
    ErrorCode O2O_IDENTITY_CONFLICT = error(1_050_001_021, "O2O渠道交易身份冲突");
    ErrorCode O2O_SUPPLIER_CHANNEL_BINDING_NOT_FOUND = error(1_050_001_022, "O2O渠道商户绑定未配置");
    ErrorCode O2O_REFUND_AMOUNT_EXCEEDED = error(1_050_001_023, "O2O退款金额超过可退金额");
    ErrorCode O2O_DETAIL_NOT_FOUND = error(1_050_001_024, "O2O渠道详情不存在");
    ErrorCode BUYER_ACCOUNT_NOT_OPENED = error(1_050_001_025, "采购商资金账户未开户");
    ErrorCode INSTORE_TOKEN_INVALID = error(1_050_001_026, "到店购旅通token缺失或已失效");
    ErrorCode INSTORE_CHANNEL_USER_BINDING_MISSING = error(1_050_001_027, "到店购渠道用户绑定缺失或停用");
    ErrorCode INSTORE_IDENTITY_CONFLICT = error(1_050_001_028, "到店购渠道交易身份冲突");
    ErrorCode INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND = error(1_050_001_029, "到店购渠道商户绑定未配置");
    ErrorCode INSTORE_REFUND_AMOUNT_EXCEEDED = error(1_050_001_030, "到店购退款金额超过可退金额");
    ErrorCode INSTORE_ORDER_NOT_FOUND = error(1_050_001_031, "到店购渠道订单不存在");
    ErrorCode INSTORE_PAYMENT_FLOW_NOT_FOUND = error(1_050_001_032, "到店购支付流水不存在");
    ErrorCode INSTORE_CANCEL_REFUND_FAILED = error(1_050_001_033, "到店购取消已支付流水转退款失败");
    ErrorCode INSTORE_REFUND_CASE_NOT_FOUND = error(1_050_001_034, "到店购退款Case不存在");
    ErrorCode INSTORE_IDEMPOTENCY_CONFLICT = error(1_050_001_035, "到店购永久业务键对应的请求内容冲突");
    ErrorCode INSTORE_CREDENTIAL_INVALID = error(1_050_001_036, "到店购入口凭证无效或用途不匹配");
    ErrorCode INSTORE_CREDENTIAL_EXPIRED = error(1_050_001_037, "到店购入口凭证已过期");
    ErrorCode INSTORE_CREDENTIAL_ALREADY_USED = error(1_050_001_038, "到店购入口凭证已使用");
    ErrorCode INSTORE_TENANT_SCOPE_CONFLICT = error(1_050_001_039, "到店购请求与采购商租户作用域不一致");
    ErrorCode INSTORE_REFUND_AMOUNT_MISMATCH = error(1_050_001_040, "到店购实际退款金额与申请金额不一致");
    ErrorCode INSTORE_CONCURRENT_UPDATE = error(1_050_001_041, "到店购状态已被并发请求推进");
    ErrorCode INSTORE_ACCOUNT_RETURN_CONFLICT = error(1_050_001_042, "到店购账户返还幂等键对应的金额冲突");
    ErrorCode INSTORE_CREDENTIAL_REVOKED = error(1_050_001_043, "到店购入口凭证已撤销");
    ErrorCode INSTORE_MEMBER_PROFILE_INVALID = error(1_050_001_044, "到店购会员资料无效");
    ErrorCode INSTORE_MEMBER_PROFILE_UNAVAILABLE = error(1_050_001_045, "到店购会员资料暂时不可用");
    ErrorCode INSTORE_ENTRY_URL_UNAVAILABLE = error(1_050_001_046, "到店购入口地址暂时不可用");
    ErrorCode INSTORE_REFUND_AMOUNT_INVALID = error(1_050_001_047, "到店购退款金额必须大于零");
    ErrorCode SUPPLIER_NOT_FOUND = error(1_050_001_048, "供应商不存在");
    ErrorCode SUPPLIER_STATUS_CONFLICT = error(1_050_001_049, "供应商状态不允许该操作");
    ErrorCode SUPPLIER_OPENING_ALREADY_DONE = error(1_050_001_050, "供应商期初已入账，禁止再改余额字段");
    ErrorCode SUPPLIER_CREDIT_CODE_DUPLICATE = error(1_050_001_051, "公司名称+统一社会信用代码联合重复");
    ErrorCode SUPPLIER_ENUM_NOT_OPEN = error(1_050_001_052, "未知枚举值");
    ErrorCode ENUM_NOT_OPEN = SUPPLIER_ENUM_NOT_OPEN;
    ErrorCode SUPPLIER_CONTACT_REQUIRED = error(1_050_001_053, "至少保留一行有效联系人");
    ErrorCode SUPPLIER_SCOPE_CONFLICT = error(1_050_001_054, "同一供应商下品类/类型/分类组合重复");
    ErrorCode SUPPLIER_REGION_INVALID = error(1_050_001_055, "配送区域与行政区划码不一致");
    ErrorCode SUPPLIER_OFFER_OUT_OF_SCOPE = error(1_050_001_056, "SKU 不在该供应商供货配置范围内");
    ErrorCode CATEGORY_CHAIN_INVALID = error(1_050_001_057, "品类/类型/分类不是启用类目的祖先链");
    ErrorCode BUYER_PARENT_INVALID = error(1_050_001_058, "上级/等级不合法或成环");
    ErrorCode BUYER_PRODUCT_DUPLICATE = error(1_050_001_059, "分销商品 SKU 已存在");
    ErrorCode L2_OVERRIDE_INVALID = error(1_050_001_060, "L2 覆盖公式或金额不合法");
    ErrorCode L3_OVERRIDE_INVALID = error(1_050_001_061, "L3 覆盖不合法");
    ErrorCode PROJECT_BUYER_IMMUTABLE = error(1_050_001_062, "非草稿项目不可改分销商");
    ErrorCode PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED = error(1_050_001_063, "平台结算未开放");
    ErrorCode PROJECT_STATUS_INVALID = error(1_050_001_064, "非法项目状态迁移");
    ErrorCode PROJECT_SKU_NOT_IN_BUYER_CATALOG = error(1_050_001_065, "项目 SKU 不是该分销商的分销商品");
    ErrorCode SUPPLY_SCOPE_MODE_INVALID = error(1_050_001_066, "供货配置模式集合为空或含未知值");
    ErrorCode BUYER_OPENING_ALREADY_DONE = error(1_050_001_067, "分销商期初已入账，禁止再改余额字段");
    ErrorCode BUYER_PROFILE_NOT_FOUND = error(1_050_001_068, "分销商不存在");
    ErrorCode PROJECT_NOT_FOUND = error(1_050_001_069, "项目不存在");
    ErrorCode BUYER_PRODUCT_NOT_FOUND = error(1_050_001_070, "分销商品不存在");
    ErrorCode PROJECT_LISTING_NOT_FOUND = error(1_050_001_071, "项目商品不存在");
    ErrorCode BUYER_STATUS_CONFLICT = error(1_050_001_072, "分销商状态不允许该操作");
    ErrorCode PLATFORM_SETTLEMENT_PRICE_MISSING = error(1_050_001_073, "标准商品缺少平台默认定价");
    ErrorCode BUYER_CREDIT_CODE_DUPLICATE = error(1_050_001_074, "公司名称+统一社会信用代码联合重复");
    ErrorCode PROJECT_PERIOD_INVALID = error(1_050_001_075, "项目结束日早于开始日");
    ErrorCode INVOICE_FIELD_INVALID = error(1_050_001_076, "开票信息字段不完整或与抬头类型冲突");
    ErrorCode BRAND_NOT_FOUND = error(1_050_001_077, "商品品牌不存在");
    ErrorCode CATEGORY_NOT_FOUND = error(1_050_001_078, "商品分类不存在");
    ErrorCode PLATFORM_SPU_NOT_FOUND = error(1_050_001_079, "平台 SPU 不存在");
    ErrorCode PLATFORM_SKU_NOT_FOUND = error(1_050_001_080, "平台 SKU 不存在");
    ErrorCode PRODUCT_TYPE_NOT_FOUND = error(1_050_001_081, "商品类型不存在");
    ErrorCode PRODUCT_TYPE_INACTIVE = error(1_050_001_082, "商品类型未启用");
    ErrorCode PRODUCT_TYPE_DUPLICATE = error(1_050_001_083, "商品类型编码或名称已存在");
    ErrorCode PRODUCT_TYPE_HAS_PRODUCTS = error(1_050_001_084, "商品类型仍有关联商品，禁止删除");
    ErrorCode BRAND_NAME_DUPLICATE = error(1_050_001_085, "品牌名称已存在");
    ErrorCode BRAND_HAS_PRODUCTS = error(1_050_001_086, "品牌仍有关联商品，禁止删除");
    ErrorCode CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT =
            error(1_050_001_087, "分类名称在同一父级下已存在");
    ErrorCode CATEGORY_HAS_CHILDREN = error(1_050_001_088, "分类仍有有效子分类，禁止删除");
    ErrorCode CATEGORY_HAS_PRODUCTS = error(1_050_001_089, "分类仍有关联商品，禁止删除");
    ErrorCode CATEGORY_PARENT_INVALID = error(1_050_001_090, "分类父级无效或形成循环");
    ErrorCode PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER =
            error(1_050_001_091, "商品仍有关联供应商报价，禁止删除");
    ErrorCode PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING =
            error(1_050_001_092, "商品仍有关联采购商上架单，禁止删除");
    ErrorCode PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT =
            error(1_050_001_093, "商品仍有关联分销商品，禁止删除");
    ErrorCode PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING =
            error(1_050_001_094, "商品仍有关联项目商品，禁止删除");
    ErrorCode PRODUCT_SCHEMA_INVALID = error(1_050_001_095, "商品履约 Schema 配置不合法");
    ErrorCode PRODUCT_STATUS_INVALID = error(1_050_001_096, "商品中心实体状态迁移不合法");
    ErrorCode PRODUCT_CONCURRENT_UPDATE = error(1_050_001_097, "商品中心实体已被并发修改");
    ErrorCode PRODUCT_DELIVERY_TYPE_INVALID = error(1_050_001_098, "商品配送履约模式不合法");
    ErrorCode PRODUCT_QUANTITY_RANGE_INVALID = error(1_050_001_099, "商品起购量与限购量区间不合法");
    ErrorCode PRODUCT_SKU_REQUIRED = error(1_050_001_100, "平台商品至少需要挂载一个 SKU");
    ErrorCode PRODUCT_SKU_CODE_DUPLICATE = error(1_050_001_101, "同一次请求内 SKU 编码重复");
    ErrorCode PRODUCT_SKU_FIELD_INVALID = error(1_050_001_102, "SKU 名称、履约模式或价格字段不合法");
    ErrorCode PRODUCT_CURRENCY_INVALID = error(1_050_001_103, "平台 SKU 币种仅支持 CNY");
    ErrorCode SUPPLY_CHAIN_ORDER_NOT_FOUND = error(1_050_001_104, "供应链总订单不存在");
    ErrorCode ORDER_QUERY_TIME_RANGE_INVALID = error(1_050_001_105, "订单受理时间区间不合法");

    /** 订单详情不可用只作为成功响应中的详情标记，不作为 CommonResult 失败码。 */
    String DETAIL_UNAVAILABLE = "DETAIL_UNAVAILABLE";

    Map<Integer, RetryDirective> RETRY_DIRECTIVES = Map.ofEntries(
            entry(BUYER_AUTH_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_DISABLED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(MULTI_ITEM_ORDER_NOT_SUPPORTED, RetryDirective.DO_NOT_RETRY),
            entry(QUANTITY_NOT_SUPPORTED, RetryDirective.DO_NOT_RETRY),
            entry(INVALID_TARGET, RetryDirective.DO_NOT_RETRY),
            entry(LISTING_VERSION_EXPIRED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(SKU_NOT_SALEABLE, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(INVENTORY_INSUFFICIENT, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(NO_ROUTABLE_SUPPLIER, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(BUYER_BALANCE_INSUFFICIENT, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(IDEMPOTENCY_CONFLICT, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(CANCEL_NOT_SUPPORTED_AFTER_ACCEPTANCE, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(DOWNSTREAM_RESULT_UNKNOWN, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(MANUAL_REQUIRED, RetryDirective.MANUAL),
            entry(REQUEST_EXPIRED, RetryDirective.DO_NOT_RETRY),
            entry(TEMPORARILY_UNAVAILABLE, RetryDirective.RETRY_SAME_KEY),
            entry(ORDER_STATE_CONFLICT, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(BUYER_EVENT_PAYLOAD_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(O2O_CHANNEL_USER_BINDING_MISSING, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(O2O_IDENTITY_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(O2O_SUPPLIER_CHANNEL_BINDING_NOT_FOUND, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(O2O_REFUND_AMOUNT_EXCEEDED, RetryDirective.DO_NOT_RETRY),
            entry(O2O_DETAIL_NOT_FOUND, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(BUYER_ACCOUNT_NOT_OPENED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(INSTORE_TOKEN_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_CHANNEL_USER_BINDING_MISSING, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(INSTORE_IDENTITY_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(INSTORE_REFUND_AMOUNT_EXCEEDED, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_ORDER_NOT_FOUND, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(INSTORE_PAYMENT_FLOW_NOT_FOUND, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(INSTORE_CANCEL_REFUND_FAILED, RetryDirective.MANUAL),
            entry(INSTORE_REFUND_CASE_NOT_FOUND, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(INSTORE_IDEMPOTENCY_CONFLICT, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(INSTORE_CREDENTIAL_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_CREDENTIAL_EXPIRED, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_CREDENTIAL_ALREADY_USED, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_TENANT_SCOPE_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_REFUND_AMOUNT_MISMATCH, RetryDirective.MANUAL),
            entry(INSTORE_CONCURRENT_UPDATE, RetryDirective.QUERY_BY_BUSINESS_KEY),
            entry(INSTORE_ACCOUNT_RETURN_CONFLICT, RetryDirective.MANUAL),
            entry(INSTORE_CREDENTIAL_REVOKED, RetryDirective.DO_NOT_RETRY),
            entry(INSTORE_MEMBER_PROFILE_INVALID, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(INSTORE_MEMBER_PROFILE_UNAVAILABLE, RetryDirective.RETRY_SAME_KEY),
            entry(INSTORE_ENTRY_URL_UNAVAILABLE, RetryDirective.RETRY_SAME_KEY),
            entry(INSTORE_REFUND_AMOUNT_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_STATUS_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_OPENING_ALREADY_DONE, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_CREDIT_CODE_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_ENUM_NOT_OPEN, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_CONTACT_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_SCOPE_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_REGION_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_OFFER_OUT_OF_SCOPE, RetryDirective.DO_NOT_RETRY),
            entry(CATEGORY_CHAIN_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_PARENT_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_PRODUCT_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(L2_OVERRIDE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(L3_OVERRIDE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_BUYER_IMMUTABLE, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_STATUS_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_SKU_NOT_IN_BUYER_CATALOG, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLY_SCOPE_MODE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_OPENING_ALREADY_DONE, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_PROFILE_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_PRODUCT_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_LISTING_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_STATUS_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(PLATFORM_SETTLEMENT_PRICE_MISSING, RetryDirective.DO_NOT_RETRY),
            entry(BUYER_CREDIT_CODE_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_PERIOD_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(INVOICE_FIELD_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BRAND_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(CATEGORY_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PLATFORM_SPU_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PLATFORM_SKU_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_TYPE_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_TYPE_INACTIVE, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_TYPE_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_TYPE_HAS_PRODUCTS, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(BRAND_NAME_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(BRAND_HAS_PRODUCTS, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT, RetryDirective.DO_NOT_RETRY),
            entry(CATEGORY_HAS_CHILDREN, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(CATEGORY_HAS_PRODUCTS, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(CATEGORY_PARENT_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_SCHEMA_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_STATUS_INVALID, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PRODUCT_CONCURRENT_UPDATE, RetryDirective.RETRY_SAME_KEY),
            entry(PRODUCT_DELIVERY_TYPE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_QUANTITY_RANGE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_SKU_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_SKU_CODE_DUPLICATE, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_SKU_FIELD_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_CURRENCY_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLY_CHAIN_ORDER_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(ORDER_QUERY_TIME_RANGE_INVALID, RetryDirective.DO_NOT_RETRY));

    static RetryDirective retryDirective(int errorCode) {
        return RETRY_DIRECTIVES.getOrDefault(errorCode, RetryDirective.DO_NOT_RETRY);
    }

    private static ErrorCode error(int code, String message) {
        return new ErrorCode(code, message);
    }

    private static Map.Entry<Integer, RetryDirective> entry(ErrorCode errorCode, RetryDirective directive) {
        return Map.entry(errorCode.getCode(), directive);
    }
}
