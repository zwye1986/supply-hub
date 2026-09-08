# SaaS 商城会员资料三元解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以字符串三元组 `(sourceSystem, externalTenantId, buyerUserId)` 完成供应链到 member-server 的会员资料解析，并收敛本轮评审发现的旅通 JSON、日志脱敏与镜像 JVM 默认值问题。

**Architecture:** member-api 暴露专用 RPC 合同，member-server 独占 `buyerUserId -> member_user.id` 的兼容解释；供应链 Adapter 只校验冻结路由、透传三元字符串并临时恢复租户上下文。旅通协议和 Docker 改动保持为独立、可验证的提交，最终由供应链全量门禁和定向 member 测试共同验收。

**Tech Stack:** Java 21、Spring Boot、OpenFeign、Jakarta Validation、MyBatis Plus、JUnit 5、Mockito、AssertJ、ArchUnit、OkHttp MockWebServer、Docker/Compose、Maven

---

## 文件边界与工作区约束

- 新增 `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/MemberProfileResolveApi.java`：专用 Feign/RPC 合同。
- 新增 `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/MemberProfileSourceSystemConstants.java`：来源系统白名单常量。
- 新增 `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/dto/MemberProfileResolveReqDTO.java`：三元字符串请求与安全诊断文本。
- 新增 `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/dto/MemberProfileResolveRespDTO.java`：最小会员资料响应与安全诊断文本。
- 新增 `aifuli-module-member/aifuli-module-member-api/src/test/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiContractTest.java`：路径、类型、校验和脱敏合同。
- 修改 `aifuli-module-member/aifuli-module-member-api/pom.xml`：仅增加测试依赖。
- 新增 `aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiImpl.java`：路由校验、member 内部主键解析和查询。
- 新增 `aifuli-module-member/aifuli-module-member-server/src/test/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiImplTest.java`：member 侧纯逻辑单测。
- 修改 `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/enums/ErrorCodeConstants.java`：增加稳定路由错误。
- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml`：保留当前 member-api、租户 starter 依赖。
- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/framework/rpc/config/RpcConfiguration.java`：只注册专用 API。
- 新增/重写 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileRoutePolicy.java`：精确路由白名单与 externalTenantId 框架转换。
- 新增/重写 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapter.java`：三元透传、结果映射、上下文恢复和安全日志。
- 新增 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationLogSanitizer.java`：外部租户不可逆短摘要。
- 重写 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapterTest.java`：替换当前错误的 Long 转换测试。
- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacade.java`：不再打印完整 externalTenantId。
- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacadeTest.java`：验证 Facade 日志脱敏。
- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`：禁止 SaaS 会员 Adapter 调用 Long 字符串解析方法。
- 修改现有旅通 Adapter、Signer 及其测试：统一 JSON 命名并保留“仅过滤 null”的行为。
- 修改 Dockerfile、`script/deploy/supply-chain-test/.env.example`：落地已确认的 6 GiB 镜像默认值。
- 修改 `docs/集成测试.md`、`docs/供应链中台INSTORE到店购渠道接入实现总结.md`：记录可联调现状、验证步骤和排障边界。

现有未跟踪的两份 HTML 部署报告包含环境信息，不读取内容、不修改、不暂存；任何提交都使用精确路径暂存。当前工作区已有的旅通 JSON、Docker `--create-home`、member 依赖和初版 Adapter 改动属于本次待修正基线，不回退。

### Task 1: 建立 member-api 专用三元解析合同

**Files:**
- Modify: `aifuli-module-member/aifuli-module-member-api/pom.xml`
- Create: `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/MemberProfileResolveApi.java`
- Create: `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/MemberProfileSourceSystemConstants.java`
- Create: `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/dto/MemberProfileResolveReqDTO.java`
- Create: `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile/dto/MemberProfileResolveRespDTO.java`
- Test: `aifuli-module-member/aifuli-module-member-api/src/test/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiContractTest.java`

- [ ] **Step 1: 增加测试依赖并写失败的 API 合同测试**

在 member-api `pom.xml` 增加：

```xml
<dependency>
    <groupId>com.aifuli</groupId>
    <artifactId>aifuli-spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

测试必须用反射断言：`MemberProfileResolveApi.PREFIX` 等于 `/rpc-api/member/profile`，`resolve` 是 POST `/resolve`，参数带 `@Valid @RequestBody`；三个请求字段类型均为 String；Bean Validation 对空值和 `33/129/129` 长度失败；响应只有 `mobile/userName`；两个 DTO 的 `toString()` 不包含测试哨兵值。核心断言如下：

```java
assertThat(MemberProfileResolveApi.PREFIX).isEqualTo("/rpc-api/member/profile");
Method resolve = MemberProfileResolveApi.class.getMethod("resolve", MemberProfileResolveReqDTO.class);
assertThat(resolve.getAnnotation(PostMapping.class).value()).containsExactly(MemberProfileResolveApi.PREFIX + "/resolve");
assertThat(Arrays.stream(resolve.getParameterAnnotations()[0])).anyMatch(Valid.class::isInstance);
assertThat(Arrays.stream(resolve.getParameterAnnotations()[0])).anyMatch(RequestBody.class::isInstance);
assertThat(MemberProfileResolveReqDTO.class.getDeclaredField("sourceSystem").getType()).isEqualTo(String.class);
assertThat(MemberProfileResolveReqDTO.class.getDeclaredField("externalTenantId").getType()).isEqualTo(String.class);
assertThat(MemberProfileResolveReqDTO.class.getDeclaredField("buyerUserId").getType()).isEqualTo(String.class);
assertThat(Arrays.stream(MemberProfileResolveRespDTO.class.getDeclaredFields())
        .filter(field -> !field.isSynthetic()).map(Field::getName))
        .containsExactlyInAnyOrder("mobile", "userName");
```

校验和脱敏用固定值：

```java
MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
request.setSourceSystem("AIFULI_MALL");
request.setExternalTenantId("TENANT-SECRET-SENTINEL");
request.setBuyerUserId("BUYER-SECRET-SENTINEL");
assertThat(request.toString()).doesNotContain("TENANT-SECRET-SENTINEL", "BUYER-SECRET-SENTINEL");

MemberProfileResolveRespDTO response = new MemberProfileResolveRespDTO();
response.setMobile("13800138000");
response.setUserName("NAME-SECRET-SENTINEL");
assertThat(response.toString()).doesNotContain("13800138000", "NAME-SECRET-SENTINEL");
```

- [ ] **Step 2: 运行合同测试并确认 RED**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-api -am \
  -Dtest=MemberProfileResolveApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `testCompile` 因 `MemberProfileResolveApi` 和 DTO 尚不存在而失败；失败原因必须与缺失合同一致。

- [ ] **Step 3: 实现最小 API、常量和 DTO**

API 的有效结构固定为：

```java
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 会员资料解析")
public interface MemberProfileResolveApi {
    String PREFIX = ApiConstants.PREFIX + "/profile";

    @PostMapping(PREFIX + "/resolve")
    @Operation(summary = "按外部三元身份解析会员资料")
    CommonResult<MemberProfileResolveRespDTO> resolve(
            @Valid @RequestBody MemberProfileResolveReqDTO request);
}
```

来源系统常量只声明当前合同支持值：

```java
public final class MemberProfileSourceSystemConstants {
    public static final String AIFULI_MALL = "AIFULI_MALL";

    private MemberProfileSourceSystemConstants() {
    }
}
```

请求字段注释与 DDL 语义一致，并使用以下约束：

```java
@Data
public class MemberProfileResolveReqDTO {
    /** SaaS外部路由来源系统。 */
    @NotBlank(message = "来源系统不能为空")
    @Size(max = 32, message = "来源系统长度不能超过 32")
    private String sourceSystem;
    /** SaaS外部租户路由标识。 */
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128, message = "外部租户标识长度不能超过 128")
    private String externalTenantId;
    /** 采购商范围内C端用户唯一标识。 */
    @NotBlank(message = "采购商用户标识不能为空")
    @Size(max = 128, message = "采购商用户标识长度不能超过 128")
    private String buyerUserId;

    @Override
    public String toString() {
        return "MemberProfileResolveReqDTO{sourceSystem='" + sourceSystem
                + "', externalTenantId='<redacted>', buyerUserId='<redacted>'}";
    }
}
```

响应只包含资料字段并固定脱敏：

```java
@Data
public class MemberProfileResolveRespDTO {
    /** 会员手机号。 */
    private String mobile;
    /** 会员展示姓名。 */
    private String userName;

    @Override
    public String toString() {
        return "MemberProfileResolveRespDTO{mobile='<redacted>', userName='<redacted>'}";
    }
}
```

四个重要类型的中文类注释均链接：
`docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api`。

- [ ] **Step 4: 运行合同测试并确认 GREEN**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-api -am \
  -Dtest=MemberProfileResolveApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `MemberProfileResolveApiContractTest` 全部通过。

- [ ] **Step 5: 精确暂存并提交 API 合同**

```bash
git add -- \
  aifuli-module-member/aifuli-module-member-api/pom.xml \
  aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/profile \
  aifuli-module-member/aifuli-module-member-api/src/test/java/com/aifuli/module/member/api/profile
git diff --cached --check
git commit -m "feat(member): add member profile resolver contract"
```

### Task 2: 在 member-server 内部解释 buyerUserId

**Files:**
- Modify: `aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/enums/ErrorCodeConstants.java`
- Create: `aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiImpl.java`
- Test: `aifuli-module-member/aifuli-module-member-server/src/test/java/com/aifuli/module/member/api/profile/MemberProfileResolveApiImplTest.java`

- [ ] **Step 1: 证明新错误码未冲突**

```bash
rg -n "1_004_001_005|MEMBER_PROFILE_ROUTE_INVALID" . --glob '!docs/superpowers/plans/2026-08-26-saas-member-profile-resolver.md'
```

Expected: 无匹配，退出码 1；`1_004_001_005` 可分配给本合同。

- [ ] **Step 2: 写 member 实现失败测试**

使用 Mockito mock `MemberUserService`，每个测试前保存并设置 `TenantContextHolder`，每个测试后恢复。覆盖以下精确结果：

```java
when(userService.getUser(1024L)).thenReturn(MemberUserDO.builder()
        .id(1024L).mobile("13800138000").name("真实姓名").nickname("昵称").build());
assertThat(api.resolve(request("AIFULI_MALL", "100", "1024")).getData().getUserName())
        .isEqualTo("真实姓名");

when(userService.getUser(1024L)).thenReturn(MemberUserDO.builder()
        .id(1024L).mobile("13800138000").name(" ").nickname("昵称").build());
assertThat(api.resolve(request("AIFULI_MALL", "100", "1024")).getData().getUserName())
        .isEqualTo("昵称");

assertThat(api.resolve(request("AIFULI_MALL", "100", "opaque-user")).getData()).isNull();
assertThat(api.resolve(request("AIFULI_MALL", "100", "9223372036854775808")).getData()).isNull();
```

另外断言用户不存在、手机号空白都返回成功空 data，且不支持的 sourceSystem、缺失租户上下文、`externalTenantId="0100"` 与上下文 `100L` 不完全一致时抛出的 `ServiceException.code` 都等于 `MEMBER_PROFILE_ROUTE_INVALID.getCode()`。非法 buyerUserId 场景使用 `verifyNoInteractions(userService)`。

- [ ] **Step 3: 运行 member-server 测试并确认 RED**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-server -am \
  -Dtest=MemberProfileResolveApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `testCompile` 因实现类和错误码尚不存在而失败。

- [ ] **Step 4: 增加稳定错误码和最小实现**

在用户错误码段追加：

```java
ErrorCode MEMBER_PROFILE_ROUTE_INVALID =
        new ErrorCode(1_004_001_005, "会员资料解析路由无效");
```

实现的核心逻辑固定为：

```java
@RestController
@Validated
@RequiredArgsConstructor
@Slf4j
public class MemberProfileResolveApiImpl implements MemberProfileResolveApi {
    private final MemberUserService userService;

    @Override
    public CommonResult<MemberProfileResolveRespDTO> resolve(MemberProfileResolveReqDTO request) {
        long startedAt = System.nanoTime();
        validateRoute(request);
        Long userId = parseMemberUserId(request.getBuyerUserId());
        if (userId == null) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", startedAt);
            return CommonResult.success(null);
        }
        MemberUserDO user = userService.getUser(userId);
        if (user == null || !hasText(user.getMobile())) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", startedAt);
            return CommonResult.success(null);
        }
        MemberProfileResolveRespDTO response = new MemberProfileResolveRespDTO();
        response.setMobile(user.getMobile());
        response.setUserName(hasText(user.getName()) ? user.getName() : user.getNickname());
        logCompletion(request.getSourceSystem(), "FOUND", startedAt);
        return CommonResult.success(response);
    }

    private static void validateRoute(MemberProfileResolveReqDTO request) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (!MemberProfileSourceSystemConstants.AIFULI_MALL.equals(request.getSourceSystem())
                || tenantId == null || !tenantId.toString().equals(request.getExternalTenantId())) {
            throw exception(MEMBER_PROFILE_ROUTE_INVALID);
        }
    }

    private static Long parseMemberUserId(String buyerUserId) {
        try {
            return Long.valueOf(buyerUserId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
```

辅助方法固定为：

```java
private static boolean hasText(String value) {
    return value != null && !value.isBlank();
}

private static String safeSourceSystem(String sourceSystem) {
    return MemberProfileSourceSystemConstants.AIFULI_MALL.equals(sourceSystem)
            ? MemberProfileSourceSystemConstants.AIFULI_MALL : "INVALID";
}

private void logCompletion(String sourceSystem, String status, long startedAt) {
    log.info("[MEMBER][profile-resolve] response sourceSystem={}, status={}, costMs={}",
            safeSourceSystem(sourceSystem), status,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
}
```

`resolve` 第一行耗时初始化之后增加只含 `safeSourceSystem` 的入口日志。类和 `resolve` 方法中文注释链接详细设计 `#32-member-server`；`catch` 只把明确的格式不匹配转为未找到，不捕获查询或基础设施异常，日志不含 request、租户、buyerUserId、手机号、姓名或异常 message。

- [ ] **Step 5: 运行 member-server 测试并确认 GREEN**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-server -am \
  -Dtest=MemberProfileResolveApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 所有 member 解析测试通过，且 Mockito 证明内部转换只发生于 member-server。

- [ ] **Step 6: 精确暂存并提交 member 实现**

```bash
git add -- \
  aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/enums/ErrorCodeConstants.java \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/api/profile \
  aifuli-module-member/aifuli-module-member-server/src/test/java/com/aifuli/module/member/api/profile
git diff --cached --check
git commit -m "feat(member): resolve external member profile identity"
```

### Task 3: 重写供应链 SaaS 会员 Adapter

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/framework/rpc/config/RpcConfiguration.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/framework/rpc/config/RpcConfigurationTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationLogSanitizer.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileRoutePolicy.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapter.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapterTest.java`

- [ ] **Step 1: 用新合同重写 Adapter 测试并确认错误基线**

将 mock 类型改为 `MemberProfileResolveApi`，合法路由固定为：

```java
new BuyerIntegrationRoute(1L, 10L, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
        "AIFULI_MALL", "100", "RPC", "SAAS_MEMBER_RPC", "v1", null)
```

FOUND 用字母数字会员号证明原样透传：

```java
when(memberProfileResolveApi.resolve(any())).thenAnswer(invocation -> {
    MemberProfileResolveReqDTO request = invocation.getArgument(0);
    assertThat(request.getSourceSystem()).isEqualTo("AIFULI_MALL");
    assertThat(request.getExternalTenantId()).isEqualTo("100");
    assertThat(request.getBuyerUserId()).isEqualTo("buyer-A9");
    assertThat(TenantContextHolder.getTenantId()).isEqualTo(100L);
    assertThat(TenantContextHolder.isIgnore()).isFalse();
    return CommonResult.success(profile("13800138000", "测试用户"));
});
assertThat(adapter.resolve(command("buyer-A9")).status()).isEqualTo(MemberResolutionStatus.FOUND);
```

分别覆盖：data null、mobile blank -> NOT_FOUND；CommonResult error、null envelope、RPC exception、externalTenantId 非数字/零/负数/Long 越界/带前导零 -> UNAVAILABLE；sourceSystem、transportMode、adapterCode、contractVersion 任一不匹配 -> UNAVAILABLE 且 `verifyNoInteractions`；成功和异常均恢复旧 tenantId/ignore。把当前“业务异常 -> NOT_FOUND”和“buyerUserId 非数字 -> NOT_FOUND”的错误测试删除。

新增 `RpcConfigurationTest`，防止回退为通用会员 API：

```java
EnableFeignClients annotation = RpcConfiguration.class.getAnnotation(EnableFeignClients.class);
assertThat(annotation).isNotNull();
assertThat(annotation.clients()).containsExactly(MemberProfileResolveApi.class);
```

- [ ] **Step 2: 运行 Adapter 测试并确认 RED**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=SaaSMemberProfileAdapterTest,RpcConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 当前 Adapter 仍依赖 `MemberUserApi`、直接解析 buyerUserId，测试编译或行为失败。

- [ ] **Step 3: 实现路由策略、日志摘要和专用 Feign 注册**

`BuyerIntegrationLogSanitizer` 使用 SHA-256 前 12 位十六进制摘要：

```java
public final class BuyerIntegrationLogSanitizer {
    private static final int DIGEST_PREFIX_LENGTH = 12;

    public static String externalTenantDigest(String externalTenantId) {
        if (externalTenantId == null) {
            return "NONE";
        }
        return DigestUtil.sha256Hex(externalTenantId).substring(0, DIGEST_PREFIX_LENGTH);
    }

    private BuyerIntegrationLogSanitizer() {
    }
}
```

`SaaSMemberProfileRoutePolicy` 固定业务常量并返回可用租户：

```java
final class SaaSMemberProfileRoutePolicy {
    static final String TRANSPORT_MODE = "RPC";
    static final String ADAPTER_CODE = "SAAS_MEMBER_RPC";
    static final String CONTRACT_VERSION = "v1";

    static boolean supports(BuyerIntegrationRoute route) {
        return route.capability() == BuyerIntegrationCapabilityCode.MEMBER_PROFILE
                && MemberProfileSourceSystemConstants.AIFULI_MALL.equals(route.sourceSystem())
                && TRANSPORT_MODE.equals(route.transportMode())
                && ADAPTER_CODE.equals(route.adapterCode())
                && CONTRACT_VERSION.equals(route.contractVersion());
    }

    static Long frameworkTenantId(String externalTenantId) {
        try {
            long value = new BigInteger(externalTenantId).longValueExact();
            return value > 0 && Long.toString(value).equals(externalTenantId) ? value : null;
        } catch (NumberFormatException | ArithmeticException invalidTenantId) {
            return null;
        }
    }

    static String safeSourceSystem(String sourceSystem) {
        return MemberProfileSourceSystemConstants.AIFULI_MALL.equals(sourceSystem)
                ? MemberProfileSourceSystemConstants.AIFULI_MALL : "INVALID";
    }

    static String safeAdapterCode(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode) ? ADAPTER_CODE : "INVALID";
    }

    private SaaSMemberProfileRoutePolicy() {
    }
}
```

这里使用 `BigInteger.longValueExact()` 只解释框架租户头；供应链任何位置都不解释 buyerUserId。`RpcConfiguration` 改为：

```java
@EnableFeignClients(clients = MemberProfileResolveApi.class)
```

并删除 `MemberUserApi` 引用。

- [ ] **Step 4: 实现最小 Adapter 结果映射**

Adapter 构造器只注入 `MemberProfileResolveApi`。核心顺序必须是：

```java
BuyerIntegrationRoute route = command.route();
long startedAt = System.nanoTime();
String tenantDigest = BuyerIntegrationLogSanitizer.externalTenantDigest(route.externalTenantId());
if (!SaaSMemberProfileRoutePolicy.supports(route)) {
    logCompletion(route, tenantDigest, "UNAVAILABLE", null, startedAt);
    return unavailable();
}
Long tenantId = SaaSMemberProfileRoutePolicy.frameworkTenantId(route.externalTenantId());
if (tenantId == null) {
    logCompletion(route, tenantDigest, "UNAVAILABLE", null, startedAt);
    return unavailable();
}

MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
request.setSourceSystem(route.sourceSystem());
request.setExternalTenantId(route.externalTenantId());
request.setBuyerUserId(command.buyerUserId());

CommonResult<MemberProfileResolveRespDTO> envelope;
Long oldTenantId = TenantContextHolder.getTenantId();
Boolean oldIgnore = TenantContextHolder.isIgnore();
try {
    TenantContextHolder.setTenantId(tenantId);
    TenantContextHolder.setIgnore(false);
    envelope = memberProfileResolveApi.resolve(request);
} catch (RuntimeException failure) {
    logFailure(route, tenantDigest, failure, startedAt);
    return unavailable();
} finally {
    TenantContextHolder.setTenantId(oldTenantId);
    TenantContextHolder.setIgnore(oldIgnore);
}
if (envelope == null || envelope.isError()) {
    logCompletion(route, tenantDigest, "UNAVAILABLE", envelope == null ? null : envelope.getCode(), startedAt);
    return unavailable();
}
MemberProfileResolveRespDTO member = envelope.getData();
if (member == null || member.getMobile() == null || member.getMobile().isBlank()) {
    logCompletion(route, tenantDigest, "NOT_FOUND", envelope.getCode(), startedAt);
    return new MemberProfileResult(MemberResolutionStatus.NOT_FOUND, null, null);
}
logCompletion(route, tenantDigest, "FOUND", envelope.getCode(), startedAt);
return new MemberProfileResult(MemberResolutionStatus.FOUND, member.getMobile(), member.getUserName());
```

辅助方法固定为：

```java
private static MemberProfileResult unavailable() {
    return new MemberProfileResult(MemberResolutionStatus.UNAVAILABLE, null, null);
}

private void logCompletion(BuyerIntegrationRoute route, String tenantDigest, String status,
                           Integer remoteCode, long startedAt) {
    log.info("[SAAS-MEMBER][resolve] response projectId={}, profileVersionId={}, sourceSystem={}, "
                    + "externalTenantDigest={}, adapterCode={}, status={}, remoteCode={}, costMs={}",
            route.projectId(), route.profileVersionId(),
            SaaSMemberProfileRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
            SaaSMemberProfileRoutePolicy.safeAdapterCode(route.adapterCode()), status, remoteCode,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
}

private void logFailure(BuyerIntegrationRoute route, String tenantDigest,
                        RuntimeException failure, long startedAt) {
    log.warn("[SAAS-MEMBER][resolve] failed projectId={}, profileVersionId={}, sourceSystem={}, "
                    + "externalTenantDigest={}, adapterCode={}, status=UNAVAILABLE, errorClass={}, "
                    + "rootCauseClass={}, costMs={}",
            route.projectId(), route.profileVersionId(),
            SaaSMemberProfileRoutePolicy.safeSourceSystem(route.sourceSystem()), tenantDigest,
            SaaSMemberProfileRoutePolicy.safeAdapterCode(route.adapterCode()),
            failure.getClass().getName(), rootCauseClass(failure),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
}

private static String rootCauseClass(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    while (current.getCause() != null && visited.add(current)) {
        current = current.getCause();
    }
    return current.getClass().getName();
}
```

入口日志使用同一安全字段和 `request` 固定事件名，不能把 request DTO 当参数输出。异常调用不把 Throwable 作为 SLF4J 最后参数。重要类和方法中文注释链接详细设计 `#33-supply-chain-server`、`#4-日志与异常边界`。

- [ ] **Step 5: 运行 Adapter 测试并确认 GREEN**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=SaaSMemberProfileAdapterTest,RpcConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Adapter 所有映射、透传和上下文恢复测试通过。

- [ ] **Step 6: 精确暂存并提交 Adapter**

```bash
git add -- \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/framework/rpc/config/RpcConfiguration.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationLogSanitizer.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/saasmall \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/framework/rpc/config/RpcConfigurationTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/saasmall
git diff --cached --check
git commit -m "feat(supply-chain): resolve SaaS member profiles"
```

### Task 4: 加固日志与 buyerUserId 架构门禁

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacadeTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapterTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [ ] **Step 1: 先写日志泄漏和架构失败测试**

给 Facade 和 Adapter 的 logger 装配 `ListAppender<ILoggingEvent>`，使用以下哨兵：

```java
String externalTenantId = "TENANT-SECRET-SENTINEL";
String buyerUserId = "BUYER-SECRET-SENTINEL";
String mobile = "13800138000";
String userName = "NAME-SECRET-SENTINEL";
String failureMessage = "RPC-SECRET-SENTINEL";
```

把 formattedMessage、argumentArray 和 throwableProxy 合并后断言五个值均不存在；Adapter RPC 异常日志还必须 `doesNotHaveThrowableProxy()`，同时包含异常类名和 root-cause 类名。

在架构测试增加两条规则并显式执行：

```java
static final ArchRule saas_member_adapter_never_calls_long_parse = noClasses()
        .that().resideInAPackage("..integration.saasmall..")
        .should().callMethod(Long.class, "parseLong", String.class);

static final ArchRule saas_member_adapter_never_calls_long_value_of = noClasses()
        .that().resideInAPackage("..integration.saasmall..")
        .should().callMethod(Long.class, "valueOf", String.class);
```

- [ ] **Step 2: 运行定向测试并确认 RED**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreChannelMemberFacadeTest,SaaSMemberProfileAdapterTest,SupplyChainModuleArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Facade 当前完整输出 externalTenantId，日志脱敏测试失败；若 Task 3 已正确移除 Long 解析，新增架构规则应通过。

- [ ] **Step 3: 将 Facade 日志改为摘要**

请求日志由：

```java
log.info("[INSTORE][member] resolve 请求 projectId={}, profileVersionId={}, sourceSystem={}, externalTenantId={}",
        route.projectId(), route.profileVersionId(), route.sourceSystem(), route.externalTenantId());
```

改为：

```java
log.info("[INSTORE][member] resolve 请求 projectId={}, profileVersionId={}, sourceSystem={}, externalTenantDigest={}",
        route.projectId(), route.profileVersionId(), route.sourceSystem(),
        BuyerIntegrationLogSanitizer.externalTenantDigest(route.externalTenantId()));
```

补充中文方法注释并链接详细设计 `#4-日志与异常边界`，其余凭证释放和稳定错误语义不变。

- [ ] **Step 4: 运行日志与架构测试并确认 GREEN**

重复 Step 2 命令。

Expected: 三个测试类全部通过，日志中无三元身份原文或会员资料。

- [ ] **Step 5: 提交日志与架构门禁**

```bash
git add -- \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacadeTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/saasmall/SaaSMemberProfileAdapterTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java
git diff --cached --check
git commit -m "test(supply-chain): guard member identity boundaries"
```

### Task 5: 收敛旅通 JSON 协议实现与命名

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapter.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSigner.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapterContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSignerTest.java`

- [ ] **Step 1: 固化 JSON 与空字符串签名契约**

契约测试必须直接断言：

```java
assertThat(request.getHeader("Content-Type")).isEqualTo("application/json; charset=UTF-8");
Map<String, String> jsonFields = JSON_MAPPER.readValue(
        request.getBody().readUtf8(), new TypeReference<Map<String, String>>() {});
assertThat(jsonFields).containsEntry("company_code", COMPANY_CODE)
        .containsEntry("token", TOKEN_SENTINEL)
        .containsKey("sign");
```

Signer 测试使用 `Map.of("blank", "", "nullValue", null)` 不可行，改用可含 null 的 `LinkedHashMap`，并断言输出包含 `blank=""`、不包含 `nullValue`，独立验签原文包含 `blank` 字段名。

- [ ] **Step 2: 运行旅通定向测试确认当前行为 GREEN**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=RtmartRequestSignerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=RtmartEntryUrlHttpAdapterContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: 当前未提交实现已经具备 JSON/null-only 行为，两组测试通过；这是对既有正确行为的特征化，不新增协议猜测。

- [ ] **Step 3: 只做语义一致的命名与注释修订**

精确替换以下生产注释：

```text
签名表单 -> 签名 JSON 参数集
签名后的完整表单 -> 签名后的完整 JSON 请求
表单参数签名器 -> JSON 参数签名器
不可变签名表单 -> 不可变签名参数集
唯一可被表单编码的参数集 -> 唯一可被 JSON 编码的参数集
```

测试中的方法/变量/助手精确改名：

```text
homePostsExactFormAndSignatureWhilePreservingChineseAndSpecialCharacters
  -> homePostsExactJsonAndSignatureWhilePreservingChineseAndSpecialCharacters
form -> jsonFields
decodeForm -> decodeJson
assertExactSignedForm -> assertExactSignedJson
```

不改 `JSON_MEDIA_TYPE`、`writeValueAsBytes`、null-only 过滤和签名算法。

- [ ] **Step 4: 重新运行旅通测试并提交**

重复 Step 2 两条命令，然后执行：

```bash
git add -- \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapter.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSigner.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlHttpAdapterContractTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartRequestSignerTest.java
git diff --cached --check
git commit -m "fix(supply-chain): align Rtmart JSON signing contract"
```

### Task 6: 落地 Docker home 与 6 GiB JVM 镜像默认值

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`
- Modify: `script/deploy/supply-chain-test/.env.example`

- [ ] **Step 1: 运行配置 RED 断言**

```bash
grep -Fqx 'ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"' \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile
if grep -Eq '^JAVA_OPTS=' script/deploy/supply-chain-test/.env.example; then
  echo 'FAIL: env example overrides image default' >&2
  exit 1
fi
```

Expected: Dockerfile grep 退出 1，且 `.env.example` 的活动旧值触发失败。

- [ ] **Step 2: 增加镜像默认值并取消示例覆盖**

保留当前已确认的：

```dockerfile
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin app
```

在 `COPY` 后、`USER` 前增加：

```dockerfile
# 镜像提供可移植的 JVM 基线；部署环境可用 JAVA_OPTS 整串覆盖。见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#91-构建。
ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
```

`.env.example` 的活动 `JAVA_OPTS` 替换为：

```dotenv
# JVM 默认使用镜像内的 6 GiB 基线；特殊服务器必须完整覆盖全部参数。
# JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8
```

- [ ] **Step 3: 运行配置 GREEN 与 Compose 解析**

```bash
grep -Fqx 'ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"' \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile
! grep -Eq '^JAVA_OPTS=' script/deploy/supply-chain-test/.env.example
SUPPLY_CHAIN_ENV_FILE=.env.example docker compose \
  --env-file script/deploy/supply-chain-test/.env.example \
  -f script/deploy/supply-chain-test/docker-compose.yml config --quiet
```

Expected: 全部退出 0。

- [ ] **Step 4: 构建并检查镜像默认值和覆盖值**

先生成供应链 JAR，再构建镜像：

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am package
docker buildx build --platform linux/amd64 --load \
  --tag aifuli/supply-chain-server:jvm-defaults-test \
  --file aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server
docker image inspect aifuli/supply-chain-server:jvm-defaults-test \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -Fqx 'JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8'
docker run --rm --platform linux/amd64 --entrypoint sh \
  -e 'JAVA_OPTS=-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8' \
  aifuli/supply-chain-server:jvm-defaults-test -c \
  'test "$JAVA_OPTS" = "-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8"'
```

Expected: build 成功，镜像元数据为 6 GiB，显式覆盖测试退出 0。Docker 不可用时保留前三项静态证据并明确报告，不虚构镜像验证通过。

- [ ] **Step 5: 提交 Docker 制品**

```bash
git add -- \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile \
  script/deploy/supply-chain-test/.env.example
git diff --cached --check
git commit -m "build(supply-chain): set production image defaults"
```

### Task 7: 更新联调文档与实现总结

**Files:**
- Modify: `docs/集成测试.md`
- Modify: `docs/供应链中台INSTORE到店购渠道接入实现总结.md`

- [ ] **Step 1: 运行文档 RED 检查**

```bash
rg -n "MemberProfileResolveApi|SAAS_MEMBER_RPC.*真实|buyerUserId.*member-server.*Long|镜像默认使用.*Xms6g" \
  docs/集成测试.md docs/供应链中台INSTORE到店购渠道接入实现总结.md
```

Expected: 缺少至少一项新实现或验证说明，不能把旧文档视为已同步。

- [ ] **Step 2: 更新自动化和人工联调说明**

在 `docs/集成测试.md` 的 INSTORE 章节增加以下事实：

```markdown
- 真实会员解析链路为供应链 `SaaSMemberProfileAdapter` → member-server
  `MemberProfileResolveApi`；请求三元组均为 String，供应链禁止把 `buyerUserId` 转成 Long，只有
  member-server 可将当前 SaaS 格式解释为 `member_user.id`。
- 路由固定为 `MEMBER_PROFILE/AIFULI_MALL/RPC/SAAS_MEMBER_RPC/v1`；错误或未知路由返回
  `UNAVAILABLE`，会员不存在或手机号为空返回 `NOT_FOUND`。
- 人工联调前应准备数值型 SaaS `externalTenantId`、该租户下存在且手机号非空的 `member_user.id`
  字符串，以及已发布的 MEMBER_PROFILE 档案。日志只能核对 project/profile、租户摘要、状态、错误码和耗时。
- 旅通 HOME/DETAIL 请求体是 `application/json; charset=UTF-8`；签名只过滤 null，空字符串参与。
- 镜像默认使用 `-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8`；环境变量只能整串覆盖。
```

加入 member-api、member-server、供应链 Adapter、ArchUnit、Rtmart ContractTest 的精确 Maven 命令，并把旧的“真实 SaaS 会员资料 Adapter 未完成”改成已完成。排障项明确 CommonResult 错误/RPC 异常不能按会员不存在处理，且不得记录 buyerUserId、手机号、姓名或完整 externalTenantId。

- [ ] **Step 3: 更新实现总结**

把“会员入口出站 Port 仍为 Test Adapter”改成：

```markdown
- `SaaSMemberProfileAdapter` 已按冻结的 `AIFULI_MALL/RPC/SAAS_MEMBER_RPC/v1` 路由调用
  member-server 专用 `MemberProfileResolveApi`；`sourceSystem + externalTenantId + buyerUserId`
  全程以 String 传输，只有 member-server 内部兼容解释当前 `member_user.id`。
```

同步加入专用 API、错误分类、日志脱敏和架构门禁，不改变“独立旅通渠道服务”和真实收银台 Adapter 仍属后续范围的结论。

- [ ] **Step 4: 检查文档并提交**

```bash
rg -n "MemberProfileResolveApi|SAAS_MEMBER_RPC|member-server|Xms6g|application/json" \
  docs/集成测试.md docs/供应链中台INSTORE到店购渠道接入实现总结.md
git diff --check -- docs/集成测试.md docs/供应链中台INSTORE到店购渠道接入实现总结.md
git add -- docs/集成测试.md docs/供应链中台INSTORE到店购渠道接入实现总结.md
git commit -m "docs(supply-chain): document SaaS member resolution"
```

### Task 8: 全量回归与提交边界复核

**Files:**
- Verify: all files listed above
- Preserve: `docs/pro143部署验证报告.html`
- Preserve: `docs/大润发部署检查清单.html`

- [ ] **Step 1: 运行 member 定向门禁**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-api -am \
  -Dtest=MemberProfileResolveApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-member/aifuli-module-member-server -am \
  -Dtest=MemberProfileResolveApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 两条命令均 BUILD SUCCESS。

- [ ] **Step 2: 运行供应链单元测试门禁**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am clean test
```

Expected: BUILD SUCCESS；`SaaSMemberProfileAdapterTest` 与 `SupplyChainModuleArchitectureTest` 被实际执行，不是 0 tests。

- [ ] **Step 3: 运行旅通契约门禁**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=RtmartEntryUrlHttpAdapterContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: BUILD SUCCESS，旅通 JSON 和签名契约全部通过。

- [ ] **Step 4: 扫描敏感实现与协议漂移**

```bash
rg -n "Long\.(parseLong|valueOf)\(.*buyerUserId|MemberUserApi|SAAS_MALL_MEMBER|application/x-www-form-urlencoded|签名表单|构造.*表单" \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test \
  docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md
```

Expected: 无生产路径旧实现或旧协议命名；测试数据中的历史无关文本需逐项确认，不得直接批量替换。

- [ ] **Step 5: 检查工作区和提交范围**

```bash
git diff --check
git status --short
git log -8 --oneline
git diff --name-only HEAD~7..HEAD
```

Expected: 两份 HTML 仍为未跟踪且未被修改/提交；本计划涉及的源码、测试、Docker 和文档没有遗漏的未提交 hunk。若原工作区还存在与本计划无关的用户文件，保持原状并在交付说明中列出。
