# 旅通命令回调协议加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复旅通接口②～⑥的动态码、退款金额、响应码、签名、nonce 和部署隔离问题，并让实现、测试与文档完全一致。

**Architecture:** 旅通协议码由 `RtmartResponseCode` 单一枚举管理；敏感动态码只沿建单调用栈进入 `BuyerCashierPort`，仅参与不可逆幂等摘要、不明文持久化；退款正数约束由 Lifecycle 最终兜底；协议校验在 Controller 内失败关闭。部署指定提交通过临时 worktree 构建，不改变调用者工作区。

**Tech Stack:** Java 21、Spring Boot、JUnit 5、AssertJ、Mockito、MyBatis Plus、Redis、Testcontainers MySQL 8.4、Liquibase、Bash、Docker Compose。

---

### Task 1: 用枚举封闭旅通响应码

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartResponseCode.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartResponse.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartMemberResponse.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartMemberCallbackController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackController.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackControllerTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/rtmart/RtmartMemberCallbackControllerTest.java`

- [ ] **Step 1: 写响应码范围与缺失签名的失败测试**

```java
@Test
void missingSignatureUsesConfirmedSignatureCode() {
    RtmartCreateOrderRequest request = validCreateOrderRequest();
    request.setSign(null);
    assertThat(controller.createOrder(request).getCode())
            .isEqualTo(RtmartResponseCode.INVALID_SIGNATURE.getCode());
}

@Test
void responseCodeCatalogContainsOnlyConfirmedCodes() {
    assertThat(Arrays.stream(RtmartResponseCode.values()).map(RtmartResponseCode::getCode))
            .containsExactly("E0000", "E4001", "E4002", "E4003", "E4004", "E5000");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:
```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=RtmartCommandCallbackControllerTest,RtmartMemberCallbackControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```
Expected: FAIL，原因是 `RtmartResponseCode` 尚不存在且缺失 sign 仍返回 `E4001`。

- [ ] **Step 3: 实现枚举并迁移响应创建入口**

```java
@Getter
@RequiredArgsConstructor
public enum RtmartResponseCode {
    SUCCESS("E0000"),
    INVALID_ARGUMENT("E4001"),
    REQUEST_EXPIRED("E4002"),
    INVALID_APP_KEY("E4003"),
    INVALID_SIGNATURE("E4004"),
    SYSTEM_ERROR("E5000");

    private final String code;
}
```

`RtmartResponse` 和 `RtmartMemberResponse` 的 `success/fail` 只接收枚举；两个 Controller 全部改用枚举。`validateProtocol` 单独先检查 sign，缺失直接返回 `INVALID_SIGNATURE`。

- [ ] **Step 4: 用内部错误常量替换整数映射**

在 `mapServiceException` 中使用 `SupplyChainErrorCodeConstants.*.getCode()` 组成明确的调用方错误集合；调用方可修正错误返回 `INVALID_ARGUMENT`，基础设施和未知错误返回 `SYSTEM_ERROR`，删除 `E4005～E4008、E5001`。

- [ ] **Step 5: 运行定向测试并确认 GREEN**

运行 Step 2 命令，Expected: PASS。

### Task 2: 让动态码贯穿到 SaaS 收银台端口

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/CreatePaymentOrderReqDTO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/instore/InstorePaymentCommandApiContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelCommandApiImpl.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierPort.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackController.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackControllerTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelCommandApiImplTest.java`

- [ ] **Step 1: 写动态码传播失败测试**

```java
@Test
void typeTwoPassesDynamicCodeToFacade() {
    RtmartCreateOrderRequest request = validCreateOrderRequest();
    request.setOrderType(2);
    request.setDynamicCode("DYNAMIC-001");
    request.setSign(signCreateOrder(request));
    when(commandFacade.createPaymentOrder(any())).thenReturn(paymentTicket());

    controller.createOrder(request);

    verify(commandFacade).createPaymentOrder(argThat(command ->
            "DYNAMIC-001".equals(command.dynamicCode())));
}
```

Facade 测试捕获 `BuyerCashierPort.CreateCashierOrderCommand`，断言类型 2 为 `DYNAMIC-001`，类型 1 为 `null`；Lifecycle 测试断言相同业务键但动态码不同会触发 `INSTORE_IDEMPOTENCY_CONFLICT`。

- [ ] **Step 2: 运行动态码相关测试并确认 RED**

Run:
```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=InstorePaymentCommandApiContractTest,RtmartCommandCallbackControllerTest,InstoreChannelCommandFacadeTest,InstoreChannelLifecycleTest,InstoreChannelCommandApiImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```
Expected: FAIL，当前各命令 record 与收银台端口没有 `dynamicCode`。

- [ ] **Step 3: 扩展命令并保持敏感信息非持久化**

为 `CreatePaymentOrderReqDTO`、`WireCreatePaymentCommand`、`PreparePaymentCommand`、`CreatePaymentOrderCommand`、`PreparedPayment` 和 `BuyerCashierPort.CreateCashierOrderCommand` 增加 `String dynamicCode`。Controller/RPC 映射时类型 1 传 `null`，类型 2 传原值；Facade 对类型 2 再次失败关闭。

在 `canonicalPaymentRequest` 的规范 Map 中增加：

```java
"dynamicCode", command.dynamicCode()
```

不得给 `InstorePaymentFlowDO` 或任何 DDL 增加动态码字段，日志也不得引用该字段。

- [ ] **Step 4: 运行 Step 2 命令并确认 GREEN**

Expected: PASS，捕获的收银台命令携带动态码，持久化对象不含该字段。

### Task 3: 在领域层封闭退款金额并返回实际退款金额

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackController.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundFlowIntegrationTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackControllerTest.java`

- [ ] **Step 1: 写非正数领域失败测试和退款响应测试**

```java
assertThatThrownBy(() -> lifecycle.acceptRefund(commandWithAmount(0L)))
        .isInstanceOf(ServiceException.class)
        .extracting("code")
        .isEqualTo(INSTORE_REFUND_AMOUNT_INVALID.getCode());
```

Controller 分别构造成功、处理中、失败 `RefundStatusView`：成功断言 `refundFee=buyerActualRefundAmount`；处理中断言申请金额；失败断言金额为空且拒绝原因保留。

- [ ] **Step 2: 运行退款相关测试并确认 RED**

Run:
```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=InstoreChannelLifecycleTest,RtmartCommandCallbackControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```
Expected: FAIL，零元退款通过核心校验且成功响应仍使用申请金额。

- [ ] **Step 3: 增加内部稳定错误并实现核心校验**

```java
ErrorCode INSTORE_REFUND_AMOUNT_INVALID =
        error(1_050_001_047, "到店购退款金额必须大于零");
```

加入 `RETRY_DIRECTIVES`，值为 `DO_NOT_RETRY`。`requireRefundable` 在读取/汇总 Case 前先判断 `refundAmount <= 0` 并抛该错误。

- [ ] **Step 4: 按退款状态映射响应金额**

成功态必须读取 `buyerActualRefundAmount`，缺失时抛内部状态异常并映射 `SYSTEM_ERROR`；处理中返回 `channelRefundAmount`；失败态返回 `null`。状态判断使用 `InstoreRefundStatus` 枚举，不使用短整型魔法值。

- [ ] **Step 5: 运行单测和真实 MySQL 集成测试**

Run:
```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=InstoreRefundFlowIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```
Expected: PASS，零元退款不产生退款 Case。

### Task 4: nonce 严格失败关闭并清理敏感日志

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart/RtmartMemberCallbackController.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/rtmart/RtmartCommandCallbackControllerTest.java`

- [ ] **Step 1: 写 Redis null、异常和重放失败测试**

```java
when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);
assertThat(controller.createOrder(validCreateOrderRequest()).getCode()).isEqualTo("E5000");

when(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
        .thenThrow(new RedisConnectionFailureException("down"));
assertThat(controller.createOrder(validCreateOrderRequest()).getCode()).isEqualTo("E5000");
```

重放明确返回 `false`，断言 `E4001` 且 Facade 未调用。

- [ ] **Step 2: 运行 Controller 测试并确认 RED**

Run Task 1 Step 2 命令，Expected: null 当前被放行，Redis 异常当前逃逸。

- [ ] **Step 3: 实现严格判断和稳定响应**

```java
try {
    Boolean consumed = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
    if (!Boolean.TRUE.equals(consumed)) {
        return consumed == null
                ? RtmartResponse.fail(SYSTEM_ERROR, "服务暂时不可用")
                : RtmartResponse.fail(INVALID_ARGUMENT, "请求已处理，请勿重复提交");
    }
} catch (RuntimeException ex) {
    log.error("[RTMART][inbound] nonce store unavailable, nonceHash={}", nonceHashPrefix(nonceStr), ex);
    return RtmartResponse.fail(SYSTEM_ERROR, "服务暂时不可用");
}
```

移除完整 `nonce_str` 和 `userCode` 日志；仅记录不可逆哈希前缀。接口①与接口②～⑥保持相同脱敏规则。

- [ ] **Step 4: 运行 Controller 测试并确认 GREEN**

Expected: PASS。

### Task 5: 让指定提交部署不修改当前工作区

**Files:**
- Modify: `aifuli-module-supply-chain/scripts/deploy-pro143.sh`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/DeployPro143ScriptContractTest.java`

- [ ] **Step 1: 写脚本契约失败测试**

```java
@Test
void specifiedCommitUsesTemporaryWorktreeWithoutCheckout() throws IOException {
    Path repoRoot = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().normalize().resolve("../..").normalize();
    String script = Files.readString(repoRoot.resolve("aifuli-module-supply-chain/scripts/deploy-pro143.sh"));
    assertThat(script).doesNotContain("git checkout");
    assertThat(script).contains("git worktree add --detach");
    assertThat(script).contains("git worktree remove --force");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:
```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=DeployPro143ScriptContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```
Expected: FAIL，脚本仍包含 `git checkout`。

- [ ] **Step 3: 使用临时 worktree 构建指定提交**

脚本引入 `BUILD_ROOT`、`WORKTREE_DIR` 和 `cleanup()`；指定 commit 时验证提交存在，执行 `git worktree add --detach "${WORKTREE_DIR}" "${COMMIT_HASH}"`，从 `BUILD_ROOT` 计算模块路径；退出时删除临时 worktree 和镜像包。当前工作区模式保持原行为。

- [ ] **Step 4: 验证脚本契约和语法**

Run:
```bash
bash -n aifuli-module-supply-chain/scripts/deploy-pro143.sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=DeployPro143ScriptContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```
Expected: 两条命令均成功，当前分支与 HEAD 不变。

### Task 6: 同步详细设计、接口文档和联调手册

**Files:**
- Modify: `docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md`
- Modify: `docs/integration/rtmart/instore-channel-command-api.html`
- Modify: `docs/集成测试.md`

- [ ] **Step 1: 更新 API 契约设计**

记录供应链中台直接承载旅通入站适配器的当前决策；在建单 DTO/命令/端口增加 `dynamicCode`；在响应 DTO 增加 `payTime/outTradeNo/refundTime/refuseReason`；链接 `2026-08-27-rtmart-command-callback-hardening-design.md`。

- [ ] **Step 2: 更新 HTML 接口文档**

删除字段“暂不返回”，时间窗口改为三分钟；错误码表只保留 `E0000、E4001～E4004、E5000`；注明成功退款金额为实际退款金额。

- [ ] **Step 3: 更新集成测试文档**

补充类型 2 动态码、缺失签名 `E4004`、nonce 重放 `E4001`、Redis 故障 `E5000`、退款成功实际金额和领域非正数拒绝步骤；修正接口①旧的 `E4003 签名验证失败` 记录。

- [ ] **Step 4: 检查文档漂移**

Run:
```bash
rg -n "暂不返回|五分钟|5分钟|E4005|E4006|E4007|E4008|E5001|E4003 签名" docs/integration/rtmart/instore-channel-command-api.html docs/集成测试.md docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md
```
Expected: 无匹配。

### Task 7: 完整回归与交付检查

**Files:**
- Verify only: all files modified above

- [ ] **Step 1: 运行供应链完整单元测试**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am clean test
```
Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 2: 运行真实 MySQL 退款集成测试**

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=InstoreRefundFlowIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```
Expected: Testcontainers MySQL 与 Liquibase 启动成功，测试全部通过。

- [ ] **Step 3: 静态与变更检查**

```bash
git diff --check
rg -n '"E(0000|4001|4002|4003|4004|5000)"' aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/rtmart
git status --short
```
Expected: `git diff --check` 无输出；错误码字符串仅存在于 `RtmartResponseCode`；工作区只包含本计划内文件。
