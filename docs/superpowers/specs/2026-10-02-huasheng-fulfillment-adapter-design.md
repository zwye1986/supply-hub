# 供应商履约参照实现切换：从 QNM 到 HUA_SHENG — 修改方案（修订版 v2）

> 状态：草案（待评审）· 修订日期：2026-10-03（v2.1：对齐供给发布文 v4 的共享地基与变更集编号，统一命名）
> 版本说明：本文为 v2，取代同日初版 v1。v1 存在三处 P0 缺陷（误判 checkpoint 机制可用、误判 DDL 无 CHECK 约束、误判 extraParameters 可用），修订依据见 §3。v2.1 不改动协议与架构决策，仅按供给发布文 v4 定案同步地基出处、变更集编号与命名。
> 命名约定：供应商/适配器一律用 `HUA_SHENG`、`QNM` 表示（对应 `adapterType` `HUA_SHENG_ORDER_V1`/`QNM_GAME_ORDER_V1`、`supplier_code` `HUASHENG`/`QNM`），prose 与代码标识符不用中文名；DB 种子 `supplier_name` 展示值（`'华昇卡券'`）保留中文，不在约定覆盖内。
> 关联设计：`2026-08-07-supply-chain-phase1-detailed-design.md` §7.3 §7.4 §10.1 §10.2
> 关联设计（管理端供给发布，共建地基）：`2026-10-03-supplier-offer-publication-admin-design.md`（**v4 已定案**）。**该文 §5 是 `AdapterMetadata` 扩展（`defaultExecutionProfile()`/`credentialKeys()`/`mappingTemplates()`/`fulfillmentSteps()`）+ AD-3 凭据集 + AD-5 `extra_config` 共享地基规格的唯一出处**；本文引用其规格、不重复描述，只落地 HUA_SHENG 专属部分（协议适配器、多步骤编排、回调入口、token、种子）。两文协调点见该文 §14 与本文 §7.0。
> ⚠ 变更集编号（已对齐 v4 §0.4 ①，作废本文原 `050`/`051` 计划）：现有链至 `052-standard-order-project-key.sql`；供给发布文占 `053`（f1 `external_sku_code`）/`054`（AD-3 凭据集/项建表）/`055`（AD-5 `extra_config` 列）/`056`（D1 `published_flag`）。**AD-3 建表与 AD-5 加列属共享地基，由 `054`/`055` 交付（规格出处 v4 §5.2/§5.3）；本文 HUA_SHENG 主数据种子让位到 `058+`**（原 `050`/`051` 与 `050-project-standard-asset-audit`/`051-standard-quote-permanent-key` 冲突，作废）。落地前对齐 `db.changelog-1.0.0.yaml` 登记顺序。
> 旧系统参照：`/Users/mac/Documents/codeSpace/cardshop` `huasheng` 包

---

## 1. 目标与边界

**语义**：以 HUA_SHENG 作为新的首要参照实现完整落地；QNM 代码与测试保留，作为多供应商扩展性的第二验证用例。

**目标**：

1. 新增 `HUA_SHENG_ORDER_V1` 适配器，实现两段式下单（`placeOrder` → `confirmOrder`）、主动取券（`takeCard`）、查单、取消、token 鉴权、加密通信。
2. 落地供应商回调 HTTP 入口（补 M1 计划 Task 12），抽象为按 `adapter_type` 路由的通用骨架。
3. 落地**多步骤履约编排**（新建，非复用）。
4. 落地 HUA_SHENG 的多密钥材料注入（三密钥）。
5. 数据库新增 HUA_SHENG 主数据、连接器、凭据集、错误码映射、状态映射；扩展 CHECK 约束。
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
| 鉴权 | 每次带 userkey | `access_token`（7200s，提前 300s 刷新，61 失效自动重试一次） | 需新增 TokenManager |
| 下单 | 一段式 `Send`/`SendKm` | **两段式** `placeOrder`→`confirmOrder` | **需新建编排** |
| 取券 | 同步返回 | 主动 `POST /api/order/takeCard` | 需新增 step |
| 双号 | `billno` | `outOrderSn`（本地）+ `orderSn`（HUA_SHENG） | ✅ `billno` / `external_order_no` |
| 回调 | 明文表单 | AES-CBC hex body + `Appid`/`X-Sign`，RSA 公钥验签 | 需新增 Controller |
| 错误码 | 散落常量 | 30+ 码三级分类（明确失败/待查单/补单） | ✅ `sc_supplier_error_code_map` 已支持 |
| 状态映射 | 3 状态 | 5 状态 | ✅ `sc_status_mapping_definition` 已支持 |
| 金额 | `sumPrice` | `totalPrice` | ✅ `reportedSettlementAmount` |
| 补单 | 无 | 7014 + 无 orderSn → 复用快照重发，指数退避 | 需新增 |
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

### 3.2 已存在可复用的续履约骨架（v1 严重低估）

`SupplierOrderLifecycle` 已有**受控续履约**机制，v1 完全没利用：

| 能力 | 位置 | 对 HUA_SHENG 的价值 |
| --- | --- | --- |
| `continueAfterTrustedRejection(operationNo, outcome, traceId, permit, nextOperationNo)` | `:133-137` | **正是两段式入口**：显式指定下一 Operation |
| `validateContinuation` 严格校验下一 Operation | `:458-478` | 保证同 BOrder 递进 `sequenceNo`、未发送、未激活 |
| `sameBOrder` 分支 | `:462-464` | **支持同一 BOrder 内 sequenceNo+1 递进**（两段式必需） |
| `relatedOperations` / `relatedBOrders` | `LockedExecution` | 已有同请求内多 Operation 聚合查询 |

`validateContinuation` 的关键约束（两段式必须遵守）：

- `isUnsentRecharge(next)`：下一 Operation 必须是 `FULFILL` + `SEND`/`SEND_KM` step + `PENDING`/`CLAIMED` + `attemptCount==0` + `resultClass==null` + `externalOperationNo==null` + `completedAt==null`。
- `sameBOrder` 时 `next.sequenceNo == old.sequenceNo + 1`。
- 下一 BOrder 必须 `activeFlag != TRUE`、`completedAt == null`、状态为 `WAITING`/`PENDING`、`externalOrderNo == null`。
- 其余同请求 Operation 必须是"更早且已 REJECTED"或"非未发送充值"。

**这是 v1 遗漏的最大机会**：两段式不必自建编排，而应接入既有续履约机制。但 `isRechargeOperation`（`:487-490`）只认 `SEND`/`SEND_KM`，需扩展以识别新 step。

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

### AD-1：两段式接入既有续履约机制

**决策**：HUA_SHENG 的 `placeOrder` / `confirmOrder` / `takeCard` 编排为同一 BOrder 下的多个 `FULFILL` Operation，用既有 `continueAfterTrustedRejection` 串联，`sequenceNo` 递增。

**理由**：复用 `validateContinuation` 的原子校验、乐观锁与事件契约（`CONTINUATION_EVENT` / `CONTINUATION_REASON`），避免自建状态机带来的资金与频控一致性风险。

**必要改造**：

1. `isRechargeOperation`（`:487-490`）扩展为识别 `PLACE_ORDER`/`CONFIRM_ORDER`/`TAKE_CARD` step。
2. `AdapterStepCode` 扩展：保留 `SEND`/`SEND_KM`，新增 `PLACE_ORDER`、`CONFIRM_ORDER`、`TAKE_CARD`。
3. `handleAccepted` 增加 checkpoint 落库（AD-2）。
4. `FulfillmentOperationExecuteHandler` 在 `ACCEPTED` 且 step 为 `PLACE_ORDER` 时创建下一 Operation（`CONFIRM_ORDER`，`sequenceNo+1`），以 `continueAfterTrustedRejection` 串联。

**风险**：`validateContinuation` 当前仅在 `REJECTED` 路径被调用（`:210-211`），`ACCEPTED` 路径（`:214`）走 `handleAccepted` 且不传 `nextOperationNo`。需扩展 `handleAccepted` 支持指定下一 Operation。这是**对既有受控机制的扩展而非绕过**，不降低其严格性。

### AD-2：checkpoint 落库与恢复（新建）

**落库**：步骤推进服务收到 `outcome.nextCheckpoint()` 时，用现有 `EnvelopeCryptoPort.encrypt(checkpoint, aad)` 加密，把返回的 ciphertext/keyVersion 分别写入 checkpointCiphertext/新增 checkpointKeyVersion，并写 checkpointSchemaVersion=1。旧 API 不存在的三参 encrypt 不得沿用。

- `aad` = `operationNo|bOrderNo|adapterStepCode`，绑定操作身份，防密文跨操作重放。
- 明文结构（不含任何密钥）：
  ```json
  {"schemaVersion":1,"orderSn":"<HUA_SHENG 单号>","couponId":123,"num":1,
   "expireTime":300,"outOrderSn":"<本地单号>","placePayloadHash":"<sha256>"}
  ```

**恢复**：`FulfillmentOperationTicketFactory:61` 已在传**密文**。由持有 `EnvelopeCryptoPort` 的 `FulfillmentOperationExecuteHandler`（`:59` 已注入）解密，经 `OperationTicket` 新字段 `checkpointPlaintext` 传给适配器。**保持适配器无加解密依赖**。

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

1. `FulfillmentOperationTicketFactory.create` 增加 `Map<String,String> extraParameters` 入参。
2. 参数来源为 `sc_supplier_connector_version.extra_config`（**地基变更集 `055` 新增的 JSON 列**，存 `expireTime`/`outMobile`/`num` 等非秘密可调参数；**密钥类禁止入此列**）。
3. 应用层注入的 token 合并进同一 Map。

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
  HuashengSignUtils.java            # 签名/验签门面
  HuashengProtocolParser.java       # 明文 JSON 解析、金额转分
  HuashengCardMapper.java           # 卡券 8 种 type 字段映射（独立类）
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
border/fulfillment/domain/Capability.java              # +CANCEL_ASSET（若采纳）
border/fulfillment/domain/FulfillmentExceptionReasonCodes.java  # +HS_* 原因码
border/fulfillment/application/SupplierOrderLifecycle.java     # handleAccepted 落库+串联；isRechargeOperation 扩展
border/fulfillment/application/FulfillmentOperationExecuteHandler.java  # 解密 checkpoint、串联下一步骤
border/fulfillment/application/FulfillmentOperationTicketFactory.java   # extraParameters + credentialMaterial
infrastructure/security/CredentialProvider.java        # +resolveSet（凭据集解析；provider 模型见 v4 §5.2）
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
    // 7018/7022/4002/4003/66 等明确失败 → REJECTED
    // 7014 + 无本地 orderSn → 触发补单（复用 checkpoint 参数快照）
    // 成功 → ACCEPTED + nextCheckpoint(orderSn, ...)（落库由 lifecycle 完成，AD-2）
}
```

#### 5.2.2 步骤串联（`FulfillmentOperationExecuteHandler`）

```java
OperationOutcome outcome = operationExecutorPort.execute(ticket);

if (outcome.outcomeClass() == OperationOutcomeClass.ACCEPTED
        && AdapterStepCode.PLACE_ORDER.name().equals(operation.getAdapterStepCode())) {
    // AD-1：按 AdapterMetadata.fulfillmentSteps() 声明推进
    String nextOperationNo = stepSequencer.advance(operation, bOrder);
    supplierOrderLifecycle.continueAfterTrustedRejection(
            operation.getOperationNo(), outcome, traceId, permit, nextOperationNo);
} else {
    supplierOrderLifecycle.confirmTrustedOutcome(
            operation.getOperationNo(), outcome, traceId, permit);
}
```

`FulfillmentStepSequencer.advance` 读 `AdapterMetadata.fulfillmentSteps()`（HUA_SHENG = `[PLACE_ORDER, CONFIRM_ORDER, TAKE_CARD]`，QNM = `[SEND]` / `[SEND_KM]` 保持既有行为），在同一 BOrder 内以 `sequenceNo+1` 创建下一 Operation 并追加 Outbox 事件。

#### 5.2.3 `handleAccepted` checkpoint 落库（AD-2）

```java
private void handleAccepted(FulfillmentOperationDO operation, BOrderDO bOrder,
                            OperationOutcome outcome, LocalDateTime now) {
    operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
    operation.setResultClass(outcome.outcomeClass().name());
    if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
        operation.setExternalOperationNo(outcome.externalOrderNo());
    }
    if (outcome.nextCheckpoint() != null && outcome.nextCheckpoint().length > 0) {
        byte[] aad = (operation.getOperationNo() + "|" + bOrder.getBOrderNo() + "|"
                + operation.getAdapterStepCode()).getBytes(StandardCharsets.UTF_8);
        operation.setCheckpointCiphertext(envelopeCrypto.encrypt(
                outcome.nextCheckpoint(), checkpointKeyVersion, aad));
        operation.setCheckpointSchemaVersion(1);
    }
    // 其余 CAS 更新与 bOrder 联动保持原样
}
```

#### 5.2.4 回调 Controller 与 Parser

`HuashengCallbackParser`：验签（HUA_SHENG 公钥 `SHA512withRSA`）→ AES 解密 → 必填字段与 ±300s 时间窗校验 → nonce 防重 → 双号一致性校验 → 券码数量校验 → 事实提取。

`SupplierCallbackController`（`/callbacks/suppliers/huasheng/callback`，`consumes = text/plain`）：

- 验签/解密/字段非法 → **400**（不重试）
- 业务冲突（券码数量不符、终态不一致） → **409**
- 内部异常 → **500**（让 HUA_SHENG 重试）
- 成功/幂等 → **200 `success`**
- 分布式锁 `HUASHENG_CALLBACK:{orderSn}`，审计写 `sc_fulfillment_call_log.direction='INBOUND'`

**新增 ArchUnit 规则**（不放宽既有规则）：

```java
/** 回调入口只允许依赖 *CallbackParser，禁止依赖 Adapter 主体、HttpExecutor 与 DO。 */
static final ArchRule callback_entry_points_only_depend_on_callback_parsers =
        noClasses().that().resideInAPackage("..interfaceapi.callback..")
            .should().dependOnClassesThat()
            .resideInAPackage("..supplieradapter..")
            .andShould().dependOnClassesThat(
                Predicates.or(Predicates.nameEndsWith("CallbackParser"),
                              Predicates.nameEndsWith("CallbackRouter")));
```

---

## 6. 待确认决策

| 决策点 | 方案选择 | 备选 | 建议 |
| --- | --- | --- | --- |
| 两段式编排 | 接入既有 `continueAfterTrustedRejection` | 自建编排子链路 | 采纳前者 |
| num 数量 | 固定 1，协议层拒绝越界 | 放开 DDL 支持多张 | 采纳前者，多数量另行立项 |
| 取券 step 归属 | **复用 `FULFILL` + `TAKE_CARD` step** | 新增 `TAKE_ASSET` kind | **复用 FULFILL**：`operation_kind` CHECK 约束无需改动，状态机分支更少 |
| 取消能力 | 新增 `CANCEL_ASSET` capability | 复用 `VOID_ASSET` | 新增（取消未交付 ≠ 作废已交付），仅需 ALTER capability CHECK |
| checkpoint 加密方 | 应用层 `EnvelopeCryptoPort` | 适配器内自行加解密 | 采纳应用层 |
| 三密钥承载 | `CredentialMaterial` 记录 | 三次扩 ticket 字段 | 采纳记录类型 |

**关键提示**：若采纳"取券复用 `FULFILL`"，则 `ck_sc_fulfillment_operation_kind` **完全无需修改**，DDL 改动仅剩 `capability_code` 一处，显著降低风险。

---

## 7. 数据库修改方案

### 7.0 变更集分配（对齐供给发布文 v4 §0.4 ①）

| 变更集 | 归属 | 内容 | 规格出处 |
| --- | --- | --- | --- |
| `053` | 共享地基（供给发布文） | `sc_supplier_offer.external_sku_code`（f1） | v4 §5.4 |
| `054` | 共享地基 | `sc_credential_set`/`sc_credential_item` 建表（AD-3） | v4 §5.2（DDL 全文见本文 §7.1） |
| `055` | 共享地基 | `sc_supplier_connector_version.extra_config` 加列（AD-5） | v4 §5.3（DDL 全文见本文 §7.1） |
| `056` | 共享地基（供给发布文） | `sc_supplier_connector.published_flag` + 唯一键（D1） | v4 §5.5 |
| `058` | **本文（HUA_SHENG）** | HUA_SHENG 供应商/凭据集数据/连接器/版本/能力/映射种子 | 本文 §7.2 |
| `059` | **本文（HUA_SHENG）** | `capability_code` CHECK 扩 `CANCEL_ASSET`（若 §6 采纳） | 本文 §7.3 |

> `054`/`055` 是**共享地基**，Phase 1 凭据/连接器 UI 即依赖，先于 HUA_SHENG 适配器交付；本文给出其可执行 DDL 全文（v4 只定编号与用途、不重复 DDL）。`058`/`059` 是 HUA_SHENG 专属种子，随适配器落地。落地前统一对齐 `db.changelog-1.0.0.yaml` 登记顺序。
> 下文 `@hs_*` 为示意变量，实现时以子查询按业务键（`supplier_code`/`connector_code`/`set_code`）绑定真实主键，不硬编码 ID。

### 7.1 地基变更集 DDL 全文（`054` 凭据集 + `055` extra_config）

> 规格出处：v4 §5.2（凭据集）/§5.3（extra_config）。以下为**已落地（as-built）** DDL，与 `db/changelog/releases/1.0.0/054-credential-set.sql`、`055-connector-version-extra-config.sql` 一致；changelog 文件为权威来源。新表建于 022 本地时区迁移之后，datetime 列注释统一 `Asia/Shanghai`；`secret_ref` 对齐 `sc_credential_slot` 用 `VARCHAR(512)`（容纳 ENVELOPE 信封密文）；`published_flag`（056）DDL 见 v4 §5.5。

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

### 7.2 HUA_SHENG 种子变更集 `058-huasheng-supplier-seed.sql`

> 供应商、凭据集/项无服务层计算列，可直接 SQL 种子；连接器/版本/能力/映射含服务层计算列（`config_digest` 等），推荐经技术菜单 UI 或服务层落地（见本节末实现提示）。

```sql
-- 1) 供应商主数据（默认 SUSPENDED=20，联调后启用；supplier_name 为面向运营的中文展示值）
INSERT INTO sc_supplier (supplier_code, supplier_name, supplier_type, status, created_at, updated_at)
VALUES ('HUASHENG', '华昇卡券', 'STANDARD_SUPPLIER', 20, NOW(3), NOW(3));

-- 2) 凭据集与凭据项数据（表由 054 建；provider_type=ENV，密钥不入库）
INSERT INTO sc_credential_set (supplier_id, set_code, status, created_at, updated_at)
VALUES (@hs_supplier_id, 'HS_MAIN', 10, NOW(3), NOW(3));

INSERT INTO sc_credential_item (credential_set_id, item_key, provider_type, secret_ref, masked_hint, active_secret_version, status, created_at, updated_at) VALUES
    (@hs_set_id, 'appSecret',         'ENV', 'env:HUASHENG_APP_SECRET',          '****',       1, 10, NOW(3), NOW(3)),
    (@hs_set_id, 'privateKey',        'ENV', 'env:HUASHENG_PRIVATE_KEY',         '**** PKCS8', 1, 10, NOW(3), NOW(3)),
    (@hs_set_id, 'callbackPublicKey', 'ENV', 'env:HUASHENG_CALLBACK_PUBLIC_KEY', '**** RSA',   1, 10, NOW(3), NOW(3));

-- 3) 连接器与版本（credential_slot_id 语义扩展为指向凭据集，AD-3；列名对齐 001-identity-and-master.sql）
INSERT INTO sc_supplier_connector (supplier_id, connector_code, adapter_type, current_version_id, status, created_at, updated_at)
VALUES (@hs_supplier_id, 'HS_DEFAULT', 'HUA_SHENG_ORDER_V1', NULL, 10, NOW(3), NOW(3));

INSERT INTO sc_supplier_connector_version (
    connector_id, version_no, base_url, merchant_account, credential_slot_id,
    protocol_version, callback_path, connect_timeout_ms, read_timeout_ms,
    rate_limit, concurrency_limit, adapter_config_schema_version, adapter_config_json,
    validation_status, validated_at, config_digest, status, published_at, created_at, updated_at, extra_config)
VALUES (
    @hs_connector_id, 1, 'https://coupon-api.jshstkj.cn', @hs_app_id, @hs_set_id,
    'HUA_SHENG_ORDER_V1', '/callbacks/suppliers/huasheng/callback', 10000, 20000,
    5, 10, 1, '{}',
    10, NOW(3), @hs_config_digest, 10, NOW(3), NOW(3), NOW(3), JSON_OBJECT('expireTime', 300, 'num', 1));

-- 4) 能力（不含 CANCEL_ASSET；如 §6 采纳，先执行 059 扩 CHECK，再在此追加 CANCEL_ASSET 行）
INSERT INTO sc_supplier_connector_capability (connector_version_id, capability_code, enabled, metadata_version, created_at) VALUES
    (@hs_version_id, 'ISSUE_ASSET',      1, 1, NOW(3)),
    (@hs_version_id, 'QUERY_ORDER',      1, 1, NOW(3)),
    (@hs_version_id, 'RECEIVE_CALLBACK', 1, 1, NOW(3)),
    (@hs_version_id, 'QUERY_BALANCE',    1, 1, NOW(3));

-- 5) 状态映射（5 状态；与附录 B 一致）
INSERT INTO sc_status_mapping_definition (connector_version_id, mapping_version, external_status, external_sub_status, business_phase, fact_type, terminal_flag, priority, status, published_at, created_at) VALUES
    (@hs_version_id, 1, 'WAIT',       '', 'CREATE',     'ACCEPTED',  0, 10, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, 'SUCCESS_HD', '', 'PROCESSING', 'ACCEPTED',  0,  5, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, 'SUCCESS',    '', 'DELIVERY',   'SUCCEEDED', 1, 20, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, 'FAILED',     '', 'CREATE',     'REJECTED',  1, 20, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, 'CANCEL',     '', 'CREATE',     'REJECTED',  1, 20, 10, NOW(3), NOW(3));

-- 6) 错误码映射（0 为成功码，不属错误映射，禁止写入）
INSERT INTO sc_supplier_error_code_map (connector_version_id, mapping_version, external_error_code, external_sub_code, error_class, action_type, retryable_flag, status, published_at, created_at) VALUES
    (@hs_version_id, 1, '7018', '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7022', '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '4002', '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '4003', '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '66',   '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7202', '', 'BUSINESS_REJECTED', 'REJECT',      0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7017', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7019', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7020', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7021', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7023', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7204', '', 'RESULT_UNKNOWN',    'QUERY_FIRST', 0, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '7014', '', 'RETRYABLE',         'RETRY_SAFE',  1, 10, NOW(3), NOW(3)),
    (@hs_version_id, 1, '61',   '', 'RETRYABLE',         'RETRY_SAFE',  1, 10, NOW(3), NOW(3));
```

> **UI 等价路径**：本节种子也可由技术菜单（供给发布文 v4 A1/E1/F1/G2）自助录入——连接器草稿、凭据集/项、能力勾选、映射「从模板初始化」（`mappingTemplates()` 即本节 5)/6) 内容）+ 追加。SQL 种子用于一键初始化与集成测试基线，UI 路径用于线上自助与灰度翻牌；两者写同一批表，`mappingTemplates()` 是映射的代码单一来源。
> **实现提示**：`sc_supplier_connector_version.config_digest`（规范化配置 SHA-256）、`adapter_config_schema_version`、`validation_status` 由服务层（`createConnectorDraft`/`validate`/`publish`）计算与流转，raw SQL 难以正确生成 `config_digest` 且会绕过「先校验后发布」。故连接器/版本/能力/映射种子**推荐经技术菜单 UI 或服务层落地**（v4 §6.2/§6.5）；§7.2 SQL 仅作集成测试基线示意，直接执行须补齐 `config_digest` 等 NOT NULL 列（上文以 `@hs_config_digest` 占位）或改走服务层。

### 7.3 HUA_SHENG 能力 CHECK 变更集 `059-huasheng-capability-check.sql`

**仅在 §6 采纳新增 `CANCEL_ASSET` 时执行**（须先于 §7.2 能力种子追加 `CANCEL_ASSET` 行）：

```sql
ALTER TABLE sc_supplier_connector_capability
    DROP CONSTRAINT ck_sc_supplier_connector_capability_code;
ALTER TABLE sc_supplier_connector_capability
    ADD CONSTRAINT ck_sc_supplier_connector_capability_code
    CHECK (capability_code IN ('CREATE_RECHARGE','ISSUE_ASSET','BIND_BENEFIT','QUERY_ORDER',
        'QUERY_ASSET','VOID_ASSET','UNBIND_ASSET','QUERY_BALANCE','QUERY_INVENTORY',
        'RECEIVE_CALLBACK','CANCEL_ASSET'));
```

`adapter_step_code`、`result_class` 无 CHECK 约束，扩展无需 ALTER（§3.3）。若采纳"取券复用 `FULFILL`"，`operation_kind` 亦无需变更。

### 7.4 部署 SQL

`script/deploy` 下新增 HUA_SHENG 供应商菜单（按 `supplier_code` 过滤），菜单与字典写入 system 库（供应链不得写 system 表，须走部署脚本）。技术菜单「供应商履约技术配置」的部署 SQL 由供给发布文 v4 §8.1 交付，本文不重复。

---

## 8. 测试方案

| 类别 | 类 | 重点 |
| --- | --- | --- |
| 单元 | `HuashengCryptoTest` | AES 加解密、RSA 签名验签、PKCS#1→X.509 包装、密钥长度校验 |
| 单元 | `HuashengSignUtilsTest` | 空载荷防绕过、常量时间比较 |
| 单元 | `HuashengProtocolParserTest` | 金额转分、双号提取、错误码解析 |
| 单元 | `HuashengCardMapperTest` | 8 种 type 字段映射与资产分类 |
| 单元 | `HuashengErrorCodeTest` | 三级分类、未知码兜底 |
| 单元 | `HuashengMaskingUtilsTest` | 脱敏、响应体屏蔽 |
| 单元 | `FulfillmentStepSequencerTest` | 步骤序列推进、`sequenceNo` 递进、末步不再推进 |
| 适配器 | `HuashengSupplierAdapterTest` | MockWebServer：两段式全流程、**num 越界拒绝**、5xx/4xx/超时、61 刷新重试、7014 补单 |
| 契约 | `HuashengSupplierAdapterContractTest` | ticket 不可变性、Composite 路由、能力集与 DB 一致、封闭 Schema |
| 集成 | `HuashengFulfillmentAcceptanceIntegrationTest` | seed → placeOrder → confirmOrder → takeCard 全闭环 + **checkpoint 落库与恢复** |
| 集成 | `HuashengContinuationIntegrationTest` | **ACCEPTED 后自动创建下一 Operation，`validateContinuation` 通过，资金不变量成立** |
| 集成 | `HuashengCallbackIntegrationTest` | 回调验签、解密、状态机推进、幂等、nonce 防重 |
| 架构 | `SupplyChainModuleArchitectureTest` | 新增回调入口规则；既有 24 条对 HUA_SHENG 生效 |

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
| 多步骤编排触及资金一致性 | **高** | `handleAccepted` 与 `validateContinuation` 是资金与频控边界 | 复用既有 `continueAfterTrustedRejection`，不新写状态机；补资金不变量集成测试 |
| `ACCEPTED` 路径缺 `nextOperationNo` 校验 | **高** | 需扩展 `handleAccepted` 支持下一 Operation | 沿用 `validateContinuation` 全部校验，不开旁路 |
| 三密钥泄露 | 中 | ticket 承载多密钥 | 内存态传递、禁日志、异常脱敏沿用 `safeFailureSummary` |
| `num>1` 崩溃 | 中 | 违反 DB CHECK | 协议层拒绝（AD-6）+ 单测锁定 |
| token 刷新与频控冲突 | 中 | 61 重试会二次外呼 | 重试不重置 `attemptCount` 上限；纳入既有限频判定 |
| token 重复获取 | 低 | 多实例并发 | Redis 分布式锁，按 appId 隔离 |
| 协议证据完整性 | 中 | 沙箱未验分支 | 标记 `EVIDENCE_INCOMPLETE` 分支禁止发布 |

---

## 11. 工作量拆解

| 任务 | 内容 | 依赖 | 备注 |
| --- | --- | --- | --- |
| T1 | 设计文档更新（§10.1/§10.2/§7.4/新增 §10.3 回调骨架） | — | — |
| T2 | `HuashengCrypto` + `HuashengSignUtils` + 单测 | T1 | — |
| T3 | `EndpointConstants` + `ErrorCode` + `ProtocolParser` + `CardMapper` + 单测 | T1 | — |
| T4 | `HuashengProperties` + `HuashengTokenManager`（应用层）+ 单测 | T2 | — |
| T5 | `HuashengHttpExecutor` + `HuashengMaskingUtils` + 单测 | T2,T4 | — |
| T6 | `HuashengSupplierAdapter` + `HuashengOrderV1Metadata` + 适配器/契约测试 | T2-T5 | — |
| T7 | 领域扩展：`AdapterStepCode` + `Capability` + 原因码 | T1 | — |
| **T8** | **三密钥：`CredentialMaterial` + `OperationTicket` + `CredentialProvider.resolveSet`** | T1 | **新增** |
| **T9** | **`extra_config` 列 + `extraParameters` 传递链打通** | T1 | **新增** |
| **T10** | **checkpoint 落库与恢复（`handleAccepted` 加密写库 + 工厂解密）** | T1 | **新增** |
| **T11** | **多步骤编排：`FulfillmentStepSequencer` + `isRechargeOperation` 扩展 + `ACCEPTED` 串联** | T10 | **新增，风险最高** |
| T12 | DDL 变更集 `058`（HUA_SHENG 种子）/`059`（能力 CHECK）+ 部署 SQL；地基 `054`/`055` 随供给发布文 §5 交付 | T7,T8 | 编号对齐 §7.0 |
| T13 | `SupplierCallbackController` + `Router` + `CallbackParser` + 架构规则 | T6 | — |
| T14 | 集成测试（闭环/续履约/回调/补单/num 拒绝） | T11,T13 | — |
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

## 附录 B：HUA_SHENG 状态映射

| 外部状态 | business_phase | fact_type | terminal |
| --- | --- | --- | --- |
| `WAIT` | CREATE | ACCEPTED | 0 |
| `SUCCESS_HD` | PROCESSING | ACCEPTED | 0 |
| `SUCCESS` | DELIVERY | SUCCEEDED | 1 |
| `FAILED` | CREATE | REJECTED | 1 |
| `CANCEL` | CREATE | REJECTED | 1 |

> 2026-10-03 评审修复编号补充：057 已用于未退役 Offer 外部编码唯一约束修正，本文尚未实施的种子和 CHECK 顺延至 058/059；语义见 `docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#154-退役商品释放外部编码`。

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
4. **报价入口待业务确认**：当前实现要求先经内部 `StandardPricingQuoteApi` 冻结后再 HTTP 下单。用户正在确认期望「直接下单时冻结」还是「业务系统先确认价格」，本项未确认前不修改受理、定价或对外契约。

实施顺序及每个切片的测试证据维护在 `docs/superpowers/plans/2026-10-03-huasheng-card-closure.md`；人工联调入口及实际结果维护在 `docs/集成测试.md`。

### 12.3 第二切片：显式凭据集绑定与运行时配置

本节覆盖并修正 AD-3 的旧混合 ID 示意，以本节为第二切片完整契约。暂不注册未完成的 HUA_SHENG 可执行适配器；多凭据模式先以隔离测试元数据验证，随后在协议适配器切片启用。

**数据迁移**：新增 `058-connector-credential-set-binding.sql`，`sc_supplier_connector_version` 增 `credential_set_id BIGINT NULL COMMENT '凭据集主键，逻辑引用sc_credential_set.id'`；旧 `credential_slot_id` 改可空，COMMENT 仍为 `凭证槽主键，逻辑引用sc_credential_slot.id`；CHECK 强制二者恰一非空。旧 QNM 版本均保留 slot，不批量改数据。`sc_credential_item.secret_ref` 改 TEXT，COMMENT 仍为 `密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME`，容纳 RSA 私钥的信封密文。054–057 不改。旧 §7.2/§7.3 未执行的种子/CHECK 示例顺延至 059+；供应商与连接器优先由 UI 和服务层创建，不执行包含虚构主键/摘要的示例 SQL。

**元数据与请求**：新增封闭 `CredentialBindingMode`（`SINGLE_SLOT`/`CREDENTIAL_SET`），`AdapterMetadata.credentialBindingMode()` 默认 `SINGLE_SLOT` 保持 QNM。连接器草稿请求新增可选 `credentialSetCode`（供应商内凭据集编号）和 `extraConfig`（非秘密 JSON），旧 `credentialSlotNo` 改为条件必填；服务端按元数据模式校验恰一输入。集按 `supplier_id+set_code` 定位，必须 ACTIVE 且 ACTIVE 项键恰等于元数据 required keys；创建、校验、发布及运行时均核实引用模式和供应商归属，缺项、额外项或跨供应商引用拒绝。校验阶段实际解析秘密后即擦除，解析失败保留安全因果且不记录正文。新注册多凭据元数据不改 QNM 既有能力声明。

**版本与配置**：配置摘要纳入引用模式、slot/set ID、规范化 extraConfig，既有已发布版本及摘要不回写。新增 `AdapterMetadata.extraConfigSchemaResource()` 默认使用原封闭连接器 Schema；HS 注册时覆盖为其非秘密可调参数 Schema。创建/校验/运行时解析均按 Schema 检查，未经 Schema 声明的字段拒绝，不接受 token、密钥或回调公钥等秘密字段。运行时 `PublishedConnectorConfiguration` 扩展 set ID、连接/读取超时和不可变 extraParameters，读取冻结的版本行；旧构造签名保留兼容委派，默认仅用于既有单槽配置，不隐式推断集 ID。

**凭据项身份冻结**：058 同时新增连接器版本 `credential_item_snapshot JSON NULL COMMENT '连接器冻结的凭据项身份与版本快照，不含秘密'`。CREDENTIAL_SET 草稿按 itemKey 排序冻结各项 `itemId/itemKey/providerType/activeSecretVersion`，纳入配置摘要；校验、发布与运行时逐项核对，拒绝身份/版本漂移，SINGLE_SLOT 历史版本保持 NULL。集一经绑定任何连接器版本，其项内容不可追加/覆盖；创建草稿和添加凭据项都先用 LambdaQueryWrapper 固定 `FOR UPDATE` 锁同一集行，添加前核对无版本引用，以避免「绑定与补项」竞争。轮换凭据创建新集及新连接器版本，不覆盖旧项；ENV 值来自部署环境，实际密钥切换仍须在途订单兼容与部署协调，不能把引用冻结宣称为环境秘密值冻结。

**Ticket**：`OperationTicket` 新增 `CredentialMaterial(appSecret,privateKeyPem)`，不包含回调公钥；原 `secretKey` 保留 QNM。单槽与集的装配路径独立，材料为空或缺 required key 失败关闭，临时 char[] 总是擦除。出站 Ticket 使用冻结读取超时（毫秒向上取整为秒，至少 1 秒），extraParameters 从已校验非秘密版本配置投影，使用防御复制。材料与 Ticket 字符串表示省略全部秘密、充值账号、checkpoint 和可携带 token 的 extraParameters；不改变订单/资金/频控状态机。

**凭据输入上限**：后端 `secretValue` 最大 8192 个字符，同时 UTF-8 明文最大 16384 字节，超限在加密/落库前按固定说明拒绝；ENV `secretRef` 仍为至多 512 字符引用。前端 ENVELOPE 改多行文本框，maxlength 8192，不回显已有值。真实 MySQL 测试使用本地生成 RSA PEM（超过 512 字符），验证加密存储/解密原文一致，跨身份搬移失败；日志和 GET 仍不含明文或密文。

**管理端配套**：适配器目录返回 credentialBindingMode。连接器表单按模式显示平台槽或当前供应商凭据集，下拉仅可用且完整的集；切换适配器或供应商清空旧引用。extraConfig JSON 可填写且服务端 Schema 校验为权威。版本详情返回脱敏凭据编号/模式，不返回 secretRef 或材料。新增所有 HTTP 映射继续使用现有权限，不新增隐式 Feign 或其它库访问。

**验证**：纯测试/管理端契约覆盖模式分派、XOR、归属/状态/项键、Schema、摘要、读取超时、Ticket 不可变及字符串脱敏。真实 MySQL/Liquibase 验证升级/重放、原 slot 保留、XOR CHECK、长 PEM 密文、发布期间停用凭据后拒绝及回滚。定向回归 QNM 发布、上架、运行时装配和标准 HTTP 挡板闭环；供应链架构全运行。前端执行真实 SFC 组件测试、改动文件 ESLint 与 build:local。
