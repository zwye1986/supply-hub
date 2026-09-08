# 供应链中台核心跨系统调用时序

本文档是《供应链中台一期核心设计》的配套时序设计，只表达参与方之间的业务调用，不展开数据库读写和中台内部事务步骤。一期 SaaS 商城与中台双向都走内部 `RPC`：组合部署使用本地 `ApiImpl`，拆分部署使用 Feign 实现同一 Interface。保留的 `HTTP + HMAC` 是后续其他采购商 Adapter，不得在 RPC 失败后自动切换。

## 主数据同步

```mermaid
sequenceDiagram
    autonumber
    participant O as 中台运营端
    participant C as 供应链中台核心服务<br/>trade-core
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）
    participant M as SaaS 商城

    O->>C: 发布平台商品、采购商、供应商<br/>及场景供应商路由规则
    C-->>O: 返回发布版本
    X->>M: Outbox + RPC 通知商品变更（CATALOG_CHANGED）
    M-->>X: 确认事件（eventId）
    M->>C: 拉取商品增量 getCatalogChanges(cursor, limit)
    C-->>M: 返回已发布商品快照和下一页游标（nextCursor）
```

`CATALOG_CHANGED` 只负责唤醒，商城必须通过游标拉取中台发布快照；通知重复或丢失都不能导致商品真相分叉。商城商品表是消费投影，不得反向修改中台标准商品、报价或供应商映射。

## 卡券、卡密与直充履约

```mermaid
sequenceDiagram
    autonumber
    participant U as C 端用户
    participant M as SaaS 商城
    participant C as 供应链中台核心服务
    participant O as 订单核心模块（order-core）
    participant R as 供应商路由模块（routing）
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）
    participant A as 供应商适配模块（supplier-adapter）
    participant Q as 供应商

    U->>M: 在商城完成支付
    M->>C: acceptFulfillment<br/>buyerOrderNo + buyerUserId + quantity=1
    C->>C: 短事务创建/锁定PROCESSING回执<br/>暂存可清除的加密恢复快照
    C->>C: 事务外校验命令并采集动态资格事实
    C->>R: 计算完整有序供应商候选与策略版本
    R-->>C: 返回确定性候选快照
    alt 业务校验、余额或路由失败
        C->>O: 最终短事务创建FAILED BuyerOrder<br/>并记录failureStage/failureCode
        Note over C: 不创建FulfillmentRequest、SupplierOrder或资金流水
        C->>C: 回执置ORDER_CREATED、关联失败orderNo<br/>并清除暂存密文
        C-->>M: 正常返回orderNo + FAILED + failureCode
    else 校验成功且资金冻结成功
        C->>C: 最终短事务原子创建BuyerOrder、FundReservation、<br/>FulfillmentRequest、冻结候选、首张SupplierOrder及Operation
        C->>O: BuyerOrder置FULFILLING并显示当前Supplier
        C->>C: 回执置ORDER_CREATED并清除暂存密文
        C-->>M: 返回orderNo + FULFILLING
        C->>X: 提交首张供应商订单异步履约任务
        X->>C: 触发SupplierOrderOperation
        C->>A: 使用稳定supplierRequestNo调用Adapter
        A->>Q: 提交直充或发码订单
        alt 权威成功
            Q-->>A: 成功结果或验签回调
            A-->>C: SUCCEEDED
            C->>C: 设置唯一有效成功并提交采购资金一次
        else 权威失败且仍有冻结候选
            Q-->>A: 明确失败
            A-->>C: FAILED
            C->>C: 原子终结当前SupplierOrder<br/>创建下一张SupplierOrder及Operation
            C->>X: 提交下一候选履约任务
        else 全部候选权威失败
            C->>C: 释放采购资金一次<br/>BuyerOrder置FAILED并显示第一候选
        else 超时或结果未知
            A-->>C: RESULT_UNKNOWN
            C->>C: 保持资金冻结并禁止切换Supplier
            X->>C: 使用原supplierRequestNo触发查单
        end
        alt 奇那梦权威成功但缺少卡密
            C->>C: SupplierOrder保持有效成功并提交资金一次<br/>BuyerOrder置MANUAL
            C->>C: 幂等创建RECOVER_ASSET异常工单
            Note over C: 不重发、不换供应商、不释放资金
            C->>C: 补录并加密卡密<br/>BuyerOrder转SUCCEEDED且不二次扣款
        end
    end
    O->>X: 提交订单状态通知任务
    X->>C: 触发商城状态通知
    C->>M: 通知订单状态或履约结果
    M-->>C: 确认事件（eventId）
    opt 卡密或券码交付
        M->>C: 查询卡密或券码资产
        C-->>M: 鉴权、审计后的敏感资产
    end
```

商城不直接调用供应商，也不保存 SupplierOrder 历史。受理 RPC 超时时只能按原 `(buyerId,buyerOrderNo)` 查询回执或原键重试；`ORDER_CREATED` 可能对应 FULFILLING 或 FAILED BuyerOrder，只有边界 `REJECTED_FINAL` 才没有订单。同键同摘要返回原结果，同键不同摘要永久冲突。中台受理后不支持采购方自动取消、退款或撤销；供应商结果未知必须查单，禁止换业务号或切换 Supplier。

## O2O 入口与 H5 跳转

```mermaid
sequenceDiagram
    autonumber
    participant U as C 端用户
    participant M as SaaS 商城 / App
    participant C as 供应链中台核心服务<br/>BuyerO2OEntryFacade
    participant T as taobao-flash-channel-service
    participant E as 淘宝闪购 / ele.me

    U->>M: 点击淘宝闪购入口
    M->>C: 获取 O2O 入口 getH5Entry(buyerUserId, returnUrl)
    C->>C: 鉴权采购商并创建/读取<br/>O2OChannelUserBinding
    C->>T: 类型化 SSO 命令<br/>携带随机 channelUserId
    T->>E: 调用 SSO SDK / getTbsgH5UrlBySso
    E-->>T: 一次性 H5 链接
    T-->>C: 规范化入口结果
    C-->>M: H5 URL + 有效期
    M-->>U: 打开盒马 H5
```

中台不透传任意协议参数；渠道服务负责 ele.me SDK、签名和编解码。`channelUserId` 是中台生成的不可枚举随机标识，不包含 Buyer 或用户内部标识。此时尚未创建 BuyerOrder，也不冻结资金，盒马 H5 内商品仍由渠道侧维护。

## O2O 下单

```mermaid
sequenceDiagram
    autonumber
    participant U as C 端用户
    participant B as SaaS 商城 / App
    participant E as 淘宝闪购 / ele.me
    participant T as taobao-flash-channel-service
    participant C as 供应链中台核心服务<br/>O2OChannelCommandFacade
    participant P as 供应链中台核心服务<br/>BuyerO2OPaymentCallbackFacade
    participant O as 订单核心模块（order-core）
    participant M as 收银台
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）

    U->>E: 在盒马 H5 下单
    E->>T: PayRequest(transactionId, uid/channelUserId,<br/>externalMerchantId, payAmount)
    T->>T: 验签、解码、防重放
    T->>C: 准备支付 preparePayment
    C->>C: 按 channelCode + channelUserId<br/>解析 Buyer/BuyerUser，失败则拒绝
    C->>C: 以 channelCode + transactionId 全局防重<br/>映射盒马Supplier并创建O2OChannelDetail<br/>按payAmount冻结BuyerAccount
    C->>O: 创建供应链订单（BuyerOrder）并投影处理中状态
    C->>C: 创建并 CAS 抢占 PREORDER Operation<br/>operationNo 作为 invocationId<br/>FUNDS_RESERVED → CASHIER_CREATING
    C->>M: 创建收银台订单 prepay(outTradeNo, payAmount)
    M-->>C: 收银台参数
    C->>C: 记录收银台订单 recordCashierOrder
    C-->>T: PayResponse
    T-->>E: 返回拉起收银台参数
    E-->>U: 拉起收银台
    U->>M: 完成用户支付
    M->>P: 支付成功回调 confirmPayment<br/>(outTradeNo, payAmount)
    P->>P: 按 outTradeNo 加载固化的 Buyer/BuyerUser<br/>并核对收银台 Principal
    P->>P: 确认资金冻结（FundReservation）<br/>并扣减采购商账本
    P->>O: 为固定盒马Supplier创建唯一SupplierOrder<br/>及关联的CHANNEL_NOTIFY O2OOperation
    P->>O: 提交用户支付事实并更新供应链订单状态
    P-->>M: 幂等确认成功
    P->>X: 提交渠道支付结果通知任务
    X->>C: 触发渠道支付结果通知
    C->>T: 通知 ele.me 支付结果
    T->>E: SDK 通知 / 查询
    E-->>T: 已接受支付通知
    T->>C: 支付通知已收敛
    C->>O: SupplierOrder置PROCESSING<br/>继续等待权威履约终态
    alt ele.me 确认并最终履约成功
        E-->>T: 渠道状态 / 225 推送
        T->>C: 更新渠道订单状态 applyChannelStatus
        C->>O: 提交履约成功事实并更新供应链订单状态
        O->>X: 提交商城订单状态通知任务
        X->>C: 触发商城状态通知
        C->>B: 按原 eventId 通过 RPC 通知供应链订单成功
    else 通知超时或结果未知
        C->>X: 创建渠道状态补偿任务
        X->>C: 触发渠道重试或查单
        C->>T: 按原渠道交易号重试或查单
        C->>O: SupplierOrder置RESULT_UNKNOWN<br/>禁止切换外部商户
        Note over C: 账本保持已扣减，BuyerOrder保持FULFILLING
    else ele.me 明确拒绝且无法接受支付
        T->>C: 渠道明确失败
        C->>O: 提交渠道失败事实并更新供应链订单状态
        C->>C: 创建支付补偿（PAYMENT_COMPENSATION）
        C->>X: 提交支付补偿任务
        X->>C: 触发支付补偿
        C->>M: 向用户发起原路退款
    end
```

用户支付发生在采购商侧；中台只协调支付结果，并在通知 ele.me 前原子确认采购资金。只有 CAS 抢占 PREORDER Operation 成功的请求可以调用收银台，并发请求只能读取已有结果或按原业务键查单。同一 `(channelCode,transactionId)` 的用户、Buyer、金额、商户或指纹不一致时属于身份冲突，不能建立第二张订单。`payAmount` 以 CNY 分传输，是冻结、扣减、退款及与 ele.me 对账的唯一一期金额基线；采购商内部 POINT 换算不进入中台。渠道通知未知不能释放或重复扣减资金。

## O2O 退款

```mermaid
sequenceDiagram
    autonumber
    participant E as 淘宝闪购 / ele.me
    participant T as taobao-flash-channel-service
    participant C as 供应链中台核心服务
    participant O as 统一订单模块（order-core）
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）
    participant M as 采购商退款服务

    E->>T: RefundRequest(refundNo, transactionId, refundAmount)
    T->>T: 验签、解码、防重放
    T->>C: 受理退款 acceptRefund<br/>（channelCode + refundNo 为永久幂等键）
    C->>C: 创建 O2O 退款单（O2ORefundCase）<br/>并预留退款额度
    C->>O: 提交退款受理事实并标记售后处理中
    C-->>T: PROCESSING
    T-->>E: PROCESSING
    C->>X: 提交采购商退款任务
    X->>C: 按退款序号触发退款
    C->>M: 发起退款 refund(middleRefundNo, refundAmount)
    alt 明确退款成功
        M-->>C: SUCCEEDED
        C->>C: 确认退款并返还采购商账户（BuyerAccount）
        C->>O: 提交退款成功事实并更新售后状态
        C->>X: 提交渠道退款结果通知任务
        X->>C: 触发渠道退款结果通知
        C->>T: 通知退款成功
        T->>E: SDK 通知退款结果
    else 结果未知
        M-->>C: UNKNOWN / timeout
        C->>X: 创建退款补偿查询任务
        X->>C: 触发退款查询
        C->>M: 按同一中台退款号查退款
        Note over C: 保持退款额度预留，不重复退款、不返还账户
    else 明确永久失败
        M-->>C: FAILED
        C->>C: 释放本次退款额度并记录失败
        C->>O: 提交退款失败事实并更新售后状态
        C->>X: 提交渠道退款失败通知任务
        X->>C: 触发渠道退款失败通知
        C->>T: 通知退款失败
    end
    Note over O: 累计确认退款等于原 payAmount 时才标记全部退款（FULLY_REFUNDED）
```

渠道服务、采购商退款服务都不修改中台订单或账本。中台先把退款对外置为 `PROCESSING`，再串行调用采购商；只有采购商明确退款成功后才返还 BuyerAccount 并通知 ele.me，避免“用户未退款但采购资金已返还”的结算漏洞。

O2O 属于 M2：只有 SaaS 商城提供并通过权威 `queryPayment(outTradeNo)` 与 `queryRefund(refundKey)` ContractTest 后才允许生产发布。M1 的奇那梦标准商品履约不依赖该能力，可独立上线。

## 点餐子系统

点餐子系统同时承担 H5 场景、业务编排和渠道协议职责，定位等价于“淘宝闪购/ele.me + 淘宝闪购渠道服务”的组合。菜品、规格、购物车和配送/取餐信息由点餐子系统维护，不进入中台标准商品池；采购商（Buyer）、餐厅供应商（Supplier）、子系统商户映射及场景供应商路由规则由中台统一维护。中台不设置点餐场景编排模块，只通过开放接口提供主数据、供应商路由、统一订单、账本和状态映射能力。

```mermaid
sequenceDiagram
    autonumber
    participant U as C 端用户
    participant M as SaaS 商城 / App
    participant C as 供应链中台开放接口
    participant O as 统一订单模块（order-core）
    participant F as 主数据与交易核心模块（trade-core）
    participant R as 供应商路由模块（routing）
    participant S as 状态映射模块
    participant D as 点餐子系统
    participant P as 收银台
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）

    U->>M: 点击点餐入口
    M->>C: 获取点餐 H5 入口
    C->>F: 校验采购商（Buyer）及点餐场景配置
    F-->>C: 返回采购商身份和有效配置
    C->>D: 请求生成用户入口
    D-->>C: 返回一次性 H5 地址
    C-->>M: 返回 H5 地址和有效期
    M-->>U: 打开点餐 H5
    U->>D: 选餐并提交订单
    D->>C: 提交点餐订单、应付金额及外部商户标识
    C->>F: 加载采购商、餐厅供应商及商户映射
    F-->>C: 返回有效主数据和映射版本
    C->>R: 按采购商、场景和外部商户请求供应商路由
    R-->>C: 返回选中供应商、规则版本和决策快照
    C->>O: 创建供应链订单（BuyerOrder）<br/>及场景详情并固化路由结果
    C->>F: 按订单金额冻结采购商账户（BuyerAccount）
    C->>P: 创建收银台订单
    P-->>C: 返回收银台参数
    C-->>D: 返回拉起收银台参数
    D-->>U: 拉起收银台
    U->>P: 完成用户支付
    P->>C: 通知支付结果
    C->>F: 确认采购资金冻结
    C->>O: 更新供应链订单状态
    C-->>P: 幂等确认成功
    C->>X: 提交支付结果通知任务
    X->>C: 触发支付结果通知
    C->>D: 通知用户支付成功
    alt 点餐子系统主动推送状态
        D->>C: 推送点餐订单状态
    else 状态通知丢失或结果未知
        C->>X: 创建状态补偿查询任务
        X->>C: 触发状态查询
        C->>D: 查询点餐订单状态
        D-->>C: 返回权威订单状态
    end
    C->>S: 映射点餐原始状态
    S->>O: 提交业务阶段和类型化状态事实
    O->>X: 提交商城订单状态通知任务
    X->>C: 触发商城状态通知
    C->>M: 通知订单状态变化
```

点餐子系统的待支付、制作中、待取餐、完成和取消状态只作为外部场景事实进入中台状态映射。只有“已完成”映射为供应链订单成功；待取餐仍是处理中。中台统一维护采购商、餐厅供应商、商户映射和场景供应商路由规则，并在建单时固化选中供应商、规则版本和决策快照。路由不依赖菜品 SKU 或供应商报价；没有可用候选时必须在创建订单和冻结资金前拒绝。

## 电影子系统

电影子系统同样承担 H5 场景、业务编排和渠道协议职责。影片、场次、座位、票价和出票信息由电影子系统维护，不进入中台标准商品池；采购商（Buyer）、影院供应商（Supplier）、子系统商户映射及场景供应商路由规则由中台统一维护。中台不设置电影场景编排模块，只通过开放接口提供主数据、供应商路由、统一订单、账本和状态映射能力。

```mermaid
sequenceDiagram
    autonumber
    participant U as C 端用户
    participant M as SaaS 商城 / App
    participant C as 供应链中台开放接口
    participant O as 统一订单模块（order-core）
    participant F as 主数据与交易核心模块（trade-core）
    participant R as 供应商路由模块（routing）
    participant S as 状态映射模块
    participant V as 电影子系统
    participant P as 收银台
    participant X as 事务消息箱（Outbox）<br/>持久化定时器（PersistentTimer）

    U->>M: 点击电影入口
    M->>C: 获取电影 H5 入口
    C->>F: 校验采购商（Buyer）及电影场景配置
    F-->>C: 返回采购商身份和有效配置
    C->>V: 请求生成用户入口
    V-->>C: 返回一次性 H5 地址
    C-->>M: 返回 H5 地址和有效期
    M-->>U: 打开电影 H5
    U->>V: 选影院、场次和座位并提交订单
    V->>C: 提交电影订单、应付金额及外部商户标识
    C->>F: 加载采购商、影院供应商及商户映射
    F-->>C: 返回有效主数据和映射版本
    C->>R: 按采购商、场景和外部商户请求供应商路由
    R-->>C: 返回选中供应商、规则版本和决策快照
    C->>O: 创建供应链订单（BuyerOrder）<br/>及场景详情并固化路由结果
    C->>F: 按订单金额冻结采购商账户（BuyerAccount）
    C->>P: 创建收银台订单
    P-->>C: 返回收银台参数
    C-->>V: 返回拉起收银台参数
    V-->>U: 拉起收银台
    U->>P: 完成用户支付
    P->>C: 通知支付结果
    C->>F: 确认采购资金冻结
    C->>O: 更新供应链订单状态
    C-->>P: 幂等确认成功
    C->>X: 提交支付结果通知任务
    X->>C: 触发支付结果通知
    C->>V: 通知用户支付成功
    alt 电影子系统主动推送状态
        V->>C: 推送电影订单状态
    else 状态通知丢失或结果未知
        C->>X: 创建状态补偿查询任务
        X->>C: 触发状态查询
        C->>V: 查询电影订单状态
        V-->>C: 返回权威订单状态
    end
    C->>S: 映射电影原始状态
    S->>O: 提交业务阶段和类型化状态事实
    O->>X: 提交商城订单状态通知任务
    X->>C: 触发商城状态通知
    C->>M: 通知订单状态变化
```

电影子系统的初始、待处理、出票中、已出票、完成、失败、取消和退款状态只作为外部场景事实进入中台状态映射。电影子系统出票成功并返回取票凭证（`ISSUED`）即代表供应链履约成功（`SUCCEEDED`），中台采购订单进入完成终态并提交采购资金扣减（线下纸质票打印与实际观影属于后续消费进度，不影响供应链订单终态）；退款状态只推进售后状态，不能覆盖主订单状态。中台统一维护采购商、影院供应商、商户映射和场景供应商路由规则，并在建单时固化选中供应商、规则版本和决策快照。路由不依赖影片、场次或供应商报价；没有可用候选时必须在创建订单和冻结资金前拒绝。
