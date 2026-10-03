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
    ErrorCode PROJECT_BUYER_IMMUTABLE = error(1_050_001_062, "项目创建后不能修改分销商");
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
    ErrorCode PRODUCT_STATUS_INVALID = error(1_050_001_096, "商品状态不合法或不支持当前流转");
    ErrorCode PRODUCT_CONCURRENT_UPDATE = error(1_050_001_097, "商品中心实体已被并发修改");
    ErrorCode PRODUCT_DELIVERY_TYPE_INVALID = error(1_050_001_098, "商品配送履约模式不合法");
    ErrorCode PRODUCT_QUANTITY_RANGE_INVALID = error(1_050_001_099, "商品起购量与限购量区间不合法");
    ErrorCode PRODUCT_SKU_REQUIRED = error(1_050_001_100, "平台商品至少需要挂载一个 SKU");
    ErrorCode PRODUCT_SKU_CODE_DUPLICATE = error(1_050_001_101, "同一次请求内 SKU 编码重复");
    ErrorCode PRODUCT_SKU_FIELD_INVALID = error(1_050_001_102, "SKU 名称、履约模式或价格字段不合法");
    ErrorCode PRODUCT_CURRENCY_INVALID = error(1_050_001_103, "平台 SKU 币种仅支持 CNY");
    ErrorCode SUPPLY_CHAIN_ORDER_NOT_FOUND = error(1_050_001_104, "供应链总订单不存在");
    ErrorCode ORDER_QUERY_TIME_RANGE_INVALID = error(1_050_001_105, "订单受理时间区间不合法");
    ErrorCode BRAND_LEGACY_API_DEPRECATED =
            error(1_050_001_106, "品牌旧接口已废弃，请使用 POST /supply-chain/brands");
    ErrorCode PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID =
            error(1_050_001_107, "SKU限购配置不合法");
    ErrorCode USER_PURCHASE_LIMIT_EXCEEDED =
            error(1_050_001_108, "当前项目下该用户购买数量已达到SKU周期限购上限");
    ErrorCode ORDER_PURCHASE_LIMIT_EXCEEDED =
            error(1_050_001_109, "本次购买数量超过SKU每订单限购上限");
    ErrorCode PHONE_BIND_PURCHASE_LIMIT_EXCEEDED =
            error(1_050_001_110, "当前项目下该履约手机号购买数量已达到SKU周期限购上限");
    ErrorCode PHONE_BIND_TARGET_REQUIRED =
            error(1_050_001_111, "绑定手机号限购要求履约目标为手机号");
    ErrorCode CATEGORY_PRODUCT_TYPE_REQUIRED =
            error(1_050_001_112, "商品类型不能为空");
    ErrorCode PRODUCT_FULFILLMENT_TYPE_IMMUTABLE =
            error(1_050_001_113, "商品履约方式已锁定，禁止修改");
    ErrorCode PRODUCT_SUGGESTED_PRICE_RULE_INVALID =
            error(1_050_001_114, "SKU销售建议价规则不合法");
    ErrorCode FULFILLMENT_ASSET_SCOPE_DENIED =
            error(1_050_001_115, "缺少独立授予的 FULFILLMENT_ASSET_READ 权限");
    ErrorCode FULFILLMENT_SCORE_INVALID =
            error(1_050_001_116, "稳定性评分与时效性评分为 0-100 的整数");
    ErrorCode FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING =
            error(1_050_001_117, "该供应商当前未合作供应此 SKU");
    ErrorCode FULFILLMENT_SCORE_ITEMS_REQUIRED =
            error(1_050_001_118, "履约能力评分至少提交一行");
    ErrorCode SUPPLY_CHAIN_B_ORDER_NOT_FOUND =
            error(1_050_001_119, "供应商履约尝试不存在");
    ErrorCode ROUTING_RULE_WRITE_NOT_OPEN =
            error(1_050_001_120, "路由规则写入口已开放，本错误码不再使用");
    ErrorCode ROUTING_RULE_NOT_FOUND =
            error(1_050_001_121, "路由规则不存在");
    ErrorCode ROUTING_RULE_CODE_MISMATCH =
            error(1_050_001_122, "规则编码与路径不一致");
    ErrorCode ROUTING_RULE_SCOPE_NOT_OPEN =
            error(1_050_001_123, "路由规则不支持按项目或商品类型配置");
    ErrorCode ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN =
            error(1_050_001_124, "路由规则必须指定候选供应商，不支持全部供应商");
    ErrorCode ROUTING_RULE_BUYER_REQUIRED =
            error(1_050_001_125, "适用分销商必须指定恰好一个");
    ErrorCode ROUTING_RULE_STRATEGY_NOT_OPEN =
            error(1_050_001_126, "本期仅支持按候选顺序的稳定优先");
    ErrorCode ROUTING_RULE_CANDIDATE_INVALID =
            error(1_050_001_127, "固定模式必须一个主供应商，自动模式必须两个及以上互异有序候选");
    ErrorCode ROUTING_RULE_MATCH_OVERLAP =
            error(1_050_001_128, "已存在相同分销商、平台SKU与履约类型的路由规则");
    ErrorCode ROUTING_RULE_LAST_CANNOT_REMOVE =
            error(1_050_001_129, "当前生效版本至少保留一条路由规则");
    ErrorCode ROUTING_RULE_VERSION_CONFLICT =
            error(1_050_001_130, "路由规则版本已变更，请刷新后重试");
    ErrorCode ROUTING_RULE_FULFILLMENT_MISMATCH =
            error(1_050_001_131, "履约类型必须与平台SKU档案一致");
    ErrorCode ROUTING_RULE_NAME_DUPLICATED =
            error(1_050_001_134, "启用集内已存在同名路由规则");
    ErrorCode ROUTING_RULE_SCOPE_DUPLICATED =
            error(1_050_001_135, "已存在相同适用范围的路由规则，请调整适用范围或停用原规则");
    ErrorCode ROUTING_RULE_PROTECTED =
            error(1_050_001_136, "系统预置的全局默认规则不可删除，范围与路由模式不可修改");
    ErrorCode ROUTING_RULE_WEIGHTS_INVALID =
            error(1_050_001_137, "综合排序权重之和必须等于100");
    ErrorCode ORDER_EXPORT_EXCEED_LIMIT =
            error(1_050_001_138, "导出结果超过上限，请缩小筛选范围后重试");
    ErrorCode PLATFORM_DEFAULT_PRICING_MISSING =
            error(1_050_001_132, "标准商品尚未配置默认定价，无法保存分销价");
    ErrorCode SUPPLIER_CODE_REQUIRED =
            error(1_050_001_133, "排除已添加供应商品时必须指定供应商");
    ErrorCode BUSINESS_CODE_FORMAT_INVALID = error(1_050_001_139, "业务编码格式错误：{}");
    ErrorCode BUSINESS_CODE_OCCUPIED = error(1_050_001_140, "作用域号码已占用：{}");
    ErrorCode BUSINESS_CODE_CAPACITY_EXHAUSTED = error(1_050_001_141, "业务编码容量已满：{}");
    ErrorCode CATEGORY_PARENT_IMMUTABLE = error(1_050_001_142, "分类创建后不能修改父分类");
    ErrorCode BUSINESS_CODE_PARENT_MISMATCH = error(1_050_001_143, "业务编码父段不匹配：{}");
    ErrorCode BUSINESS_CODE_MANUAL_NOT_ALLOWED = error(1_050_001_144, "{}编号由系统生成，请勿手填");
    ErrorCode BUSINESS_CODE_PERMANENT_EXISTS = error(1_050_001_145, "永久编码已存在：{}");
    ErrorCode BUSINESS_CODE_LEGACY_PARENT_OCCUPIED = error(1_050_001_146, "旧父对象占用父段：{}");
    ErrorCode ROUTING_RULE_SCOPE_UNSUPPORTED =
            error(1_050_001_147, "路由规则范围组合不支持，请按七级匹配层级配置");
    ErrorCode ROUTING_RULE_PROJECT_BUYER_MISMATCH =
            error(1_050_001_148, "项目不属于所选分销商");

    // 项目应用开通与凭证状态错误。编号从 162 起，避免与已发布的路由规则 147/148 冲突。
    ErrorCode PROJECT_APP_ALREADY_ENABLED = error(1_050_001_162, "项目应用已开通");
    ErrorCode PROJECT_APP_NOT_ENABLED = error(1_050_001_163, "项目应用未开通或不可用");
    ErrorCode PROJECT_APP_CONFIRMATION_REQUIRED = error(1_050_001_164, "吊销项目应用凭证须确认并填写原因");
    ErrorCode PROJECT_APP_CREDENTIAL_STATE_INVALID = error(1_050_001_165, "项目应用凭证状态异常");

    // 供应商履约技术配置：连接器发布的供应商级唯一守卫（D1）。
    // 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#55-d1-published_flag-唯一键变更集-056。
    // 服务层预检先于 DB 唯一键 uk_sc_connector_supplier_published 报错，返回清晰业务异常而非裸 DuplicateKeyException。
    ErrorCode SUPPLIER_ALREADY_HAS_PUBLISHED_CONNECTOR =
            error(1_050_001_166, "供应商已存在其它已发布连接器版本，请先退役原版本后再发布");

    // 上架即发布（B1）能力缺口与连接器缺失守卫。设计见
    // docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心。
    // 文案含 {} 占位符，经 exception(code, args...) 格式化；前端 message.error 透传（要求 #2）。
    ErrorCode SUPPLIER_NO_PUBLISHED_CONNECTOR =
            error(1_050_001_167, "供应商[{}]尚无已发布连接器版本，请先在『履约技术配置』发布连接器");
    ErrorCode OFFER_CAPABILITY_MISSING =
            error(1_050_001_168, "供应商[{}]当前连接器未启用[{}]能力，该商品无法上架，"
                    + "请先在『履约技术配置』启用能力并重新发布连接器版本");

    // 批量上下架入参整体拒绝。设计见
    // docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status
    // 一处覆盖三种入参不合法：未选择任何商品、编码为空白、超过单次 200 条上限。
    // 不复用 PRODUCT_STATUS_INVALID（语义为状态机非法迁移）与 PRODUCT_SKU_FIELD_INVALID（语义为 SKU 名称/履约模式/价格），
    // 避免向用户展示与真实原因不符的文案。整体拒绝发生在逐项独立事务之前，不产生任何写入。
    ErrorCode PRODUCT_BATCH_INPUT_INVALID =
            error(1_050_001_169, "批量上下架入参不合法：未选择商品、商品编码为空或超过单次 200 条上限");

    // 非法状态迁移专用错误（含动态占位符：商品名称、编码、当前状态、目标状态）。设计见
    // docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status
    ErrorCode PRODUCT_STATUS_TRANSITION_NOT_ALLOWED =
            error(1_050_001_170, "商品【{}】（{}）当前状态【{}】不支持变更为【{}】");

    // 项目标准资产查询稳定错误，设计见 docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#4-资产结果与封闭载荷。
    ErrorCode ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE =
            error(1_050_001_158, "此类商品不能查询资产，请通过查单接口获取订单信息");
    ErrorCode ASSET_QUERY_ORDER_NOT_ACCEPTED =
            error(1_050_001_159, "订单未受理，请通过查单接口获取订单信息");
    ErrorCode ASSET_QUERY_ORDER_TERMINATED =
            error(1_050_001_160, "订单已失败或取消，请通过查单接口获取订单结果");

    // 标准商品缺项目订单首次冻结报价的可条件重试稳定错误：内部首次报价形成后按原项目键重新受理即可。
    // 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#3-受理内查取与唯一消费。
    ErrorCode STANDARD_PRICING_QUOTE_MISSING =
            error(1_050_001_161, "标准商品缺少项目订单首次冻结报价");

    ErrorCode FREQUENCY_RESOURCE_NOT_FOUND = error(1_050_001_151, "限频资源不存在");
    ErrorCode FREQUENCY_VERSION_CONFLICT = error(1_050_001_152, "限频资源版本或持有者已变化");
    ErrorCode FREQUENCY_STATE_INVALID = error(1_050_001_153, "限频状态不允许该操作");
    ErrorCode FREQUENCY_RELEASE_UNSAFE = error(1_050_001_154, "尚无可信释放依据");
    ErrorCode FREQUENCY_SUCCESS_WINDOW = error(1_050_001_155, "成功占用最短窗口尚未达到");
    ErrorCode FREQUENCY_ACTION_CONFLICT = error(1_050_001_156, "管理操作幂等号已用于其他请求");
    ErrorCode FREQUENCY_PARAMETER_INVALID = error(1_050_001_157, "限频管理参数不正确");

    /** 订单详情不可用只作为成功响应中的详情标记，不作为 CommonResult 失败码。 */
    String DETAIL_UNAVAILABLE = "DETAIL_UNAVAILABLE";

    Map<Integer, RetryDirective> RETRY_DIRECTIVES = Map.ofEntries(
            entry(FREQUENCY_RESOURCE_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_VERSION_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_STATE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_RELEASE_UNSAFE, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_SUCCESS_WINDOW, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_ACTION_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(FREQUENCY_PARAMETER_INVALID, RetryDirective.DO_NOT_RETRY),
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
            entry(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(USER_PURCHASE_LIMIT_EXCEEDED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(ORDER_PURCHASE_LIMIT_EXCEEDED, RetryDirective.DO_NOT_RETRY),
            entry(PHONE_BIND_PURCHASE_LIMIT_EXCEEDED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PHONE_BIND_TARGET_REQUIRED, RetryDirective.DO_NOT_RETRY),
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
            entry(ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE, RetryDirective.DO_NOT_RETRY),
            entry(ASSET_QUERY_ORDER_NOT_ACCEPTED, RetryDirective.DO_NOT_RETRY),
            entry(ASSET_QUERY_ORDER_TERMINATED, RetryDirective.DO_NOT_RETRY),
            entry(STANDARD_PRICING_QUOTE_MISSING, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(SUPPLY_CHAIN_ORDER_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(ORDER_QUERY_TIME_RANGE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(ORDER_EXPORT_EXCEED_LIMIT, RetryDirective.DO_NOT_RETRY),
            entry(BRAND_LEGACY_API_DEPRECATED, RetryDirective.DO_NOT_RETRY),
            entry(CATEGORY_PRODUCT_TYPE_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_FULFILLMENT_TYPE_IMMUTABLE, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_SUGGESTED_PRICE_RULE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(FULFILLMENT_ASSET_SCOPE_DENIED, RetryDirective.DO_NOT_RETRY),
            entry(FULFILLMENT_SCORE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING, RetryDirective.DO_NOT_RETRY),
            entry(FULFILLMENT_SCORE_ITEMS_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLY_CHAIN_B_ORDER_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_WRITE_NOT_OPEN, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_NOT_FOUND, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_CODE_MISMATCH, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_SCOPE_NOT_OPEN, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_BUYER_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_STRATEGY_NOT_OPEN, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_CANDIDATE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_MATCH_OVERLAP, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_LAST_CANNOT_REMOVE, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_VERSION_CONFLICT, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_FULFILLMENT_MISMATCH, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_NAME_DUPLICATED, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_SCOPE_DUPLICATED, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_PROTECTED, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_WEIGHTS_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_SCOPE_UNSUPPORTED, RetryDirective.DO_NOT_RETRY),
            entry(ROUTING_RULE_PROJECT_BUYER_MISMATCH, RetryDirective.DO_NOT_RETRY),
            entry(PLATFORM_DEFAULT_PRICING_MISSING, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_CODE_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_FORMAT_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_OCCUPIED, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_CAPACITY_EXHAUSTED, RetryDirective.DO_NOT_RETRY),
            entry(CATEGORY_PARENT_IMMUTABLE, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_PARENT_MISMATCH, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_MANUAL_NOT_ALLOWED, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_PERMANENT_EXISTS, RetryDirective.DO_NOT_RETRY),
            entry(BUSINESS_CODE_LEGACY_PARENT_OCCUPIED, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_APP_ALREADY_ENABLED, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_APP_NOT_ENABLED, RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE),
            entry(PROJECT_APP_CONFIRMATION_REQUIRED, RetryDirective.DO_NOT_RETRY),
            entry(PROJECT_APP_CREDENTIAL_STATE_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_ALREADY_HAS_PUBLISHED_CONNECTOR, RetryDirective.DO_NOT_RETRY),
            entry(SUPPLIER_NO_PUBLISHED_CONNECTOR, RetryDirective.DO_NOT_RETRY),
            entry(OFFER_CAPABILITY_MISSING, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_BATCH_INPUT_INVALID, RetryDirective.DO_NOT_RETRY),
            entry(PRODUCT_STATUS_TRANSITION_NOT_ALLOWED, RetryDirective.DO_NOT_RETRY));

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
