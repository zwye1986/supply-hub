# 供应商履约参照实现切换：从 QNM 到 HUA_SHENG — 修改方案（修订版 v2）

> **2026-10-09最新范围覆盖：** 用户已确认第一张type201采用单实例、华昇专用持久步骤＋现有Operation/Timer，并要求“先不考虑QNM”。生产接线、checkpoint、原单恢复、回调、资产与资金以 `docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md` 为准；实施计划见同日 `huasheng-type201-production-closure.md`。本文旧通用步骤／分布式回调锁／QNM切换候选不再作为本期实施依据。A1～A5已在当前基线，相关“未实施”文字只代表历史时点；正式协议与用户补充来源仍保留。新生产闭环尚未实现或验收。

> 状态：分切片实施中；§12.1 通信协议与§12.3 凭据绑定已验收，多步骤与回调须先完成各自规格审查。修订日期：2026-10-04（正式文档已读取，§12.6区分已确认事实与仍待确认的资金/重复调用保证）。
> 版本说明：本文为 v2，取代同日初版 v1。v1 存在三处 P0 缺陷（误判 checkpoint 机制可用、误判 DDL 无 CHECK 约束、误判 extraParameters 可用），修订依据见 §3。v2.1 不改动协议与架构决策，仅按供给发布文 v4 定案同步地基出处、变更集编号与命名。v2.2 按当前实际代码与旧系统协议消费行为修正后续实施约束，不能将旧系统消费行为当作供应商协议保证。
> 命名约定：供应商/适配器一律用 `HUA_SHENG`、`QNM` 表示（对应 `adapterType` `HUA_SHENG_ORDER_V1`/`QNM_GAME_ORDER_V1`），prose 与代码标识符不用中文名；面向运营的供应商展示名称可保留中文。供应商业务编号由现有管理端服务生成，不强制改为预设代码。
> 关联设计：`2026-08-07-supply-chain-phase1-detailed-design.md` §7.3 §7.4 §10.1 §10.2
> 关联设计（管理端供给发布，共建地基）：`2026-10-03-supplier-offer-publication-admin-design.md`（**v4 已定案**）。**该文 §5 是 `AdapterMetadata` 扩展（`defaultExecutionProfile()`/`credentialKeys()`/`mappingTemplates()`/`fulfillmentSteps()`）+ AD-3 凭据集 + AD-5 `extra_config` 共享地基规格的唯一出处**；本文引用其规格、不重复描述，只落地 HUA_SHENG 专属部分（协议适配器、多步骤编排、回调入口、token、配置录入）。两文协调点见该文 §14 与本文 §7.0。
> 变更集编号：054–057 已落地，不能修改历史 SQL。当前第二切片新增 058 连接器凭据集绑定与长密文存储；后续 checkpoint 等迁移必须读取实际 changelog 后顺延编号。不得执行旧版本中复用 slot 主键或虚构摘要的 HUA_SHENG 种子 SQL。
> 旧系统参照：`/Users/mac/Documents/codeSpace/cardshop` `huasheng` 包

---

## 1. 目标与边界

**语义**：以 HUA_SHENG 作为新的首要参照实现完整落地；QNM 代码与测试保留，作为多供应商扩展性的第二验证用例。

**目标**：

1. 新增 `HUA_SHENG_ORDER_V1` 适配器，实现两段式下单（`placeOrder` → `confirmOrder`）、主动取券（`takeCard`）、查单、token 鉴权、加密通信；取消未交付订单另行细化，不属于第一条交付链路。
2. 落地供应商回调 HTTP 入口（补 M1 计划 Task 12），抽象为按 `adapter_type` 路由的通用骨架。
3. 落地**多步骤履约编排**（新建，非复用）。
4. 落地 HUA_SHENG 的多密钥材料注入（三密钥）。
5. 经真实配置服务创建供应商、凭据集、连接器与供给；需要的增量结构迁移单独交付，不绕过发布校验。
6. 设计文档、集成测试文档同步。

**不在范围**：删除 QNM 代码；改造路由层；改造资金冻结/账本；接入品牌/卡券列表查询（非履约范畴）；HUA_SHENG 多数量（`num>1`）支持（见 §6 AD-6）。

---

## 2. 协议差异与现有代码能力对照

| 维度 | QNM（现参照） | HUA_SHENG（新参照） | 现有代码是否已具备 |
| --- | --- | --- | --- |
| 签名 | MD5 大写，参数拼接 | `SHA512withRSA` PKCS#1 v1.5，**签名原文为 AES 密文 hex** | 需新增 |
| 加密 | 无 | AES-CBC + PKCS5Padding，key=appSecret、IV=appId | 需新增 |
| 请求介质 | 表单 | AES 密文 hex 字符串（`text/plain`） | 需新增 HttpExecutor |
| 响应介质 | JSON | **明文 JSON**（仅请求与回调体加密） | 已有 ObjectMapper 能力 |
| 鉴权 | 每次带 userkey | `access_token`（读取 expires_in；旧实现兜底7200s，提前300s刷新，61有界重试） | 需新增 TokenManager |
| 下单 | 一段式 `Send`/`SendKm` | **两段式** `placeOrder`→`confirmOrder` | **需新建编排** |
| 取券 | 同步返回 | 主动 `POST /api/order/takeCard` | 需新增 step |
| 双号 | `billno` | `outOrderSn`（本地）+ `orderSn`（HUA_SHENG） | ✅ `billno` / `external_order_no` |
| 回调 | 明文表单 | AES-CBC hex body + `Appid`/`X-Sign`，RSA 公钥验签 | 需新增 Controller |
| 错误码 | 散落常量 | 30+ 码三级分类（明确失败/待查单/补单） | ✅ `sc_supplier_error_code_map` 已支持 |
| 状态映射 | 3 状态 | 外部 WAIT/SUCCESS/FAILED/CANCEL；SUCCESS 还须取券 | 状态映射表已具备，最终交付须额外校验资产 |
| 金额 | `sumPrice` | `totalPrice` | ✅ `reportedSettlementAmount` |
| 不确定响应 | 无 | 7014 等先保留原订单身份，查单后决定恢复 | 禁止仅凭错误码重新下单 |
| 三密钥 | 1 个 secretKey | appSecret + privateKey + callbackPublicKey | **需扩展 ticket 契约** |

---

## 3. 现有代码事实核验（v2 修订依据）

以下为重新核实的关键事实。v1 基于错误前提，本节是修订的证据基础。

### 3.1 checkpoint 机制确为死代码（v1 误判）

| 事实 | 证据 |
| --- | --- |
| `nextCheckpoint` 无任何消费方 | 全仓 Grep 仅命中 `OperationOutcome.java:26,31,35,36`（定义与防御拷贝） |
| `checkpoint_ciphertext` 从不写入 | 全仓 Grep `setCheckpointCiphertext` 在 main 源码**零出现** |
| `handleAccepted` 不写 checkpoint | `SupplierOrderLifecycle.java:641-662` 仅写 `status/resultClass/externalOrderNo/attemptCount` |
| Ticket 侧已在读但恒为 null | `FulfillmentOperationTicketFactory:61` `.checkpointPayload(operation.getCheckpointCiphertext())` |

**结论**：多步骤编排需从零落库与恢复，不能"复用"。

### 3.2 充值拒绝续履约不能直接用于卡券步骤成功

当前方法名是 `SupplierOrderLifecycle.confirmTrustedRejectedAndContinue`，其前置条件包含非空手机号频控资格和可信 REJECTED 事实。其 `validateContinuation` 只允许尚未发送的 `SEND`/`SEND_KM` 操作，并核对活动 B 单和队列身份。该机制服务于充值失败后的受控续履约，不能通过扩大步骤枚举就成为卡券成功的下一步。

可复用的是 `RechargeFrequencyLifecycle.lockForResult` 的聚合锁定能力：非 MOBILE 卡券也锁 Request→Order→同 Order 的全部 BOrder（id排序）→Operation（id排序），不创建虚假手机号队列。卡券必须新增明确的步骤推进事务，核对原认领版本、冻结连接器/B 单身份、当前步骤和已发送状态。推进与最终交付资金收敛分开；不得把 ACCEPTED 改成 REJECTED。

### 3.3 DDL 的实际 CHECK 约束（v1 误判两处）

```
ck_sc_fulfillment_operation_kind    CHECK (operation_kind IN ('FULFILL','QUERY_ORDER','QUERY_ASSET','VOID_ASSET','UNBIND_ASSET'))
ck_sc_fulfillment_operation_status  CHECK (status IN (0,10,20,30,40,50,90))
ck_sc_b_order_quantity              CHECK (requested_quantity = 1 AND fulfilled_quantity IN (0,1))
ck_sc_supplier_connector_capability_code CHECK (capability_code IN (...,10 项))
```

`adapter_step_code VARCHAR(64)` 与 `result_class VARCHAR(32)` **无 CHECK 约束**，扩展 step 无需 ALTER。

### 3.4 架构测试约束（v1 误判需放宽）

- `adapter_does_not_write_orders`（`:73-75`）：`..supplieradapter..` 不得依赖 `..border..dal..`。
- `admin_controllers_do_not_depend_on_supplier_adapter`（`:188-191`）：**仅约束 `..interfaceapi.admin..`**。新建 `..interfaceapi.callback..` 不在约束范围，回调 Controller 可依赖 `supplieradapter` 的 Parser，**无需放宽既有规则**。
- 无规则禁止 `supplieradapter` 访问 Redis/外部依赖。

### 3.5 extraParameters 传递链断裂（v1 误判）

`FulfillmentOperationTicketFactory:62` 硬编码 `.extraParameters(Map.of())`；`QnmSupplierAdapter:115` 遍历的 `extraParameters` 恒为空 Map，从未生效。HUA_SHENG 需要 `expireTime`/`outMobile`，传递链必须打通。

---

## 4. 架构决策

### AD-1：新增事实驱动的卡券步骤推进

**决策**：同一 BOrder 下以 `FULFILL` 操作表示 PLACE_ORDER、CONFIRM_ORDER、TAKE_CARD。正常 PLACE 返回 WAIT 才需要 CONFIRM；PLACE 返回 SUCCESS 可跳过 CONFIRM，直接待取券。供应商 SUCCESS 表示供应商订单成功，尚不能推断本地资产已交付。

新增应用层步骤推进事务，按现有聚合锁序锁定请求与订单，核对外呼时冻结的操作版本和身份。同一事务完成旧步骤、写入加密 checkpoint、创建唯一下一步及其持久化 Timer；资金沿用本笔订单的预占，步骤接受不提交或释放。重复事实只留审计或返回已推进，不创建第二个下一步骤。原手机号充值续履约入口及 SEND/SEND_KM 判定保持独立。

网络超时、响应非法或单号不一致进入结果未知，禁止原外呼 Timer 再次发送。需要专用卡券查单与恢复任务，通过原 outOrderSn/orderSn 查询可信状态后恢复本条步骤链。具体恢复代次、超时与幂等键须在 Task3 详细规格中定案后实施。

### AD-2：checkpoint 落库与恢复（新建）

**落库**：步骤推进服务收到 `outcome.nextCheckpoint()` 时，用现有 `EnvelopeCryptoPort.encrypt(checkpoint, aad)` 加密，把返回的 ciphertext/keyVersion 分别写入 checkpointCiphertext/新增 checkpointKeyVersion，并写 checkpointSchemaVersion=1。旧 API 不存在的三参 encrypt 不得沿用。

- `aad` = `operationNo|bOrderNo|adapterStepCode`，绑定操作身份，防密文跨操作重放。
- 明文结构（不含任何密钥）：
  ```json
  {"schemaVersion":1,"orderSn":"<HUA_SHENG 单号>","couponId":123,"num":1,
   "expireTime":300,"outOrderSn":"<本地单号>","placePayloadHash":"<sha256>"}
  ```

**恢复**：`FulfillmentOperationTicketFactory:61` 已在传**密文**。由应用层解密后，经 Ticket 的 checkpoint 输入传给适配器，不把密文误当明文。下一步使用新操作的 AAD 重新加密，不能复制旧 ciphertext。**适配器不依赖信封存储加解密端口**。具体字段扩展与解密入口在 Task3 规格定案。

### AD-3：三密钥材料注入

> **地基出处**：凭据集表（`sc_credential_set`/`sc_credential_item`）的规格、变更集编号（`054`）、`provider_type` 双路模型（`ENV`+`ENVELOPE`）、`AdapterMetadata.credentialKeys()` 契约与凭据护栏，**以供给发布文 v4 §5.2 为唯一出处**，本文不重复。本 AD-3 只定义 HUA_SHENG 专属的 **ticket 承载契约**（`CredentialMaterial`）与注入路径；所需凭据项键 `[appSecret, privateKey, callbackPublicKey]` 由 HUA_SHENG 的 `credentialKeys()` 声明（v4 §5.1），凭据 UI 据此渲染行数。

**问题**：`OperationTicket.secretKey` 是单 String，无法承载三密钥。

**决策**：新增内嵌记录类型，保留 `secretKey` 兼容 QNM：

```java
public record OperationTicket(
    // ... 现有字段不变 ...
    String secretKey,                     // 保留：QNM 等单密钥适配器
    CredentialMaterial credentialMaterial  // 新增
) {
    /** 供应商出站凭据材料集合。回调验签公钥仅入站使用，不进入出站材料以缩小暴露面。 */
    public record CredentialMaterial(
        String appSecret,          // AES-CBC key
        String privateKeyPem       // RSA 出站签名私钥；回调公钥不进入出站 ticket
    ) {}
}
```

**注入路径**：

1. 凭据集表由共享地基变更集 `054` 建；旧 `credential_slot_id` 继续仅引用 `sc_credential_slot.id`。新增独立 `credential_set_id` 引用 `sc_credential_set.id`，两种引用恰有一种非空，完整实施契约见 §12.3。
2. 复用已落地 `CredentialSetResolver.resolveSet(Long)` 返回 `Map<String,char[]>`；ENV/ENVELOPE 项由现有 `CredentialItemResolver` 分派，ENVELOPE AAD 仍为 `credential_set_id|item_key`。不在单槽 `CredentialProvider` 上混入集身份。
3. `FulfillmentOperationTicketFactory` 组装只包含出站 appSecret/privateKey 的 `CredentialMaterial`，callbackPublicKey 留入站路径；所有临时 char[] 在成功和失败路径擦除。Ticket/材料的 toString 不回显秘密、履约目标或 token，沿用安全失败摘要。

### AD-4：token 由应用层注入，适配器不访问 Redis

**决策**：`HuashengTokenManager` 置于 `border.fulfillment.application`，不放在 `supplieradapter`。

2026-10-04隔离通信切片的可实施规格见`2026-10-04-huasheng-http-token-parser-design.md#8-tokenmanager缓存与有界-61`，已通过独立规格复审。该规格保留本AD的应用所有权，由应用CommunicationFacade组合manager、builder、transport和parser，token仅入当次请求构造；下面TokenAuthenticatedExecutor与ExecuteHandler注入示例是旧生产接线草案，不在A实施。真实Timer接线须在Task3-C另行定案，不能把示例当已实现接口。

**理由**：适配器只做协议转换；token 缓存是横切关注点。

**契约**：

```java
/** 需要 token 鉴权的适配器实现此接口，由应用层在调用前准备 token。 */
public interface TokenAuthenticatedExecutor {
    String resolveAccessToken(OperationTicket ticket);   // 返回 null 表示需先获取
    String refreshAccessToken(OperationTicket ticket);   // 收到 61 UNAUTHORIZED 时刷新
}
```

`FulfillmentOperationExecuteHandler` 在 `operationExecutorPort instanceof TokenAuthenticatedExecutor` 时取 token 注入 `extraParameters`（键 `hs.accessToken`），遇 61 刷新重试一次。

### AD-5：打通 extraParameters 真实传递链

> **地基出处**：`sc_supplier_connector_version.extra_config` 列的规格与变更集编号（`055`）**以供给发布文 v4 §5.3 为唯一出处**（非秘密可调参数、密钥类禁入、UI 可编辑见 v4 E1）。本 AD-5 只定义 HUA_SHENG 专属的**运行时传递链**（把该列的值经 `extraParameters` 注入 ticket）。

1. `PublishedConnectorConfiguration` 从冻结版本读取并校验 extraConfig，投影为不可变非秘密参数，工厂传入 Ticket，完整契约见 §12.3。
2. HUA_SHENG 注册时声明专属 Schema；num 固定 1。expireTime 等参数只有 Schema 明确允许时可写入。
3. token 由应用层临时注入，禁止持久化进 extraConfig、配置摘要或审计正文。

### AD-6：HUA_SHENG 一期固定 `num=1`，协议层拒绝越界

**问题**：`ck_sc_b_order_quantity CHECK (requested_quantity = 1 AND fulfilled_quantity IN (0,1))` 硬约束 1。`num>1` 会在回调落库时**违反 CHECK 导致崩溃**。

**决策**：固定 `num=1`，在**协议边界显式拒绝**而非依赖 DB 崩溃：

- `HuashengSupplierAdapter` 组装 `placeOrder` 前校验 `num == 1`，否则返回 `REJECTED` + `externalErrorCode=HS_NUM_UNSUPPORTED`。
- 回调解析校验 `list.size() == num == 1`，不符则 `MANUAL_REQUIRED`，**不写终态**。
- 多数量支持列为后续议题，需另行放开 DDL 并适配资产数量模型与结算口径。

---

## 5. 代码修改方案

### 5.1 新增与改动文件

```
新增：
supplieradapter/huasheng/
  HuashengSupplierAdapter.java      # OperationExecutorPort 实现
  HuashengHttpExecutor.java         # OkHttp3，密文 hex body，明文 JSON 响应
  HuashengCrypto.java               # AES-CBC + RSA-SHA512（迁移自 cardshop）
  HuashengProtocolParser.java       # 明文 JSON 解析、金额转分
  HuashengCardMapper.java           # 卡券 7 种已知 type 字段映射（独立类）
  HuashengMaskingUtils.java         # 脱敏
  HuashengCallbackParser.java       # 回调验签+解密+事实提取
  HuashengEndpointConstants.java    # 端点与字段常量
  HuashengErrorCode.java            # 错误码枚举 + 三级分类
  HuashengProperties.java           # @ConfigurationProperties
supplieradapter/metadata/
  HuashengOrderV1Metadata.java      # TYPE = "HUA_SHENG_ORDER_V1"；实现 v4 §5.1 全部元数据方法（credentialKeys=[appSecret,privateKey,callbackPublicKey]、mappingTemplates=§7.2 状态/错误码映射、defaultExecutionProfile、fulfillmentSteps=[PLACE_ORDER,CONFIRM_ORDER,TAKE_CARD]）
interfaceapi/callback/
  SupplierCallbackController.java   # /callbacks/suppliers/**
  SupplierCallbackRouter.java
border/fulfillment/application/
  HuashengTokenManager.java         # AD-4
  FulfillmentStepSequencer.java     # AD-1 步骤推进

改动：
border/fulfillment/domain/OperationTicket.java        # +credentialMaterial（AD-3）
border/fulfillment/domain/AdapterStepCode.java         # +PLACE_ORDER/CONFIRM_ORDER/TAKE_CARD
border/fulfillment/domain/FulfillmentExceptionReasonCodes.java  # +HS_* 原因码
border/fulfillment/application/SupplierOrderLifecycle.java     # 复用最终资产/资金收敛，充值拒绝入口保持独立
border/fulfillment/application/FulfillmentOperationExecuteHandler.java  # 解密 checkpoint、串联下一步骤
border/fulfillment/application/FulfillmentOperationTicketFactory.java   # extraParameters + credentialMaterial
infrastructure/security/DefaultCredentialSetResolver.java # 独立集解析；不在单槽端口混入集身份
supplieradapter/metadata/AdapterMetadata.java          # 共享地基（v4 §5.1 唯一出处）：+defaultExecutionProfile()/credentialKeys()/mappingTemplates()；本文共建 +fulfillmentSteps() 默认方法
```

### 5.2 关键设计

#### 5.2.1 `HuashengSupplierAdapter`

```java
@Override
public OperationOutcome execute(OperationTicket ticket) {
    validateTicket(ticket);
    return switch (ticket.adapterStepCode()) {
        case PLACE_ORDER   -> executePlaceOrder(ticket);
        case CONFIRM_ORDER -> executeConfirmOrder(ticket);
        case TAKE_CARD     -> executeTakeCard(ticket);
        // HUA_SHENG 无直充端点；QNM 步骤不属于本适配器协议
        default -> throw new IllegalArgumentException(
            "HUA_SHENG 不支持步骤: " + ticket.adapterStepCode());
    };
}

private OperationOutcome executePlaceOrder(OperationTicket ticket) {
    if (ticket.quantity() != 1L) {   // AD-6
        return OperationOutcome.builder()
            .outcomeClass(OperationOutcomeClass.REJECTED)
            .externalErrorCode("HS_NUM_UNSUPPORTED").build();
    }
    Map<String,Object> payload = buildPlacePayload(ticket);
    var http = httpExecutor.postAuthorized(PATH_PLACE_ORDER, payload,
            tokenFromExtra(ticket), ticket);
    return handlePlaceResponse(ticket, http);
}

private OperationOutcome handlePlaceResponse(OperationTicket ticket, var http) {
    // 5xx/408/429/超时 → RESULT_UNKNOWN（严禁 REJECTED）
    // 4xx → MANUAL_REQUIRED
    // code != 0 且 requiresPlaceReconciliation → RESULT_UNKNOWN（待查单）
    // 7018/7022/4002/4003/66 是旧实现候选失败码；端点/身份及无后续发券保证核实后才可终拒
    // 7014 + 无本地 orderSn → 保持结果未知，先用原单号查单；不凭错误码重下
    // 成功 → ACCEPTED + nextCheckpoint(orderSn, ...)（落库由 lifecycle 完成，AD-2）
}
```

#### 5.2.2 步骤推进与恢复

以 AD-1 和后续 Task3 的审查通过规格为准。旧示例中的 `continueAfterTrustedRejection` 不存在，且现有充值拒绝入口不能消费 ACCEPTED；已删除该示例，防止误实现。同步响应、可信查单与回调应使用同一受控步骤事实入口，按当前步骤与供应商状态决定继续确认、等待、取券或最终收敛。

#### 5.2.3 checkpoint 持久化

使用 `EnvelopeCryptoPort.encrypt(byte[], aad)` 返回的 ciphertext/keyVersion，同时持久化 schemaVersion。解密需传回相同 keyVersion 和绑定操作身份的 AAD。新增 checkpointKeyVersion 迁移在 Task3 开始前确认实际编号。跨步骤传递先解密再以新 AAD 加密；写旧步骤、新步骤与 Timer 任一失败全部回滚，不在适配器写库。

#### 5.2.4 回调 Controller 与 Parser

`HuashengCallbackParser`：验签（HUA_SHENG 公钥 `SHA512withRSA`）→ AES 解密 → 必填字段与 ±300s 时间窗校验 → nonce 防重 → 双号一致性校验 → 券码数量校验 → 事实提取。

`SupplierCallbackController`（`/callbacks/suppliers/huasheng/callback`，`consumes = text/plain`）：

- 验签/解密/字段非法 → 候选 **400**（供应商是否重试未核实）
- 业务冲突（券码数量不符、终态不一致） → **409**
- 内部异常 → 候选 **500**（只有正式供应商协议可确认其重试行为）
- 成功/幂等 → 候选 **200 `success`**（响应正文与重试契约在 Task4 核实）
- 分布式锁 `HUASHENG_CALLBACK:{orderSn}`，审计写 `sc_fulfillment_call_log.direction='INBOUND'`

**架构规则目标**：回调入口只通过明确的 CallbackParser/Router 或应用层门面处理供应商协议，不直接访问 Adapter 外呼主体、HttpExecutor 或订单 DAL。Task4 以实际分层和 ArchUnit 正向/反向样例验证规则，不能照搬旧稿逻辑相反的 `noClasses` 示例，不放宽已有架构约束。

---

## 6. 待确认决策

| 决策点 | 方案选择 | 备选 | 建议 |
| --- | --- | --- | --- |
| 卡券步骤编排 | 新增受控成功推进，复用聚合锁 | 将 ACCEPTED 伪造为充值 REJECTED | 前者，后者禁止 |
| num 数量 | 固定 1，协议层拒绝越界 | 放开 DDL 支持多张 | 采纳前者，多数量另行立项 |
| 取券 step 归属 | **复用 `FULFILL` + `TAKE_CARD` step** | 新增 `TAKE_ASSET` kind | **复用 FULFILL**：`operation_kind` CHECK 约束无需改动，状态机分支更少 |
| 取消能力 | 后续独立设计 | 混入第一条取券闭环 | 后续处理，本次不扩 capability CHECK |
| checkpoint 加密方 | 应用层 `EnvelopeCryptoPort` | 适配器内自行加解密 | 采纳应用层 |
| 三密钥承载 | `CredentialMaterial` 记录 | 三次扩 ticket 字段 | 采纳记录类型 |

**迁移边界**：取券使用 `FULFILL` 无需修改 operation_kind CHECK；本次不扩取消能力，但显式凭据绑定、长密文存储与 checkpoint keyVersion 仍须独立追加迁移。

---

## 7. 数据库修改方案

### 7.0 变更集分配

054 凭据集、055 extra_config、056 published_flag、057 未退役 Offer 外部编码唯一约束均已落地；058 为 §12.3 连接器凭据集绑定，含引用互斥 CHECK 和长密文存储。后续 checkpoint 等追加迁移在实施前以实际 YAML 登记顺序定编号，不预占种子或取消能力编号。

### 7.1 地基变更集 DDL 全文（`054` 凭据集 + `055` extra_config）

> 规格出处：v4 §5.2（凭据集）/§5.3（extra_config）。以下为**已落地（as-built）** DDL，与 `db/changelog/releases/1.0.0/054-credential-set.sql`、`055-connector-version-extra-config.sql` 一致；changelog 文件为权威来源。新表建于 022 本地时区迁移之后，datetime 列注释统一 `Asia/Shanghai`；054 原始 `secret_ref` 为 `VARCHAR(512)`，不能容纳常见 RSA 私钥信封密文，058 增量改为 TEXT（见 §12.3）；`published_flag`（056）DDL 见 v4 §5.5。

```sql
-- 变更集 055：连接器版本扩展可调参数（AD-5；规格出处 v4 §5.3）
ALTER TABLE sc_supplier_connector_version
    ADD COLUMN extra_config JSON NULL
    COMMENT '连接器版本非秘密可调参数，如expireTime秒、outMobile；密钥类禁止入此列' AFTER adapter_config_json;

-- 变更集 054：凭据集与凭据项（AD-3；规格出处 v4 §5.2）
CREATE TABLE sc_credential_set (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商凭据集内部主键',
    supplier_id BIGINT NOT NULL COMMENT '所属供应商内部主键，逻辑引用sc_supplier.id',
    set_code VARCHAR(32) NOT NULL COMMENT '凭据集编号，供应商内唯一',
    status SMALLINT NOT NULL COMMENT '凭据集状态：10可用，20停用，90归档',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_credential_set (supplier_id, set_code),
    CONSTRAINT ck_sc_credential_set_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商凭据集，多密钥材料集合';

CREATE TABLE sc_credential_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭据项内部主键',
    credential_set_id BIGINT NOT NULL COMMENT '凭据集主键，逻辑引用sc_credential_set.id',
    item_key VARCHAR(32) NOT NULL COMMENT '凭据项键，如appSecret、privateKey、callbackPublicKey、secretKey',
    provider_type VARCHAR(32) NOT NULL COMMENT '密钥托管类型：ENV环境变量引用，ENVELOPE信封加密密文，KMS托管',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME',
    masked_hint VARCHAR(128) NULL COMMENT '供后台识别的脱敏提示，禁止含可还原信息',
    active_secret_version INT NOT NULL DEFAULT 1 COMMENT '当前生效密钥版本',
    status SMALLINT NOT NULL COMMENT '凭据项状态：10可用，20停用，90归档',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_credential_item (credential_set_id, item_key),
    CONSTRAINT ck_sc_credential_item_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='凭据集内的单个密钥项引用';
```

### 7.2 HUA_SHENG 配置录入

供应商、凭据集、连接器、能力、映射和供给通过当前管理端及生产配置服务创建，完整地经过 createDraft→validate→publish。供应商业务编号由现有服务分配；测试同样通过服务建立数据，不用虚构主键、预设摘要或手写已发布版本绕过校验。

HUA_SHENG 三项凭据为 appSecret/privateKey/callbackPublicKey，使用显式 credential_set_id，credential_slot_id 必须为空。元数据与映射模板只有 Task3 协议完整后才注册。PLACE/CONFIRM/QUERY 的供应商外部 SUCCESS 不能直接终结；最终 SUCCEEDED 需要完整 TAKE 响应或可信成功回调携带的一张有效卡券事实。未知外部状态保持不确定性并停止自动推进。

旧稿 §7.2 的 raw SQL 把凭据集 ID 写入单槽列并跳过发布校验，已经删除，不可作为部署或集成测试入口。

### 7.3 取消能力

第一条卡券交付链路不实施取消订单或已交付券作废，不扩展 capability CHECK。两者业务含义及供应商协议不同，须后续单独设计和验收。

### 7.4 部署 SQL

复用技术菜单「供应商履约技术配置」与现有权限，不新增按供应商预设代码过滤的专用菜单。若后续确需菜单或字典改动，只能由 script/deploy 写 system 库；供应链服务不得访问 system 表。

---

## 8. 测试方案

| 类别 | 类 | 重点 |
| --- | --- | --- |
| 单元 | `HuashengCryptoTest` | AES 加解密、RSA 签名验签、PKCS#1→X.509 包装、密钥长度校验 |
| 单元 | `HuashengProtocolParserTest` | 金额原文保留、双号提取、错误码解析；未核实单位前不生成结算分值 |
| 单元 | `HuashengCardMapperTest` | 7 种已知 type 字段映射与资产分类 |
| 单元 | `HuashengErrorCodeTest` | 三级分类、未知码兜底 |
| 单元 | `HuashengMaskingUtilsTest` | 脱敏、响应体屏蔽 |
| 单元 | `FulfillmentStepSequencerTest` | 步骤序列推进、`sequenceNo` 递进、末步不再推进 |
| 适配器 | `HuashengSupplierAdapterTest` | MockWebServer：两段式全流程、**num 越界拒绝**、5xx/4xx/超时、61 有界刷新、7014 查单保持原身份 |
| 契约 | `HuashengSupplierAdapterContractTest` | ticket 不可变性、Composite 路由、能力集与 DB 一致、封闭 Schema |
| 集成 | `HuashengFulfillmentAcceptanceIntegrationTest` | 真实配置服务 → placeOrder → 按状态confirmOrder/跳过 → takeCard 全闭环 + **checkpoint 落库与恢复** |
| 集成 | `HuashengContinuationIntegrationTest` | **可信步骤事实按锁序恰一次推进；最终取券前不提交资金** |
| 集成 | `HuashengCallbackIntegrationTest` | 回调验签、解密、状态机推进、幂等、nonce 防重 |
| 架构 | `SupplyChainModuleArchitectureTest` | 新增回调入口规则；既有架构规则对 HUA_SHENG 生效，以实际执行计数为准 |

**关键门禁**：`num>1` 必须在协议层被拒绝并有单测锁定，**不得依赖 DB CHECK 崩溃**。

---

## 9. 迁移与回滚

1. 代码 + DDL 落地，`sc_supplier.status=20`、`enabled=false`。
2. 联调：MockWebServer → 真实挡板 → 供应商沙箱。
3. 灰度：`status=10` + `enabled=true`，路由规则加入 HUA_SHENG 候选。
4. HUA_SHENG 成为主候选，QNM 降级为备份（代码保留）。

**回滚**：`enabled=false` 停止新订单（已有在途回调仍处理）；路由移除候选。DDL 均为追加/ALTER ADD，回滚只需改状态，不删表。

---

## 10. 风险登记

| 风险 | 等级 | 说明 | 缓解 |
| --- | --- | --- | --- |
| 多步骤编排触及资金一致性 | **高** | `handleAccepted` 与 `validateContinuation` 是资金与频控边界 | 复用聚合锁与最终资产/资金边界，新增受控步骤推进；补并发与资金不变量集成测试 |
| `ACCEPTED` 路径缺 `nextOperationNo` 校验 | **高** | 新增受控卡券推进，不复用充值拒绝入口 | 锁定原认领身份、当前步骤与唯一下一步，全部写入同事务 |
| 三密钥泄露 | 中 | ticket 承载多密钥 | 内存态传递、禁日志、异常脱敏沿用 `safeFailureSummary` |
| `num>1` 崩溃 | 中 | 违反 DB CHECK | 协议层拒绝（AD-6）+ 单测锁定 |
| token 刷新与频控冲突 | 中 | 61 重试会二次外呼 | 重试不重置 `attemptCount` 上限；纳入既有限频判定 |
| token 重复获取 | 低 | 多实例并发 | 缓存与锁按冻结连接器版本、appId、凭据版本身份隔离；具体实现由 Task3 细化 |
| 协议证据完整性 | 中 | 沙箱未验分支 | 标记 `EVIDENCE_INCOMPLETE` 分支禁止发布 |

---

## 11. 工作量拆解

| 任务 | 内容 | 依赖 | 备注 |
| --- | --- | --- | --- |
| T1 | 设计文档更新（§10.1/§10.2/§7.4/新增 §10.3 回调骨架） | — | — |
| T2 | `HuashengCrypto` + 单测 | T1 | — |
| T3 | `EndpointConstants` + `ErrorCode` + `ProtocolParser` + `CardMapper` + 单测 | T1 | — |
| T4 | `HuashengProperties` + `HuashengTokenManager`（应用层）+ 单测 | T2 | — |
| T5 | `HuashengHttpExecutor` + `HuashengMaskingUtils` + 单测 | T2,T4 | — |
| T6 | `HuashengSupplierAdapter` + `HuashengOrderV1Metadata` + 适配器/契约测试 | T2-T5 | — |
| T7 | 领域扩展：`AdapterStepCode` + `Capability` + 原因码 | T1 | — |
| **T8** | **三密钥：`CredentialMaterial` + `OperationTicket` + `DefaultCredentialSetResolver.resolveSet`** | T1 | **新增** |
| **T9** | **`extra_config` 列 + `extraParameters` 传递链打通** | T1 | **新增** |
| **T10** | **checkpoint 落库与恢复（`handleAccepted` 加密写库 + 工厂解密）** | T1 | **新增** |
| **T11** | **多步骤编排：`FulfillmentStepSequencer` + 卡券推进入口新增，充值判断保持独立 + `ACCEPTED` 串联** | T10 | **新增，风险最高** |
| T12 | 058 显式凭据绑定；后续 checkpoint 迁移按实际编号；配置经管理端服务创建 | T7,T8 | 编号对齐 §7.0 |
| T13 | `SupplierCallbackController` + `Router` + `CallbackParser` + 架构规则 | T6 | — |
| T14 | 集成测试（闭环/步骤推进/回调/未知结果查单/num 拒绝） | T11,T13 | — |
| T15 | 文档同步与门禁验证 | T14 | — |

**关键路径**：T8-T11 为 v2 新增四项前置，其中 T11 风险最高（触及资金与频控边界）。**建议 T8-T11 先做技术预研与单测验证可行性，再进入完整实现。**

---

## 附录 A：HUA_SHENG 端点清单

| 端点编码 | 路径 | 用途 | step |
| --- | --- | --- | --- |
| `HS_TOKEN` | `POST /api/token` | 获取 token（不带 Authorization） | — |
| `HS_PLACE` | `POST /api/order/placeOrder` | 预下单 | `PLACE_ORDER` |
| `HS_CONFIRM` | `POST /api/order/confirmOrder` | 确认下单 | `CONFIRM_ORDER` |
| `HS_TAKE` | `POST /api/order/takeCard` | 主动取券 | `TAKE_CARD` |
| `HS_QUERY` | `POST /api/order/queryOrder` | 查单 | — |
| `HS_CANCEL` | `POST /api/order/cancelOrder` | 取消订单 | — |
| `HS_BALANCE` | `POST /api/balance` | 余额查询 | — |
| `HS_CALLBACK` | `POST /callbacks/suppliers/huasheng/callback` | 回调入站 | — |

## 附录 B：HUA_SHENG 状态事实

旧系统的供应商状态常量为 WAIT、SUCCESS、FAILED、CANCEL。SUCCESS_HD 来自本地订单待交付状态，不能注册为供应商外部状态。

- WAIT：预下单待确认或供应商处理中；结合当前步骤判断确认/等待，不能最终收敛。
- SUCCESS：供应商订单成功，须继续 takeCard 并核实 orderSn、num=1、卡券 list 数量与形态；不直接映射最终 SUCCEEDED。
- FAILED/CANCEL：候选可信失败事实；仍需双号、当前步骤与迟到冲突检查，不能仅凭状态字符串释放资金。
- 未知状态或字段矛盾：结果未知或人工处置，不重下、不换供应商。

这些事实来自 cardshop 旧实现，尚无真实供应商沙箱验收。Task3 的协议规格须列明实际响应证据与无法证实的保证，保持未验证分支不可上线。

## 12. 闭环推进与实施前核验（2026-10-03）

用户已要求按「HUA_SHENG 卡券履约适配、首次报价调用接线、全配置链路验收」推进。开发在当前仓库分支进行；测试环境迁移和实际供应商联调仍需真实环境条件，自动化挡板不得标记为真实供应商验收。

### 12.1 第一切片：通信密码协议

先实现独立且无外部副作用的 `supplieradapter.huasheng.HuashengCrypto`。协议依据为旧仓库 `cardshop/cardShop-admin/src/main/java/com/ruoyi/web/mc/api/service/huasheng/HuashengClient.java` 与 `HuashengCrypto.java` 的实际请求格式；只复用协议事实，不引入旧订单、账户或 Spring 配置。

- AES 使用 `AES/CBC/PKCS5Padding`，密钥为 appSecret 的 UTF-8 字节（16、24、32 字节），IV 为 appId 的 UTF-8 字节（严格 16 字节）。正文密文编码为小写 hex。
- RSA 使用 `SHA512withRSA`；签名原文是收到/发送的密文 hex 字符串的 UTF-8 字节，不是 JSON 明文，也不对 hex 的大小写或空白进行归一化。签名编码为小写 hex。
- 出站私钥只接受 PKCS#8（`PRIVATE KEY` PEM 或裸 Base64）；回调公钥接受 X.509（`PUBLIC KEY`）和 PKCS#1（`RSA PUBLIC KEY`）。不把 PKCS#1 私钥伪装成 PKCS#8。
- 非法 AES 长度、空加解密正文、奇数长度/非 hex 密文、非法密钥编码均拒绝；校验失败的异常只含固定说明，不含密钥、正文或签名值，保留异常因果。验签对缺字段、非法签名、篡改、错误公钥返回 false。
- 测试以 JCA 独立构造的 AES/RSA 已知输入核对协议，而不只验证本实现的加解密互逆；覆盖中文正文、密文签名、明文误签、篡改、空签名、非法编码、PKCS#8/X.509/PKCS#1 公钥。

### 12.2 后续切片的修正约束

以下为代码核验后发现的旧草案缺口。对应切片开始前须先改正文相关章节及共享地基文档，使新规格成为唯一契约，不能照搬上文示意代码直接实施。

1. **凭据身份分离**：现有 `credential_slot_id` 是 `sc_credential_slot` 的逻辑引用，已有 QNM 发布与运行时都按此查询。不得重新解释为 `sc_credential_set.id`；HUA_SHENG 必须使用新增显式 `credential_set_id`，由元数据声明凭据模式，在连接器创建、校验、发布和运行时统一校验供应商归属及完整项键。054/055/056/057 已交付 SQL 不修改。
2. **步骤推进不伪造拒绝**：现有 `confirmTrustedRejectedAndContinue` 必须携带手机号频控资格且只对可信拒绝有效。卡券 PLACE_ORDER/CONFIRM_ORDER 的 ACCEPTED 不得冒充 REJECTED；新增明确的多步骤推进事务，复用订单/请求/B 单锁、原外呼资格和资产资金收敛边界。前一步完成、下一步创建、checkpoint 冻结和 Timer 注册同事务提交，并发/重放不产生第二步；预下单或确认成功尚不代表券已交付，不扣最终资金。
3. **结果未知保持原订单**：网络错误、非法响应和外部单号不一致不得释放预占或切换供应商；先用原 outOrderSn/orderSn 查单，取得可信状态后决定恢复步骤。只有最终取得完整的一张券或可信失败才收敛资金。
4. **报价入口已完成（2026-10-04）**：用户同意项目直接下单，中台按配置价格首次冻结；已有内部 `StandardPricingQuoteApi` 保留兼容。具体事务、来源、准入及回滚契约见 `2026-10-03-standard-project-direct-order-price-freeze-design.md`；484ece8已实现，准确自动化范围见集成测试§7.81。全配置目录桥接与 HUA_SHENG 履约仍未完成。

实施顺序及每个切片的测试证据维护在 `docs/superpowers/plans/2026-10-03-huasheng-card-closure.md`；人工联调入口及实际结果维护在 `docs/集成测试.md`。

### 12.3 第二切片：显式凭据集绑定与运行时配置

本节覆盖并修正 AD-3 的旧混合 ID 示意，以本节为第二切片完整契约。暂不注册未完成的 HUA_SHENG 可执行适配器；多凭据模式先以隔离测试元数据验证，随后在协议适配器切片启用。

**数据迁移**：新增 `058-connector-credential-set-binding.sql`，`sc_supplier_connector_version` 增 `credential_set_id BIGINT NULL COMMENT '凭据集主键，逻辑引用sc_credential_set.id'`；旧 `credential_slot_id` 改可空，COMMENT 仍为 `凭证槽主键，逻辑引用sc_credential_slot.id`；CHECK 强制二者恰一非空。旧 QNM 版本均保留 slot，不批量改数据。`sc_credential_item.secret_ref` 改 TEXT，COMMENT 仍为 `密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME`，容纳 RSA 私钥的信封密文。054–057 不改。旧 §7.2/§7.3 的错误种子/CHECK 示例已删除，不保留编号；供应商与连接器由 UI 和服务层创建，不执行包含虚构主键/摘要的 SQL。

**元数据与请求**：新增封闭 `CredentialBindingMode`（`SINGLE_SLOT`/`CREDENTIAL_SET`），`AdapterMetadata.credentialBindingMode()` 默认 `SINGLE_SLOT` 保持 QNM。连接器草稿请求新增可选 `credentialSetCode`（供应商内凭据集编号）和 `extraConfig`（非秘密 JSON），旧 `credentialSlotNo` 改为条件必填；服务端按元数据模式校验恰一输入。集按 `supplier_id+set_code` 定位，必须 ACTIVE 且 ACTIVE 项键恰等于元数据 required keys；创建、校验、发布及运行时均核实引用模式和供应商归属，缺项、额外项或跨供应商引用拒绝。校验阶段实际解析秘密后即擦除，解析失败保留安全因果且不记录正文。新注册多凭据元数据不改 QNM 既有能力声明。

**版本与配置**：配置摘要纳入引用模式、slot/set ID、规范化 extraConfig，既有已发布版本及摘要不回写。新增 `AdapterMetadata.extraConfigSchemaResource()` 默认使用原封闭连接器 Schema；HS 注册时覆盖为其非秘密可调参数 Schema。创建/校验/运行时解析均按 Schema 检查，未经 Schema 声明的字段拒绝，不接受 token、密钥或回调公钥等秘密字段。运行时 `PublishedConnectorConfiguration` 扩展 set ID、连接/读取超时和不可变 extraParameters，读取冻结的版本行；旧构造签名保留兼容委派，默认仅用于既有单槽配置，不隐式推断集 ID。

**凭据项身份冻结**：058 同时新增连接器版本 `credential_item_snapshot JSON NULL COMMENT '连接器冻结的凭据项身份与版本快照，不含秘密'`。CREDENTIAL_SET 草稿按 itemKey 排序冻结各项 `itemId/itemKey/providerType/activeSecretVersion`，纳入配置摘要；校验、发布与运行时逐项核对，拒绝身份/版本漂移，SINGLE_SLOT 历史版本保持 NULL。集一经绑定任何连接器版本，其项内容不可追加/覆盖；创建草稿和添加凭据项都先用 LambdaQueryWrapper 固定 `FOR UPDATE` 锁同一集行，添加前核对无版本引用，以避免「绑定与补项」竞争。轮换凭据创建新集及新连接器版本，不覆盖旧项；ENV 值来自部署环境，实际密钥切换仍须在途订单兼容与部署协调，不能把引用冻结宣称为环境秘密值冻结。

**并发锁与当前读**：MySQL RR 事务可能在锁集行前已有旧快照。取得集行锁后，创建草稿读取凭据项集合、添加项检查现有项及版本引用都必须使用当前读（固定 FOR UPDATE），不能复用锁前对象或普通快照查询；版本集引用加索引。SET 模式发布先只读定位集 ID，再按「集→连接器→版本→凭据项」锁定并重新校验关联，避免与添加项的「集→版本引用」相反顺序死锁；旧 SLOT 发布不增加集锁。若锁后版本引用与定位结果不同，拒绝而不使用旧引用。并发 IT 要主动建立旧 RR 快照后等待集锁，证明绑定提交后追加仍被拒绝；不修改进程默认事务隔离级别。

**Ticket**：`OperationTicket` 新增 `CredentialMaterial(appSecret,privateKeyPem)`，不包含回调公钥；原 `secretKey` 保留 QNM。单槽与集的装配路径独立，材料为空或缺 required key 失败关闭，临时 char[] 总是擦除。出站 Ticket 使用冻结读取超时（毫秒向上取整为秒，至少 1 秒），extraParameters 从已校验非秘密版本配置投影，使用防御复制。材料与 Ticket 字符串表示省略全部秘密、充值账号、checkpoint 和可携带 token 的 extraParameters；不改变订单/资金/频控状态机。

Ticket 同时携带冻结 `connectTimeoutMs/readTimeoutMs`，供后续 HUA_SHENG HTTP 客户端分别控制连接与读取；既有 timeoutSeconds 保留作 QNM 兼容入口，旧 builder/构造调用缺毫秒值时由秒值补默认。不得只传秒值而丢失独立连接超时。

**凭据输入上限**：后端 `secretValue` 最大 8192 个字符，同时 UTF-8 明文最大 16384 字节，超限在加密/落库前按固定说明拒绝；ENV `secretRef` 仍为至多 512 字符引用。前端 ENVELOPE 改多行文本框，maxlength 8192，不回显已有值。真实 MySQL 测试使用本地生成 RSA PEM（超过 512 字符），验证加密存储/解密原文一致，跨身份搬移失败；日志和 GET 仍不含明文或密文。

**管理端配套**：适配器目录返回 credentialBindingMode。连接器表单按模式显示平台槽或当前供应商凭据集，下拉仅可用且完整的集；切换适配器或供应商清空旧引用。extraConfig JSON 可填写且服务端 Schema 校验为权威。版本详情返回脱敏凭据编号/模式，不返回 secretRef 或材料。新增所有 HTTP 映射继续使用现有权限，不新增隐式 Feign 或其它库访问。

**验证**：纯测试/管理端契约覆盖模式分派、XOR、归属/状态/项键、Schema、摘要、读取超时、Ticket 不可变及字符串脱敏。真实 MySQL/Liquibase 验证升级/重放、原 slot 保留、XOR CHECK、长 PEM 密文、发布期间停用凭据后拒绝及回滚。定向回归 QNM 发布、上架、运行时装配和标准 HTTP 挡板闭环；供应链架构全运行。前端执行真实 SFC 组件测试、改动文件 ESLint 与 build:local。


**质量审查安全修正（实现前定案）**：extraConfig 是请求中的 JSON 字符串，调用者即使提交非法 token/密钥字段而被 Schema 拒绝，通用日志也不能记录该字符串。将 `/supply-chain/supplier-config/connectors/drafts` 及其 `/admin-api` 等价路径的精确根/子路径加入 common 的共享敏感规则，兼容 Servlet context path，排除 drafts-other；复用现有 Servlet Filter、非生产Interceptor、全局异常与Gateway保护，省略请求/查询/响应正文和原始异常因果，保留业务身份/traceId/错误类型及固定摘要。原始请求字节继续透传，禁止仅依赖Controller注解或尝试递归解析所有字符串。普通相邻路径行为保持。中文注释链接本文及共享文§15.1；不改应用启动、认证、RPC、数据源或事务配置。

影响评估：common规则由网关与使用web starter的独立进程共同消费，新增规则只匹配供应链草稿写路径，不改变其它模块路径。验证须在供应链测试使用实际Servlet Filter/Interceptor/GlobalExceptionHandler覆盖合法/Schema拒绝/畸形extra JSON，检查日志、响应与透传字节；Gateway真实Reactive Filter覆盖拒绝/异常与畸形正文，common匹配测试覆盖前缀/context/相邻路径。先独立RED再GREEN，运行common相关测试、gateway相关测试和供应链全量/架构；发布需携带新common依赖的供应链与网关构建，真实部署仍暂缓。

**最终回归的测试夹具修正（实现前核验）**：RechargeFrequencyAcceptanceIntegrationTest 的通用 cloneRow 将057新增的 live_external_sku_code 生成列也纳入显式 INSERT，真实MySQL按3105拒绝。夹具读取information_schema.COLUMNS时按EXTRA排除VIRTUAL GENERATED/STORED GENERATED列，保留所有可写业务列与原覆盖字段；不修改生产表、历史迁移或领域执行行为。058的XOR拒绝在当前MySQL/Spring组合映射为UncategorizedSQLException，测试应验证根SQLException的3819及实际约束标识，并检查无行插入，不绑定可变的顶层Spring异常类别，也不能放宽为任意异常。两项修正均先保存真实RED再回归相关频控/Schema IT和本切片最终定向门禁。

**第二切片交付记录（2026-10-03）**：后端 `23400fa` 与管理端前端 `69f6903` 已实现上述契约，独立规格及质量复审通过。供应链全量 Surefire 1658、相关 Failsafe 51+3、common18、Gateway6及前端29项均通过；供应链计数已包含日志专项15与架构28，不重复相加。实际执行范围、失败排障及人工验收前置见 `docs/集成测试.md#779-连接器显式凭据集验收入口2026-10-03自动化已通过`。该记录不代表第三切片协议、完整履约或真实供应商验收已完成。

### 12.4 第三切片实施前的协议证据缺口

本节是只读核验结果，尚不是 Task3 可直接实施的完整规格。旧仓 HuashengConstants 注释提供 Apifox「华昇卡券API接口文档」项目57114177-5188-44e4-99f8-2a8581e4be12的位置线索，但本地未发现协议正文。旧仓 `doc/华昇卡券预生产部署手册.md#9-本次测试环境实际联调结果` 记录2026-09-08 token及type304的place→confirm→take成功；这是内部历史观察，缺原始脱敏报文和正式供应商保证，不能据此证明金额单位、重复调用安全或其它类型。核验来源：cardshop 的 `HuashengSupplierTradeServiceImpl`、`HuashengConstants`、`HuashengClient`、`HuashengCardMapper` 及对应测试；本仓 `OperationAssetFact`、`SupplierOrderLifecycle.persistAssets`、`ProjectStandardAssetPayloadDecoder` 与项目资产响应 DTO。真实供应商沙箱未执行。

1. **状态不能直接代表交付**：旧供应商常量只含 WAIT/SUCCESS/FAILED/CANCEL；SUCCESS_HD 是旧系统本地待交付状态。PLACE 返回 SUCCESS 时旧流程跳过 CONFIRM；QUERY 返回 SUCCESS 时仍尝试 TAKE。完整 TAKE 响应或可信成功回调均须核验 orderSn 一致、num 与预期一致、list 完整且形态可识别，才能产生最终资产事实。
2. **金额单位尚无协议证据**：旧 PLACE/CONFIRM 将 totalPrice 原文（测试样例含四位小数）用于日志/remark；旧 TAKE 解析只读取 orderSn/num/list，夹具未提供 totalPrice，无法证明远端响应永不包含该字段。不能据此断言 totalPrice 单位或四位小数的舍入规则。未取得正式协议证据时 reportedSettlementAmount 不填写，原文只可经脱敏摘要观察，不能当成本核对通过或用于最终账本扣减。
3. **资产字段无法无损落入现模型**：旧实现识别 201(code/pass)、202(code 或 pass)、203(code/pass/crc)、301(url)、302(url/urlPass)、303(code/url/urlPass)、304(code/url)。本仓 OperationAssetFact 仅 cardNo/cardSecret；203 和 303 的第三字段及 URL 的显式含义会丢失。不得拼接进密码或把 URL 密码伪装成核验码；需先定案类型化资产载荷、指纹、加密持久化、项目响应与管理端展示的统一契约。未支持的类型必须失败关闭，不能丢字段仍宣称交付成功。
4. **恢复机制尚不存在**：当前 ExecuteHandler 遇 EXECUTING/RESULT_UNKNOWN 直接跳过，现有手机号恢复须真实 queueId。卡券必须新增持久化查单与代次围栏，网络异常后不得由原下单任务盲重发。

5. **重发、时间与金额不能靠模拟证明**：旧代码只在按 outOrderSn 查单返回7014且本地尚无 orderSn时考虑补单，但没有供应商幂等期限/一致性证据，幂等/一致性证据及恢复规格补齐前不得自动开启补单；confirm/take 的重复调用能否回放同资产、FAILED/CANCEL后是否仍可能发券亦待供应商证据。61只允许刷新token后同业务payload至多重试一次，不设全局无界 RETRY_SAFE。token读取data.expires_in。validTime仅有日期夹具，不能猜测时区和当日截止时刻。

Task3 开始写实现前必须完成：协议解析与端点的完整契约、资产载荷契约、受控步骤事务及查单恢复的详细规格、对应文件与测试计划，并完成规格审查。问题可凭真实协议材料解决的优先核实；关键业务语义仍不清时向用户确认，不按旧系统的错误实现推断。

### 12.5 第三切片通信核验与拆分边界

2026-10-03只读核验记录，依据旧 HuashengConstants/Client/TokenManager/TradeServiceImpl 与本仓 QnmHttpExecutor、PublishedConnectorConfigurationResolver、OperationTicket。以下记录实现事实，不是供应商正式保证，也不授权缺证据的生产重发。

1. **请求构造事实**：旧实现每次post复制业务Map，再覆盖秒级timestamp和去连字符UUID nonce；JSON经AES小写hex加密后对原hex字符串做RSA签名。token端点同样加密签名，只是不带Bearer。业务字段重试复用，但每次重新生成nonce、读取timestamp并重新加密/签名，不是复用整个报文。出站头为X-AppId/X-Sign，回调Appid单独处理。业务响应为明文JSON，不能拿响应去AES解密。
2. **端点与载荷事实**：token `/api/token` 使用app_id/app_secret；place `/api/order/placeOrder` 使用outOrderSn/couponId/num/expireTime/notifyUrl及可选outMobile；confirm `/api/order/confirmOrder` 与take `/api/order/takeCard` 只使用orderSn；query `/api/order/queryOrder` 使用orderSn或outOrderSn二选一。目录、余额、取消不纳入第一条卡券闭环。旧expireTime钳制60–1800和token默认7200均是旧实现选择，不冒充正式协议范围。
3. **token缺口**：旧token manager读取access_token/expires_in，缺字段或非正TTL会兜底7200，只有实例级单账号缓存；历史一次7200观察不能证明缺字段安全。新的完整通信设计应采用严格类型、正TTL及保守过期计算，限定刷新次数与并发，按冻结连接器/通信身份隔离，短TTL不无限刷新。业务code61后是否能安全重发仍须供应商保证；刷新失败不得把已经发送的业务标为未发送。旧token的全局缓存不能直接移植，多实例授权是否互相使token失效尚未证实。
4. **复用能力核验**：本仓不存在HttpClientPort。QnmHttpExecutor仅支持form与QNM头、合并秒级超时，不适合发送密文raw body；不为HS改QNM行为。当前Ticket已冻结baseUrl、连接/读取毫秒值、merchantAccount与CredentialMaterial，出站不含回调公钥。HS appId唯一来源及Schema尚须定案，不能从多个字段同时取值。现有extraConfig保护拒绝含token的参数名，不能直接加入旧tokenRefreshAheadSeconds。通信组件可使用已有OkHttp依赖，明确禁自动重试/重定向、响应长度界限与严格JSON；这些是本地防御策略，不替代协议证据。
5. **失败事实边界**：本地构造/签名前失败、网络失败、非2xx、结构非法、供应商code61和其它业务code分别表达通信事实。业务端点超时、HTTP408/429/5xx、非法响应不能证明未执行；7014/FAILED/CANCEL不在通信层直接触发重下、切供应商或释放资金。请求/响应正文、token、签名、密文、目标和券字段不进日志或诊断toString，异常保留内部因果但对外使用固定摘要。

Task3拆为三个仍未实施的规格切片：A为隔离HTTP/token/严格parser及独立JCA/HTTP挡板测试（不注册适配器、不接Timer/资金）；B为无损资产契约，先核实203/303字段和一期支持范围，再统一事实/指纹/加密载荷/两套查询DTO与实际前端展示；C为受控步骤事务和持久化查单恢复，先明确重发保证或明确禁止重发，再实现唯一下一步/代次围栏。每个切片先完整设计和测试计划再代码；本节仅保存核验事实与边界，不标记Task3完成。

### 12.6 正式文档读取与用户补充契约（2026-10-04）

**证据范围：** 用户提供 Apifox 正式分享链接并授权访问；已通过浏览器读取概述、流程、错误码、统一下单、确认、查询、取券、token、余额、回调参数与卡券详情的实际正文/字段。分享访问凭据不写入仓库。此前 `/tmp/huasheng-protocol-2026-10-04/*.md` 为密码门页 HTML，不是协议正文，不作为实现依据。本节保存事实摘要与原文定位，不复制整份文档，不代表供应商沙箱已验收。

**正式来源：** [概述](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/9165626m0)、[流程](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/9165623m0)、[错误码](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/9168637m0)、[下单](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554275e0)、[确认](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554274e0)、[查询](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486591724e0)、[取券](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554277e0)、[token](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554278e0)、[余额](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/497843028e0)、[回调参数](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/487479396e0)、[卡券详情](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554272e0)。使用正常详情页读取；带 `.md` 的下载地址曾返回门页，不能因正文为空推断接口无字段。

#### 12.6.1 已从正式文档确认

- 密码协议与第一切片一致：AES-CBC/PKCS7、小写 hex、appSecret UTF-8 原字节为密钥、appId 的16字节为 IV；RSA PKCS1 v1.5/SHA512 签的是密文文本原字节。Java AES 的 PKCS5Padding 对本16字节块提供等价填充，沿用已通过独立 JCA 断言的实现。
- 请求顶层加入秒级 timestamp 与每次唯一 nonce，不包 data。时差窗口300秒，nonce最多64字符。每次重试重新生成 timestamp/nonce/密文/签名，业务幂等号不变。普通响应为明文 JSON，以整数 code=0 判断业务成功，不以 HTTP200 代替。
- `/api/token` 的明文字段是 app_id/app_secret，仍必须加密并签名；不带 Authorization。其它业务带 Bearer token；token 与经销商及 X-AppId 绑定，文档有效期7200秒，运行时还须严格校验 expires_in，不用非法值无条件兜底。61要求重新授权，安全错误66不能误作已扣款失败。
- 下单 `outOrderSn` 在同一经销商内为幂等键，同值必须保持同参数；couponId正整数、num为1–10（本期仍仅1）、expireTime可选60–1800秒/默认300、outMobile可选、notifyUrl必填且公开 HTTP/HTTPS。保存不可变下单参数摘要及原外部单号，不能随重试更换通知地址/商品/数量。
- 查询详情明确路径 `/api/order/queryOrder`；orderSn/outOrderSn严格二选一。流程图另写了 `/api/queryOrder` 和 `/api/order/query`，与实际接口详情不一致；实现以详情路径为准，在 HTTP 契约测试锁定，不发送两个别名试探。
- 订单 SUCCESS 指支付成功，仍须完整取券；FAILED 表示未分配任何券且已退款；CANCEL表示取消。退款另有 NONE/WAIT/SUCCESS，提卡另有 N/Y，Y表示整套券已通过主动查询或回调交付。状态/退款/数量矛盾不能终态收敛。
- price/totalPrice 为最多4位小数的定点字符串；amount/totalAmount为最多2位小数的面值字符串。TAKE正文没有总价字段，仅 orderSn/num/list，不得伪造结算金额；后续从可信 PLACE/CONFIRM/QUERY/checkpoint或可信回调提供金额，并核对本笔订单身份。
- 取券只有完整返回全部卡券后才标 isTake=Y。卡密字段为 code/pass，另有 crc/url/urlPass；type201/202/203/301/302/303/304的字段组合需完整保留。validTime来自批次、格式不统一且允许空字符串，不能直接假设 ISO 时间并吞解析异常或把无法识别值变成过期。
- 回调 Header 为 Appid/X-Sign，正文密文；包含timestamp/nonce、双单号、totalPrice、SUCCESS/FAILED及num/list。成功是全部卡券，失败未分配任何卡券且库存/余额已退回。文档中的 `/api/order/notifyCallbackDoc` 只是展示，不是我方接收地址；本仓须实现自己的受保护入口。处理后 HTTP200才确认，非200/超时会重投。
- 错误码7014表示订单不存在，7015表示状态错误，7101要求先查单决定确认重试；7016、7019、7020及7302不得触发换单。重试策略必须区分阶段与已发送事实，不能只用统一“可重试”映射驱动原履约 Timer 重发。
- 概述列测试 HTTP 地址，但接口详情测试下拉实际为 HTTPS；出站继续遵守允许主机/HTTPS校验，使用部署方确认的有效 endpoint，不为文档差异削弱网络保护。余额字段只写 number/余额，未明确单位，不将其直接用于账本一致性结论。

#### 12.6.2 用户明确补充，作为本次开发契约

2026-10-04 用户回答正式文档缺失约定：price/totalPrice 单位是人民币元，转本系统分时不足1分四舍五入；同一orderSn重复confirmOrder必须只扣一次；takeCard响应丢失或isTake=Y后重复调用仍返回同一整套卡密。**这三项来自用户补充，不能描述成已经在正式页面看到或已通过真实供应商验证。** 后续实现、挡板与真实联调按此验收。

- 金额以 BigDecimal 精确解析定点字符串，拒绝负数、非法格式、超出协议小数精度及溢出；元×100后 `RoundingMode.HALF_UP` 转整数分，不能使用double或截断。总价按totalPrice整笔舍入一次，不先舍入单价再乘数量；本期数量1。单元测试至少锁定0.0049→0分、0.0050→1分、9.9999→1000分与溢出拒绝。面值与实际结算价职责分离，不用面值扣供应商账户。
- CONFIRM网络未知仍先用原单查状态，SUCCESS跳过确认取券，WAIT才允许依据本约定受控重发；重发沿用orderSn且不再创建PLACE或第二个本地预占。恢复代次、认领围栏、次数/期限与迟到事实必须在完整Task3-C设计定案后实施。
- TAKE重试沿用原orderSn，卡密与数量必须相同；最终资产指纹/订单收敛检查重复事实，不能重复交付或扣款。isTake=Y而取券暂失败不推断无券或退款，不创建新订单。
- 总价须与配置冻结的供应商成本核对；金额差异如何形成异常处置由应用层现有金额边界及Task3-C设计确定，不能默默覆盖报价/预占或把差额四舍五入外再吞掉。

#### 12.6.3 下一切片仍须先定案的设计

协议基本语义缺口已由正式资料和用户补充解决；隔离HTTP/token/严格parser及协议卡片保真详细设计已完成并独立复审通过，见`2026-10-04-huasheng-http-token-parser-design.md`，尚未实施。它细化有界61、缓存身份、validTime及七型协议DTO，保留AD-4分层；7200秒缓存接受上限是本地策略，不能冒称官方上限。仍未完成生产多步骤/回调规格：持久化checkpoint金额来源、旧认领/恢复代次围栏、QUERY后PLACE/CONFIRM/TAKE恢复、未知结果截止与人工处置、分布式授权协调、冻结凭据项身份真实投影、类型化协议卡片到资产的无损桥接、回调冻结版本定位与防重。第一张目标为固定价/type201一张卡号卡密；其余尚未桥接类型失败关闭，不要求首单先实现全部七型资产业务。未注册 HUA_SHENG metadata、未写执行器、未做供应商调用，不标Task3–6完成。
