-- 大润发到店购渠道（INSTORE）接入变更集。
-- 设计依据：docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md
-- 与 O2O（ele.me）平行、不硬合：独立 orderType=INSTORE、独立详情模块、独立渠道服务；
-- 共享 sc_supply_chain_order 总表与 trade-core 资金账本，但详情表、状态机、入站契约独立。
-- 本变更集只追加新表与前向扩展总表 CHECK，不修改任何 O2O/CARD 已发布表结构。

-- 扩展总表 order_type 枚举与字段约束以容纳 INSTORE 分支。
-- INSTORE 场景：buyer_order_no 承载旅通 order_id；acceptance_receipt_id 为空；
-- 不绑定平台 SKU；buyer_settlement_amount > 0；detail_type = INSTORE_CHANNEL；展示供应商非空。
ALTER TABLE sc_supply_chain_order
    DROP CHECK ck_sc_order_type;

ALTER TABLE sc_supply_chain_order
    ADD CONSTRAINT ck_sc_order_type CHECK (order_type IN ('CARD', 'O2O', 'INSTORE'));

ALTER TABLE sc_supply_chain_order
    DROP CHECK ck_sc_order_card_fields;

ALTER TABLE sc_supply_chain_order
    ADD CONSTRAINT ck_sc_order_card_fields CHECK (
        (order_type = 'CARD' AND buyer_order_no IS NOT NULL AND acceptance_receipt_id IS NOT NULL AND platform_sku_id IS NOT NULL AND quantity = 1)
        OR (order_type = 'O2O' AND buyer_order_no IS NULL AND acceptance_receipt_id IS NULL)
        OR (order_type = 'INSTORE' AND buyer_order_no IS NOT NULL AND acceptance_receipt_id IS NULL AND platform_sku_id IS NULL AND sku_code_snapshot IS NULL AND sku_name_snapshot IS NULL AND quantity IS NULL AND buyer_settlement_amount > 0 AND display_supplier_id IS NOT NULL)
    );

ALTER TABLE sc_supply_chain_order
    MODIFY COLUMN order_type VARCHAR(16) NOT NULL
        COMMENT '订单类型：CARD标准商品履约，O2O配送O2O渠道，INSTORE到店购渠道';

ALTER TABLE sc_supply_chain_order
    MODIFY COLUMN detail_type VARCHAR(32) NOT NULL
        COMMENT '场景详情类型：FULFILLMENT、O2O_CHANNEL 或 INSTORE_CHANNEL';

-- 表中文名：到店购渠道用户绑定
-- 核心职责：以渠道匿名用户标识与一次性 token 安全映射采购商及其 C 端用户，旅通持 token 反查归属
-- 与 O2O 反向：ele.me 由中台生成匿名 channelUserId 交给渠道；大润发由旅通持 token 调中台换会员信息。
CREATE TABLE sc_instore_channel_user_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购渠道用户绑定内部主键',
    binding_no VARCHAR(32) NOT NULL COMMENT '到店购渠道用户绑定稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '采购商范围内C端用户唯一标识',
    channel_code VARCHAR(32) NOT NULL COMMENT '到店购渠道编码，一期为RT_MART',
    channel_user_id VARCHAR(128) NOT NULL COMMENT '中台生成的高熵随机渠道匿名用户标识，不暴露给旅通',
    token VARCHAR(256) NOT NULL COMMENT '中台签发的高熵一次性授权token，旅通持此调会员接口换归属',
    token_status SMALLINT NOT NULL COMMENT 'token状态：10 ACTIVE有效，20 USED已使用，30 EXPIRED已过期',
    token_expires_at DATETIME(3) NULL COMMENT 'token失效时间，UTC；一次性token使用后即置USED',
    status SMALLINT NOT NULL COMMENT '绑定状态：10 ACTIVE已启用，20 DISABLED已停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '绑定状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '绑定创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '绑定最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_user_binding_no (binding_no),
    UNIQUE KEY uk_sc_instore_user_binding_buyer (buyer_id, buyer_user_id, channel_code),
    UNIQUE KEY uk_sc_instore_user_binding_channel (channel_code, channel_user_id),
    UNIQUE KEY uk_sc_instore_user_binding_token (channel_code, token),
    KEY idx_sc_instore_user_binding_status (buyer_id, channel_code, status),
    KEY idx_sc_instore_user_binding_token_status (channel_code, token_status, token_expires_at),
    CONSTRAINT ck_sc_instore_user_binding_status CHECK (status IN (10, 20)),
    CONSTRAINT ck_sc_instore_user_binding_token_status CHECK (token_status IN (10, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商C端用户与到店购渠道匿名标识及一次性token的映射';

-- 表中文名：到店购渠道订单头
-- 核心职责：保存到店购渠道订单聚合状态，1:1 对应 BuyerOrder，承载旅通 order_id；
-- 与支付流水 1:N（一笔订单可有多笔 trade_id 支付流水），订单头聚合各流水状态。
CREATE TABLE sc_instore_channel_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购渠道订单头内部主键',
    instore_order_no VARCHAR(32) NOT NULL COMMENT '到店购渠道订单稳定业务编号',
    order_id BIGINT NOT NULL COMMENT '供应链总订单主键，逻辑引用sc_supply_chain_order.id；一笔采购订单至多一条渠道订单头',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    buyer_user_id VARCHAR(128) NOT NULL COMMENT '从启用绑定解析并固化的采购商范围内C端用户唯一标识',
    channel_code VARCHAR(32) NOT NULL COMMENT '到店购渠道编码，一期为RT_MART',
    channel_user_binding_id BIGINT NOT NULL COMMENT '受理时使用的到店购渠道用户绑定主键',
    channel_user_id VARCHAR(128) NOT NULL COMMENT '受理时使用的渠道匿名用户标识快照',
    channel_order_id VARCHAR(128) NOT NULL COMMENT '旅通订单号order_id，与channel_code构成永久幂等键',
    company_code VARCHAR(32) NOT NULL COMMENT '旅通企业组编号company_code，受理时固化',
    supplier_channel_binding_id BIGINT NOT NULL COMMENT '受理时冻结的渠道商户绑定版本主键',
    supplier_id BIGINT NOT NULL COMMENT '到店购外部商户供应商主键，例如大润发',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '到店购外部商户供应商编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '到店购外部商户供应商名称快照',
    external_store_id VARCHAR(128) NULL COMMENT '渠道外部门店编号shop_code',
    external_store_name VARCHAR(256) NULL COMMENT '渠道外部门店名称shop_name快照',
    store_snapshot_json JSON NULL COMMENT '门店地址等非高频检索展示快照',
    total_amount BIGINT NOT NULL COMMENT '订单应付总额total_amount，单位为分，旅通建单传入',
    total_paid_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已支付累计金额，单位为分，各成功支付流水求和',
    currency CHAR(3) NOT NULL COMMENT '交易币种，一期固定CNY',
    total_channel_refund_amount BIGINT NOT NULL DEFAULT 0 COMMENT '渠道累计发起退款金额，单位为分',
    total_buyer_refund_amount BIGINT NOT NULL DEFAULT 0 COMMENT '采购商累计实际退款金额，单位为分',
    total_account_return_amount BIGINT NOT NULL DEFAULT 0 COMMENT '中台累计返还采购商账户金额，单位为分',
    active_refund_case_id BIGINT NULL COMMENT '当前正在采购商退款的退款Case主键',
    order_status SMALLINT NOT NULL COMMENT '订单头聚合状态：0已创建，10处理中，20已完成，30已取消，40失败，90人工处理',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '订单头聚合乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '渠道订单创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '渠道订单最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '渠道订单进入业务终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_order_no (instore_order_no),
    UNIQUE KEY uk_sc_instore_order_order (order_id),
    UNIQUE KEY uk_sc_instore_order_channel (channel_code, channel_order_id),
    KEY idx_sc_instore_order_user_binding (channel_user_binding_id, created_at),
    KEY idx_sc_instore_order_buyer_user (buyer_id, buyer_user_id, created_at),
    KEY idx_sc_instore_order_supplier (supplier_id, created_at),
    KEY idx_sc_instore_order_status (order_status, updated_at),
    KEY idx_sc_instore_order_active_refund (active_refund_case_id),
    CONSTRAINT ck_sc_instore_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_instore_order_amount CHECK (total_amount >= 0 AND total_paid_amount BETWEEN 0 AND total_amount AND total_channel_refund_amount BETWEEN 0 AND total_paid_amount AND total_buyer_refund_amount BETWEEN 0 AND total_paid_amount AND total_account_return_amount BETWEEN 0 AND total_paid_amount),
    CONSTRAINT ck_sc_instore_order_status CHECK (order_status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到店购场景一对一渠道订单头与聚合状态';

-- 表中文名：到店购支付流水
-- 核心职责：保存旅通 trade_id 支付流水状态；与渠道订单头 N:1（一笔订单可有多笔支付流水）。
-- 这是 INSTORE 与 O2O 1:1 详情模型的根本差异：承载"一笔订单多笔支付流水号"的 1:N 语义。
CREATE TABLE sc_instore_payment_flow (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购支付流水内部主键',
    payment_flow_no VARCHAR(32) NOT NULL COMMENT '到店购支付流水稳定业务编号',
    instore_order_id BIGINT NOT NULL COMMENT '到店购渠道订单头主键，逻辑引用sc_instore_channel_order.id',
    order_id BIGINT NOT NULL COMMENT '供应链总订单主键，逻辑引用sc_supply_chain_order.id',
    flow_sequence INT NOT NULL COMMENT '同一渠道订单内支付流水顺序号，从1递增',
    channel_code VARCHAR(32) NOT NULL COMMENT '到店购渠道编码，一期为RT_MART',
    trade_id VARCHAR(128) NOT NULL COMMENT '旅通支付流水号trade_id，与channel_code构成支付永久幂等键',
    user_code VARCHAR(128) NOT NULL COMMENT '旅通会员编号user_code，受理时固化用于归属二次校验',
    pay_amount BIGINT NOT NULL COMMENT '本笔支付流水金额，单位为分',
    order_type INT NOT NULL COMMENT '旅通支付单类型order_type：1线下扫码购，2动态码',
    pay_status SMALLINT NOT NULL COMMENT '支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理',
    out_trade_no VARCHAR(128) NOT NULL COMMENT '拉起采购商收银台的稳定外部交易号',
    cashier_url VARCHAR(512) NULL COMMENT '采购商收银台地址，建单时返回',
    cashier_reference VARCHAR(128) NULL COMMENT '采购商收银台支付单引用',
    buyer_paid_amount BIGINT NULL COMMENT '采购商收银台实际支付金额，单位为分',
    pay_time DATETIME(3) NULL COMMENT '支付成功时间，UTC，映射旅通接口③pay_time',
    external_transaction_id VARCHAR(128) NULL COMMENT '旅通返回的支付流水号transaction_id，支付成功时必填',
    fund_reservation_no VARCHAR(32) NULL COMMENT '本笔流水对应的FundReservation业务编号',
    supplier_order_id BIGINT NULL COMMENT '支付成功后创建的固定商户供应商订单主键；未成功或未创建时为空',
    request_fingerprint BINARY(32) NOT NULL COMMENT '建单请求规范化SHA-256指纹',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '支付流水乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '支付流水创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '支付流水最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '支付流水进入业务终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_flow_no (payment_flow_no),
    UNIQUE KEY uk_sc_instore_flow_trade (channel_code, trade_id),
    UNIQUE KEY uk_sc_instore_flow_order_seq (instore_order_id, flow_sequence),
    UNIQUE KEY uk_sc_instore_flow_out_trade (out_trade_no),
    UNIQUE KEY uk_sc_instore_flow_supplier_order (supplier_order_id),
    KEY idx_sc_instore_flow_order (order_id, flow_sequence),
    KEY idx_sc_instore_flow_status (pay_status, updated_at),
    KEY idx_sc_instore_flow_user_code (user_code, created_at),
    CONSTRAINT ck_sc_instore_flow_sequence CHECK (flow_sequence >= 1),
    CONSTRAINT ck_sc_instore_flow_amount CHECK (pay_amount >= 0 AND (buyer_paid_amount IS NULL OR buyer_paid_amount >= 0)),
    CONSTRAINT ck_sc_instore_flow_order_type CHECK (order_type IN (1, 2)),
    CONSTRAINT ck_sc_instore_flow_paid_match CHECK (pay_status <> 20 OR (buyer_paid_amount = pay_amount AND external_transaction_id IS NOT NULL)),
    CONSTRAINT ck_sc_instore_flow_status CHECK (pay_status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到店购支付流水，与渠道订单头N:1';

-- 表中文名：到店购退款Case
-- 核心职责：串行协调渠道发起或取消已支付触发的退款流程，退款挂在具体支付流水上
-- 与 O2O 退款 Case 区别：退款对象是单笔支付流水（payment_flow_id），而非订单头；支持旅通接口⑤多次退款。
CREATE TABLE sc_instore_refund_case (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购退款Case内部主键',
    refund_case_no VARCHAR(32) NOT NULL COMMENT '中台退款稳定业务编号',
    instore_order_id BIGINT NOT NULL COMMENT '到店购渠道订单头主键',
    payment_flow_id BIGINT NOT NULL COMMENT '退款对应的支付流水主键，逻辑引用sc_instore_payment_flow.id',
    order_id BIGINT NOT NULL COMMENT '供应链总订单主键，逻辑引用sc_supply_chain_order.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    channel_code VARCHAR(32) NOT NULL COMMENT '到店购渠道编码，一期为RT_MART',
    channel_refund_no VARCHAR(128) NOT NULL COMMENT '旅通退款单号refund_bn，与channel_code构成退款永久幂等键',
    refund_sequence INT NOT NULL COMMENT '同一支付流水内退款顺序号，从1递增',
    refund_type VARCHAR(32) NOT NULL COMMENT '退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款',
    channel_refund_amount BIGINT NOT NULL COMMENT '渠道要求退款金额，单位为分',
    buyer_actual_refund_amount BIGINT NULL COMMENT '采购商实际退给C端用户的金额，单位为分',
    account_return_amount BIGINT NULL COMMENT '中台返还采购商账户金额，单位为分',
    currency CHAR(3) NOT NULL COMMENT '退款币种，一期固定CNY',
    cashier_refund_reference VARCHAR(128) NULL COMMENT '采购商收银台退款单引用',
    request_fingerprint BINARY(32) NOT NULL COMMENT '退款请求规范化SHA-256指纹',
    status SMALLINT NOT NULL COMMENT '退款状态：0待处理，10排队中，20采购商退款中，30退款成功，40退款失败，60已完成，90人工处理',
    buyer_refunding_flag TINYINT NULL COMMENT '采购商退款活动标记：1正在退款，NULL其他，用于限制同一支付流水只有一个活动退款',
    result_reason_code VARCHAR(64) NULL COMMENT '退款结果或人工处理原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '退款Case乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '退款Case创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '退款Case最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '退款Case完成时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_refund_case_no (refund_case_no),
    UNIQUE KEY uk_sc_instore_refund_channel_no (channel_code, channel_refund_no),
    UNIQUE KEY uk_sc_instore_refund_flow_seq (payment_flow_id, refund_sequence),
    UNIQUE KEY uk_sc_instore_refund_active (payment_flow_id, buyer_refunding_flag),
    KEY idx_sc_instore_refund_order (order_id, created_at),
    KEY idx_sc_instore_refund_status (status, updated_at),
    CONSTRAINT ck_sc_instore_refund_sequence CHECK (refund_sequence >= 1),
    CONSTRAINT ck_sc_instore_refund_amount CHECK (channel_refund_amount >= 0 AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount >= 0) AND (account_return_amount IS NULL OR account_return_amount >= 0)),
    CONSTRAINT ck_sc_instore_refund_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_instore_refund_active CHECK (buyer_refunding_flag IS NULL OR buyer_refunding_flag = 1),
    CONSTRAINT ck_sc_instore_refund_type CHECK (refund_type IN ('CHANNEL_REFUND', 'CANCEL_REFUND')),
    CONSTRAINT ck_sc_instore_refund_rule CHECK ((refund_type = 'CHANNEL_REFUND' AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount = channel_refund_amount) AND (account_return_amount IS NULL OR account_return_amount = channel_refund_amount)) OR (refund_type = 'CANCEL_REFUND' AND (buyer_actual_refund_amount IS NULL OR buyer_actual_refund_amount = channel_refund_amount) AND (account_return_amount IS NULL OR account_return_amount = channel_refund_amount))),
    CONSTRAINT ck_sc_instore_refund_status CHECK (status IN (0, 10, 20, 30, 40, 60, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到店购退款串行协同Case';

-- 表中文名：到店购外部操作
-- 核心职责：维护建单、查支付、取消支付、退款与查退款等可恢复业务副作用
-- 与 O2O 操作区别：无 CHANNEL_NOTIFY 操作类型（旅通为 pull 模型，主动来查，中台不推送渠道）。
CREATE TABLE sc_instore_operation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购外部操作内部主键',
    operation_no VARCHAR(32) NOT NULL COMMENT '到店购外部操作稳定业务编号；PREORDER操作同时将其作为唯一invocationId',
    instore_order_id BIGINT NOT NULL COMMENT '到店购渠道订单头主键',
    payment_flow_id BIGINT NULL COMMENT '支付流水相关操作对应的支付流水主键；会员查询操作为空',
    refund_case_id BIGINT NULL COMMENT '退款相关操作对应的退款Case主键',
    supplier_order_id BIGINT NULL COMMENT '支付成功后固定商户供应商订单主键',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型：PREORDER预下单，PAYMENT_QUERY支付查单，CANCEL_PAY取消支付流水，BUYER_REFUND采购商退款，REFUND_QUERY退款查单',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '外部业务副作用稳定幂等键',
    request_fingerprint BINARY(32) NOT NULL COMMENT '不可变操作请求SHA-256指纹',
    external_operation_no VARCHAR(128) NULL COMMENT '渠道或采购商返回的外部操作号',
    status SMALLINT NOT NULL COMMENT '操作状态：0待执行，10执行中，20结果未知，30成功，40确定失败，90人工处理',
    result_class VARCHAR(32) NULL COMMENT '封闭标准操作结果分类',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已发起外部调用次数',
    next_recover_at DATETIME(3) NULL COMMENT '下一次查单或恢复时间，UTC',
    response_digest BINARY(32) NULL COMMENT '脱敏外部响应SHA-256摘要',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '操作状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '操作创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '操作最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '操作进入终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_operation_no (operation_no),
    UNIQUE KEY uk_sc_instore_operation_key (idempotency_key),
    KEY idx_sc_instore_operation_order (instore_order_id, created_at),
    KEY idx_sc_instore_operation_flow (payment_flow_id, created_at),
    KEY idx_sc_instore_operation_refund (refund_case_id, created_at),
    KEY idx_sc_instore_operation_supplier_order (supplier_order_id, created_at),
    KEY idx_sc_instore_operation_recover (status, next_recover_at, id),
    CONSTRAINT ck_sc_instore_operation_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_sc_instore_operation_type CHECK (operation_type IN ('PREORDER', 'PAYMENT_QUERY', 'CANCEL_PAY', 'BUYER_REFUND', 'REFUND_QUERY')),
    CONSTRAINT ck_sc_instore_operation_status CHECK (status IN (0, 10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到店购场景可恢复语义外部操作';

-- 表中文名：到店购固定商户供应商订单
-- 核心职责：保存到店购场景支付成功且资金处理后为固定外部商户（例如大润发）创建的供应商履约订单与结算事实
-- 对标 sc_o2o_supplier_order；到店即完成，支付成功即履约成功，无 225 推送与渠道通知收敛。
CREATE TABLE sc_instore_supplier_order (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购供应商订单内部主键',
    supplier_order_no VARCHAR(32) NOT NULL COMMENT '到店购供应商订单稳定业务编号',
    order_id BIGINT NOT NULL COMMENT '供应链总订单主键，逻辑引用sc_supply_chain_order.id；到店购一笔采购至多一张有效成功供应商订单',
    instore_order_id BIGINT NOT NULL COMMENT '到店购渠道订单头主键，逻辑引用sc_instore_channel_order.id',
    payment_flow_id BIGINT NOT NULL COMMENT '触发本供应商订单的成功支付流水主键，逻辑引用sc_instore_payment_flow.id',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键',
    supplier_id BIGINT NOT NULL COMMENT '固定外部商户供应商主键，逻辑引用sc_supplier.id',
    supplier_channel_binding_id BIGINT NOT NULL COMMENT '受理时冻结的渠道商户映射版本主键，逻辑引用sc_supplier_channel_binding.id',
    supplier_code_snapshot VARCHAR(32) NOT NULL COMMENT '固定外部商户供应商编码快照',
    supplier_name_snapshot VARCHAR(128) NOT NULL COMMENT '固定外部商户供应商名称快照',
    channel_code VARCHAR(32) NOT NULL COMMENT '到店购渠道编码，一期为RT_MART',
    channel_order_id VARCHAR(128) NOT NULL COMMENT '旅通订单号order_id快照，便于对账与运维检索',
    trade_id VARCHAR(128) NOT NULL COMMENT '触发本单的成功支付流水号trade_id快照',
    settlement_mode VARCHAR(32) NOT NULL COMMENT '结算模式：CHANNEL_AMOUNT按渠道金额结算，一期固定',
    channel_pay_amount BIGINT NOT NULL COMMENT '成功支付流水金额快照，单位为分',
    supplier_settlement_amount BIGINT NOT NULL COMMENT '供应商应结金额，单位为分；一期等于channel_pay_amount',
    currency CHAR(3) NOT NULL COMMENT '结算币种，一期固定CNY',
    fulfillment_status SMALLINT NOT NULL COMMENT '履约状态：0待通知，10通知中，20结果未知，30成功，40失败，90人工处理；到店即完成，支付成功即30',
    settlement_status SMALLINT NOT NULL COMMENT '结算核对状态：0待对账，10已匹配，20有差异，90人工处理',
    external_fulfillment_no VARCHAR(128) NULL COMMENT '渠道侧履约单号，由权威查单返回；到店购无225推送',
    active_flag TINYINT NULL COMMENT '活动尝试标记：1非终态，NULL终态，用于限制同一采购订单只有一个活动供应商订单',
    effective_success_flag TINYINT NULL COMMENT '有效成功标记：1唯一有效成功，NULL其他，用于限制同一采购订单至多一张有效成功',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '供应商订单乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '供应商订单创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '供应商订单最后更新时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '供应商订单进入业务终态时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_supplier_order_no (supplier_order_no),
    UNIQUE KEY uk_sc_instore_supplier_order_order (order_id),
    UNIQUE KEY uk_sc_instore_supplier_order_instore (instore_order_id),
    UNIQUE KEY uk_sc_instore_supplier_order_flow (payment_flow_id),
    UNIQUE KEY uk_sc_instore_supplier_order_active (order_id, active_flag),
    UNIQUE KEY uk_sc_instore_supplier_order_success (order_id, effective_success_flag),
    UNIQUE KEY uk_sc_instore_supplier_order_external (supplier_id, external_fulfillment_no),
    KEY idx_sc_instore_supplier_order_supplier (supplier_id, created_at),
    KEY idx_sc_instore_supplier_order_status (fulfillment_status, updated_at),
    KEY idx_sc_instore_supplier_order_settlement (settlement_status, updated_at),
    CONSTRAINT ck_sc_instore_supplier_order_mode CHECK (settlement_mode = 'CHANNEL_AMOUNT'),
    CONSTRAINT ck_sc_instore_supplier_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_sc_instore_supplier_order_amount CHECK (channel_pay_amount >= 0 AND supplier_settlement_amount = channel_pay_amount),
    CONSTRAINT ck_sc_instore_supplier_order_flags CHECK (active_flag IS NULL OR active_flag = 1),
    CONSTRAINT ck_sc_instore_supplier_order_success_flag CHECK (effective_success_flag IS NULL OR effective_success_flag = 1),
    CONSTRAINT ck_sc_instore_supplier_order_external CHECK (external_fulfillment_no IS NULL OR external_fulfillment_no <> ''),
    CONSTRAINT ck_sc_instore_supplier_order_fulfillment CHECK (fulfillment_status IN (0, 10, 20, 30, 40, 90)),
    CONSTRAINT ck_sc_instore_supplier_order_settlement_status CHECK (settlement_status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到店购场景固定外部商户供应商履约订单与结算事实';
