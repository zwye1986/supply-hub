# 采购方卡券商品开放 API 接入文档

文档版本：1.0<br>
接口版本：v2 / `contractVersion=2.0.0`<br>
适用对象：接入供应链平台、采购虚拟卡券商品的项目服务端。

> 本文依据仓库当前 HTTP 控制器、签名实现和响应 DTO 编写，不新增接口或改变现有契约。新项目使用 v2 平台 SKU 契约。环境地址、项目凭证、可采购商品及商务价格由平台交付；本文不代表某环境已为新项目完成开通。本次未执行远端交易验证。示例编号及卡券值均为虚构，不可用于真实交易。

## 1. 接入范围与准备

### 1.1 当前支持

- 单商品、单件下单：每个订单 `quantity` 固定为整数 `1`。
- 异步履约：下单取得受理回执，采购方主动查询订单状态，再查询卡券资产。
- 卡券交付类型：`CARD_CODE_SECRET`（卡号卡密）、`VOUCHER_CODE`（券码）、`VOUCHER_URL`（券链接）；具体类型以开通商品及实际响应为准。
- 永久订单幂等：同一项目的同一采购方订单号不能产生第二笔订单。

**本文接口不提供**商品目录/实时价格/库存查询、余额查询、批量购买、采购方发券回调、卡券核销、退款或取消。不得推测这些接口路径。商品清单、权益内容、有效期规则和价格通过平台约定方式交付。下单后不支持自动退款、取消或撤销；异常单联系平台处理。

### 1.2 平台交付清单

| 项目 | 说明 |
| --- | --- |
| 测试/生产 `BASE_URL` | 分别提供 HTTPS 地址，不包含接口路径；禁止跨环境混用凭证 |
| `appId` | 项目应用标识，决定可信项目及订单归属 |
| `appKey` | HMAC 密钥，只保存在采购方服务端；不得放入 Header、正文或前端 |
| `keyVersion` | 正整数密钥版本，随凭证交付，不可自行假定为 1 |
| 平台 SKU 清单 | `platformSkuCode`、商品名称、权益、交付类型、有效期规则和采购价格 |
| 接入参数 | 环境实际时间窗、限流约定、建议超时和问题联系人 |

平台须完成项目及应用启用、`STANDARD_PRODUCT` 能力开通、项目选品、定价、可用供给及结算账户配置。商品编码存在不代表该项目有权采购；提交请求时仍执行当前准入检查。

### 1.3 最小调用流程

1. 采购方生成项目内永久唯一的 `buyerOrderNo`，持久化原始下单参数。
2. 调用下单接口，保存 `requestId`、`receiptVersion`、`orderNo` 和状态。
3. `receiptStatus=ACCEPTED` 后，使用原 `buyerOrderNo` 查询履约进度。
4. 查单显示 `assetAvailable=true` 时查询资产；保存并向获授权用户交付卡券。
5. 对 `MANUAL`、明确失败或持续异常联系平台，不自行换订单号补单。

**受理成功不等于发券成功；HTTP 200 或 `code=0` 也不等于履约成功。**

## 2. 通用 HTTP 约定

三个接口均为 **POST**，请求和响应使用 JSON；请求正文使用 UTF-8 编码，`Content-Type: application/json`。

| 操作 | 精确路径 | `operationCode` |
| --- | --- | --- |
| 下单 | `/open-api/supply-chain/project/v2/orders` | `STANDARD_ORDER_CREATE_V2` |
| 查单 | `/open-api/supply-chain/project/v2/orders/query` | `STANDARD_ORDER_QUERY_V2` |
| 查询卡券资产 | `/open-api/supply-chain/project/v2/orders/assets/query` | `STANDARD_ORDER_ASSET_QUERY_V2` |

- URL 不携带 query 参数，不增加尾部 `/`，不改路径或请求方法。
- JSON 严格解析：未知字段、重复键、尾随内容、错误类型均拒绝。不能把数字作为字符串发送。
- 正文硬上限为 1 MiB，环境可以配置更低上限；正常卡券请求远小于此限制。
- 所有业务编号均按不透明字符串保存，不按前缀或长度推算，不转为数字。
- 响应枚举使用**英文名称字符串**，不是数据库数值状态码。
- 时间使用带时区偏移的字符串，例如 `2026-10-20T10:00:00+08:00`；无对应事实时可为 `null`。
- 接口响应设置 `Cache-Control: no-store`；采购方及代理不得缓存敏感响应。
- 可携带 `trace-id` 用于排障，并保存响应链路编号；它不参与签名，也不替代订单号。

### 2.1 通用返回结构

```json
{"code":0,"msg":"","data":{}}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | `0` 表示接口处理成功；非 0 按错误码处理 |
| `msg` | string | 人类可读说明，不得依赖文案进行程序分支 |
| `data` | object/null | 业务响应；错误时可能为空或缺省 |

认证拒绝明确使用 **HTTP 200 + `code=1050001001`**。代理或网络仍可能返回非 200、非 JSON 或无响应；不得只判断 HTTP 状态。

下单可能返回 `code=0`，但 `data.receiptStatus=REJECTED_RETRYABLE/REJECTED_FINAL`：这属于有回执的业务拒单，不是采购成功。

## 3. 签名与鉴权

不需要先登录或获取 access token；每次请求独立生成认证头。

### 3.1 必填 Header

| Header | 值与规则 |
| --- | --- |
| `appId` | 平台分配的项目应用标识，最长 128 字符 |
| `keyVersion` | 平台分配的正整数版本，十进制，不加前导零 |
| `contractVersion` | 固定 `2.0.0` |
| `operationCode` | 必须与第 2 节对应接口完全一致 |
| `timestamp` | 当前 Unix 毫秒时间戳，十进制字符串；默认允许与服务端相差 ±5 分钟，以环境约定为准 |
| `nonce` | 每次 HTTP 请求新生成；仅允许 `A-Z a-z 0-9 _ -`，最长 128 字符，推荐 16 字节安全随机数的无填充 Base64URL |
| `requestSha256` | **实际发送的原始正文字节**的 SHA-256，小写十六进制，64 字符 |
| `signature` | HMAC-SHA256 的无填充 Base64URL，43 字符 |

每个认证头只传一个值，不重复声明。标准卡券接口不传 `buyerUserId`、`channelCode`，也不传 `buyerId`、`projectId`、`tenantId` 等身份覆盖字段。项目范围由凭证决定。

### 3.2 签名算法

按以下 **9 个字段**顺序，以单个 LF（`\n`）连接，最后两个字段为空字符串：

```text
appId
keyVersion
contractVersion
operationCode
timestamp
nonce
requestSha256
<空字符串>
<空字符串>
```

准确表达式：

```java
String canonical = String.join("\n",
        appId, Integer.toString(keyVersion), "2.0.0", operationCode,
        Long.toString(timestamp), nonce, requestSha256, "", "");
```

因此 `canonical` **以两个 LF 结尾**，不要额外增删换行，也不要使用 CRLF。

```text
requestSha256 = lowercase_hex(SHA256(rawBodyBytes))
signature = Base64URL_without_padding(
    HMAC_SHA256(key=UTF8(appKey), message=UTF8(canonical))
)
```

`appKey` 按交付的原始字符串转为 UTF-8 字节使用，**不要自行先做 Base64 解码**。路径和 HTTP 方法不直接加入签名串，但服务端会校验路径/方法对应的操作码及契约版本。

**先序列化一次、计算摘要、签名，然后发送同一份字节。** 签名后修改空格、字段顺序、转义、换行或由 HTTP 库重新序列化，都会导致摘要不一致。重试、查单、资产查询均需新的时间戳/nonce 和重新签名，不能复用整套认证头。

### 3.3 Java 调用示例

使用 **JDK 17+** 的 `HttpClient`、JCA 签名及 Jackson JSON 库。Maven 项目引入以下依赖（已有 Jackson 的项目沿用兼容的统一版本）：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.3</version>
</dependency>
```

通过环境变量 `SC_BASE_URL`、`SC_APP_ID`、`SC_APP_KEY`、`SC_KEY_VERSION` 提供平台实际交付参数。将以下代码保存为 `SupplyChainCardClient.java`。示例不设置业务自动重试、不跟随重定向，也不打印密钥或卡券明文；网络异常按第 7 节确认结果，不假设请求未送达。调用创建会产生真实采购，必须在获准环境使用。

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class SupplyChainCardClient {
    public static final String ROOT = "/open-api/supply-chain/project/v2/orders";
    private static final String CONTRACT_VERSION = "2.0.0";
    private static final Map<String, String> OPERATIONS = Map.of(
            ROOT, "STANDARD_ORDER_CREATE_V2",
            ROOT + "/query", "STANDARD_ORDER_QUERY_V2",
            ROOT + "/assets/query", "STANDARD_ORDER_ASSET_QUERY_V2");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String baseUrl;
    private final String appId;
    private final String appKey;
    private final int keyVersion;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SupplyChainCardClient(String baseUrl, String appId, String appKey, int keyVersion) {
        URI uri = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("必须使用平台交付的 HTTPS 根地址");
        }
        if (appId == null || appId.isBlank() || appKey == null || appKey.isBlank()
                || keyVersion <= 0) {
            throw new IllegalArgumentException("项目凭证不完整");
        }
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.appId = appId;
        this.appKey = appKey;
        this.keyVersion = keyVersion;
    }

    // 只序列化一次：摘要计算与发送必须使用同一个 byte[]，不再交给 HTTP 库重新序列化。
    HttpRequest buildRequest(String path, Object payload)
            throws IOException, GeneralSecurityException {
        String operation = OPERATIONS.get(path);
        if (operation == null) {
            throw new IllegalArgumentException("未登记的接口路径");
        }
        byte[] body = JSON.writeValueAsBytes(payload);
        String timestamp = Long.toString(System.currentTimeMillis());
        byte[] randomBytes = new byte[16];
        RANDOM.nextBytes(randomBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = String.join("\n", appId, Integer.toString(keyVersion),
                CONTRACT_VERSION, operation, timestamp, nonce, digest, "", "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("appId", appId)
                .header("keyVersion", Integer.toString(keyVersion))
                .header("contractVersion", CONTRACT_VERSION)
                .header("operationCode", operation)
                .header("timestamp", timestamp)
                .header("nonce", nonce)
                .header("requestSha256", digest)
                .header("signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    public JsonNode call(String path, Object payload)
            throws IOException, InterruptedException, GeneralSecurityException {
        HttpResponse<byte[]> response = http.send(
                buildRequest(path, payload), HttpResponse.BodyHandlers.ofByteArray());
        // 不回显原始响应；非200、网络超时等均交由调用方按原业务键确认结果。
        if (response.statusCode() != 200) {
            throw new IOException("平台 HTTP 状态: " + response.statusCode());
        }
        JsonNode result;
        try {
            result = JSON.readTree(response.body());
        } catch (IOException ex) {
            // JSON解析异常可能携带正文片段，不向日志传播敏感响应内容。
            throw new IOException("平台响应 JSON 解析失败，请按原业务键确认结果");
        }
        if (result == null || !result.path("code").isIntegralNumber()) {
            throw new IOException("平台响应缺少有效错误码，请按原业务键确认结果");
        }
        long code = result.path("code").longValue();
        if (code != 0) {
            throw new IOException("平台接口错误码: " + code);
        }
        JsonNode data = result.get("data");
        if (data == null || !data.isObject()) {
            throw new IOException("平台响应缺少业务数据，请按原业务键确认结果");
        }
        return data; // 仍需判断回执、订单和资产状态，不能直接视为采购成功。
    }

    public static void main(String[] args) throws Exception {
        SupplyChainCardClient client = new SupplyChainCardClient(
                System.getenv("SC_BASE_URL"), System.getenv("SC_APP_ID"),
                System.getenv("SC_APP_KEY"), Integer.parseInt(System.getenv("SC_KEY_VERSION")));
        // 确认采购授权后才取消对应调用的注释；订单号必须在发送前持久化。
        // 不要每次重试都重新生成订单号，也不要连续调用后就假定已发券。
        String buyerOrderNo = "PURCHASE_DEMO_20261020_0001";
        Map<String, Object> createPayload = Map.of(
                "buyerOrderNo", buyerOrderNo,
                "platformSkuCode", "<平台交付的SKU编码>",
                "quantity", 1,
                "target", Map.of("deliveryMode", "BUYER_ASSET_QUERY"));
        // JsonNode created = client.call(ROOT, createPayload);
        // JsonNode summary = client.call(ROOT + "/query", Map.of("buyerOrderNo", buyerOrderNo));
        // 查单确认assetAvailable=true后再获卡；不要打印返回的卡券明文。
        // JsonNode assets = client.call(ROOT + "/assets/query", Map.of("buyerOrderNo", buyerOrderNo));
    }
}
```

示例 `15` 秒只是客户端演示超时，不是履约时效承诺；`InterruptedException` 应由业务入口妥善传播或恢复线程中断标记，不得吞掉后盲目重购。Java 也可复用平台 API 包中的 `ProjectAppAuthSigner`，凭证的用户/渠道作用域均置为 `null`；具体依赖版本由平台另行提供。

## 4. 下单接口

`POST /open-api/supply-chain/project/v2/orders`

### 4.1 请求

```json
{
  "buyerOrderNo": "PURCHASE_DEMO_20261020_0001",
  "platformSkuCode": "SKU_DEMO_001",
  "quantity": 1,
  "target": {"deliveryMode": "BUYER_ASSET_QUERY"}
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `buyerOrderNo` | string | 是 | 采购方项目订单号；非空白，最长 128 字符，不含控制字符；同一项目永久唯一 |
| `platformSkuCode` | string | 是 | 平台交付的 SKU 编码；非空白，最长 64 字符，不含控制字符；原样传递 |
| `quantity` | integer | 是 | 固定为 `1`，不接受 `"1"`、`1.0`、0、负数或大于 1 |
| `target` | object | 是 | 卡券固定为 `{"deliveryMode":"BUYER_ASSET_QUERY"}`，不添加其它目标字段 |
| `previousReceiptVersion` | integer | 否 | 正整数，仅用于条件修复后重新受理可重试拒单，见第 7 节；首次请求不传 |

不接受 `buyerSkuCode`、供应商编码、价格、实付金额、报价 token、通知地址、用户身份、项目身份等其它字段。SKU 编码不是供应商商品编号，也不是分销/项目选品记录编号。

价格由服务端按项目配置确定；同一永久订单键已冻结的首次报价不会因重试重新定价。本文接口不返回价格，接入前应确认商务价格及结算账户，不用换号试单来探价。

### 4.2 已受理响应示例

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "requestId": "RCP_DEMO_001",
    "buyerOrderNo": "PURCHASE_DEMO_20261020_0001",
    "receiptStatus": "ACCEPTED",
    "receiptVersion": 1,
    "orderNo": "DEMO_ORDER_001",
    "orderStatus": "PROCESSING",
    "rejectionCode": null,
    "idempotentReplay": false,
    "acceptedAt": "2026-10-20T10:00:00+08:00"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `requestId` | string | 稳定受理回执号，排障及幂等回放使用；不是卡券号 |
| `buyerOrderNo` | string | 采购方订单号 |
| `receiptStatus` | string | 回执状态，见第 6 节 |
| `receiptVersion` | integer | 当前回执版本；条件重试时使用实际最新值，禁止固定写 1 |
| `orderNo` | string/null | 平台订单号；尚未建单时为空 |
| `orderStatus` | string/null | 公共订单状态；尚未建单时为空 |
| `rejectionCode` | string/null | 稳定业务拒绝码；处理中或已受理时为空 |
| `idempotentReplay` | boolean | 是否为原永久业务键的幂等回放；不代表本次新发了一张券 |
| `acceptedAt` | string/null | 受理成功时间；未受理时为空 |

`receiptVersion=1` 及 `orderStatus=PROCESSING` 仅是示例，不是固定返回值。即使订单最终失败，原回执也可能保持 `ACCEPTED`，须继续查订单状态。

## 5. 查询接口

### 5.1 查询订单

`POST /open-api/supply-chain/project/v2/orders/query`

请求只能包含一个字段：

```json
{"buyerOrderNo":"PURCHASE_DEMO_20261020_0001"}
```

`buyerOrderNo` 类型及长度同下单。仅能查询认证项目所属订单，不支持用平台订单号替代。

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "buyerOrderNo": "PURCHASE_DEMO_20261020_0001",
    "requestId": "RCP_DEMO_001",
    "receiptStatus": "ACCEPTED",
    "receiptVersion": 1,
    "rejectionCode": null,
    "failureReasonCode": null,
    "failureReasonNote": null,
    "orderNo": "DEMO_ORDER_001",
    "orderStatus": "SUCCEEDED",
    "fulfillmentStatus": "SUCCEEDED",
    "requestedQuantity": 1,
    "deliveredQuantity": 1,
    "assetAvailable": true,
    "acceptedAt": "2026-10-20T10:00:00+08:00",
    "completedAt": "2026-10-20T10:00:05+08:00",
    "updatedAt": "2026-10-20T10:00:05+08:00"
  }
}
```

除下单已说明的同名字段外：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `failureReasonCode` | string/null | 履约失败稳定原因码；只在有可靠失败事实时提供，与受理拒绝码不同 |
| `failureReasonNote` | string/null | 脱敏失败说明，可能为空，不据文案做自动决策 |
| `fulfillmentStatus` | string/null | 履约状态；未形成履约请求时为空 |
| `requestedQuantity` | integer | 请求数量，当前为 1 |
| `deliveredQuantity` | integer | 已成功交付数量 |
| `assetAvailable` | boolean | 当前存在可授权交付的卡券资产，不包含卡密本身 |
| `completedAt` | string/null | 订单进入业务终态的时间；人工处理等未完结状态可能为空 |
| `updatedAt` | string/null | 状态最后更新时间 |

查单只读取平台已记录的进度，不会因每次查询立即向供应商查单或推进履约。已成功订单也可能因资产过期等原因不再有可交付资产，应及时获卡并按商品规则交付。

### 5.2 查询卡券资产

`POST /open-api/supply-chain/project/v2/orders/assets/query`

请求与查单一致：

```json
{"buyerOrderNo":"PURCHASE_DEMO_20261020_0001"}
```

卡号卡密交付示例：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "buyerOrderNo": "PURCHASE_DEMO_20261020_0001",
    "orderNo": "DEMO_ORDER_001",
    "assets": [
      {
        "assetNo": "FA_DEMO_001",
        "sequenceNo": 1,
        "assetType": "CARD_CODE_SECRET",
        "cardCode": "DEMO_CARD_NOT_VALID",
        "cardSecret": "DEMO_SECRET_NOT_VALID",
        "maskedValue": "DEMO****VALID",
        "validFrom": null,
        "validUntil": null,
        "lifecycleStatus": "AVAILABLE"
      }
    ],
    "hint": null
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `buyerOrderNo` | string | 采购方订单号 |
| `orderNo` | string/null | 平台订单号；受理处理中可能为空 |
| `assets` | array | 当前获授权可交付资产；尚未就绪时可能为空数组 |
| `hint` | string/null | 等待/人工处理提示；有资产时为空，不是机器状态码 |

资产对象字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `assetNo` | string | 资产稳定编号；建议以其做本地资产去重 |
| `sequenceNo` | integer | 履约交付序号，不作为全局唯一键 |
| `assetType` | string | `CARD_CODE_SECRET` / `VOUCHER_CODE` / `VOUCHER_URL` |
| `cardCode` | string，可缺省 | 卡号；按实际卡号卡密资产返回 |
| `cardSecret` | string，可缺省 | 卡密；按实际卡号卡密资产返回 |
| `voucherCode` | string，可缺省 | 券码；仅券码类型返回 |
| `voucherUrl` | string，可缺省 | 券链接；仅券链接类型返回 |
| `maskedValue` | string/null | 脱敏展示值，不能用于兑换 |
| `validFrom` | string/null | 资产有效期开始时间，带时区偏移 |
| `validUntil` | string/null | 资产有效期结束时间，带时区偏移 |
| `validTimeRaw` | string，可缺省 | 供应商有效期原文，不保证日期格式；可为空字符串 |
| `validTimeState` | string，可缺省 | 原有效期字段状态：`ABSENT`（缺失）、`NULL`（显式空）、`VALUE`（字符串，包括空字符串）；旧资产可能不返回 |
| `lifecycleStatus` | string | `AVAILABLE`（可交付）或 `DELIVERED`（已交付） |

注意事项：

- 根据 `assetType` 读取相应字段，不能一律取 `cardCode`。不相关的明文字段省略，不保证输出 `null`。
- 卡号/卡密须原样交付，不转数字、不裁剪前导零，不自行补密钥，也不假定两字段必然不同。
- 标准有效期为空不等于永久有效；以商品规则及有效期原文为补充，不把原文强制解析成固定日期格式。
- `assets=[]` 配合等待提示不是发券成功，继续查单；人工提示联系平台。已失败/取消、未受理、非卡券订单按错误码处理。
- 重复获卡是读取原资产，不重新购买，也不自动改变资产状态；`DELIVERED` 不等于用户已核销。
- 明文卡号、卡密、券码和券链接不得写入普通访问日志、异常报文、埋点或客服截图。采购方对落库、展示和用户授权实施保护。

## 6. 状态与判定

### 6.1 `receiptStatus`：是否已承担订单履约

| 状态 | 含义 | 采购方动作 |
| --- | --- | --- |
| `PROCESSING` | 受理处理中 | 原订单号查单，不换号 |
| `ACCEPTED` | 已受理 | 继续查看订单履约结果，不再次采购 |
| `REJECTED_RETRYABLE` | 条件修复后可重试的拒单 | 先修复条件，再按第 7 节带回执版本重试 |
| `REJECTED_FINAL` | 最终拒单 | 停止同单受理重试，联系平台/按业务处理 |

### 6.2 `orderStatus`：平台订单结果

| 状态 | 含义 |
| --- | --- |
| `CREATED` | 已创建 |
| `PROCESSING` | 处理中 |
| `PARTIALLY_SUCCEEDED` | 公共枚举保留的部分成功；当前单件标准接口不提供部分成功流程 |
| `SUCCEEDED` | 订单成功；检查 `assetAvailable` 后获卡 |
| `FAILED` | 订单失败，查看失败原因；不再对该已受理单使用条件重试 |
| `CANCELLED` | 已取消；枚举存在不代表提供对外取消接口 |
| `MANUAL` | 人工处理，不能判定已失败或可安全重新采购 |

### 6.3 `fulfillmentStatus`：履约进度

`ACCEPTED`（待履约/路由中）、`PROCESSING`（履约中）、`SUCCEEDED`（成功）、`FAILED`（失败）、`MANUAL`（人工处理）。不要把此字段与受理回执的 `ACCEPTED/PROCESSING` 混用。

项目暂停或标准商品能力关闭后，在应用及密钥仍有效、能力曾开通的条件下，可继续摘要查单；**不代表仍可下单或获卡**。项目关闭/归档、应用或密钥停用/吊销/过期等会拒绝访问。请在停用项目前处理未交付资产。

## 7. 永久幂等、超时与条件重试

### 7.1 永久业务键

- 订单身份是 **认证项目 + `buyerOrderNo`**，不是 nonce、凭证版本或当天日期。
- 同号、同 SKU、同数量、同目标重发走既有回执；已受理后不会重复预留资金、路由采购或发券。
- 同号更换 SKU/目标等请求不变量返回 `1050001012 IDEMPOTENCY_CONFLICT`。不要修改大小写或空格来试图绕过身份比较。
- 更换密钥、nonce、HTTP 接口版本不能让同一个订单号变成新订单。

### 7.2 超时/断网/服务异常

1. 将本地订单标记为“结果未知”，保存原始参数，使用原号查单。
2. 已查到回执则按回执/订单状态继续处理；`MANUAL` 不得自动重购。
3. 查单暂时不存在不必然证明首次请求从未受理，需考虑在途提交；使用退避查单，必要时原号同参重发创建。
4. 所有重发使用新 timestamp/nonce/签名，但业务参数保持不变。禁止超时后生成新 `buyerOrderNo` 盲目重购。
5. 建议与平台约定指数退避及抖动，例如 2/4/8 秒后逐渐放缓；这是客户端建议，不是平台限流或完成时限承诺。长期未完成转人工排查。

### 7.3 条件修复后重新受理

仅 `receiptStatus=REJECTED_RETRYABLE` 适用。普通同参重发只返回原拒单结果，不会自动重新尝试。

1. 先查单取得最新 `receiptVersion` 并确认仍为可重试拒单。
2. 与平台确认不可售/供给/账户等阻断条件已修复。
3. 使用**原订单号、原商品、原数量及原目标**，增加 `previousReceiptVersion`，值为刚查到的版本。

```json
{
  "buyerOrderNo": "PURCHASE_DEMO_20261020_0001",
  "platformSkuCode": "SKU_DEMO_001",
  "quantity": 1,
  "target": {"deliveryMode": "BUYER_ASSET_QUERY"},
  "previousReceiptVersion": 3
}
```

`3` 仅为示例。版本竞争时重新查单，不循环盲增版本。条件重试不支持修改原请求、已受理后重履约或刷新原订单价格。

## 8. 常见错误与拒绝码

### 8.1 外层 `code`（数字）

| code | 含义 | 建议动作 |
| --- | --- | --- |
| `400` | 请求参数/严格 JSON 格式错误 | 检查字段、类型及未知参数，不原样盲重试 |
| `500` | 系统错误 | 原号查单确认结果，退避/联系平台；不换号 |
| `1050001001` | 采购商调用身份无效 | 核对环境、凭证版本、原始字节摘要、签名、时间、nonce、能力及项目状态 |
| `1050001008` | 标准商品当前不可售 | 核对项目授权 SKU 和当前可售条件；此类错误可能发生在产生回执之前 |
| `1050001012` | 永久业务键对应的请求内容冲突 | 停止重发，核对本地原始参数并查询原单 |
| `1050001014` | 下游调用结果未知 | 使用原业务键查询，不换号补购 |
| `1050001015` | 业务需要受控人工处置 | 联系平台，不能按失败自动重购 |
| `1050001017` | 服务暂时不可用 | 原业务键退避查单/重试 |
| `1050001104` | 订单不存在或当前项目下不可见 | 核对凭证项目、环境和订单号；超时场景按第 7 节处理 |
| `1050001158` | 此类商品不能查询资产 | 使用查单接口，确认商品交付类型 |
| `1050001159` | 订单未受理 | 查回执拒绝原因，不循环获卡 |
| `1050001160` | 订单已失败或取消 | 查最终订单结果，不循环获卡 |

外层错误不保证已生成 `requestId` 或回执。例如 SKU 未找到/未选品可能在回执创建前即失败。不得凭某个错误码推断已有订单，查询确认。

### 8.2 `data.rejectionCode`（字符串）

有回执的业务拒单主要包括：

| rejectionCode | 含义 |
| --- | --- |
| `SKU_NOT_SALEABLE` | 当前商品不可售/项目准入未就绪 |
| `LISTING_VERSION_EXPIRED` | 商品商业版本已失效 |
| `INVENTORY_INSUFFICIENT` | 可兑现供给不足 |
| `NO_ROUTABLE_SUPPLIER` | 没有可路由供给 |
| `BUYER_ACCOUNT_NOT_OPENED` | 结算资金账户未开户 |
| `BUYER_BALANCE_INSUFFICIENT` | 可用余额不足，保留的稳定拒绝码；具体资金准入依项目配置 |
| `INVALID_TARGET` | 履约目标不符合已发布规则 |
| `QUANTITY_NOT_SUPPORTED` | 不支持该数量；本 HTTP 接口非法数量通常先以参数错误拒绝 |
| `ORDER_PURCHASE_LIMIT_EXCEEDED` | 超过单笔限购 |

可重试与否以 **`receiptStatus`** 为准，不仅凭拒绝码或文案猜测。`failureReasonCode` 是已受理后履约失败的另一维度，不作为重新开单授权。遇到未识别枚举/原因码保留原值并转人工，禁止默认判为成功。

## 9. 接入验收清单

以下在测试环境执行，涉及购买须由平台批准 SKU、数量和预算，不直接对生产环境试错。

- [ ] 平台已交付环境、凭证、可采购 SKU、价格与交付规则，双方确认结算账户。
- [ ] 正常下单得到回执，查单最终成功、交付数量 1，资产可读取且类型/内容匹配。
- [ ] 同号同参重放返回相同回执/订单及原资产，不产生第二笔采购。
- [ ] 同号更换 SKU 被拒绝；非法 quantity、额外身份/价格字段、重复键等被拒绝。
- [ ] 正文签名后改动、过期时间戳、nonce 重用均被拒绝，程序能识别 HTTP 200 下的非 0 错误码。
- [ ] 网络超时使用原号查单恢复，不自动换号；人工处理状态不会触发补购。
- [ ] 可重试拒单的条件修复后，使用最新回执版本重试；并发重试不重复下单。
- [ ] 资产未就绪、未受理、失败、项目暂停等场景有正确处理，不缓存或泄露明文。
- [ ] 提供排障时仅含环境、时间、buyerOrderNo、requestId、orderNo、错误码和 trace-id，不附密钥/签名/卡券明文。

## 附录 A：旧版本兼容

新项目只按本文 v2 接入。已有 v1 项目仍使用 `/open-api/supply-chain/project/v1/orders` 及其 query/assets/query 路径，创建字段为 `buyerSkuCode`，操作码不带 `_V2`，契约版本为 `1.0.0`；不能将平台 SKU 填入 v1 字段冒充旧商品码。

同项目 v1/v2 查询可读本项目历史订单，但**创建同号跨版本会冲突**，不能用换版本的方式恢复采购。历史订单按原创建契约维护。

## 附录 B：维护与代码核对依据

本节供平台维护人员核对，不要求接入方阅读内部实现：

- API 根目录：`aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/`
  - `api/auth/ProjectAuthOperations.java`：精确路径、操作码、版本及状态策略。
  - `api/auth/ProjectAppAuthSigner.java`：九字段签名、摘要及认证 Header。
  - `api/fulfillment/dto/ProjectStandardOrder*RespDTO.java`：响应字段及敏感字段省略规则。
  - `api/fulfillment/dto/FulfillmentReceiptStatus.java`、`FulfillmentStatus.java`，`api/order/dto/SupplyChainOrderStatus.java`：对外枚举名。
  - `error/SupplyChainErrorCodeConstants.java`：数字错误码目录。
- Server 根目录：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`
  - `interfaceapi/open/project/ProjectPlatformSkuOrderController.java`、`ProjectStandardHttpRequestParser.java`：实际 HTTP 入口及严格参数解析。
  - `infrastructure/auth/ProjectAppAuthFilter.java`、`ProjectAppAuthProperties.java`：鉴权、nonce、时间窗、大小限制及 no-store。
  - `border/fulfillment/application/FulfillmentTargetSchemaRegistry.java`：卡券目标闭包。
  - `border/fulfillment/application/ProjectStandardOrderQueryService.java`、`ProjectStandardAssetQueryService.java`、`ProjectStandardAssetPayloadDecoder.java`：归属校验、状态投影、资产交付及有效期字段。
- 详细设计：[平台 SKU 下单与历史兼容](../superpowers/specs/2026-10-12-platform-sku-order-contract-design.md)、[HTTP 传输与严格解析](../superpowers/specs/2026-10-02-standard-project-http-transport-design.md)、[查询与资产交付](../superpowers/specs/2026-10-02-project-standard-query-asset-design.md)。
- 联调操作与结果统一维护于 [集成测试](../集成测试.md)。文档验收清单不是已执行的真实交易证据。
