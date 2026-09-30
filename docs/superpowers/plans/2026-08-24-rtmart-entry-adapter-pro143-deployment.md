# 旅通扫码购入口适配器与 pro143 部署 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `RtmartEntryUrlPort` 增加真实旅通 HOME/DETAIL HTTP 实现，并提供可安全部署到 pro143 的 Docker Compose 产物。

**Architecture:** 保持既有 Port/Facade 不变，在 `integration.rtmart` 内封装配置、PKCS#8 私钥解析、SHA1withRSA 签名、OkHttp 表单请求和正式 `jumpUrl` 响应翻译。真实 Adapter 通过 Boot AutoConfiguration 在启用时替换失败关闭 Bean；部署层使用 linux/amd64 单实例 host network、Nacos 服务发现和服务器 `.env` 明文秘密隔离。

**Tech Stack:** Java 21、Spring Boot AutoConfiguration/ConfigurationProperties、OkHttp、Jackson、JUnit 5、AssertJ、MockWebServer、Maven、Docker Buildx、Docker Compose。

---

## 文件结构

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryProperties.java`：配置与启动校验。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryException.java`：不含敏感正文的内部失败分类。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartPrivateKeyParser.java`：单行 Base64/PEM PKCS#8 解析。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSigner.java`：nonce、Unix 秒、参数排序和 RSA 签名。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryResponse.java`：旅通 `msg/result/code/jumpUrl` DTO。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapter.java`：HOME/DETAIL 表单外呼和响应翻译。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryAdapterConfiguration.java`：真实 Adapter Bean 装配。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：在 fallback 前导入真实配置。
- Create tests: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSignerTest.java`、`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapterContractTest.java`、`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryAdapterConfigurationTest.java`。
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`、`aifuli-module-supply-chain/aifuli-module-supply-chain-server/.dockerignore`。
- Create: `script/deploy/supply-chain-test/docker-compose.yml`、`.env.example`、`supply-chain-server-test.yaml`。
- Modify: `docs/集成测试.md`：真实旅通入口联调、部署前置、验证点和排障。

### Task 1: 签名配置与密码学边界

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryProperties.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryException.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartPrivateKeyParser.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSigner.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSignerTest.java`

- [ ] **Step 1: 写失败测试**

测试使用 `KeyPairGenerator("RSA")` 生成密钥，以固定 `Clock` 与固定 nonce 调用：

```java
Map<String, String> signed = signer.sign(Map.of(
        "company_code", "X0000A",
        "token", "TOKEN-SENTINEL"));

assertThat(signed).containsEntry("app_key", "test-app")
        .containsEntry("nonce_str", "00112233445566778899aabbccddeeff")
        .containsEntry("timestamp", "1787559288")
        .containsKey("sign");
assertThat(verify(publicKey, canonicalWithoutSign(signed), signed.get("sign"))).isTrue();
```

另测 sign/null/空值排除、非法 PKCS#8、私钥不出现在异常、配置时长/响应上限/base URL 校验。

- [ ] **Step 2: 运行红灯**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server \
  -Dtest=RtmartRequestSignerTest test
```

Expected: testCompile 因目标类不存在失败。

- [ ] **Step 3: 写最小实现**

核心接口固定为：

```java
public final class RtmartEntryException extends RuntimeException {
    public enum Reason { CONFIGURATION, SIGNATURE, TRANSPORT, HTTP_STATUS, REMOTE_ERROR, PROTOCOL }

    private final Reason reason;
    private final String remoteCode;
    private final Integer httpStatus;

    private RtmartEntryException(Reason reason, String message, String remoteCode,
                                 Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.remoteCode = remoteCode;
        this.httpStatus = httpStatus;
    }

    static RtmartEntryException configuration(String message, Throwable cause) {
        return new RtmartEntryException(Reason.CONFIGURATION, message, null, null, cause);
    }
    static RtmartEntryException signature(Throwable cause) {
        return new RtmartEntryException(Reason.SIGNATURE, "旅通请求签名失败", null, null, cause);
    }
    static RtmartEntryException transport(Throwable cause) {
        return new RtmartEntryException(Reason.TRANSPORT, "旅通入口请求传输失败", null, null, cause);
    }
    static RtmartEntryException httpStatus(int status) {
        return new RtmartEntryException(Reason.HTTP_STATUS, "旅通入口请求 HTTP 状态异常", null, status, null);
    }
    static RtmartEntryException remote(String code) {
        return new RtmartEntryException(Reason.REMOTE_ERROR, "旅通入口请求返回业务错误", code, null, null);
    }
}

final class RtmartRequestSigner {
    private final String appKey;
    private final PrivateKey privateKey;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    RtmartRequestSigner(String appKey, PrivateKey privateKey,
                        Clock clock, Supplier<String> nonceSupplier) {
        this.appKey = appKey;
        this.privateKey = privateKey;
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    static RtmartRequestSigner production(String appKey, PrivateKey privateKey, Clock clock) {
        SecureRandom secureRandom = new SecureRandom();
        return new RtmartRequestSigner(appKey, privateKey, clock, () -> {
            byte[] nonce = new byte[16];
            secureRandom.nextBytes(nonce);
            return HexFormat.of().formatHex(nonce);
        });
    }

    Map<String, String> sign(Map<String, String> businessParameters) {
        TreeMap<String, String> parameters = businessParameters.entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right, TreeMap::new));
        parameters.put("app_key", appKey);
        parameters.put("nonce_str", nonceSupplier.get());
        parameters.put("timestamp", Long.toString(clock.instant().getEpochSecond()));
        String source = parameters.entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue())
                .collect(Collectors.joining());
        parameters.put("sign", rsaSha1Base64(source));
        return Collections.unmodifiableMap(parameters);
    }

    private String rsaSha1Base64(String source) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(privateKey);
            signature.update(source.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw RtmartEntryException.signature(exception);
        }
    }
}

final class RtmartPrivateKeyParser {
    static PrivateKey parsePkcs8(String configuredPrivateKey) {
        try {
            String normalized = configuredPrivateKey
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (RuntimeException | GeneralSecurityException exception) {
            throw RtmartEntryException.configuration("旅通 RSA 私钥配置无效", exception);
        }
    }
}
```

`RtmartEntryProperties` 前缀为 `aifuli.supply-chain.instore.rtmart`，默认 disabled、HTTPS base URL、
HOME/DETAIL path、3s/5s/10s 超时和 65536 字节上限；loopback HTTP 只允许测试。

- [ ] **Step 4: 运行绿灯并提交**

重复 Step 2，Expected: 目标测试全部 PASS。提交签名与配置边界。

### Task 2: HOME/DETAIL OkHttp Adapter

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryResponse.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapter.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapterContractTest.java`

- [ ] **Step 1: 写 MockWebServer 失败契约**

构造固定签名器和真实 `OkHttpClient`，验证 HOME/DETAIL 表单、单次调用以及正式响应：

```java
server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""
            {"msg":"","result":"success","code":"E0000",
             "jumpUrl":"https://hourh5-em-shop.feiniugo.com/pages/transferpage?token=SECRET"}
            """));

EntryUrlResult result = adapter.getHomeUrl(homeCommand());
assertThat(result.entryUrl()).startsWith("https://hourh5-em-shop.feiniugo.com/");
RecordedRequest request = server.takeRequest();
assertThat(request.getPath()).isEqualTo("/pay_center/rt-pos-api/getRtPosUrl/home");
assertThat(server.getRequestCount()).isOne();
```

参数化覆盖 E4001/E4002/E4003/E4004/E5000/未知 code、E0000 矛盾 envelope、非法或非 HTTPS jumpUrl、
非 2xx、3xx、不合法 JSON、空响应、超限、超时。日志捕获断言 token/sign/private key/jumpUrl 不出现。

- [ ] **Step 2: 运行红灯**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server \
  -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=RtmartEntryUrlHttpAdapterContractTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: testCompile 因 Adapter/response DTO 不存在失败。

- [ ] **Step 3: 实现最小 Adapter**

```java
final class RtmartEntryUrlHttpAdapter implements RtmartEntryUrlPort {
    @Override
    public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
        return execute(Operation.HOME, command.projectId(), command.channelCode(), command.companyCode(),
                Map.of("company_code", command.companyCode(), "longitude", command.longitude(),
                        "latitude", command.latitude(), "token", command.token()));
    }

    @Override
    public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
        return execute(Operation.DETAIL, command.projectId(), command.channelCode(), command.companyCode(),
                Map.of("company_code", command.companyCode(), "trade_id", command.tradeId(),
                        "token", command.token()));
    }

    private EntryUrlResult execute(Operation operation, Long projectId,
                                   String channelCode, String companyCode,
                                   Map<String, String> parameters) {
        Map<String, String> signed = signer.sign(parameters);
        Request request = buildFormRequest(operation, signed);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw RtmartEntryException.httpStatus(response.code());
            }
            RtmartEntryResponse envelope = objectMapper.readValue(readBounded(response), RtmartEntryResponse.class);
            if (!"E0000".equals(envelope.code())) {
                throw RtmartEntryException.remote(envelope.code());
            }
            return new EntryUrlResult(requireSuccessfulJumpUrl(envelope));
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw RtmartEntryException.transport(exception);
        }
    }
}
```

OkHttp 设置 `retryOnConnectionFailure(false)`、`followRedirects(false)`、`followSslRedirects(false)`；表单只由签名后 Map
构造。2xx 后限长读取 JSON，成功只接受 `E0000 + success + HTTPS jumpUrl`。异常只携带 reason/code/status/cause。

- [ ] **Step 4: 运行绿灯并回归**

先重复 Step 2，再运行 `RtmartEntryUrlPortContractTest,InstoreBuyerEntryFacadeTest`，Expected: 全部 PASS。提交 HTTP Adapter。

### Task 3: AutoConfiguration 与失败关闭替换

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryAdapterConfiguration.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryAdapterConfigurationTest.java`
- Modify test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/configuration/InstoreEntryPortConfigurationTest.java`

- [ ] **Step 1: 写失败装配测试**

使用 `ApplicationContextRunner` 验证 disabled 时只有 fallback；enabled + 完整配置时只有真实 Adapter；缺 app key、私钥、
非法 base URL/私钥时 context startup failure；自定义用户 Bean 仍优先。

- [ ] **Step 2: 运行红灯**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server \
  -Dtest='RtmartEntryAdapterConfigurationTest,InstoreEntryPortConfigurationTest' test
```

Expected: 新配置类不存在或真实 Bean 未注册而 FAIL。

- [ ] **Step 3: 实现装配**

```java
@AutoConfiguration(before = InstoreEntryPortConfiguration.class)
@EnableConfigurationProperties(RtmartEntryProperties.class)
@ConditionalOnProperty(prefix = "aifuli.supply-chain.instore.rtmart",
        name = "enabled", havingValue = "true")
public class RtmartEntryAdapterConfiguration {
    @Bean
    OkHttpClient rtmartEntryOkHttpClient(RtmartEntryProperties properties) {
        properties.validateEnabled();
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .writeTimeout(properties.getWriteTimeout())
                .readTimeout(properties.getReadTimeout())
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    @Bean
    RtmartEntryUrlPort rtmartEntryUrlHttpAdapter(RtmartEntryProperties properties,
                                                 OkHttpClient rtmartEntryOkHttpClient,
                                                 ObjectMapper objectMapper) {
        PrivateKey key = RtmartPrivateKeyParser.parsePkcs8(properties.getPrivateKey());
        RtmartRequestSigner signer = RtmartRequestSigner.production(
                properties.getAppKey(), key, Clock.systemUTC());
        return new RtmartEntryUrlHttpAdapter(
                properties, rtmartEntryOkHttpClient, objectMapper, signer);
    }
}
```

配置创建单例 `OkHttpClient`、PKCS#8 私钥、signer 和 Port；AutoConfiguration imports 将该配置放在 fallback 之前。

- [ ] **Step 4: 运行绿灯并提交**

重复 Step 2，Expected: 全部 PASS；再运行 server `clean test`。提交装配切片。

### Task 4: 联调文档与部署产物

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/.dockerignore`
- Create: `script/deploy/supply-chain-test/docker-compose.yml`
- Create: `script/deploy/supply-chain-test/.env.example`
- Create: `script/deploy/supply-chain-test/supply-chain-server-test.yaml`
- Modify: `docs/集成测试.md`

- [ ] **Step 1: 新增安全部署文件**

Dockerfile 只复制精确 JAR、使用 Java 21 JRE、非 root UID、58190 TCP healthcheck。Compose 使用 `${SUPPLY_CHAIN_IMAGE}`、
`network_mode: host`、`restart: unless-stopped`、`.env` 和日志轮转；`.env.example` 只含空占位符且显式关闭 timer/outbox。

- [ ] **Step 2: 新增 Nacos YAML 与人工联调说明**

Nacos 文件只保存 enabled/base URL/path/timeouts/maxResponseBytes 及 timer/outbox=false。`docs/集成测试.md` 记录：
正式成功 envelope、046 排障、MySQL 8.0.36/8.4 差异、Nacos 注册 `172.19.64.105:58190`、摘要-only 数据验证。

- [ ] **Step 3: 静态验证并提交**

Run:

```bash
# 从仓库根目录执行；SUPPLY_CHAIN_ENV_FILE 相对 docker-compose.yml 所在目录解析。
SUPPLY_CHAIN_ENV_FILE=.env.example \
  docker compose --env-file script/deploy/supply-chain-test/.env.example \
  -f script/deploy/supply-chain-test/docker-compose.yml config --quiet
git diff --check
```

Expected: Compose 配置语法通过，Git diff 无空白错误。若本机 daemon 尚未启动，`docker compose config` 仍应可执行。

### Task 5: 完整门禁与 linux/amd64 镜像

- [ ] **Step 1: 供应链全量单测**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server clean test
```

Expected: BUILD SUCCESS，0 failures/errors。

- [ ] **Step 2: 旅通契约门禁**

执行 Task 2 的 Failsafe 命令，Expected: 全部契约测试通过。

- [ ] **Step 3: 打包和镜像验证**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am package
docker buildx build --platform linux/amd64 --load \
  -t aifuli/supply-chain-server:$(git rev-parse --short HEAD) \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server
docker image inspect aifuli/supply-chain-server:$(git rev-parse --short HEAD) \
  --format '{{.Os}}/{{.Architecture}}'
```

Expected: JAR 打包成功，镜像为 `linux/amd64`。

- [ ] **Step 4: 代码审查、敏感值扫描与最终提交**

审查相对设计提交的 diff；确认没有实际 app key、数据库/Nacos 密码、RSA 私钥、token/jumpUrl sentinel 泄漏到生产文件；
只提交本计划列出的文件，不提交 `.flattened-pom 2/3.xml`。完成后记录准确测试数量、镜像 tag 和未执行的远程部署步骤。
