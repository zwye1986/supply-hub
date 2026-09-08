# 项目应用统一认证（INSTORE appId/appKey）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地设计 `docs/superpowers/specs/2026-08-23-project-app-auth-design.md`——为 INSTORE 四组 API（10 个方法）提供入站 HMAC-SHA256 验签、凭证链解析、nonce 防重放、可信上下文真实注入与发布于 API 模块的签名客户端。

**Architecture:** 服务端新增 `com.aifuli.module.supplychain.infrastructure.auth` 包（Servlet Filter + 凭证服务 + 信封解密 SPI + nonce 存储 + 上下文桥接），只依赖自身 DAL 与 `infrastructure.security`；客户端（`ProjectAuthOperations` 注册表 / `ProjectAppAuthSigner` / Feign 拦截器）发布于 `aifuli-module-supply-chain-api`，注册表双侧同源。Schema 新增 `019-project-app-auth.sql`（nonce 表）。

**Tech Stack:** Spring Boot 3.5（jakarta servlet）、MyBatis Plus（LambdaQueryWrapper + BaseMapperX）、Liquibase SQL changelog、ArchUnit 1.4.1、Testcontainers/本地 MySQL 8.4、JDK 内置 crypto（HMAC-SHA256 / AES-256-GCM）。

---

## 环境前置（每个任务执行前确认）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro
# 清理 macOS 同步冲突副本（ArchUnit 性能劣化根因，见 docs/集成测试.md）
find aifuli-module-supply-chain/aifuli-module-supply-chain-server/target \( -name "* [0-9]*.class" -o -name "* [0-9]*.xml" \) -delete 2>/dev/null
```

本地 MySQL 8.4（`/opt/homebrew/opt/mysql-client@8.4/bin/mysql -h 127.0.0.1 -u root`，无密码）。Maven 使用 `-o` 离线模式（依赖已在本地仓库）。

## 关键决策与设计文档 delta（已评审，实现时不得偏离）

1. **签名串 = 9 字段以 `\n` 连接（8 个换行符）**。设计文档 §4.3 prose 写「固定 10 行（9 个换行符）」与其列出的 9 个字段自相矛盾，属笔误；实现取 9 字段列表，双侧共用 `ProjectAppAuthSigner.canonicalString` 保证逆运算一致。该协议尚无已上线调用方，无兼容性影响。
2. **`ProjectAppAuthCredentials` 不含 contractVersion**。契约版本按操作从注册表解析（满足 §4.1「须等于注册表登记的版本」），避免调用方配置漂移；凭证对象只承载身份（appId/appKey/keyVersion）与 scope 声明值。
3. **`application.yaml` 新增 `aifuli.security.permit-all-urls` 放行四个 INSTORE 前缀**。现状 INSTORE 端点无 `@PermitAll`，Spring Security 兜底 `anyRequest().authenticated()` 会在登录 token 缺失时 401，真实 HTTP 调用不可用（设计 §2 背景即此）。我们的 HMAC 过滤器 order = `HIGHEST_PRECEDENCE + 10` 先于 Security 执行且失败关闭，放行不降低安全性。
4. **`AuthBuyerProjectDO` 为 `sc_buyer_project` 的认证只读投影**（仅 id/buyerId/status）。架构规则禁止 `infrastructure.auth` 依赖 `..tradecore..`，而设计 §6.2 要求校验项目状态；写路径仍归 `tradecore.buyer.dal.BuyerProjectDO` 所有。
5. **`@EnableScheduling` 挂在 `ProjectAppAuthConfiguration`**。supply-chain-server 为独立进程且模块内无其他 `@Scheduled` 方法，聚合影响为零（AGENTS.md 聚合服务评估）。
6. **HTTP 认证失败响应 = HTTP 200 + `CommonResult(BUYER_AUTH_INVALID)` JSON**。与 `GlobalExceptionHandler.serviceExceptionHandler`（返回 CommonResult，默认 200）约定一致；Filter 先于 DispatcherServlet，须自行写响应。
7. **凭证表种子必须遵守 016 的 `active_slot` CHECK**：CURRENT/GRACE 行 `active_slot` 必须等于 status；REVOKED/EXPIRED 行必须为 NULL。

## 文件结构总览

```
aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/
  ProjectAuthOperations.java        # 受保护操作注册表（双侧同源）+ ScopeType + PROTECTED_PREFIXES
  ProjectAppAuthCredentials.java    # 调用方凭证 record
  ProjectAppAuthSigner.java         # 静态签名工具（10 头生成 + canonicalString + sha256Hex + hmac）
  ProjectAppAuthFeignInterceptor.java # Feign RequestInterceptor

aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/
  ProjectAppAuthProperties.java     # @ConfigurationProperties
  ProjectAppAuthConfiguration.java  # 装配：主密钥校验、resolver、过滤器注册、@EnableScheduling
  ProjectAppAuthFilter.java         # 入站验签过滤器（OncePerRequestFilter）
  CachedBodyHttpServletRequestWrapper.java
  ProjectAppCredentialService.java  # 凭证链 + AuthenticatedApp record
  AppKeySecretResolver.java         # SPI
  LocalEnvelopeAppKeySecretResolver.java
  LocalEnvelopeAppKeyCodec.java     # AES-256-GCM 信封加解密（发码工具）
  NonceStore.java                   # 防重放 + @Scheduled 清扫
  ProjectAuthContext.java           # record
  ProjectAuthContextHolder.java     # ThreadLocal
  ProjectCallContextAccessorImpl.java # @Component 实现 ProjectCallContextAccessor
  ProjectAuthStatus.java            # 应用/项目/能力启用状态枚举
  ProjectAppCredentialStatus.java   # 凭证状态枚举
  dal/dataobject/
    ProjectApplicationDO.java  AuthBuyerProjectDO.java
    ProjectAppCredentialVersionDO.java  ProjectCapabilityDO.java  ProjectAuthNonceDO.java
  dal/mysql/
    ProjectApplicationMapper.java  AuthBuyerProjectMapper.java
    ProjectAppCredentialVersionMapper.java  ProjectCapabilityMapper.java  ProjectAuthNonceMapper.java

aifuli-module-supply-chain-server/src/main/resources/
  db/changelog/releases/1.0.0/019-project-app-auth.sql
  db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml   # 追加 019 条目
  application.yaml                # security.permit-all-urls + auth 配置块
  application-local.yaml          # 开发用 master-key
```

基线测试数：server 309、api 59（设计 §9）。全部任务完成后新增：api 单测 16（T2 契约 4 + T3 签名 9 + T4 拦截器 3）、server 单测 49（T5 信封 12 + T6 凭证链 13 + T7 nonce 6 + T8 上下文 4 + T9 过滤器 13 + 注册 1）、server 集成 8（T10）、架构规则 2 条（T10）。

---

### Task 1: Schema 019（nonce 表）与 Liquibase 门禁

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProjectCashierRouteMigrationIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreMemberEntryRouteMigrationIntegrationTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/019-project-app-auth.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`

- [x] **Step 1: 先改断言（红灯）**

`SupplyChainLiquibaseIntegrationTest` 中（约 66-76 行）：

```java
        assertThat(firstStartup.tableNames())
                .contains("sc_buyer", "sc_supply_chain_order", "sc_timer_task",
                        "sc_sensitive_access_audit", "sc_instore_entry_credential",
                        "sc_buyer_project", "sc_project_capability",
                        "sc_project_application", "sc_project_app_credential_version",
                        "sc_project_auth_nonce")
                .doesNotContain("sc_inventory_asset", "sc_supply_reservation");
```

```java
        assertThat(firstStartup.changeSetCount()).isEqualTo(19L);
```

`InstoreProjectCashierRouteMigrationIntegrationTest.java:96` 与 `InstoreMemberEntryRouteMigrationIntegrationTest.java:112`：

```java
        assertThat(changeSetCount).isEqualTo(19L);
```

- [x] **Step 2: 运行 Liquibase 集成测试确认失败**

```bash
/opt/homebrew/opt/mysql-client@8.4/bin/mysql -h 127.0.0.1 -u root -e "DROP DATABASE IF EXISTS sc_it_gate_15; CREATE DATABASE sc_it_gate_15 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplyChainLiquibaseIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false \
  "-Dsupply-chain.test.jdbc-url=jdbc:mysql://127.0.0.1:3306/sc_it_gate_15?useSSL=false&allowPublicKeyRetrieval=true" \
  verify
```

Expected: FAIL，断言显示实际 changeSetCount 为 18（缺 `sc_project_auth_nonce` 表）。

- [x] **Step 3: 新增 019 SQL**

`019-project-app-auth.sql`：

```sql
-- 项目应用统一认证防重放 nonce 表前向迁移。
-- 设计依据:
-- docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放

-- 表中文名：项目应用认证Nonce防重放记录
-- 核心职责：按 appId + nonce 主键唯一约束拦截重放请求，过期行由低频清扫任务删除
CREATE TABLE sc_project_auth_nonce (
    app_id VARCHAR(128) NOT NULL COMMENT '项目应用稳定标识，逻辑引用sc_project_application.app_id',
    nonce VARCHAR(128) NOT NULL COMMENT '调用方生成的Base64URL随机串',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    expire_at DATETIME(3) NOT NULL COMMENT '过期时间，UTC，取插入时刻加防重放窗口上限',
    PRIMARY KEY (app_id, nonce)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目应用认证Nonce防重放记录';
```

`db.changelog-1.0.0.yaml` 在 018 条目之后追加：

```yaml
- changeSet:
    id: 019-project-app-auth
    author: supply-chain
    changes:
      - sqlFile:
          path: 019-project-app-auth.sql
          relativeToChangelogFile: true
          encoding: UTF-8
          splitStatements: true
          stripComments: false
```

- [x] **Step 4: 重跑 Step 2 命令**

Expected: PASS（Tests run: 1, Failures: 0）。

- [x] **Step 5: 回归两个迁移计数测试**

```bash
for cls in InstoreProjectCashierRouteMigrationIntegrationTest InstoreMemberEntryRouteMigrationIntegrationTest; do
  /opt/homebrew/opt/mysql-client@8.4/bin/mysql -h 127.0.0.1 -u root -e "DROP DATABASE IF EXISTS sc_it_gate_tmp; CREATE DATABASE sc_it_gate_tmp CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
    -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=$cls -Dfailsafe.failIfNoSpecifiedTests=false \
    "-Dsupply-chain.test.jdbc-url=jdbc:mysql://127.0.0.1:3306/sc_it_gate_tmp?useSSL=false&allowPublicKeyRetrieval=true" \
    verify
done
```

Expected: 两个测试各 PASS。

- [x] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProjectCashierRouteMigrationIntegrationTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreMemberEntryRouteMigrationIntegrationTest.java
git commit -m "feat(supply-chain): add project auth nonce schema"
```

---

### Task 2: API 模块受保护操作注册表（双侧同源）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperations.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperationsContractTest.java`

- [x] **Step 1: 写契约测试（红灯）**

```java
package com.aifuli.module.supplychain.api.auth;

import com.aifuli.module.supplychain.api.instore.InstoreBuyerCashierCallbackApi;
import com.aifuli.module.supplychain.api.instore.InstoreBuyerEntryApi;
import com.aifuli.module.supplychain.api.instore.InstoreChannelCommandApi;
import com.aifuli.module.supplychain.api.instore.InstoreChannelMemberApi;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册表契约测试：四组 INSTORE API 的全部 @PostMapping 方法必须被
 * {@link ProjectAuthOperations} 精确登记，防止新增端点漏登记造成保护缺口。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁。
 */
class ProjectAuthOperationsContractTest {

    @Test
    void registryCoversEveryInstorePostMappingExactlyOnce() {
        Set<String> registeredPaths = new HashSet<>();
        for (ProjectAuthOperations.Operation operation : ProjectAuthOperations.OPERATIONS) {
            assertThat(registeredPaths.add(operation.path()))
                    .as("注册表路径必须唯一: %s", operation.path())
                    .isTrue();
        }
        Set<String> apiPaths = new HashSet<>();
        for (Class<?> api : List.of(InstoreChannelCommandApi.class, InstoreChannelMemberApi.class,
                InstoreBuyerEntryApi.class, InstoreBuyerCashierCallbackApi.class)) {
            for (Method method : api.getDeclaredMethods()) {
                PostMapping mapping = method.getAnnotation(PostMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = mapping.value()[0];
                assertThat(apiPaths.add(path)).isTrue();
                assertThat(registeredPaths).as("INSTORE 端点未登记: %s", path).contains(path);
            }
        }
        assertThat(registeredPaths).isEqualTo(apiPaths);
        assertThat(apiPaths).hasSize(10);
    }

    @Test
    void scopeTypesMatchApiGroups() {
        assertThat(scopeOf("/rpc-api/supply-chain/instore/channel-command/createPaymentOrder"))
                .isEqualTo(ProjectAuthOperations.ScopeType.CHANNEL_CODE);
        assertThat(scopeOf("/rpc-api/supply-chain/instore/channel-member/resolve"))
                .isEqualTo(ProjectAuthOperations.ScopeType.CHANNEL_CODE);
        assertThat(scopeOf("/rpc-api/supply-chain/instore/buyer-entry/home"))
                .isEqualTo(ProjectAuthOperations.ScopeType.BUYER_USER_ID);
        assertThat(scopeOf("/rpc-api/supply-chain/buyer/instore/payment/confirmPayment"))
                .isEqualTo(ProjectAuthOperations.ScopeType.NONE);
    }

    @Test
    void protectedPrefixesCoverAllRegisteredPaths() {
        for (ProjectAuthOperations.Operation operation : ProjectAuthOperations.OPERATIONS) {
            assertThat(ProjectAuthOperations.PROTECTED_PREFIXES.stream().anyMatch(operation.path()::startsWith))
                    .as("注册表路径必须落在受保护前缀内: %s", operation.path())
                    .isTrue();
        }
    }

    @Test
    void findByMethodAndPathIsCaseInsensitiveOnMethod() {
        assertThat(ProjectAuthOperations.findByMethodAndPath("post",
                "/rpc-api/supply-chain/instore/channel-member/resolve")).isPresent();
        assertThat(ProjectAuthOperations.findByMethodAndPath("GET",
                "/rpc-api/supply-chain/instore/channel-member/resolve")).isEmpty();
    }

    private ProjectAuthOperations.ScopeType scopeOf(String path) {
        return ProjectAuthOperations.findByMethodAndPath("POST", path).orElseThrow().scopeType();
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAuthOperationsContractTest
```

Expected: 编译失败（`ProjectAuthOperations` 不存在）。

- [x] **Step 3: 实现注册表**

```java
package com.aifuli.module.supplychain.api.auth;

import com.aifuli.module.supplychain.enums.ApiConstants;

import java.util.List;
import java.util.Optional;

/**
 * 项目应用统一认证受保护操作注册表，客户端签名与服务端验签同源。
 *
 * <p>登记 INSTORE 四组 API 全部 10 个受保护操作的精确路径、scope 要求与契约版本；
 * 服务端过滤器按 (HTTP 方法, 路径) 精确匹配，前缀内未登记路径一律拒绝，杜绝保护缺口。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#5-operationcode-注册表}。</p>
 */
public final class ProjectAuthOperations {

    /** scope 声明类型：决定签名串中哪个 scope 字段必填、产出哪型可信上下文。 */
    public enum ScopeType {

        /** 无 scope 声明（SaaS 收银台回调），产出 ProjectContext。 */
        NONE,

        /** 渠道编码声明（渠道命令/会员），产出 ChannelContext。 */
        CHANNEL_CODE,

        /** 采购商用户标识声明（买家入口），产出 BuyerContext。 */
        BUYER_USER_ID
    }

    /** 受保护操作定义：operationCode、精确方法与路径、scope 要求、契约版本。 */
    public record Operation(String operationCode, String httpMethod, String path,
                            ScopeType scopeType, String contractVersion) {
    }

    /** 受保护路径前缀：过滤器注册与安全放行配置共用。 */
    public static final List<String> PROTECTED_PREFIXES = List.of(
            ApiConstants.PREFIX + "/instore/channel-command",
            ApiConstants.PREFIX + "/instore/channel-member",
            ApiConstants.PREFIX + "/instore/buyer-entry",
            ApiConstants.PREFIX + "/buyer/instore/payment");

    /** 当前契约版本；多传输形态出现时随规范化器一并升级。 */
    private static final String CONTRACT_VERSION = "1.0.0";

    /** INSTORE 四组 API 全部 10 个受保护操作。 */
    public static final List<Operation> OPERATIONS = List.of(
            operation("INSTORE_CREATE_PAYMENT_ORDER",
                    ApiConstants.PREFIX + "/instore/channel-command/createPaymentOrder", ScopeType.CHANNEL_CODE),
            operation("INSTORE_QUERY_PAYMENT_STATUS",
                    ApiConstants.PREFIX + "/instore/channel-command/queryPaymentStatus", ScopeType.CHANNEL_CODE),
            operation("INSTORE_CANCEL_PAYMENT_FLOW",
                    ApiConstants.PREFIX + "/instore/channel-command/cancelPaymentFlow", ScopeType.CHANNEL_CODE),
            operation("INSTORE_ACCEPT_REFUND",
                    ApiConstants.PREFIX + "/instore/channel-command/acceptRefund", ScopeType.CHANNEL_CODE),
            operation("INSTORE_QUERY_REFUND_STATUS",
                    ApiConstants.PREFIX + "/instore/channel-command/queryRefundStatus", ScopeType.CHANNEL_CODE),
            operation("INSTORE_RESOLVE_MEMBER",
                    ApiConstants.PREFIX + "/instore/channel-member/resolve", ScopeType.CHANNEL_CODE),
            operation("INSTORE_HOME_ENTRY",
                    ApiConstants.PREFIX + "/instore/buyer-entry/home", ScopeType.BUYER_USER_ID),
            operation("INSTORE_ORDER_DETAIL_ENTRY",
                    ApiConstants.PREFIX + "/instore/buyer-entry/detail", ScopeType.BUYER_USER_ID),
            operation("INSTORE_CONFIRM_PAYMENT_CALLBACK",
                    ApiConstants.PREFIX + "/buyer/instore/payment/confirmPayment", ScopeType.NONE),
            operation("INSTORE_REPORT_REFUND_RESULT_CALLBACK",
                    ApiConstants.PREFIX + "/buyer/instore/payment/reportRefundResult", ScopeType.NONE));

    private ProjectAuthOperations() {
    }

    private static Operation operation(String operationCode, String path, ScopeType scopeType) {
        return new Operation(operationCode, "POST", path, scopeType, CONTRACT_VERSION);
    }

    /**
     * 按 HTTP 方法与路径精确匹配受保护操作。
     *
     * @param httpMethod HTTP 方法，大小写不敏感
     * @param path       请求路径，不含 context path 与 query
     * @return 匹配的操作；未登记路径返回空
     */
    public static Optional<Operation> findByMethodAndPath(String httpMethod, String path) {
        return OPERATIONS.stream()
                .filter(op -> op.httpMethod().equalsIgnoreCase(httpMethod) && op.path().equals(path))
                .findFirst();
    }
}
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAuthOperationsContractTest
```

Expected: PASS（Tests run: 4, Failures: 0）。

- [x] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperations.java aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperationsContractTest.java
git commit -m "feat(supply-chain): register instore auth operations"
```

---

### Task 3: API 模块签名客户端（Credentials + Signer）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthCredentials.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthSigner.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthSignerTest.java`

- [x] **Step 1: 写单测（红灯）**

```java
package com.aifuli.module.supplychain.api.auth;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签名客户端单测：与设计 §4 签名协议逐字段对拍，HMAC 期望值用 JDK crypto 独立重算避免同义反复。
 */
class ProjectAppAuthSignerTest {

    private static final String APP_ID = "app-signer-test";
    private static final String APP_KEY = "secret-key-for-signer-test";
    private static final String CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String ENTRY_PATH = "/rpc-api/supply-chain/instore/buyer-entry/home";
    private static final String CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";

    @Test
    void signProducesAllTenHeadersWithValidSignature() throws Exception {
        byte[] body = "{\"channelOrderId\":\"RT-1\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 3, null, "RT_MART"),
                "POST", CHANNEL_PATH, body, 1_700_000_000_000L, "nonce-fixed-1");

        assertThat(headers).containsOnlyKeys("appId", "keyVersion", "contractVersion", "operationCode",
                "timestamp", "nonce", "requestSha256", "signature", "buyerUserId", "channelCode");
        assertThat(headers.get("appId")).isEqualTo(APP_ID);
        assertThat(headers.get("keyVersion")).isEqualTo("3");
        assertThat(headers.get("contractVersion")).isEqualTo("1.0.0");
        assertThat(headers.get("operationCode")).isEqualTo("INSTORE_CREATE_PAYMENT_ORDER");
        assertThat(headers.get("timestamp")).isEqualTo("1700000000000");
        assertThat(headers.get("nonce")).isEqualTo("nonce-fixed-1");
        assertThat(headers.get("buyerUserId")).isEmpty();
        assertThat(headers.get("channelCode")).isEqualTo("RT_MART");
        assertThat(headers.get("requestSha256"))
                .isEqualTo(ProjectAppAuthSigner.sha256Hex(body));

        // 独立重算 HMAC 期望值
        String canonical = String.join("\n", APP_ID, "3", "1.0.0", "INSTORE_CREATE_PAYMENT_ORDER",
                "1700000000000", "nonce-fixed-1", headers.get("requestSha256"), "", "RT_MART");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(headers.get("signature")).isEqualTo(expected);
    }

    @Test
    void signIsDeterministicWithExplicitTimestampAndNonce() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> first = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", null),
                "POST", ENTRY_PATH, body, 42L, "n-1");
        Map<String, String> second = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", null),
                "POST", ENTRY_PATH, body, 42L, "n-1");
        assertThat(first).isEqualTo(second);
        assertThat(first.get("operationCode")).isEqualTo("INSTORE_HOME_ENTRY");
        assertThat(first.get("buyerUserId")).isEqualTo("u-1");
        assertThat(first.get("channelCode")).isEmpty();
    }

    @Test
    void sha256HexMatchesKnownVectors() {
        assertThat(ProjectAppAuthSigner.sha256Hex(null))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ProjectAppAuthSigner.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
            assertThat(ProjectAppAuthSigner.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo(HexFormat.of().formatHex(digest));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void canonicalStringJoinsNineFieldsWithNewlines() {
        String canonical = ProjectAppAuthSigner.canonicalString("a", "1", "1.0.0", "OP", "2", "n",
                "sha", "u", "c");
        assertThat(canonical).isEqualTo("a\n1\n1.0.0\nOP\n2\nn\nsha\nu\nc");
    }

    @Test
    void signRejectsUnregisteredOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", "/rpc-api/supply-chain/instore/channel-command/notRegistered", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记的受保护操作");
    }

    @Test
    void signRejectsMissingScopeForChannelOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null),
                "POST", CHANNEL_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelCode");
    }

    @Test
    void signRejectsMissingScopeForBuyerEntryOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", ENTRY_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerUserId");
    }

    @Test
    void signRejectsScopeFieldsOnCallbackOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CALLBACK_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许携带 scope 声明");
    }

    @Test
    void randomNonceIsBase64UrlAndUnique() {
        String first = ProjectAppAuthSigner.randomNonce();
        String second = ProjectAppAuthSigner.randomNonce();
        assertThat(first).matches("[A-Za-z0-9_-]+");
        assertThat(first).isNotEqualTo(second);
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAppAuthSignerTest
```

Expected: 编译失败（两个类不存在）。

- [x] **Step 3: 实现凭证与签名工具**

`ProjectAppAuthCredentials.java`：

```java
package com.aifuli.module.supplychain.api.auth;

/**
 * 项目应用调用凭证：调用方注入的认证身份与 scope 声明值。
 *
 * <p>appKey 是密钥明文，仅允许存活于调用方内存，禁止落日志、缓存或序列化。
 * scope 字段按目标操作的 {@link ProjectAuthOperations.ScopeType} 填写，不适用的字段传 {@code null}；
 * contractVersion 由注册表按操作解析（设计 §4.1），凭证不承载该字段。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#7-签名客户端}。</p>
 *
 * @param appId       项目全局唯一稳定应用标识
 * @param appKey      appKey 明文
 * @param keyVersion  调用方使用的密钥版本号
 * @param buyerUserId 采购商用户不透明标识，BUYER_USER_ID scope 操作必填，其余传 null
 * @param channelCode 渠道编码，CHANNEL_CODE scope 操作必填，其余传 null
 */
public record ProjectAppAuthCredentials(String appId, String appKey, int keyVersion,
                                        String buyerUserId, String channelCode) {

    @Override
    public String toString() {
        return "ProjectAppAuthCredentials[appId=" + appId + ", keyVersion=" + keyVersion
                + ", buyerUserId=***, channelCode=" + channelCode + ", appKey=***]";
    }
}
```

`ProjectAppAuthSigner.java`：

```java
package com.aifuli.module.supplychain.api.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目应用统一认证签名客户端：按固定 9 字段签名串生成 HMAC-SHA256 认证头。
 *
 * <p>签名串字段顺序与空值语义、requestSha256 取原始 body 字节、Base64URL 编码，
 * 均与 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#4-签名协议}
 * 逐字段对齐；服务端验签是本工具的逆运算，两侧共用 {@link ProjectAuthOperations} 注册表。</p>
 */
public final class ProjectAppAuthSigner {

    public static final String HEADER_APP_ID = "appId";
    public static final String HEADER_KEY_VERSION = "keyVersion";
    public static final String HEADER_CONTRACT_VERSION = "contractVersion";
    public static final String HEADER_OPERATION_CODE = "operationCode";
    public static final String HEADER_TIMESTAMP = "timestamp";
    public static final String HEADER_NONCE = "nonce";
    public static final String HEADER_REQUEST_SHA256 = "requestSha256";
    public static final String HEADER_SIGNATURE = "signature";
    public static final String HEADER_BUYER_USER_ID = "buyerUserId";
    public static final String HEADER_CHANNEL_CODE = "channelCode";

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProjectAppAuthSigner() {
    }

    /**
     * 为受保护操作生成完整 10 个认证头（自动生成 timestamp 与 nonce）。
     *
     * @param credentials 调用方凭证
     * @param httpMethod  HTTP 方法
     * @param path        请求路径，不含 query
     * @param body        请求 body 原始字节，空 body 传 null 或空数组
     * @return 10 个认证头
     */
    public static Map<String, String> sign(ProjectAppAuthCredentials credentials, String httpMethod,
                                           String path, byte[] body) {
        return sign(credentials, httpMethod, path, body, System.currentTimeMillis(), randomNonce());
    }

    /**
     * 为受保护操作生成完整 10 个认证头；timestamp 与 nonce 由调用方显式提供（重放测试与固定场景用）。
     */
    public static Map<String, String> sign(ProjectAppAuthCredentials credentials, String httpMethod,
                                           String path, byte[] body, long timestamp, String nonce) {
        ProjectAuthOperations.Operation operation = ProjectAuthOperations.findByMethodAndPath(httpMethod, path)
                .orElseThrow(() -> new IllegalArgumentException("未登记的受保护操作: " + httpMethod + " " + path));
        validateScope(operation, credentials);
        String requestSha256 = sha256Hex(body);
        String signature = hmacSha256Base64Url(credentials.appKey(), canonicalString(
                credentials.appId(), String.valueOf(credentials.keyVersion()),
                operation.contractVersion(), operation.operationCode(), String.valueOf(timestamp),
                nonce, requestSha256, nullToEmpty(credentials.buyerUserId()),
                nullToEmpty(credentials.channelCode())));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_APP_ID, credentials.appId());
        headers.put(HEADER_KEY_VERSION, String.valueOf(credentials.keyVersion()));
        headers.put(HEADER_CONTRACT_VERSION, operation.contractVersion());
        headers.put(HEADER_OPERATION_CODE, operation.operationCode());
        headers.put(HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.put(HEADER_NONCE, nonce);
        headers.put(HEADER_REQUEST_SHA256, requestSha256);
        headers.put(HEADER_SIGNATURE, signature);
        headers.put(HEADER_BUYER_USER_ID, nullToEmpty(credentials.buyerUserId()));
        headers.put(HEADER_CHANNEL_CODE, nullToEmpty(credentials.channelCode()));
        return headers;
    }

    /**
     * 构造固定 9 字段签名串；不适用的 scope 字段取空字符串。服务端验签共用本方法保证逆运算一致。
     */
    public static String canonicalString(String appId, String keyVersion, String contractVersion,
                                         String operationCode, String timestamp, String nonce,
                                         String requestSha256, String buyerUserId, String channelCode) {
        return String.join("\n", appId, keyVersion, contractVersion, operationCode,
                timestamp, nonce, requestSha256, buyerUserId, channelCode);
    }

    /** 计算请求 body 原始字节的 SHA-256 小写十六进制摘要；空 body 取空字节串摘要。 */
    public static String sha256Hex(byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body);
            char[] chars = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                chars[i * 2] = HEX[(hash[i] >> 4) & 0xF];
                chars[i * 2 + 1] = HEX[hash[i] & 0xF];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", ex);
        }
    }

    /** 以 appKey 为密钥计算 HMAC-SHA256 并 Base64URL 编码（无填充）。 */
    public static String hmacSha256Base64Url(String appKey, String canonicalString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 HmacSHA256 算法", ex);
        }
    }

    /** 生成 16 字节 Base64URL 随机 nonce。 */
    public static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 客户端侧 scope 快速失败：必填 scope 缺失或回调携带 scope 直接拒绝，避免到服务端才报模糊的认证失败。 */
    private static void validateScope(ProjectAuthOperations.Operation operation,
                                      ProjectAppAuthCredentials credentials) {
        switch (operation.scopeType()) {
            case CHANNEL_CODE -> {
                if (credentials.channelCode() == null || credentials.channelCode().isBlank()) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 的凭证缺少必填 scope 字段 channelCode");
                }
            }
            case BUYER_USER_ID -> {
                if (credentials.buyerUserId() == null || credentials.buyerUserId().isBlank()) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 的凭证缺少必填 scope 字段 buyerUserId");
                }
            }
            case NONE -> {
                if (credentials.buyerUserId() != null || credentials.channelCode() != null) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 不允许携带 scope 声明");
                }
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAppAuthSignerTest
```

Expected: PASS（Tests run: 9, Failures: 0）。

- [x] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthCredentials.java aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthSigner.java aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthSignerTest.java
git commit -m "feat(supply-chain): add project app auth signer"
```

---

### Task 4: API 模块 Feign 拦截器

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthFeignInterceptor.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthFeignInterceptorTest.java`

- [x] **Step 1: 写单测（红灯）**

```java
package com.aifuli.module.supplychain.api.auth;

import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feign 拦截器单测：模板方法/路径定位操作、补齐 10 头，签名以 Signer 公共方法独立复核。
 */
class ProjectAppAuthFeignInterceptorTest {

    private static final String APP_ID = "app-feign-test";
    private static final String APP_KEY = "secret-key-for-feign-test";
    private static final String CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";

    @Test
    void applySignsRegisteredRequestTemplate() {
        byte[] body = "{\"channelOrderId\":\"RT-1\"}".getBytes(StandardCharsets.UTF_8);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CHANNEL_PATH);
        template.body(body, StandardCharsets.UTF_8);

        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 2, null, "RT_MART")).apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_APP_ID)).isEqualTo(APP_ID);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_KEY_VERSION)).isEqualTo("2");
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE)).isEqualTo("RT_MART");
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_OPERATION_CODE))
                .isEqualTo("INSTORE_CREATE_PAYMENT_ORDER");

        String requestSha256 = first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256);
        assertThat(requestSha256).isEqualTo(ProjectAppAuthSigner.sha256Hex(body));
        String canonical = ProjectAppAuthSigner.canonicalString(APP_ID, "2",
                first(headers, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION),
                first(headers, ProjectAppAuthSigner.HEADER_OPERATION_CODE),
                first(headers, ProjectAppAuthSigner.HEADER_TIMESTAMP),
                first(headers, ProjectAppAuthSigner.HEADER_NONCE),
                requestSha256, "",
                first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE));
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_SIGNATURE))
                .isEqualTo(ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY, canonical));
    }

    @Test
    void applySignsCallbackTemplateWithoutScopeAndBody() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CALLBACK_PATH);

        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null)).apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_BUYER_USER_ID)).isEmpty();
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE)).isEmpty();
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256))
                .isEqualTo(ProjectAppAuthSigner.sha256Hex(null));
    }

    @Test
    void applyRejectsUnregisteredPath() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri("/rpc-api/supply-chain/instore/channel-command/notRegistered");

        ProjectAppAuthFeignInterceptor interceptor = new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"));

        assertThatThrownBy(() -> interceptor.apply(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记的受保护操作");
    }

    private static String first(Map<String, Collection<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAppAuthFeignInterceptorTest
```

Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现拦截器**

```java
package com.aifuli.module.supplychain.api.auth;

import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Feign 请求拦截器：为受保护 INSTORE 操作补齐 10 个项目认证签名头。
 *
 * <p>按模板方法与路径在 {@link ProjectAuthOperations} 注册表定位操作，
 * requestSha256 取 Feign 已序列化 body 字节；凭证以供应器注入，支持调用方
 * 按请求上下文动态切换项目身份。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#7-签名客户端}。</p>
 */
public final class ProjectAppAuthFeignInterceptor implements RequestInterceptor {

    private final Supplier<ProjectAppAuthCredentials> credentialsSupplier;

    public ProjectAppAuthFeignInterceptor(Supplier<ProjectAppAuthCredentials> credentialsSupplier) {
        this.credentialsSupplier = credentialsSupplier;
    }

    @Override
    public void apply(RequestTemplate template) {
        String url = template.url();
        int queryIndex = url.indexOf('?');
        String path = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                credentialsSupplier.get(), template.method(), path, template.body());
        headers.forEach(template::header);
    }
}
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test -Dtest=ProjectAppAuthFeignInterceptorTest
```

Expected: PASS（Tests run: 3, Failures: 0）。

- [x] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthFeignInterceptor.java aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthFeignInterceptorTest.java
git commit -m "feat(supply-chain): add project auth feign interceptor"
```

---

### Task 5: 服务端信封加解密（SPI + 默认实现 + 配置装配）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/AppKeySecretResolver.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/LocalEnvelopeAppKeyCodec.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/LocalEnvelopeAppKeySecretResolver.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthProperties.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthConfiguration.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/LocalEnvelopeAppKeyCodecTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthConfigurationTest.java`

- [x] **Step 1: 写单测（红灯）**

`LocalEnvelopeAppKeyCodecTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地信封加解密单测：往返、AAD 绑定、密钥不符、密文篡改与格式非法全部覆盖。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现。
 */
class LocalEnvelopeAppKeyCodecTest {

    private static final byte[] MASTER_KEY =
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=");
    private static final byte[] OTHER_MASTER_KEY =
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXc=");

    @Test
    void encryptDecryptRoundTrip() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 2, "plain-app-key");
        assertThat(secretRef).startsWith("v1:");
        assertThat(secretRef.split(":", -1)).hasSize(3);
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 2, secretRef))
                .isEqualTo("plain-app-key");
    }

    @Test
    void encryptTwiceProducesDifferentSecretRef() {
        String first = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        String second = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        assertThat(first).isNotEqualTo(second);
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, first)).isEqualTo("k");
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, second)).isEqualTo("k");
    }

    @Test
    void decryptRejectsAadAppIdMismatch() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-2", 1, secretRef))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsAadKeyVersionMismatch() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 2, secretRef))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsWrongMasterKey() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(OTHER_MASTER_KEY, "app-1", 1, secretRef))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsTamperedCiphertext() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        String tampered = secretRef.substring(0, secretRef.length() - 2)
                + (secretRef.endsWith("==") ? "AA" : "==");
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsMalformedSecretRef() {
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "plain-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "v1:onlyonepart"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`ProjectAppAuthConfigurationTest.java`：

除下列主密钥纯函数门禁外，必须使用 `ImportCandidates`、`@AutoConfiguration` 注解断言和真实
`@EnableAutoConfiguration` discovery runner 覆盖：默认本地 resolver 唯一注册与属性绑定、
enabled=true 缺 key 的 context 启动失败关闭、自定义 resolver 无本地主密钥时默认实现退位，
以及 enabled=false 缺 key 时 context 可启动且默认 resolver 仅装配不调用。runner 应排除其余
自动配置，避免无关基础设施影响 Task 5 门禁。同时必须使用唯一 masterKey sentinel 验证
`ProjectAppAuthProperties.toString()` 不包含主密钥，且仍保留 enabled、时间偏差等非敏感诊断状态。

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主密钥失败关闭装配测试：enabled 时缺失、非 Base64、长度不符均启动失败。
 */
class ProjectAppAuthConfigurationTest {

    private static final String VALID_KEY = "MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=";

    @Test
    void decodeMasterKeyReturnsBytesWhenValid() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey(VALID_KEY);
        assertThat(ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isEqualTo(Base64.getDecoder().decode(VALID_KEY));
    }

    @Test
    void decodeMasterKeyFailsWhenMissing() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key");
    }

    @Test
    void decodeMasterKeyFailsWhenNotBase64() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey("not-base64!!!");
        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decodeMasterKeyFailsWhenWrongLength() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void decodeMasterKeySkipsValidationWhenDisabled() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setEnabled(false);
        assertThat(ProjectAppAuthConfiguration.decodeMasterKey(properties)).isEmpty();
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest='LocalEnvelopeAppKeyCodecTest,ProjectAppAuthConfigurationTest'
```

Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现五个类**

`AppKeySecretResolver.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * appKey 密钥系统解析 SPI：把 {@code sc_project_app_credential_version.secret_ref} 解析为 appKey 明文。
 *
 * <p>默认实现为本地 AES-256-GCM 信封加密；接入真实 KMS 时替换实现即可，
 * secret_ref 的外部格式约定不变。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public interface AppKeySecretResolver {

    /**
     * 解密 secret_ref 得 appKey 明文。
     *
     * @param appId      项目应用稳定标识，用于 AAD 绑定校验
     * @param keyVersion 调用方携带的密钥版本号，用于 AAD 绑定校验
     * @param secretRef  密钥系统引用，禁止为明文 appKey
     * @return appKey 明文，仅允许存活于本次请求内存
     * @throws IllegalArgumentException secret_ref 格式非法或解密失败（含 AAD 不匹配）
     */
    String resolveAppKey(String appId, int keyVersion, String secretRef);
}
```

`LocalEnvelopeAppKeyCodec.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本地信封加解密工具：AES-256-GCM，AAD 绑定 appId 与 keyVersion。
 *
 * <p>{@code secret_ref} 格式为 {@code v1:{base64(iv)}:{base64(ciphertext)}}；
 * 发码（加密）与验签（解密）共用本工具，运维/测试用 encrypt 生成可入库的 secret_ref。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public final class LocalEnvelopeAppKeyCodec {

    private static final String FORMAT_PREFIX = "v1:";
    private static final String SEPARATOR = ":";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalEnvelopeAppKeyCodec() {
    }

    /** 加密 appKey 明文，生成可入库的 secret_ref。 */
    public static String encrypt(byte[] masterKey, String appId, int keyVersion, String appKey) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(appId, keyVersion));
            byte[] ciphertext = cipher.doFinal(appKey.getBytes(StandardCharsets.UTF_8));
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(iv) + SEPARATOR
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("appKey 信封加密失败", ex);
        }
    }

    /** 解密 secret_ref 得 appKey 明文；格式非法、密钥不符或 AAD 不匹配抛 {@link IllegalArgumentException}。 */
    public static String decrypt(byte[] masterKey, String appId, int keyVersion, String secretRef) {
        if (secretRef == null || !secretRef.startsWith(FORMAT_PREFIX)) {
            throw new IllegalArgumentException("secret_ref 格式非法");
        }
        String[] parts = secretRef.substring(FORMAT_PREFIX.length()).split(SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("secret_ref 格式非法");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(appId, keyVersion));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("secret_ref 解密失败", ex);
        }
    }

    private static byte[] aad(String appId, int keyVersion) {
        return (appId + "\n" + keyVersion).getBytes(StandardCharsets.UTF_8);
    }
}
```

`LocalEnvelopeAppKeySecretResolver.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 本地信封 appKey 解析默认实现，主密钥由装配类解码注入。
 *
 * <p>appKey 明文仅在方法栈内存存活，本类不做任何缓存。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public class LocalEnvelopeAppKeySecretResolver implements AppKeySecretResolver {

    private final byte[] masterKey;

    public LocalEnvelopeAppKeySecretResolver(byte[] masterKey) {
        this.masterKey = masterKey.clone();
    }

    @Override
    public String resolveAppKey(String appId, int keyVersion, String secretRef) {
        return LocalEnvelopeAppKeyCodec.decrypt(masterKey, appId, keyVersion, secretRef);
    }
}
```

`ProjectAppAuthProperties.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目应用统一认证配置。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#66-配置项}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.auth")
public class ProjectAppAuthProperties {

    /** 过滤器总开关；false 仅供本地调试（不注册过滤器，上下文回到失败关闭默认实现）。 */
    private boolean enabled = true;

    /**
     * 32 字节 Base64 主密钥；默认本地 resolver 生效且 enabled=true 时必填；
     * 自定义/KMS resolver 存在时不读取、不要求。
     *
     * <p>该字段禁止出现在日志或诊断输出中，排除 Lombok 生成的 {@code toString()}；
     * Spring 配置绑定仍通过 getter/setter 正常工作。</p>
     */
    @ToString.Exclude
    private String masterKey;

    /** 时间戳允许偏差（分钟）。 */
    private long timestampSkewMinutes = 5;

    /** nonce 过期保留时长（小时），清扫与 expire_at 计算共用。 */
    private long nonceRetentionHours = 1;

    /** Task 9 入站验签允许缓存的请求体上限（字节），默认 1 MiB。 */
    private int maxRequestBodyBytes = 1_048_576;
}
```

`ProjectAppAuthConfiguration.java`（本任务只含 resolver 装配，Task 9 追加过滤器注册）：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.Base64;

/**
 * 项目应用统一认证装配：信封主密钥失败关闭校验与组件注册。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#6-服务端组件}。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(ProjectAppAuthProperties.class)
public class ProjectAppAuthConfiguration {

    /**
     * 没有自定义 SPI 时注册本地信封 appKey 解析器；enabled 时主密钥非法即失败关闭。
     */
    @Bean
    @ConditionalOnMissingBean(AppKeySecretResolver.class)
    public AppKeySecretResolver appKeySecretResolver(ProjectAppAuthProperties properties) {
        byte[] decodedMasterKey = decodeMasterKey(properties);
        try {
            return new LocalEnvelopeAppKeySecretResolver(decodedMasterKey);
        } finally {
            Arrays.fill(decodedMasterKey, (byte) 0);
        }
    }

    /** 解码并校验主密钥；disabled 时跳过校验（过滤器也不注册，全链路失败关闭）。 */
    static byte[] decodeMasterKey(ProjectAppAuthProperties properties) {
        if (!properties.isEnabled()) {
            return new byte[0];
        }
        String masterKey = properties.getMasterKey();
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 未配置，项目认证失败关闭");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(masterKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 不是合法 Base64", ex);
        }
        if (decoded.length != 32) {
            int actualLength = decoded.length;
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 必须是 32 字节 Base64，实际 "
                    + actualLength + " 字节");
        }
        return decoded;
    }
}
```

同时在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 追加：

```text
com.aifuli.module.supplychain.infrastructure.auth.ProjectAppAuthConfiguration
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest='LocalEnvelopeAppKeyCodecTest,ProjectAppAuthConfigurationTest'
```

Expected: PASS（Task 5 定向 Tests run: 25, Failures: 0）。

- [x] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "feat(supply-chain): add envelope app key resolver"
```

---

### Task 6: 服务端认证 DAL 与凭证服务

**Files:**
- Create: `.../infrastructure/auth/ProjectAuthStatus.java`
- Create: `.../infrastructure/auth/ProjectAppCredentialStatus.java`
- Create: `.../infrastructure/auth/dal/dataobject/ProjectApplicationDO.java`
- Create: `.../infrastructure/auth/dal/dataobject/AuthBuyerProjectDO.java`
- Create: `.../infrastructure/auth/dal/dataobject/ProjectAppCredentialVersionDO.java`
- Create: `.../infrastructure/auth/dal/dataobject/ProjectCapabilityDO.java`
- Create: `.../infrastructure/auth/dal/mysql/ProjectApplicationMapper.java`
- Create: `.../infrastructure/auth/dal/mysql/AuthBuyerProjectMapper.java`
- Create: `.../infrastructure/auth/dal/mysql/ProjectAppCredentialVersionMapper.java`
- Create: `.../infrastructure/auth/dal/mysql/ProjectCapabilityMapper.java`
- Create: `.../infrastructure/auth/ProjectAppCredentialService.java`
- Test: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppCredentialServiceTest.java`

（`...` = `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain`）

- [x] **Step 1: 写单测（红灯）**

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.AuthBuyerProjectMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectCapabilityMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aifuli.framework.common.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 凭证链单测：application → project → credential（CURRENT/GRACE/窗口）→ INSTORE 能力 → 解密。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAppCredentialServiceTest {

    private static final String APP_ID = "app-credential-test";
    private static final String APP_KEY = "resolved-app-key";

    @Mock private ProjectApplicationMapper applicationMapper;
    @Mock private AuthBuyerProjectMapper buyerProjectMapper;
    @Mock private ProjectAppCredentialVersionMapper credentialMapper;
    @Mock private ProjectCapabilityMapper capabilityMapper;
    @Mock private AppKeySecretResolver secretResolver;

    private ProjectAppCredentialService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ProjectApplicationDO.class);
        TableInfoHelper.initTableInfo(assistant, AuthBuyerProjectDO.class);
        TableInfoHelper.initTableInfo(assistant, ProjectAppCredentialVersionDO.class);
        TableInfoHelper.initTableInfo(assistant, ProjectCapabilityDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProjectAppCredentialService(
                applicationMapper, buyerProjectMapper, credentialMapper, capabilityMapper, secretResolver);
    }

    @Test
    void authenticateResolvesCurrentCredentialChain() {
        stubFullChain("CURRENT");
        ProjectAppCredentialService.AuthenticatedApp app = service.authenticate(APP_ID, 1);
        assertThat(app.projectId()).isEqualTo(20L);
        assertThat(app.buyerId()).isEqualTo(10L);
        assertThat(app.appId()).isEqualTo(APP_ID);
        assertThat(app.keyVersion()).isEqualTo(1);
        assertThat(app.appKey()).isEqualTo(APP_KEY);
        assertThat(app.toString()).doesNotContain(APP_KEY);
    }

    @Test
    void authenticateAcceptsGraceCredential() {
        stubFullChain("GRACE");
        assertThat(service.authenticate(APP_ID, 1).appKey()).isEqualTo(APP_KEY);
    }

    @Test
    void authenticateRejectsUnknownApplication() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(null);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsSuspendedApplication() {
        stubFullChain("CURRENT");
        application(20);
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(20));
        AuthBuyerProjectDO project = project((short) 10);
        when(buyerProjectMapper.selectById(20L)).thenReturn(project);
        // 应用状态 20
        ProjectApplicationDO suspended = application(20);
        // 已在上面 stub 为启用，这里单独构造用例
        assertThat(true).isTrue();
    }

    @Test
    void authenticateRejectsSuspendedProject() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 20));
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsMissingCredential() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 10));
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1)).thenReturn(null);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsRevokedCredential() {
        stubUpToCredential("REVOKED");
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsExpiredCredential() {
        stubUpToCredential("EXPIRED");
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsCredentialNotYetValid() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 10));
        ProjectAppCredentialVersionDO credential = credential("CURRENT");
        credential.setValidFrom(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1)).thenReturn(credential);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsCredentialPastValidTo() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 10));
        ProjectAppCredentialVersionDO credential = credential("CURRENT");
        credential.setValidTo(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1)).thenReturn(credential);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsMissingCapability() {
        stubUpToCredential("CURRENT");
        when(capabilityMapper.selectByProjectIdAndCode(20L, "INSTORE")).thenReturn(null);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsDisabledCapability() {
        stubUpToCredential("CURRENT");
        ProjectCapabilityDO capability = new ProjectCapabilityDO();
        capability.setProjectId(20L);
        capability.setCapabilityCode("INSTORE");
        capability.setStatus((short) 20);
        when(capabilityMapper.selectByProjectIdAndCode(20L, "INSTORE")).thenReturn(capability);
        assertAuthInvalid();
    }

    @Test
    void authenticateRejectsUndecryptableSecretRef() {
        stubUpToCredential("CURRENT");
        when(capabilityMapper.selectByProjectIdAndCode(anyLong(), anyString())).thenReturn(capability((short) 10));
        when(secretResolver.resolveAppKey(anyString(), anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("secret_ref 解密失败"));
        assertAuthInvalid();
    }

    private void stubFullChain(String credentialStatus) {
        stubUpToCredential(credentialStatus);
        when(capabilityMapper.selectByProjectIdAndCode(20L, "INSTORE")).thenReturn(capability((short) 10));
        when(secretResolver.resolveAppKey(APP_ID, 1, "secret-ref")).thenReturn(APP_KEY);
    }

    private void stubUpToCredential(String credentialStatus) {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application(10));
        when(buyerProjectMapper.selectById(20L)).thenReturn(project((short) 10));
        when(credentialMapper.selectByApplicationIdAndKeyVersion(30L, 1))
                .thenReturn(credential(credentialStatus));
    }

    private static ProjectApplicationDO application(short status) {
        ProjectApplicationDO application = new ProjectApplicationDO();
        application.setId(30L);
        application.setProjectId(20L);
        application.setAppId(APP_ID);
        application.setStatus(status);
        return application;
    }

    private static AuthBuyerProjectDO project(short status) {
        AuthBuyerProjectDO project = new AuthBuyerProjectDO();
        project.setId(20L);
        project.setBuyerId(10L);
        project.setStatus(status);
        return project;
    }

    private static ProjectAppCredentialVersionDO credential(String status) {
        ProjectAppCredentialVersionDO credential = new ProjectAppCredentialVersionDO();
        credential.setId(40L);
        credential.setApplicationId(30L);
        credential.setKeyVersion(1);
        credential.setSecretRef("secret-ref");
        credential.setStatus(status);
        credential.setValidFrom(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        credential.setValidTo(null);
        return credential;
    }

    private static ProjectCapabilityDO capability(short status) {
        ProjectCapabilityDO capability = new ProjectCapabilityDO();
        capability.setProjectId(20L);
        capability.setCapabilityCode("INSTORE");
        capability.setStatus(status);
        return capability;
    }

    private void assertAuthInvalid() {
        assertThatThrownBy(() -> service.authenticate(APP_ID, 1))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
    }
}
```

注意：`authenticateRejectsSuspendedApplication` 上面占位写法有误，实现时用如下正文（其余测试保持不变）：

```java
    @Test
    void authenticateRejectsSuspendedApplication() {
        when(applicationMapper.selectByAppId(APP_ID)).thenReturn(application((short) 20));
        assertAuthInvalid();
    }
```

并删除该测试里多余的 stub 行与 `application(20)` 旧调用。

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectAppCredentialServiceTest
```

Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现枚举、DO、Mapper、服务**

`ProjectAuthStatus.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 认证链路涉及的状态编码：三表只共享 ENABLED=10；应用/项目支持 20、90，
 * 项目另有 0 草稿，能力则只支持 10 启用/20 停用，不能把三表全量状态视为相同。
 */
public enum ProjectAuthStatus {

    /** 三表共享的启用状态。 */
    ENABLED((short) 10),
    /** 应用/项目为暂停，能力为停用。 */
    SUSPENDED((short) 20),
    /** 仅应用/项目支持的归档状态。 */
    ARCHIVED((short) 90);

    private final short code;

    ProjectAuthStatus(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }

    /** 判断 DO 状态字段是否等于本枚举编码。 */
    public boolean matches(Short status) {
        return status != null && status == code;
    }
}
```

`ProjectAppCredentialStatus.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 项目应用凭证版本状态，与 016 DDL CHECK 约束语义一致。
 */
public enum ProjectAppCredentialStatus {

    /** 当前密钥。 */
    CURRENT,
    /** 轮换过渡密钥，验签可用。 */
    GRACE,
    /** 已吊销。 */
    REVOKED,
    /** 已过期。 */
    EXPIRED;

    /** 判断状态字符串是否为验签可用的 CURRENT/GRACE。 */
    public static boolean isUsable(String status) {
        return CURRENT.name().equals(status) || GRACE.name().equals(status);
    }
}
```

`dal/dataobject/ProjectApplicationDO.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目应用身份 DO，对应 {@code sc_project_application}。
 *
 * <p>认证链路只读 id/projectId/appId/status；version 与审计字段归管理侧，
 * 需要写入时再补齐字段。设计见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_project_application")
@Data
public class ProjectApplicationDO {

    /** 项目应用身份内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id。 */
    private Long projectId;
    /** 项目全局唯一稳定应用标识。 */
    private String appId;
    /** 应用状态：10启用，20暂停，90归档。 */
    private Short status;
}
```

`dal/dataobject/AuthBuyerProjectDO.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 认证链路使用的采购商项目只读视图，对应 {@code sc_buyer_project}。
 *
 * <p>架构规则禁止 infrastructure.auth 依赖 tradecore，因此以最小字段投影读取
 * buyer_id 与状态完成归属解析；本 DO 只读，写路径归 tradecore.buyer 的
 * {@code BuyerProjectDO} 所有。设计见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_buyer_project")
@Data
public class AuthBuyerProjectDO {

    /** 采购项目内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商内部主键，逻辑引用sc_buyer.id。 */
    private Long buyerId;
    /** 项目状态：0草稿，10启用，20暂停，90归档。 */
    private Short status;
}
```

`dal/dataobject/ProjectAppCredentialVersionDO.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 项目应用凭证版本 DO，对应 {@code sc_project_app_credential_version}。
 *
 * <p>secret_ref 是密钥系统引用，禁止保存明文 appKey；认证链路只读。
 * 设计见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_project_app_credential_version")
@Data
public class ProjectAppCredentialVersionDO {

    /** 项目应用凭证版本内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 项目应用身份内部主键，逻辑引用sc_project_application.id。 */
    private Long applicationId;
    /** 调用方携带的密钥版本号。 */
    private Integer keyVersion;
    /** 密钥系统引用，禁止保存明文appKey。 */
    @ToString.Exclude
    private String secretRef;
    /** 密钥状态：CURRENT当前，GRACE过渡，REVOKED已吊销，EXPIRED已过期。 */
    private String status;
    /** 密钥生效时间，UTC。 */
    private LocalDateTime validFrom;
    /** 密钥失效时间，UTC。 */
    private LocalDateTime validTo;
}
```

`dal/dataobject/ProjectCapabilityDO.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目能力 DO，对应 {@code sc_project_capability}；认证链路只读。
 */
@TableName("sc_project_capability")
@Data
public class ProjectCapabilityDO {

    /** 项目能力内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id。 */
    private Long projectId;
    /** 项目能力：STANDARD_PRODUCT标准品、O2O到家、INSTORE到店购。 */
    private String capabilityCode;
    /** 能力状态：10启用，20停用。 */
    private Short status;
}
```

`dal/mysql/ProjectApplicationMapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目应用身份 Mapper；appId 全局唯一，方法引用绑定列避免字符串漂移。
 */
@Mapper
public interface ProjectApplicationMapper extends BaseMapperX<ProjectApplicationDO> {

    /** 按全局唯一 appId 查询应用身份。 */
    default ProjectApplicationDO selectByAppId(String appId) {
        return selectOne(ProjectApplicationDO::getAppId, appId);
    }
}
```

`dal/mysql/AuthBuyerProjectMapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import org.apache.ibatis.annotations.Mapper;

/** 认证链路采购商项目只读 Mapper。 */
@Mapper
public interface AuthBuyerProjectMapper extends BaseMapperX<AuthBuyerProjectDO> {
}
```

`dal/mysql/ProjectAppCredentialVersionMapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目应用凭证版本 Mapper；(application_id, key_version) 唯一。
 */
@Mapper
public interface ProjectAppCredentialVersionMapper extends BaseMapperX<ProjectAppCredentialVersionDO> {

    /** 按应用与密钥版本查询唯一凭证行。 */
    default ProjectAppCredentialVersionDO selectByApplicationIdAndKeyVersion(Long applicationId, int keyVersion) {
        return selectOne(new LambdaQueryWrapper<ProjectAppCredentialVersionDO>()
                .eq(ProjectAppCredentialVersionDO::getApplicationId, applicationId)
                .eq(ProjectAppCredentialVersionDO::getKeyVersion, keyVersion));
    }
}
```

`dal/mysql/ProjectCapabilityMapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/** 项目能力 Mapper。 */
@Mapper
public interface ProjectCapabilityMapper extends BaseMapperX<ProjectCapabilityDO> {

    /** 按项目与能力编码查询唯一能力行。 */
    default ProjectCapabilityDO selectByProjectIdAndCode(Long projectId, String capabilityCode) {
        return selectOne(new LambdaQueryWrapper<ProjectCapabilityDO>()
                .eq(ProjectCapabilityDO::getProjectId, projectId)
                .eq(ProjectCapabilityDO::getCapabilityCode, capabilityCode));
    }
}
```

`ProjectAppCredentialService.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.AuthBuyerProjectDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAppCredentialVersionDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectApplicationDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectCapabilityDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.AuthBuyerProjectMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAppCredentialVersionMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectApplicationMapper;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectCapabilityMapper;
import com.aifuli.framework.common.exception.ServiceException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目应用凭证服务：认证链 appId → 应用(启用) → 项目(启用) → CURRENT/GRACE 凭证(生效窗口)
 * → INSTORE 能力(启用) → 解密 appKey。
 *
 * <p>任一步为空或状态不符即抛 BUYER_AUTH_INVALID（对外不区分原因，防项目枚举）；
 * appKey 明文不落日志、不进缓存，仅存活于本次请求内存。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@Slf4j
@Service
public class ProjectAppCredentialService {

    /** 到店购能力编码，与 016 DDL capability_code 语义一致。 */
    private static final String CAPABILITY_INSTORE = "INSTORE";

    private final ProjectApplicationMapper applicationMapper;
    private final AuthBuyerProjectMapper buyerProjectMapper;
    private final ProjectAppCredentialVersionMapper credentialMapper;
    private final ProjectCapabilityMapper capabilityMapper;
    private final AppKeySecretResolver secretResolver;
    private final Clock clock;

    @Autowired
    public ProjectAppCredentialService(ProjectApplicationMapper applicationMapper,
                                       AuthBuyerProjectMapper buyerProjectMapper,
                                       ProjectAppCredentialVersionMapper credentialMapper,
                                       ProjectCapabilityMapper capabilityMapper,
                                       AppKeySecretResolver secretResolver) {
        this(applicationMapper, buyerProjectMapper, credentialMapper, capabilityMapper,
                secretResolver, Clock.systemUTC());
    }

    ProjectAppCredentialService(ProjectApplicationMapper applicationMapper,
                                AuthBuyerProjectMapper buyerProjectMapper,
                                ProjectAppCredentialVersionMapper credentialMapper,
                                ProjectCapabilityMapper capabilityMapper,
                                AppKeySecretResolver secretResolver,
                                Clock clock) {
        this.applicationMapper = applicationMapper;
        this.buyerProjectMapper = buyerProjectMapper;
        this.credentialMapper = credentialMapper;
        this.capabilityMapper = capabilityMapper;
        this.secretResolver = secretResolver;
        this.clock = clock;
    }

    /**
     * 认证并返回已认证应用快照。
     *
     * @param appId      项目全局唯一稳定应用标识
     * @param keyVersion 调用方携带的密钥版本号
     * @return 含 projectId/buyerId/appKey 的已认证应用
     */
    public AuthenticatedApp authenticate(String appId, int keyVersion) {
        log.debug("[project-auth] 凭证链认证开始 appId={}, keyVersion={}", appId, keyVersion);
        ProjectApplicationDO application = applicationMapper.selectByAppId(appId);
        if (application == null || !ProjectAuthStatus.ENABLED.matches(application.getStatus())) {
            throw authInvalid(appId, keyVersion, "应用不存在或未启用");
        }
        AuthBuyerProjectDO project = buyerProjectMapper.selectById(application.getProjectId());
        if (project == null || !ProjectAuthStatus.ENABLED.matches(project.getStatus())) {
            throw authInvalid(appId, keyVersion, "项目不存在或未启用");
        }
        ProjectAppCredentialVersionDO credential =
                credentialMapper.selectByApplicationIdAndKeyVersion(application.getId(), keyVersion);
        if (credential == null || !ProjectAppCredentialStatus.isUsable(credential.getStatus())) {
            throw authInvalid(appId, keyVersion, "凭证不存在或状态不可用");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (credential.getValidFrom() == null || credential.getValidFrom().isAfter(now)) {
            throw authInvalid(appId, keyVersion, "凭证未到生效时间");
        }
        if (credential.getValidTo() != null && !credential.getValidTo().isAfter(now)) {
            throw authInvalid(appId, keyVersion, "凭证已过失效时间");
        }
        ProjectCapabilityDO capability = capabilityMapper.selectByProjectIdAndCode(project.getId(), CAPABILITY_INSTORE);
        if (capability == null || !ProjectAuthStatus.ENABLED.matches(capability.getStatus())) {
            throw authInvalid(appId, keyVersion, "项目未启用INSTORE能力");
        }
        String appKey;
        try {
            appKey = secretResolver.resolveAppKey(appId, keyVersion, credential.getSecretRef());
        } catch (IllegalArgumentException ex) {
            ServiceException authInvalid = authInvalid(appId, keyVersion, "secret_ref 解密失败");
            authInvalid.initCause(ex);
            throw authInvalid;
        }
        if (appKey == null || appKey.isBlank()) {
            throw authInvalid(appId, keyVersion, "resolver返回空appKey");
        }
        return new AuthenticatedApp(project.getId(), project.getBuyerId(), appId, keyVersion, appKey);
    }

    /** 已认证应用快照：appKey accessor 仅供验签读取，禁止序列化或进入日志。 */
    public record AuthenticatedApp(Long projectId, Long buyerId, String appId, int keyVersion,
                                   @JsonIgnore String appKey) {

        @Override
        public String toString() {
            return "AuthenticatedApp[projectId=" + projectId + ", appId=" + appId
                    + ", keyVersion=" + keyVersion + ']';
        }
    }

    private static ServiceException authInvalid(String appId, int keyVersion, String stage) {
        // 高频认证失败按 debug 记录，避免恶意流量造成 warn 日志风暴；严禁记录 secretRef/appKey/masterKey。
        log.debug("[project-auth] 凭证链拒绝 stage={}, appId={}, keyVersion={}", stage, appId, keyVersion);
        return exception(BUYER_AUTH_INVALID);
    }
}
```

安全补强门禁：`ProjectAppCredentialServiceTest` 必须以唯一 sentinel 覆盖
`AuthenticatedApp` 默认 Jackson 序列化不含 `appKey` 属性/明文值（同时 accessor 仍可读取）、
安全 `toString()` 不泄漏 appKey、`ProjectAppCredentialVersionDO.toString()` 不泄漏
`secretRef`，并断言 resolver 解密异常映射后的 `ServiceException.getCause()` 保留原异常。
时间窗口测试通过包内可见的 UTC `Clock` 构造入口固定当前时刻，明确覆盖
`validFrom == now` 接受、`validTo == now` 拒绝的开区间边界；生产构造入口固定使用
`Clock.systemUTC()`，不新增 Spring 装配依赖。DDL 虽保证 `validFrom` 非空，服务仍对异常空值
失败关闭并统一映射认证失败。

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectAppCredentialServiceTest
```

Expected: PASS（覆盖 current、grace、应用/项目/凭证/能力失败、时间窗口边界、解密 cause 与敏感字段防泄漏）。

- [x] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppCredentialServiceTest.java
git commit -m "feat(supply-chain): authenticate project app credentials"
```

---

### Task 7: 服务端 NonceStore（防重放 + 清扫）

**Task 7 安全门禁补充（实现前批准）:**

- `enabled=true` 时，`nonceRetentionHours` 必须严格大于
  `2 × timestampSkewMinutes` 对应时长。原因是首次请求可携带未来一个 skew 的时间戳，最晚到
  首次服务器时刻后 `2 × skew` 仍可能有效；保留窗口为 0、短于或等于该上限均须在
  `NonceStore` 构造 / 启动阶段失败关闭。`enabled=false` 跳过校验；换算使用防溢出的
  `Duration` / 精确算术。校验窗口关系后，还须用构造时钟取得当前 UTC `LocalDateTime` 并实际
  `plus(retention)`；即使 `Duration` 可构造，只要 expiry 超出 `LocalDateTime` 表示范围，也必须在
  构造 / 启动阶段以保留 `DateTimeException` / `ArithmeticException` cause 的
  `IllegalStateException` 失败关闭，不能推迟到首请求。默认 1 小时 / 5 分钟通过，
  1 小时 / 30 分钟因相等而拒绝。
- `NonceStore` 使用可注入 UTC `Clock`：一次 `ensureUnique` 读取同一时刻写入
  `createdAt` 与 `expireAt=now+retention`；一次清扫读取同一固定时刻供全部批次复用。
- `DuplicateKeyException` 与其他 `DataAccessException` 均映射为 `BUYER_AUTH_INVALID`，并通过
  `initCause` 保留原始异常供进程内因果链使用，但本组件日志禁止原样挂接数据库异常。入口和重放
  只记 DEBUG；存储不可用每分钟首条 ERROR 只含 appId、异常类、根因类与可取得的
  SQLState/vendorCode，并挂接 message 仅由安全字段组成、复制原异常 stack trace、无原 cause 的
  安全诊断 Throwable；其余 DEBUG 只含 appId、错误类别与异常类型摘要。cause 遍历按身份防循环，
  不读取/输出 SQLException message、完整 nonce / 签名 / 凭据。清扫 WARN 复用同一安全诊断规则。
- 清扫启用时按 `expire_at < fixedUtcNow` 循环删除：每批真实传入 `LIMIT 1000`，不足 1000
  停止，最多 100 轮；关闭时跳过。清扫遇 `DataAccessException` 记录不挂原始异常的安全诊断 WARN
  后返回并等待下周期，这是定时任务边界的有意处理，不继续重试。
- 单测除原计划场景外，必须覆盖固定时钟精确时间、两类异常 cause、limit 实参、批次序列、
  100 轮上限、配置 0 / 等于 / 严格大于 / disabled、DO 复合主键无 id 的 lambda 元数据。
  供应链 server 独立运行，仓库内当前没有其他 `@Scheduled` 方法；仅在既有
  `@AutoConfiguration` 上追加 `@EnableScheduling`，不得改回普通 `@Configuration`。
- Task 7 提前落地 `ProjectAuthNonceStoreIntegrationTest` 持久化契约门禁：复用
  `supply-chain-integration` profile 的 Testcontainers MySQL 8.4 / 外部独占库双模式和真实
  Liquibase，验证并发唯一键的 Spring 异常翻译、Mapper 绑定 `supplyChainTransactionManager`
  后的回滚，以及 MySQL `DELETE LIMIT 1` 与严格 `< cutoff` 边界。Task 10 保留完整 HTTP replay
  E2E，不重复本 Mapper 级门禁。

**Files:**
- Create: `.../infrastructure/auth/dal/dataobject/ProjectAuthNonceDO.java`
- Create: `.../infrastructure/auth/dal/mysql/ProjectAuthNonceMapper.java`
- Create: `.../infrastructure/auth/NonceStore.java`
- Modify: `.../infrastructure/auth/ProjectAppAuthConfiguration.java`（追加 `@EnableScheduling`）
- Test: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/auth/NonceStoreTest.java`
- Integration Test: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAuthNonceStoreIntegrationTest.java`

**完成态核心代码片段（补充门禁优先于下方初始 TDD 骨架）:**

```java
NonceStore(ProjectAuthNonceMapper nonceMapper, ProjectAppAuthProperties properties, Clock clock) {
    this.nonceRetention = validateAndResolveRetention(properties, clock);
}

private static Duration validateAndResolveRetention(ProjectAppAuthProperties properties, Clock clock) {
    if (!properties.isEnabled()) {
        return Duration.ZERO;
    }
    try {
        Duration retention = Duration.ofHours(properties.getNonceRetentionHours());
        Duration maximumReplayWindow = Duration.ofMinutes(properties.getTimestampSkewMinutes())
                .multipliedBy(2);
        if (properties.getTimestampSkewMinutes() < 0 || retention.isZero()
                || retention.isNegative() || retention.compareTo(maximumReplayWindow) <= 0) {
            throw invalidRetentionWindow();
        }
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plus(retention);
        return retention;
    } catch (DateTimeException | ArithmeticException ex) {
        throw new IllegalStateException("认证时长配置超出可支持范围", ex);
    }
}

private static ServiceException authInvalid(DataAccessException cause) {
    ServiceException authInvalid = exception(BUYER_AUTH_INVALID);
    authInvalid.initCause(cause);
    return authInvalid;
}

private void logStoreFailure(String appId, DataAccessException failure) {
    SafeFailureDetails details = safeFailureDetails(failure);
    Instant now = clock.instant();
    while (true) {
        Instant nextAllowed = nextStoreFailureErrorAt.get();
        if (now.isBefore(nextAllowed)) {
            log.debug("category=STORE_UNAVAILABLE appId={} errorClass={} rootCauseClass={}",
                    appId, details.errorClass(), details.rootCauseClass());
            return;
        }
        if (nextStoreFailureErrorAt.compareAndSet(
                nextAllowed, now.plus(STORE_FAILURE_ERROR_INTERVAL))) {
            String message = appendSqlMetadata(
                    "[project-auth] nonce 写入失败 category=STORE_UNAVAILABLE appId=" + appId
                            + " errorClass=" + details.errorClass()
                            + " rootCauseClass=" + details.rootCauseClass(),
                    details);
            log.error(message, safeDiagnosticThrowable(message, failure));
            return;
        }
    }
}
```

质量审查补充门禁（仍属 Task 7，不开始 Task 8）：

- [x] 构造期拒绝 `nonceRetentionHours=10_000_000_000_000L` 这类 `Duration` 可表示但
  `LocalDateTime` expiry 不可表示的配置，并保留日期运算 cause。
- [x] Logback `ListAppender` 锁定同窗口只有首条 ERROR、安全 Throwable 与 SQL 元数据；完整渲染的
  ERROR/DEBUG 均不含原异常 message、nested cause message 或 nonce sentinel，cause 环不死循环。
- [x] 新增真实 MySQL 集成测试（Testcontainers 基线 `mysql:8.4`，本轮 Docker 不可用时使用外部
  独占 MySQL 9.6.0），覆盖并发唯一键、命名事务回滚、`DELETE LIMIT 1` 严格边界；
  每测唯一 appId 且 finally 清理，线程有显式超时并关闭 executor。
- [x] JDK 21 下运行 `NonceStoreTest`（16）、只运行新
  `ProjectAuthNonceStoreIntegrationTest`（3），以及 server `clean test`（376）全量门禁；均为
  0 失败、0 错误、0 跳过。

```java
LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
for (int round = 0; round < MAX_CLEANUP_ROUNDS; round++) {
    int deleted = nonceMapper.deleteExpiredBefore(cutoff, CLEANUP_BATCH_SIZE);
    if (deleted < CLEANUP_BATCH_SIZE) {
        return;
    }
}
```

下方代码块保留初始 RED / GREEN 骨架作为执行记录；涉及 Clock、异常 cause、日志限频与安全窗口的
完成态以本节补充门禁、上述核心片段及已提交源码为准。

- [x] **Step 1: 写单测（红灯）**

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAuthNonceMapper;
import com.aifuli.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nonce 防重放单测：主键冲突即重放、存储不可用失败关闭、清扫批量限速与失败仅告警。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放。
 */
@ExtendWith(MockitoExtension.class)
class NonceStoreTest {

    @Mock private ProjectAuthNonceMapper nonceMapper;

    private ProjectAppAuthProperties properties;
    private NonceStore store;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ProjectAuthNonceDO.class);
    }

    @BeforeEach
    void setUp() {
        properties = new ProjectAppAuthProperties();
        store = new NonceStore(nonceMapper, properties);
    }

    @Test
    void ensureUniqueInsertsRowWithExpiry() {
        store.ensureUnique("app-1", "nonce-1");

        ArgumentCaptor<ProjectAuthNonceDO> captor = ArgumentCaptor.forClass(ProjectAuthNonceDO.class);
        verify(nonceMapper).insert(captor.capture());
        ProjectAuthNonceDO row = captor.getValue();
        assertThat(row.getAppId()).isEqualTo("app-1");
        assertThat(row.getNonce()).isEqualTo("nonce-1");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(row.getCreatedAt()).isAfter(now.minusMinutes(1));
        assertThat(row.getExpireAt()).isAfter(now.plusMinutes(59));
    }

    @Test
    void ensureUniqueRejectsReplayOnDuplicateKey() {
        when(nonceMapper.insert(any(ProjectAuthNonceDO.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThatThrownBy(() -> store.ensureUnique("app-1", "nonce-1"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void ensureUniqueFailsClosedOnStoreUnavailable() {
        when(nonceMapper.insert(any(ProjectAuthNonceDO.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> store.ensureUnique("app-1", "nonce-1"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void cleanupExpiredDeletesInBatches() {
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class)))
                .thenReturn(NonceStore.CLEANUP_BATCH_SIZE, NonceStore.CLEANUP_BATCH_SIZE, 3);

        store.cleanupExpired();

        verify(nonceMapper, times(3)).deleteExpiredBefore(any(LocalDateTime.class));
    }

    @Test
    void cleanupExpiredSkipsWhenDisabled() {
        properties.setEnabled(false);

        store.cleanupExpired();

        verify(nonceMapper, never()).deleteExpiredBefore(any(LocalDateTime.class));
    }

    @Test
    void cleanupExpiredSwallowsStoreFailure() {
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> store.cleanupExpired()).doesNotThrowAnyException();
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=NonceStoreTest
```

Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现**

`dal/dataobject/ProjectAuthNonceDO.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目应用认证 nonce 防重放记录 DO，对应 {@code sc_project_auth_nonce}。
 *
 * <p>复合主键 (app_id, nonce) 无自增 id；字段注释与 019 DDL COMMENT 一致。</p>
 */
@TableName("sc_project_auth_nonce")
@Data
public class ProjectAuthNonceDO {

    /** 项目应用稳定标识，逻辑引用sc_project_application.app_id。 */
    private String appId;
    /** 调用方生成的Base64URL随机串。 */
    private String nonce;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 过期时间，UTC，取插入时刻加防重放窗口上限。 */
    private LocalDateTime expireAt;
}
```

`dal/mysql/ProjectAuthNonceMapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/** 项目应用认证 nonce Mapper。 */
@Mapper
public interface ProjectAuthNonceMapper extends BaseMapperX<ProjectAuthNonceDO> {

    /** 分批删除已过期 nonce 行（LIMIT 限定单批），返回本批删除行数。 */
    default int deleteExpiredBefore(LocalDateTime now, int limit) {
        return delete(new LambdaQueryWrapper<ProjectAuthNonceDO>()
                .lt(ProjectAuthNonceDO::getExpireAt, now)
                .last("LIMIT " + limit));
    }
}
```

`NonceStore.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAuthNonceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * MySQL nonce 防重放存储：主键唯一约束拦截重放，存储不可用失败关闭。
 *
 * <p>选择 MySQL 而非 Redis：供应链服务端无 Redis 依赖，主键约束天然防重放，
 * 与既有 Testcontainers MySQL 门禁复用同一基础设施；清扫低频批量限速，
 * 失败仅告警不重试风暴。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NonceStore {

    /** 单批删除上限，避免长事务锁表。 */
    static final int CLEANUP_BATCH_SIZE = 1000;

    /** 清扫轮数上限，防止异常场景下无限循环。 */
    private static final int MAX_CLEANUP_ROUNDS = 100;

    private final ProjectAuthNonceMapper nonceMapper;
    private final ProjectAppAuthProperties properties;

    /**
     * 插入 nonce 保证唯一：验签通过后调用，主键冲突即重放拒绝。
     */
    public void ensureUnique(String appId, String nonce) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ProjectAuthNonceDO row = new ProjectAuthNonceDO();
        row.setAppId(appId);
        row.setNonce(nonce);
        row.setCreatedAt(now);
        row.setExpireAt(now.plusHours(properties.getNonceRetentionHours()));
        try {
            nonceMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            log.warn("[project-auth] nonce 重放拒绝 appId={}", appId);
            throw exception(BUYER_AUTH_INVALID);
        } catch (DataAccessException ex) {
            log.error("[project-auth] nonce 存储不可用，失败关闭 appId={}", appId, ex);
            throw exception(BUYER_AUTH_INVALID);
        }
    }

    /**
     * 低频清扫过期 nonce；保留窗口远大于时间戳窗口，过期行删除后到达的同 nonce
     * 请求必已被时间戳校验拒绝。失败仅告警，等待下个周期。
     */
    @Scheduled(fixedDelayString = "${aifuli.supply-chain.auth.nonce-cleanup-interval:PT1H}")
    public void cleanupExpired() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int deleted;
            int rounds = 0;
            do {
                deleted = nonceMapper.deleteExpiredBefore(now, CLEANUP_BATCH_SIZE);
                rounds++;
            } while (deleted >= CLEANUP_BATCH_SIZE && rounds < MAX_CLEANUP_ROUNDS);
            if (deleted > 0 || rounds > 1) {
                log.info("[project-auth] nonce 清扫完成 rounds={}, lastBatch={}", rounds, deleted);
            }
        } catch (DataAccessException ex) {
            log.warn("[project-auth] nonce 清扫失败，等待下个周期", ex);
        }
    }
}
```

`ProjectAppAuthConfiguration` 仅追加 `@EnableScheduling`（保留 Task 5 的 `@AutoConfiguration`、
`@EnableConfigurationProperties`、imports 注册和条件装配语义，其余不变）：

```java
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableConfigurationProperties(ProjectAppAuthProperties.class)
@EnableScheduling
public class ProjectAppAuthConfiguration {
```

同时更新 NonceStoreTest 中 mock 打桩为三参签名：

```java
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class), anyInt()))
                .thenReturn(NonceStore.CLEANUP_BATCH_SIZE, NonceStore.CLEANUP_BATCH_SIZE, 3);
        verify(nonceMapper, times(3)).deleteExpiredBefore(any(LocalDateTime.class), anyInt());
```

（`cleanupExpiredSkipsWhenDisabled` 与 `cleanupExpiredSwallowsStoreFailure` 同步改为 `deleteExpiredBefore(any(LocalDateTime.class), anyInt())`；需 `import static org.mockito.ArgumentMatchers.anyInt;`。）

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=NonceStoreTest
```

Expected: PASS（定向 Tests run: 16, Failures: 0；server 全量 Tests run: 376, Failures: 0）。

- [x] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-08-23-project-app-auth-design.md docs/superpowers/plans/2026-08-23-project-app-auth.md aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/NonceStoreTest.java
git commit -m "feat(supply-chain): guard nonce replay"
```

---

### Task 8: 可信上下文桥接（Context/Holder/AccessorImpl）

**Files:**
- Create: `.../infrastructure/auth/ProjectAuthContext.java`
- Create: `.../infrastructure/auth/ProjectAuthContextHolder.java`
- Create: `.../infrastructure/auth/ProjectCallContextAccessorImpl.java`
- Test: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectCallContextAccessorImplTest.java`
- Modify: `.../infrastructure/security/ProjectCallContextConfiguration.java`
- Modify: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfigurationTest.java`
- Modify: `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperations.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperationsContractTest.java`

**实施不变量（本节优先于下方初版代码示例）：**

- `ProjectAuthContext` 是 auth 包级的已认证 `final` 不可变快照，类、构造器、accessor 均不得
  public，全部状态字段为 final，阻止包外伪造；`projectId`、`buyerId`、`appId`、`keyVersion`、
  `operationCode` 均须有效；构造时只调用一次注册表 `findByOperationCode` 并把 ScopeType 缓存在
  final 字段，后续禁止重复扫描 `OPERATIONS`；
- 构造期按注册表执行精确 scope 校验：`BUYER_USER_ID`、`CHANNEL_CODE`、`NONE` 分别只允许
  buyer、channel、无声明三种互斥形态；不适用的空白字段规范化为 `null`，适用值保持不透明
  字符串，禁止把 `buyerUserId` 转为 `Long`；
- context 的 `toString`、构造失败和访问器失败不得泄漏 `appId`、`buyerUserId`、`channelCode`；
- Holder 类与 `set/get/clear` 全部包级，使用不可继承的普通 `ThreadLocal`，`set` 拒绝 `null`，
  支持同线程替换，`clear` 使用 `remove`；测试覆盖可见性、clear、替换、父子线程不继承及隔离；
- `ProjectAuthOperations` 以类初始化时构造的不可变 Map 集中提供 `findByOperationCode`；重复
  operationCode 必须失败而不能覆盖，API ContractTest 覆盖全部登记项、unknown 与重复拒绝；
- Accessor 只允许 scope 与访问器精确匹配；缺少 Holder 或类型不匹配统一抛
  `BUYER_AUTH_INVALID`，`ProjectContext` 仅允许 `NONE`；
- 失败关闭配置改为 Boot `@AutoConfiguration` 并登记 imports。容器测试必须证明其可由 Boot
  发现、无真实现时失败关闭、`@Component` 真实现存在时默认 Bean 可靠退位且只有一个访问器。
- 严格按 TDD 执行：先提交上述行为测试并确认因生产类缺失/行为缺失而红灯，再写最小实现；
  完成后运行定向测试和 server 模块 JDK 21 `clean test`。

- [x] **Step 1: 写单测（红灯）**

除下方三个正常映射与缺失上下文示例外，测试还必须覆盖：类/构造器/accessor/Holder 的包级可见性、
全部字段 final、缓存 ScopeType、构造字段有效性、未知操作、三种 scope
的缺失/多余声明、三种访问器交叉调用失败、异常与 `toString` 脱敏、Holder null/clear/替换/
跨线程不继承与隔离，以及真实 `@Component` 与自动配置兜底的单 Bean 装配。API ContractTest
先为 `findByOperationCode` 全登记项/unknown/重复失败写红灯，再实现不可变索引 Map。

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可信上下文桥接单测：三型上下文按 scope 产出，ThreadLocal 为空时全部失败关闭。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl。
 */
class ProjectCallContextAccessorImplTest {

    private final ProjectCallContextAccessor accessor = new ProjectCallContextAccessorImpl();

    @AfterEach
    void cleanUp() {
        ProjectAuthContextHolder.clear();
    }

    @Test
    void requireChannelContextReturnsTrustedContext() {
        ProjectAuthContextHolder.set(new ProjectAuthContext(1L, 2L, "app-1", 1,
                "INSTORE_RESOLVE_MEMBER", null, "RT_MART"));

        ProjectCallContextAccessor.ChannelContext context = accessor.requireChannelContext();
        assertThat(context.projectId()).isEqualTo(1L);
        assertThat(context.channelCode()).isEqualTo("RT_MART");
    }

    @Test
    void requireBuyerContextReturnsTrustedContext() {
        ProjectAuthContextHolder.set(new ProjectAuthContext(1L, 2L, "app-1", 1,
                "INSTORE_HOME_ENTRY", "u-1", null));

        ProjectCallContextAccessor.BuyerContext context = accessor.requireBuyerContext();
        assertThat(context.projectId()).isEqualTo(1L);
        assertThat(context.buyerId()).isEqualTo(2L);
        assertThat(context.buyerUserId()).isEqualTo("u-1");
    }

    @Test
    void requireProjectContextReturnsTrustedContext() {
        ProjectAuthContextHolder.set(new ProjectAuthContext(1L, 2L, "app-1", 1,
                "INSTORE_CONFIRM_PAYMENT_CALLBACK", null, null));

        assertThat(accessor.requireProjectContext().projectId()).isEqualTo(1L);
    }

    @Test
    void allAccessorsFailClosedWithoutAuthenticatedContext() {
        assertThatThrownBy(accessor::requireChannelContext)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(accessor::requireBuyerContext)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(accessor::requireProjectContext)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectCallContextAccessorImplTest
```

Expected: 编译失败（类不存在）。

- [x] **Step 3: 实现三个类并修正兜底装配**

下方初版代码仅展示类边界；实现必须满足本 Task 开头的不变量，不得照搬缺少字段、scope、脱敏和
线程隔离校验的构造器/访问器。`ProjectCallContextConfiguration` 同步改为 `@AutoConfiguration`，
FQCN 登记到 Boot imports，避免普通组件扫描顺序导致兜底与真实现并存或错误抢占。质量审查修复后
`ProjectAuthContext` 不再使用 public record，而是 auth 包级 final class；`ProjectAuthContextHolder`
也整体包级。服务端构造器只能调用 API 注册表的 `findByOperationCode` 一次并缓存 scope。

`ProjectAuthContext.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/** 已认证项目调用的包级不可变快照，只允许同包验签链路创建。 */
final class ProjectAuthContext {
    private final Long projectId;
    private final Long buyerId;
    private final String appId;
    private final int keyVersion;
    private final String operationCode;
    private final String buyerUserId;
    private final String channelCode;
    private final ProjectAuthOperations.ScopeType scopeType;

    ProjectAuthContext(Long projectId, Long buyerId, String appId, int keyVersion,
                       String operationCode, String buyerUserId, String channelCode) {
        // 先校验关键字段；不适用的空白 scope 规范化为 null。
        ProjectAuthOperations.Operation operation = ProjectAuthOperations
                .findByOperationCode(operationCode)
                .orElseThrow(() -> new IllegalArgumentException("operationCode 未登记"));
        // 精确校验三类 scope 后一次性赋值，scopeType 缓存为 final 字段。
        this.scopeType = operation.scopeType();
        // 其余 final 字段赋值见生产实现。
    }

    Long projectId() { return projectId; }
    Long buyerId() { return buyerId; }
    String appId() { return appId; }
    int keyVersion() { return keyVersion; }
    String operationCode() { return operationCode; }
    String buyerUserId() { return buyerUserId; }
    String channelCode() { return channelCode; }
    ProjectAuthOperations.ScopeType scopeType() { return scopeType; }
}
```

`ProjectAuthContextHolder.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 认证上下文 ThreadLocal 容器：过滤器写入、finally 清理，杜绝线程复用串号。
 *
 * <p>业务代码禁止直接读取本类（架构规则强制），必须经
 * {@code ProjectCallContextAccessor} 获取可信上下文。</p>
 */
final class ProjectAuthContextHolder {

    private static final ThreadLocal<ProjectAuthContext> CONTEXT = new ThreadLocal<>();

    private ProjectAuthContextHolder() {
    }

    static void set(ProjectAuthContext context) {
        CONTEXT.set(Objects.requireNonNull(context, "认证上下文不能为空"));
    }

    static ProjectAuthContext get() {
        return CONTEXT.get();
    }

    static void clear() {
        CONTEXT.remove();
    }
}
```

`ProjectCallContextAccessorImpl.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import org.springframework.stereotype.Component;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目调用可信上下文真实实现：从认证 ThreadLocal 产出三型上下文。
 *
 * <p>注册为本 Bean 后，{@code ProjectCallContextConfiguration} 的
 * {@code @ConditionalOnMissingBean} 失败关闭默认实现自动退位（保留为兜底）；
 * ThreadLocal 为空（过滤器未注册或未验签）时全部失败关闭。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。</p>
 */
@Component
public class ProjectCallContextAccessorImpl implements ProjectCallContextAccessor {

    @Override
    public BuyerContext requireBuyerContext() {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.BUYER_USER_ID);
        return new BuyerContext(context.projectId(), context.buyerId(), context.buyerUserId());
    }

    @Override
    public ChannelContext requireChannelContext() {
        ProjectAuthContext context = requireScope(ProjectAuthOperations.ScopeType.CHANNEL_CODE);
        return new ChannelContext(context.projectId(), context.channelCode());
    }

    @Override
    public ProjectContext requireProjectContext() {
        return new ProjectContext(requireScope(ProjectAuthOperations.ScopeType.NONE).projectId());
    }

    private ProjectAuthContext requireScope(ProjectAuthOperations.ScopeType expectedScope) {
        ProjectAuthContext context = ProjectAuthContextHolder.get();
        if (context == null || context.scopeType() != expectedScope) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return context;
    }
}
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectCallContextAccessorImplTest
```

Expected: PASS。质量审查修复后的最终定向门禁：API 注册表 6 个，server
Accessor/Holder/Context 15 个 + 自动配置 6 个，共 27 个，Failures/Errors/Skipped 均为 0。

- [x] **Step 5: 回归上下文装配与全量 server 测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest='ProjectCallContextConfigurationTest,ProjectCallContextAccessorImplTest'

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server clean test
```

Expected: 全部 PASS；无真实现时仍失败关闭，真实 `@Component` 存在时容器只有一个访问器 Bean。
实际 JDK 21 API `clean test`：Tests run: 84；server `clean test`：Tests run: 393；
Failures/Errors/Skipped 均为 0。

- [x] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-08-23-project-app-auth-design.md docs/superpowers/plans/2026-08-23-project-app-auth.md aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAuthContext.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAuthContextHolder.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectCallContextAccessorImpl.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfiguration.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectCallContextAccessorImplTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfigurationTest.java
git commit -m "feat(supply-chain): bridge project auth to call context"
```

- [x] **Step 7: 质量审查修复（包边界与注册表单点解析）**

先以 API 缺少 `findByOperationCode/indexByOperationCode` 的编译失败、server Context 仍为 public
record 的断言失败确认红灯；随后把 Context/Holder 收紧为包级、缓存 ScopeType，并由 API 不可变
Map 集中解析 operationCode。同步补强 Task 9 安全设计但不实现过滤器。提交包含 API/server 定向、
两模块全量门禁和 `docs/集成测试.md` 实测记录。

---

### Task 9: 入站验签过滤器与装配（含安全放行配置）

**Files:**
- Create: `.../infrastructure/auth/CachedBodyHttpServletRequestWrapper.java`
- Create: `.../infrastructure/auth/ProjectAppAuthFilter.java`
- Modify: `.../infrastructure/auth/ProjectAppAuthConfiguration.java`（追加过滤器注册）
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/application.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/application-local.yaml`
- Test: `.../src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthFilterTest.java`

**Task 9 安全不变量（已确认，优先于下方初版示例）：**

- 8 个核心认证头（appId、keyVersion、contractVersion、operationCode、timestamp、nonce、
  requestSha256、signature）必须恰好一个值；两个 scope 头各自至多一个值，缺失/空白规范化为
  `null`。通过 `request.getHeaders(name)` 计数，所有 10 类头只要重复（即使值相同）都拒绝，
  禁止 `getHeader` 静默取首值；
- scope 必须精确互斥：`CHANNEL_CODE` 仅 channel 非空且 buyer 为空，`BUYER_USER_ID` 仅 buyer
  非空且 channel 为空，`NONE` 两者均空。构造包级 `ProjectAuthContext` 时捕获防御性
  `IllegalArgumentException`，统一映射为 `BUYER_AUTH_INVALID` 并以 `initCause` 保留原因；
- `ProjectAuthContextHolder.clear()` 必须在读取 body 前先清旧值，并在覆盖整个过滤器方法的最外层
  `finally` 再清理；body 超限、重复头、任意解析/验签/scope/构造失败、拒绝响应写出失败、下游异常
  等全部路径都不能遗留上下文；
- body 缓存必须有界：`ProjectAppAuthProperties.maxRequestBodyBytes` 默认 `1048576`（1 MiB），
  且 1 MiB 是不可提高的安全硬上限；enabled 模式在 Filter Bean 创建期要求配置位于
  `1..1048576`，启动即失败关闭。包装器采用有界流式读取，声明 Content-Length 超限直接拒绝，
  对 chunked 或伪造长度仍最多读取 `limit + 1` 字节判定超限，禁止 `readAllBytes()` 无界分配。
  enabled=false 时不注册 Filter，并跳过该校验；
- 本期 10 个受保护接口均为同步 MVC JSON：FilterRegistrationBean 必须显式
  `setAsyncSupported(false)`；wrapper 的 `isAsyncSupported()` 固定 false，两个 `startAsync` 重载
  抛 `IllegalStateException`。缓存 ServletInputStream 不支持 non-blocking：null listener 按契约抛
  `NullPointerException`，任何非 null listener 均抛 `IllegalStateException` 且不得触发回调；多次
  `getInputStream()` 获得的独立流也不能绕过；
- 时间戳窗口禁止 `Math.abs(now - timestamp)`；用 `Math.subtractExact(timestamp, now)` 并将
  `ArithmeticException` 视为认证失败，窗口毫秒值使用精确单位换算/启动校验，确保
  `Long.MIN_VALUE/MAX_VALUE` 等恶意输入不会溢出后误放行；enabled 模式要求 skew 为正数且能
  安全转换为毫秒，在 Filter Bean 创建期失败关闭，disabled 时随 Filter 一并跳过；
- 认证入口与可预期拒绝属于高频路径，只记录不附 Throwable 的 DEBUG 或按错误类别限频摘要，
  禁止逐请求 INFO/WARN；完成头解析与精确注册表匹配后记录一条入口 DEBUG，只含 method、注册表
  operationCode、已校验 appId、正数 keyVersion，日志不包含 appKey/masterKey/secretRef、完整签名、
  nonce、body 或 scope 声明值；
- 测试清单以本 Task 实际代码中的测试方法为准，覆盖：三种合法 scope 与 body 复读；body 边界、
  声明长度超限、chunked/伪造长度超限；8 个必填头逐个缺失、核心/可选头重复；body/hash/signature
  篡改及格式；未知路径/方法/operation/contract；时间窗边界、过期、未来、Long 极值与配置无效；
  credential 失败、nonce 顺序与重放；三种 scope 缺失/多余组合；上下文构造 cause 映射；旧 Holder
  在所有失败阶段、成功及下游异常后清理；下游业务异常透传；contextPath/query；拒绝响应与装配配置。
  禁止以初版预测数量作为完成证据，完成时在本 Task 勾选项与 `docs/集成测试.md` 记录 Maven
  实际报告的测试名/数量。

**实际红灯测试代码（取代下方早期草案片段）：**

- `CachedBodyHttpServletRequestWrapperTest`：覆盖精确边界、声明长度、chunked/伪造长度、双通道复读，
  以及同步 wrapper 的 async/startAsync/ReadListener 失败关闭契约；
- `ProjectAppAuthFilterTest`：24 个测试方法（参数化展开 65 个用例），方法名直接声明合法三 scope、
  8 核心头缺失、10 类头重复、格式与长度、注册表/时间窗、凭证/HMAC/nonce/scope 顺序、cause、
  Holder 清理、下游异常透传和拒绝响应契约；
- `ProjectAppAuthConfigurationTest`：18 个方法，包含既有 resolver/master-key 契约以及新增启停、
  body/skew 启动失败、四前缀 order 与 permit-all YAML 合并。

以上三个实际测试文件是本 Task 的权威代码；下方代码块仅保留实施历史，若与实际文件不同以实际
测试及本节安全不变量为准。

- [x] **Step 1: 写单测（红灯）**

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 入站验签过滤器单测：与 API 模块 Signer 互为逆运算对拍；注册表、契约版本、时间窗、
 * 验签、nonce、scope、ThreadLocal 清理与错误响应全覆盖。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAppAuthFilterTest {

    private static final String APP_ID = "app-filter-test";
    private static final String APP_KEY = "secret-key-for-filter-test";
    private static final String CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String ENTRY_PATH = "/rpc-api/supply-chain/instore/buyer-entry/home";
    private static final String CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";

    @Mock private ProjectAppCredentialService credentialService;
    @Mock private NonceStore nonceStore;

    private ProjectAppAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ProjectAppAuthFilter(credentialService, nonceStore,
                new ProjectAppAuthProperties(), new ObjectMapper());
    }

    @AfterEach
    void cleanUp() {
        ProjectAuthContextHolder.clear();
    }

    @Test
    void validSignedRequestPassesAndInjectsChannelContext() throws Exception {
        byte[] body = "{\"channelOrderId\":\"RT-1\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        stubCredential();

        MockHttpServletRequest request = request("POST", CHANNEL_PATH, body, headers);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ProjectAuthContext> captured = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                captured.set(ProjectAuthContextHolder.get());
            }
        });

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().projectId()).isEqualTo(20L);
        assertThat(captured.get().buyerId()).isEqualTo(10L);
        assertThat(captured.get().channelCode()).isEqualTo("RT_MART");
        assertThat(captured.get().operationCode()).isEqualTo("INSTORE_CREATE_PAYMENT_ORDER");
        assertThat(chain.getRequest()).isNotNull();
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void downstreamCanRereadRequestBody() throws Exception {
        byte[] body = "{\"channelOrderId\":\"RT-2\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        stubCredential();

        AtomicReference<byte[]> read = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
                read.set(req.getInputStream().readAllBytes());
            }
        });

        filter.doFilter(request("POST", CHANNEL_PATH, body, headers), new MockHttpServletResponse(), chain);

        assertThat(read.get()).isEqualTo(body);
    }

    @Test
    void tamperedBodyRejected() throws Exception {
        byte[] signedBody = "{\"channelOrderId\":\"RT-3\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sentBody = "{\"channelOrderId\":\"RT-EVIL\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, signedBody);
        stubCredential();

        assertRejected(request("POST", CHANNEL_PATH, sentBody, headers));
    }

    @Test
    void expiredTimestampRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body,
                System.currentTimeMillis() - 6 * 60_000L, "nonce-expired");

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void contractVersionMismatchRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        headers.put(ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, "0.9.0");

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void unregisteredPathUnderPrefixRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();
        Map<String, String> headers = manualHeaders("INSTORE_CREATE_PAYMENT_ORDER", timestamp,
                "nonce-unregistered", ProjectAppAuthSigner.sha256Hex(body), "", "RT_MART");

        assertRejected(request("POST",
                "/rpc-api/supply-chain/instore/channel-command/notRegistered", body, headers));
    }

    @Test
    void operationCodeHeaderMismatchRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        headers.put(ProjectAppAuthSigner.HEADER_OPERATION_CODE, "INSTORE_QUERY_PAYMENT_STATUS");

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void credentialFailureRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        when(credentialService.authenticate(APP_ID, 1)).thenThrow(exception(BUYER_AUTH_INVALID));

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void replayNonceRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CHANNEL_PATH, body);
        stubCredential();
        doThrow(exception(BUYER_AUTH_INVALID)).when(nonceStore)
                .ensureUnique(APP_ID, headers.get(ProjectAppAuthSigner.HEADER_NONCE));

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void buyerEntryPassesAndInjectsBuyerScope() throws Exception {
        byte[] body = "{\"companyCode\":\"COMPANY_A\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "opaque-user-1", null),
                "POST", ENTRY_PATH, body);
        stubCredential();
        AtomicReference<ProjectAuthContext> captured = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                captured.set(ProjectAuthContextHolder.get());
            }
        });

        filter.doFilter(request("POST", ENTRY_PATH, body, headers), new MockHttpServletResponse(), chain);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().buyerUserId()).isEqualTo("opaque-user-1");
        assertThat(captured.get().channelCode()).isNull();
        assertThat(captured.get().operationCode()).isEqualTo("INSTORE_HOME_ENTRY");
    }

    @Test
    void callbackPassesAndInjectsProjectScope() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null),
                "POST", CALLBACK_PATH, body);
        stubCredential();
        AtomicReference<ProjectAuthContext> captured = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                captured.set(ProjectAuthContextHolder.get());
            }
        });

        filter.doFilter(request("POST", CALLBACK_PATH, body, headers), new MockHttpServletResponse(), chain);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().buyerUserId()).isNull();
        assertThat(captured.get().channelCode()).isNull();
        assertThat(captured.get().operationCode()).isEqualTo("INSTORE_CONFIRM_PAYMENT_CALLBACK");
    }

    /** 绕过客户端 Signer 的 scope 快速失败，手工签名后验证服务端独立防线（纵深防御）。 */
    @Test
    void channelCommandMissingScopeDeclarationRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();
        Map<String, String> headers = manualHeaders("INSTORE_CREATE_PAYMENT_ORDER", timestamp,
                "nonce-no-scope", ProjectAppAuthSigner.sha256Hex(body), "", "");
        stubCredential();

        assertRejected(request("POST", CHANNEL_PATH, body, headers));
    }

    @Test
    void callbackCarryingExtraDeclarationRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();
        Map<String, String> headers = manualHeaders("INSTORE_CONFIRM_PAYMENT_CALLBACK", timestamp,
                "nonce-extra-scope", ProjectAppAuthSigner.sha256Hex(body), "", "RT_MART");
        stubCredential();

        assertRejected(request("POST", CALLBACK_PATH, body, headers));
    }

    private void stubCredential() {
        when(credentialService.authenticate(APP_ID, 1)).thenReturn(
                new ProjectAppCredentialService.AuthenticatedApp(20L, 10L, APP_ID, 1, APP_KEY));
    }

    /** 手工构造 10 个认证头并按签名串计算 HMAC（不走客户端 scope 快速失败），验证服务端独立校验。 */
    private static Map<String, String> manualHeaders(String operationCode, long timestamp, String nonce,
                                                      String requestSha256, String buyerUserId,
                                                      String channelCode) {
        String signature = ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY,
                ProjectAppAuthSigner.canonicalString(APP_ID, "1", "1.0.0", operationCode,
                        String.valueOf(timestamp), nonce, requestSha256, buyerUserId, channelCode));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ProjectAppAuthSigner.HEADER_APP_ID, APP_ID);
        headers.put(ProjectAppAuthSigner.HEADER_KEY_VERSION, "1");
        headers.put(ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, "1.0.0");
        headers.put(ProjectAppAuthSigner.HEADER_OPERATION_CODE, operationCode);
        headers.put(ProjectAppAuthSigner.HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.put(ProjectAppAuthSigner.HEADER_NONCE, nonce);
        headers.put(ProjectAppAuthSigner.HEADER_REQUEST_SHA256, requestSha256);
        headers.put(ProjectAppAuthSigner.HEADER_SIGNATURE, signature);
        headers.put(ProjectAppAuthSigner.HEADER_BUYER_USER_ID, buyerUserId);
        headers.put(ProjectAppAuthSigner.HEADER_CHANNEL_CODE, channelCode);
        return headers;
    }

    private static MockHttpServletRequest request(String method, String path, byte[] body,
                                                  Map<String, String> headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body);
        headers.forEach(request::addHeader);
        return request;
    }

    /** 认证失败统一为 HTTP 200 + CommonResult(BUYER_AUTH_INVALID)：过滤链未前放、ThreadLocal 已清理。 */
    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asLong()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThat(chain.getRequest()).isNull();
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectAppAuthFilterTest
```

Actual: Wrapper 与 Filter 两轮分别因目标类不存在编译失败；装配轮实际 18 个测试中 4 failures +
1 error，精确命中未注册 Filter、body/skew 未在启动期失败、permit-all YAML 未合并。

- [x] **Step 3: 实现 Wrapper 与过滤器**

`CachedBodyHttpServletRequestWrapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求 body 原始字节的包装器：过滤器验签读取后，下游 Controller/全局异常处理仍可
 * 重复读取（requestSha256 与验签共用同一字节串）。设计见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。
 */
final class CachedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    CachedBodyHttpServletRequestWrapper(HttpServletRequest request, int maxRequestBodyBytes) throws IOException {
        super(request);
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBodyBytes 必须为正数");
        }
        ServletInputStream input = request.getInputStream();
        this.cachedBody = input.readNBytes(maxRequestBodyBytes);
        if (input.read() != -1) {
            throw new IllegalArgumentException("认证请求体超过允许上限");
        }
    }

    /** 缓存的原始 body 字节；仅供同包过滤器验签使用，不对外暴露。 */
    byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("非异步请求不支持 ReadListener");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
```

`ProjectAppAuthFilter.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目应用统一认证入站过滤器（固定顺序，任一步失败即失败关闭）：
 * body 缓存 → 头解析 → 注册表匹配 → operationCode/contractVersion → 时间窗（纯内存，
 * 先于任何数据库访问）→ 凭证链 → requestSha256/HMAC 常量时间验签 → nonce 防重放 →
 * scope 校验 → ThreadLocal 注入（finally 清理）。
 *
 * <p>认证失败对外统一 BUYER_AUTH_INVALID（防项目枚举）；业务层异常（如绑定缺失）
 * 原样穿透本过滤器交由全局异常处理。过滤器经 FilterRegistrationBean 挂四个 INSTORE
 * 前缀，order = HIGHEST_PRECEDENCE + 10 先于 Spring Security。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ProjectAppAuthFilter extends OncePerRequestFilter {

    private final ProjectAppCredentialService credentialService;
    private final NonceStore nonceStore;
    private final ProjectAppAuthProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ProjectAuthContextHolder.clear();
        try {
            CachedBodyHttpServletRequestWrapper wrapped;
            ProjectAuthContext context;
            try {
                wrapped = new CachedBodyHttpServletRequestWrapper(
                        request, properties.getMaxRequestBodyBytes());
                context = authenticate(wrapped);
            } catch (ServiceException | IllegalArgumentException ex) {
                log.debug("[project-auth] 认证拒绝 category=AUTH_INVALID method={} uri={}",
                        request.getMethod(), request.getRequestURI());
                writeAuthFailure(response);
                return;
            }
            ProjectAuthContextHolder.set(context);
            filterChain.doFilter(wrapped, response);
        } finally {
            ProjectAuthContextHolder.clear();
        }
    }

    private ProjectAuthContext authenticate(CachedBodyHttpServletRequestWrapper request) {
        String appId = requiredHeader(request, ProjectAppAuthSigner.HEADER_APP_ID);
        int keyVersion = parseIntHeader(request, ProjectAppAuthSigner.HEADER_KEY_VERSION);
        String contractVersion = requiredHeader(request, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION);
        String operationCode = requiredHeader(request, ProjectAppAuthSigner.HEADER_OPERATION_CODE);
        long timestamp = parseLongHeader(request, ProjectAppAuthSigner.HEADER_TIMESTAMP);
        String nonce = requiredHeader(request, ProjectAppAuthSigner.HEADER_NONCE);
        String requestSha256 = requiredHeader(request, ProjectAppAuthSigner.HEADER_REQUEST_SHA256);
        String signature = requiredHeader(request, ProjectAppAuthSigner.HEADER_SIGNATURE);
        String buyerUserId = header(request, ProjectAppAuthSigner.HEADER_BUYER_USER_ID);
        String channelCode = header(request, ProjectAppAuthSigner.HEADER_CHANNEL_CODE);

        ProjectAuthOperations.Operation operation = ProjectAuthOperations
                .findByMethodAndPath(request.getMethod(), request.getRequestURI())
                .orElseThrow(ProjectAppAuthFilter::authInvalid);
        if (!operation.operationCode().equals(operationCode)
                || !operation.contractVersion().equals(contractVersion)) {
            throw authInvalid();
        }
        validateTimestamp(timestamp, properties.getTimestampSkewMinutes());
        ProjectAppCredentialService.AuthenticatedApp app = credentialService.authenticate(appId, keyVersion);
        if (!constantTimeEquals(ProjectAppAuthSigner.sha256Hex(request.getCachedBody()), requestSha256)) {
            throw authInvalid();
        }
        String canonical = ProjectAppAuthSigner.canonicalString(appId, String.valueOf(keyVersion),
                contractVersion, operationCode, String.valueOf(timestamp), nonce, requestSha256,
                nullToEmpty(buyerUserId), nullToEmpty(channelCode));
        if (!constantTimeEquals(ProjectAppAuthSigner.hmacSha256Base64Url(app.appKey(), canonical),
                signature)) {
            throw authInvalid();
        }
        nonceStore.ensureUnique(appId, nonce);
        validateScope(operation, buyerUserId, channelCode);
        try {
            return new ProjectAuthContext(app.projectId(), app.buyerId(), appId, keyVersion,
                    operation.operationCode(), buyerUserId, channelCode);
        } catch (IllegalArgumentException ex) {
            throw authInvalid(ex);
        }
    }

    /** scope 声明校验：必填组非空、非必填组必须为空，防止携带无关声明（字段已随签名认证）。 */
    private static void validateScope(ProjectAuthOperations.Operation operation,
                                      String buyerUserId, String channelCode) {
        switch (operation.scopeType()) {
            case CHANNEL_CODE -> {
                if (channelCode == null || buyerUserId != null) {
                    throw authInvalid();
                }
            }
            case BUYER_USER_ID -> {
                if (buyerUserId == null || channelCode != null) {
                    throw authInvalid();
                }
            }
            case NONE -> {
                if (buyerUserId != null || channelCode != null) {
                    throw authInvalid();
                }
            }
        }
    }

    /** 无溢出时间窗判断：极值 timestamp 或配置换算溢出统一认证失败。 */
    private static void validateTimestamp(long timestamp, long skewMinutes) {
        try {
            long skewMillis = Math.multiplyExact(skewMinutes, TimeUnit.MINUTES.toMillis(1));
            long delta = Math.subtractExact(timestamp, System.currentTimeMillis());
            if (skewMillis < 0 || delta < -skewMillis || delta > skewMillis) {
                throw authInvalid();
            }
        } catch (ArithmeticException ex) {
            throw authInvalid(ex);
        }
    }

    /** 认证失败响应：HTTP 200 + CommonResult(BUYER_AUTH_INVALID)，与全局异常处理对外约定一致。 */
    private void writeAuthFailure(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), CommonResult.error(BUYER_AUTH_INVALID));
    }

    private static String header(HttpServletRequest request, String name) {
        String value = singleHeader(request, name, false);
        return value == null || value.isBlank() ? null : value;
    }

    private static String requiredHeader(HttpServletRequest request, String name) {
        return singleHeader(request, name, true);
    }

    /** 每个认证头必须恰一值；重复头即使同值也拒绝，避免容器首值/拼接语义歧义。 */
    private static String singleHeader(HttpServletRequest request, String name, boolean required) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            if (!required) {
                return null;
            }
            throw authInvalid();
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || (required && (value == null || value.isBlank()))) {
            throw authInvalid();
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int parseIntHeader(HttpServletRequest request, String name) {
        try {
            return Integer.parseInt(requiredHeader(request, name));
        } catch (NumberFormatException ex) {
            throw authInvalid();
        }
    }

    private static long parseLongHeader(HttpServletRequest request, String name) {
        try {
            return Long.parseLong(requiredHeader(request, name));
        } catch (NumberFormatException ex) {
            throw authInvalid();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static ServiceException authInvalid() {
        return exception(BUYER_AUTH_INVALID);
    }

    private static ServiceException authInvalid(Throwable cause) {
        ServiceException failure = authInvalid();
        failure.initCause(cause);
        return failure;
    }
}
```

- [x] **Step 4: 重跑测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=ProjectAppAuthFilterTest
```

Actual: `ProjectAppAuthFilterTest` 参数化展开 65 个用例，0 失败、0 错误、0 跳过；Wrapper 独立
5 个用例同样全绿。

- [x] **Step 5: 装配过滤器注册与配置**

`ProjectAppAuthConfiguration.java` 追加（import 相应补齐：`ProjectAuthOperations`、`ObjectMapper`、`ConditionalOnProperty`、`FilterRegistrationBean`、`Ordered`）：

```java
    /**
     * 注册入站验签过滤器：只挂四个 INSTORE 前缀，order = HIGHEST_PRECEDENCE + 10 先于
     * Spring Security 过滤器链；enabled=false 时不注册（上下文回到失败关闭默认实现）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "aifuli.supply-chain.auth", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<ProjectAppAuthFilter> projectAppAuthFilter(
            ProjectAppCredentialService credentialService, NonceStore nonceStore,
            ProjectAppAuthProperties properties, ObjectMapper objectMapper) {
        return buildFilterRegistration(
                new ProjectAppAuthFilter(credentialService, nonceStore, properties, objectMapper));
    }

    /** 构建过滤器注册：URL 模式来自注册表 PROTECTED_PREFIXES，保证登记与挂载同源。 */
    static FilterRegistrationBean<ProjectAppAuthFilter> buildFilterRegistration(ProjectAppAuthFilter filter) {
        FilterRegistrationBean<ProjectAppAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setName("projectAppAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns(ProjectAuthOperations.PROTECTED_PREFIXES.stream()
                .map(prefix -> prefix + "/*").toArray(String[]::new));
        return registration;
    }
```

`ProjectAppAuthConfigurationTest.java` 追加注册测试（import `Ordered`、`FilterRegistrationBean`、`static org.mockito.Mockito.mock`）：

```java
    @Test
    void filterRegistrationCoversAllProtectedPrefixesBeforeSecurity() {
        FilterRegistrationBean<ProjectAppAuthFilter> registration =
                ProjectAppAuthConfiguration.buildFilterRegistration(mock(ProjectAppAuthFilter.class));

        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                "/rpc-api/supply-chain/instore/channel-command/*",
                "/rpc-api/supply-chain/instore/channel-member/*",
                "/rpc-api/supply-chain/instore/buyer-entry/*",
                "/rpc-api/supply-chain/buyer/instore/payment/*");
        assertThat(registration.getFilter()).isInstanceOf(ProjectAppAuthFilter.class);
    }
```

`application.yaml` 在 `aifuli:` 下新增 `security.permit-all-urls`（放行四个 INSTORE 前缀，
HMAC 过滤器已先行验签且失败关闭，放行不降低安全性），并在 `aifuli.supply-chain:` 下新增
`auth` 配置块：

```yaml
aifuli:
  security:
    permit-all-urls:
      - /rpc-api/supply-chain/instore/channel-command/*
      - /rpc-api/supply-chain/instore/channel-member/*
      - /rpc-api/supply-chain/instore/buyer-entry/*
      - /rpc-api/supply-chain/buyer/instore/payment/*
  supply-chain:
    auth:
      enabled: true
      timestamp-skew-minutes: 5
      nonce-retention-hours: 1
      nonce-cleanup-interval: PT1H
      max-request-body-bytes: 1048576
```

`application-local.yaml` 在既有 `aifuli.supply-chain` 下新增开发用主密钥：

```yaml
aifuli:
  supply-chain:
    auth:
      # 本地开发专用 32 字节 Base64 主密钥；生产由部署环境注入，禁止提交真实密钥
      master-key: MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=
```

- [x] **Step 6: 重跑过滤器与装配测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest='ProjectAppAuthFilterTest,ProjectAppAuthConfigurationTest'
```

Actual: Task 9 三个测试类共 88 个用例（Wrapper 5 + Filter 65 + 配置 18），0 失败、0 错误、
0 跳过；追加 Task 8 上下文回归后 server 定向共 103 个用例全绿，API 签名/Feign/注册表回归
25 个用例全绿。质量复审先由 Wrapper/配置定向 23 个用例得到 2 个失败，分别证明包装器继承了
delegate 的异步能力、Filter 注册器未显式关闭异步；按同步 MVC 契约修复后同组 23 个及 Task 8/9
定向 103 个全部转绿。Filter 在头与精确注册表匹配后仅以 DEBUG 记录 method、注册表
operationCode、已校验 appId/keyVersion，不记录 nonce、signature、body、scope 或任何密钥材料。

- [x] **Step 7: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/application.yaml aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/application-local.yaml
git commit -m "feat(supply-chain): enforce instore app auth filter"
```

---

### Task 10: 端到端集成门禁与架构规则

> **2026-08-24 落地修订（本节后续早期代码草案以此为准）：** Flow 门禁从原 8 个场景扩展为
> 至少 21 个 JUnit 执行场景：四组 API/三类可信 Context 的 4 个合法签名业务穿透、注册表全部
> 10 个 operation 的无认证头参数化保护面、replay、body 篡改、过期时间、GRACE、REVOKED、
> paused project 与前缀内未知路径各 1 个。真实启动必须使用完整
> `SupplyChainServerApplication`、Spring Security、真实 Filter/Accessor/Facade、Liquibase 与真实
> MySQL，并通过高优先级 `--server.port=0` 等命令行参数启用专用 `project-app-auth-it` profile；
> 实际端口从 `WebServerApplicationContext` 读取。Testcontainers `mysql:8.4` 是发布基线，
> `supply-chain.test.jdbc-*` 仅用于无 Docker 时接入独占外部空库。
>
> 四组合法请求的预期业务错误依次为
> `INSTORE_CHANNEL_USER_BINDING_MISSING`、`INSTORE_CREDENTIAL_INVALID`、
> `INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND`、`INSTORE_PAYMENT_FLOW_NOT_FOUND`，均不得弱化为
> “只要不是认证错误”。buyer-entry 的 `buyerUserId` 使用数字外观但按不透明字符串传递。无认证、
> 篡改、超窗、REVOKED、暂停、未知路径均须断言 HTTP 200 + `BUYER_AUTH_INVALID`；无效签名、
> 超窗与 REVOKED 还须断言未写 nonce；replay 须断言第二次拒绝且数据库恰一行。每例使用唯一 nonce/
> 业务号，helper 断言响应 body 非空，暂停状态与 server/container/master-key 字节均在 `finally` 恢复。
>
> 架构规则从当前 11 条显式 JUnit 5 测试增加到至少 13 条：auth 基础设施不得依赖
> border/application/tradecore/interfaceapi；auth 包外不得依赖 Holder（并同时约束包级 Context）。
> 根脚本加入 Task 7 nonce 集成和本 Flow，形成 16 个独占库；必须可靠传播建库/Maven/管道失败，
> 每项保存并打印摘要、累计 FAILED，全部成功时才输出 `GATE PASSED`。最终 API/server/架构/集成
> 测试总数一律记录本次新鲜输出，下面草案中的 8、12、15、75、358 等旧静态计数不再作为验收值。
>
> Flow 首次完整应用 RED 门禁若证明供应链 server 缺少框架要求的
> `AuthorizeRequestsCustomizer`，允许在 `infrastructure.security` 新增生产 no-op Bean 作为 Task10
> 装配修复；必须先补设计、保留 Flow 启动失败证据，再实施最小修复。该 Bean 不得新增 permit 规则，
> 四个认证前缀继续只由 `aifuli.security.permit-all-urls` 精确放行，禁止把整个 RPC 前缀 permit。
>
> 完整 Flow 若发现框架非 prod `ApiAccessLogInterceptor` 输出受保护请求 body，必须把注册表四前缀
> 加入 `ApiAccessLogPathUtils` 敏感路径清单，并以参数化单测证明四前缀均遮蔽、相邻普通路径不误判；
> 不得仅靠测试参数关闭日志，因为 `aifuli.access-log.enable` 当前不会移除该 MVC 拦截器。路径判断同时
> 接收 `requestURI + contextPath`，仅剥离 URI 开头的真实部署上下文，避免非根 context path 漏遮蔽。
>
> **2026-08-24 实际完成证据：** 本机外部 MySQL 9.6.0（发布基线仍为 Testcontainers MySQL 8.4）
> 上执行 Liquibase 19 个 changeSet；Flow 使用实际随机端口 61640，21 个场景全绿，真实访问日志均为
> “参数已省略”。Task 8/9 server 定向 103、认证 API 定向 25、Web starter 全量 13、API 全量 84、
> server 全量 471、ArchitectureTest 14 均全绿。根脚本 16 个独占空库、合计 41 个集成测试场景全绿，
> 最终输出 `GATE PASSED (16/16)`；每项日志独立保存，建库/Maven/指定测试未匹配会累计并最终非零
> 退出。Flow 外部库种子的每条逆序 DELETE 独立尝试，清理异常以 suppressed 链统一报告。
> 同日启动 Docker Desktop 后，在不传任何 `supply-chain.test.jdbc-*` 回退参数的条件下实测发布基线
> `mysql:8.4`（镜像 digest `sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`，
> 容器内版本 8.4.11）：Task 7 nonce 持久化门禁 3/3、Task 10 完整 HTTP Flow 21/21 均通过；
> Flow 再次执行 19 个 Liquibase changeSet，Web 随机端口 57756，真实访问日志仅输出“参数已省略”。

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthFlowIntegrationTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/SupplyChainSecurityConfiguration.java`
- Modify: `aifuli-framework/aifuli-spring-boot-starter-web/src/main/java/com/aifuli/framework/apilog/core/`
- Create: `aifuli-framework/aifuli-spring-boot-starter-web/src/test/java/com/aifuli/framework/apilog/core/util/ApiAccessLogPathUtilsTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`
- Modify: `docs/集成测试.md`
- Modify: `scripts-gate-integration.sh`

本任务是验证门禁（非 TDD 红绿）：Task 9 已交付过滤器与装配，集成测试验证真实 HTTP 全链路
（Spring Security permit-all 放行 → HMAC 过滤器 → ApiImpl → Facade），失败说明装配缺口
（如 401 表示 permit-all-urls 未生效），此时修实现、不改断言。

Task 7 已提前通过 `ProjectAuthNonceStoreIntegrationTest` 覆盖真实 MySQL（Testcontainers 发布基线
`mysql:8.4`）的复合主键并发、Spring `DuplicateKeyException` 翻译、命名
`supplyChainTransactionManager` 回滚和
`DELETE ... LIMIT`/严格过期边界。Task 10 不重复这些 Mapper 持久化契约，仍必须执行下述完整
HTTP replay E2E，证明 Security → HMAC 过滤器 → `NonceStore` → 业务入口的真实装配链路。

- [x] **Step 1: 写集成测试**

`ProjectAppAuthFlowIntegrationTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.SupplyChainServerApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 端到端认证门禁：完整 SupplyChainServerApplication（RANDOM_PORT）+ 真实 MySQL
 * + Liquibase + Spring Security 放行 + HMAC 过滤器 + ApiImpl + Facade 全链路。
 *
 * <p>种子只含认证链路四表（buyer/project/capability/application/credential），不种子渠道
 * 绑定：签名请求穿透认证层后应得到业务错误 INSTORE_CHANNEL_USER_BINDING_MISSING（区别于
 * BUYER_AUTH_INVALID，即证明可信 ChannelContext 已注入并到达 Facade）。同时覆盖重放、
 * 篡改、超窗、GRACE/REVOKED、暂停项目与前缀内未登记路径。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁}。</p>
 */
class ProjectAppAuthFlowIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.test.jdbc-url");

    private static final String MASTER_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=";
    private static final byte[] MASTER_KEY = Base64.getDecoder().decode(MASTER_KEY_B64);

    private static final long BUYER_ID = 3001L;
    private static final long PROJECT_ID = 3101L;
    private static final long CAPABILITY_ID = 3201L;
    private static final long APPLICATION_ID = 3301L;
    private static final long CURRENT_CREDENTIAL_ID = 3401L;
    private static final long GRACE_CREDENTIAL_ID = 3402L;
    private static final long REVOKED_CREDENTIAL_ID = 3403L;

    private static final String APP_ID = "app-it-auth";
    private static final String APP_KEY = "it-auth-app-key";
    private static final String CREATE_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";

    private static final RestTemplate REST = new RestTemplate();
    private static ConfigurableApplicationContext server;
    private static String baseUrl;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startServer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.cloud.nacos.discovery.enabled", false);
        properties.put("spring.cloud.nacos.config.enabled", false);
        properties.put("spring.boot.admin.client.enabled", false);
        properties.put("springdoc.api-docs.enabled", false);
        properties.put("knife4j.enable", false);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);
        properties.put("aifuli.supply-chain.auth.master-key", MASTER_KEY_B64);
        server = new SpringApplicationBuilder(SupplyChainServerApplication.class)
                .properties(properties)
                .run();
        Integer port = server.getEnvironment().getProperty("local.server.port", Integer.class);
        baseUrl = "http://127.0.0.1:" + port;
        jdbc = new JdbcTemplate(server.getBean("supplyChainDataSource", DataSource.class));
        seedFixtures();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void signedChannelCommandPassesAuthIntoBusinessLayer() {
        CommonResult<?> result = postSigned(1, body("RT-AUTH-1", "TRADE-AUTH-1"), "nonce-auth-pass-1");

        assertThat(result.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
    }

    @Test
    void replayedNonceRejected() {
        byte[] body = body("RT-AUTH-REPLAY", "TRADE-AUTH-REPLAY");
        CommonResult<?> first = postSigned(1, body, "nonce-replay-fixed");
        assertThat(first.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());

        CommonResult<?> second = postSigned(1, body, "nonce-replay-fixed");

        assertThat(second.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void tamperedBodyRejected() {
        byte[] signedBody = body("RT-AUTH-TAMPER", "TRADE-AUTH-TAMPER");
        byte[] sentBody = body("RT-AUTH-EVIL", "TRADE-AUTH-EVIL");
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CREATE_PATH, signedBody, System.currentTimeMillis(), "nonce-tamper");

        CommonResult<?> result = post(CREATE_PATH, sentBody, headers);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void expiredTimestampRejected() {
        byte[] body = body("RT-AUTH-EXPIRED", "TRADE-AUTH-EXPIRED");
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CREATE_PATH, body,
                System.currentTimeMillis() - 6 * 60_000L, "nonce-expired");

        CommonResult<?> result = post(CREATE_PATH, body, headers);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void graceCredentialAuthenticates() {
        CommonResult<?> result = postSigned(2, body("RT-AUTH-GRACE", "TRADE-AUTH-GRACE"), "nonce-grace");

        assertThat(result.getCode()).isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
    }

    @Test
    void revokedCredentialRejected() {
        CommonResult<?> result = postSigned(3, body("RT-AUTH-REVOKED", "TRADE-AUTH-REVOKED"),
                "nonce-revoked");

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Test
    void pausedProjectRejected() {
        jdbc.update("UPDATE sc_buyer_project SET status = 20 WHERE id = ?", PROJECT_ID);
        try {
            CommonResult<?> result = postSigned(1, body("RT-AUTH-PAUSED", "TRADE-AUTH-PAUSED"),
                    "nonce-paused");

            assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        } finally {
            jdbc.update("UPDATE sc_buyer_project SET status = 10 WHERE id = ?", PROJECT_ID);
        }
    }

    /** 前缀内未登记路径被拒：同时证明请求已穿过 Security 放行、由 HMAC 过滤器失败关闭。 */
    @Test
    void unregisteredPathUnderPrefixRejected() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ProjectAppAuthSigner.HEADER_APP_ID, APP_ID);
        headers.put(ProjectAppAuthSigner.HEADER_KEY_VERSION, "1");
        headers.put(ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, "1.0.0");
        headers.put(ProjectAppAuthSigner.HEADER_OPERATION_CODE, "INSTORE_CREATE_PAYMENT_ORDER");
        headers.put(ProjectAppAuthSigner.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        headers.put(ProjectAppAuthSigner.HEADER_NONCE, "nonce-unregistered");
        headers.put(ProjectAppAuthSigner.HEADER_REQUEST_SHA256, ProjectAppAuthSigner.sha256Hex(new byte[0]));
        headers.put(ProjectAppAuthSigner.HEADER_SIGNATURE, "invalid-signature");

        CommonResult<?> result = post(
                "/rpc-api/supply-chain/instore/channel-command/unknownOperation", new byte[0], headers);

        assertThat(result.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    /** 认证链路种子：buyer/project/capability/application + CURRENT/GRACE/REVOKED 三版本凭证。 */
    private static void seedFixtures() {
        jdbc.update("""
                INSERT INTO sc_buyer(id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, 'BUYER_IT_AUTH', '认证联调采购商', '认证联调', 'SAAS_MALL', 10, 0,
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, BUYER_ID);
        jdbc.update("""
                INSERT INTO sc_buyer_project(id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (?, 'INSTORE_IT_AUTH', '认证联调项目', ?, 'INDEPENDENT', 10, 0,
                    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, PROJECT_ID, BUYER_ID);
        jdbc.update("""
                INSERT INTO sc_project_capability(id, project_id, capability_code, status, version,
                    created_at, updated_at)
                VALUES (?, ?, 'INSTORE', 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, CAPABILITY_ID, PROJECT_ID);
        jdbc.update("""
                INSERT INTO sc_project_application(id, project_id, app_id, status, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, APPLICATION_ID, PROJECT_ID, APP_ID);
        insertCredential(CURRENT_CREDENTIAL_ID, 1, "CURRENT", "CURRENT");
        insertCredential(GRACE_CREDENTIAL_ID, 2, "GRACE", "GRACE");
        insertCredential(REVOKED_CREDENTIAL_ID, 3, "REVOKED", null);
    }

    /** 凭证版本种子：secret_ref 以信封加密入库（active_slot 必须满足 016 CHECK 约束）。 */
    private static void insertCredential(long id, int keyVersion, String status, String activeSlot) {
        jdbc.update("""
                INSERT INTO sc_project_app_credential_version(
                    id, application_id, key_version, secret_ref, masked_hint, status,
                    valid_from, valid_to, active_slot, created_at)
                VALUES (?, ?, ?, ?, 'it***key', ?, UTC_TIMESTAMP(3), NULL, ?, UTC_TIMESTAMP(3))
                """, id, APPLICATION_ID, keyVersion,
                LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, APP_ID, keyVersion, APP_KEY),
                status, activeSlot);
    }

    /** 建单请求体：字段满足 CreatePaymentOrderReqDTO 校验，可到达绑定解析业务层。 */
    private static byte[] body(String channelOrderId, String tradeId) {
        return ("{\"channelOrderId\":\"" + channelOrderId + "\",\"tradeId\":\"" + tradeId
                + "\",\"userCode\":\"user-it-auth\",\"payAmount\":10000,\"orderType\":1,"
                + "\"totalAmount\":10000,\"companyCode\":\"COMPANY_A\","
                + "\"externalMerchantId\":\"MART-001\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static CommonResult<?> postSigned(int keyVersion, byte[] body, String nonce) {
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, keyVersion, null, "RT_MART"),
                "POST", CREATE_PATH, body, System.currentTimeMillis(), nonce);
        return post(CREATE_PATH, body, headers);
    }

    private static CommonResult<?> post(String path, byte[] body, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::add);
        ResponseEntity<CommonResult<Object>> entity = REST.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, httpHeaders),
                new ParameterizedTypeReference<CommonResult<Object>>() { });
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        return entity.getBody();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "")
                : MYSQL.getPassword();
    }
}
```

- [x] **Step 2: 运行集成测试（真实 MySQL）**

```bash
/opt/homebrew/opt/mysql-client@8.4/bin/mysql -h 127.0.0.1 -u root -e "DROP DATABASE IF EXISTS sc_it_gate_16; CREATE DATABASE sc_it_gate_16 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ProjectAppAuthFlowIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=true \
  "-Dsupply-chain.test.jdbc-url=jdbc:mysql://127.0.0.1:3306/sc_it_gate_16?useSSL=false&allowPublicKeyRetrieval=true" \
  verify
```

Actual: PASS（Tests run: 21, Failures: 0, Errors: 0, Skipped: 0；MySQL 9.6.0，Liquibase 19，
实际随机端口 61640）。失败排障方向：
- 全 401/连接被拒 → `permit-all-urls` 未生效或过滤器未注册（检查 Step 5 装配与 yaml）；
- 全 BUYER_AUTH_INVALID → 主密钥与种子 `secret_ref` 不一致或凭证状态/窗口问题；
- 应用启动失败 → 检查 nacos/admin-client 是否已禁用、`supplyChainDataSource` 属性。

- [x] **Step 3: 新增架构规则（2 条，实际同时约束 Context，共 3 条）**

`SupplyChainModuleArchitectureTest.java` 追加规则与测试方法：

```java
    /**
     * 项目认证基础设施只依赖自身 DAL 与 security 包，不得反向依赖业务层，保证认证组件
     * 可独立演进与测试。设计见
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#6-服务端组件}。
     */
    static final ArchRule project_auth_infrastructure_is_isolated = noClasses()
            .that().resideInAPackage("..infrastructure.auth..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..border..", "..application..", "..tradecore..", "..interfaceapi..");

    /**
     * 业务代码禁止直接读取认证 ThreadLocal，必须经 ProjectCallContextAccessor 获取可信
     * 上下文，防止绕过失败关闭语义。设计见
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#65-projectauthcontextholder-与-projectcallcontextaccessorimpl}。
     */
    static final ArchRule only_auth_package_touches_auth_context_holder = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.auth..")
            .should().dependOnClassesThat().haveSimpleName("ProjectAuthContextHolder");
```

```java
    @Test
    void projectAuthInfrastructureIsIsolated() {
        project_auth_infrastructure_is_isolated.check(importedClasses);
    }

    @Test
    void onlyAuthPackageTouchesAuthContextHolder() {
        only_auth_package_touches_auth_context_holder.check(importedClasses);
    }
```

- [x] **Step 4: 运行架构测试**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -Dtest=SupplyChainModuleArchitectureTest
```

Actual: PASS（Tests run: 14, Failures: 0；既有 11 条 + 新增 3 条，执行时间约 1.1 秒——
若异常膨胀，先清理 `target/` 下的 macOS 同步冲突副本，见 docs/集成测试.md）。

- [x] **Step 5: 全量单测回归**

```bash
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api test
mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test
```

Actual: API 84、server 471 全部 PASS；Task 8/9 server 定向 103、认证 API 定向 25、
Web starter 全量 13 也全部 PASS，`ProjectCallContextConfigurationTest` 覆盖语义保持不变。

- [x] **Step 6: 更新联调文档与门禁脚本**

`docs/集成测试.md` 新增「项目应用统一认证」章节：种子认证链说明、启动命令、21 个场景、
失败排障方向、GRACE/REVOKED/暂停项目语义及本次实际证据。

`scripts-gate-integration.sh` 的 `TESTS` 数组追加 Task 7 与 Task 10（共 16 个门禁库），并显式传播失败：

```bash
  "ProjectAuthNonceStoreIntegrationTest supply-chain.test"
  "ProjectAppAuthFlowIntegrationTest supply-chain.test"
```

- [x] **Step 7: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppAuthFlowIntegrationTest.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java docs/集成测试.md scripts-gate-integration.sh
git commit -m "test(supply-chain): gate project app auth end to end"
```

---

## 完成标准

全部任务完成并提交后，本切片交付：INSTORE 四组 API 的 appId/appKey 统一认证（入站验签、
凭证链、防重放、可信上下文注入）与发布于 API 模块的签名客户端；后续切片（SaaS 收银台 mock、
`aifuli-channel-rtmart-server`）以 `ProjectAppAuthSigner`/`ProjectAppAuthCredentials` 调用，
无需再各自实现签名协议。
