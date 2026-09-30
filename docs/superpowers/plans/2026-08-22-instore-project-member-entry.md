# INSTORE 项目化会员资料与入口凭证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让采购商用户按可信采购项目获取大润发 HOME/DETAIL 最终入口 URL，并让旅通使用一次性 token 按冻结的 `MEMBER_PROFILE` 档案解析 SaaS 会员资料，全程保持 `buyerUserId` 为不透明字符串。

**Architecture:** RPC DTO 不接收项目与 SaaS 路由字段，`ApiImpl` 只从失败关闭的 `ProjectCallContextAccessor` 取得可信上下文并转换为 Facade 命令。新凭证冻结 `projectId + memberProfileVersionId + companyCode`；Facade 在本地事务之外调用 `RtmartEntryUrlPort` 或 `BuyerMemberProfilePort`，URL 失败撤销 token，会员查询失败在未过期时释放 token。

**Tech Stack:** Java 17、Spring Boot、Spring Cloud OpenFeign、MyBatis Plus、Liquibase、JUnit 5、Mockito、AssertJ、ArchUnit、Testcontainers MySQL 8.4

**Design baseline:** `docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md`

---

## 文件结构与职责

本计划锁定以下新边界，实施时不要把职责合并回 Facade 或 Mapper：

```text
aifuli-module-supply-chain-api/api/instore
├── InstoreBuyerEntryApi.java                 # SaaS 商城 HOME/DETAIL 稳定 RPC
├── InstoreChannelMemberApi.java              # 旅通接口①稳定 RPC
└── dto/*                                     # 不含 projectId/sourceSystem/externalTenantId

aifuli-module-supply-chain-server
├── infrastructure/security
│   ├── ProjectCallContextAccessor.java       # 可信买家/渠道上下文端口
│   └── ProjectCallContextConfiguration.java  # 未接认证 Adapter 时失败关闭
├── integration/buyer
│   ├── BuyerMemberProfilePort.java           # SaaS 会员资料出站端口
│   └── BuyerIntegrationRouteResolver.java    # current/frozen 两种路由解析
├── integration/rtmart
│   └── RtmartEntryUrlPort.java               # HOME/DETAIL 最终 URL 出站端口
├── border/instore/application
│   ├── InstoreChannelUserBindingResolver.java# 项目会员稳定绑定
│   ├── InstoreEntryCredentialService.java    # 凭证签发、抢占、释放、消费、撤销
│   └── InstoreEntryAccessService.java        # companyCode 与订单详情只读校验
└── application/facade
    ├── InstoreBuyerEntryFacade.java          # 买家入口事务外编排
    └── InstoreChannelMemberFacade.java       # 会员解析事务外编排
```

所有新重要类、公共方法和状态转换必须写中文职责/不变量注释，并链接
`docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md` 的对应章节。DTO 字段注释与 018 DDL `COMMENT` 使用相同业务表述；第三方调用入口、脱敏请求摘要、结果分类和耗时按仓库日志规范记录。

### Task 1: 固化 RPC、错误目录与可信上下文合同

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/InstoreBuyerEntryApi.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/InstoreChannelMemberApi.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/HomeEntryReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/OrderDetailEntryReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/InstoreEntryUrlRespDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/ResolveInstoreMemberReqDTO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/api/instore/dto/InstoreMemberRespDTO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextAccessor.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfiguration.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/api/instore/InstoreMemberEntryApiContractTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextConfigurationTest.java`

- [ ] **Step 1: 写 API 字段边界和错误映射失败测试**

契约测试用反射和 Bean Validation 锁定以下字段，不只检查 JSON 样例：

```java
assertThat(fieldTypes(HomeEntryReqDTO.class))
        .containsEntry("companyCode", String.class)
        .containsEntry("longitude", String.class)
        .containsEntry("latitude", String.class);
assertThat(fieldTypes(OrderDetailEntryReqDTO.class))
        .containsExactly(entry("orderNo", String.class));
assertThat(fieldTypes(ResolveInstoreMemberReqDTO.class))
        .containsExactly(entry("token", String.class));
assertThat(fieldTypes(InstoreMemberRespDTO.class))
        .containsEntry("userCode", String.class)
        .containsEntry("mobile", String.class)
        .containsEntry("userName", String.class)
        .containsEntry("companyCode", String.class)
        .doesNotContainKeys("projectId", "buyerId", "sourceSystem",
                "externalTenantId", "memberProfileVersionId");
```

在 `SupplyChainErrorMappingTest` 断言 043～046 和处置指令：

```java
assertThat(retryDirective(INSTORE_CREDENTIAL_REVOKED.getCode()))
        .isEqualTo(RetryDirective.DO_NOT_RETRY);
assertThat(retryDirective(INSTORE_MEMBER_PROFILE_INVALID.getCode()))
        .isEqualTo(RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE);
assertThat(retryDirective(INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode()))
        .isEqualTo(RetryDirective.RETRY_SAME_KEY);
assertThat(retryDirective(INSTORE_ENTRY_URL_UNAVAILABLE.getCode()))
        .isEqualTo(RetryDirective.RETRY_SAME_KEY);
```

- [ ] **Step 2: 运行 API 测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
  -DskipTests=false \
  -Dtest=InstoreMemberEntryApiContractTest,SupplyChainErrorMappingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 `api.instore` 契约和 043～046 尚不存在。

- [ ] **Step 3: 实现最小 RPC DTO 与接口**

DTO 使用 Lombok `@Data`、`Serializable`、Swagger Schema 和明确长度校验。请求必填规则为：

```java
public class HomeEntryReqDTO implements Serializable {
    @NotBlank @Size(max = 64) private String companyCode;
    @NotBlank @Size(max = 32) private String longitude;
    @NotBlank @Size(max = 32) private String latitude;
}

public class OrderDetailEntryReqDTO implements Serializable {
    @NotBlank @Size(max = 32) private String orderNo;
}

public class ResolveInstoreMemberReqDTO implements Serializable {
    @NotBlank @Size(max = 256) private String token;
}
```

两个 API 的稳定方法签名固定为：

```java
CommonResult<InstoreEntryUrlRespDTO> getHomeEntry(@Valid @RequestBody HomeEntryReqDTO request);
CommonResult<InstoreEntryUrlRespDTO> getOrderDetailEntry(
        @Valid @RequestBody OrderDetailEntryReqDTO request);

CommonResult<InstoreMemberRespDTO> resolveMember(
        @Valid @RequestBody ResolveInstoreMemberReqDTO request);
```

路径分别使用 `${ApiConstants.PREFIX}/instore/buyer-entry/home|detail` 和
`${ApiConstants.PREFIX}/instore/channel-member/resolve`。响应 DTO 不接受内部 ID；`userName` 不加 `@NotBlank`。

- [ ] **Step 4: 增加错误码和失败关闭上下文端口**

错误目录追加：

```java
ErrorCode INSTORE_CREDENTIAL_REVOKED = error(1_050_001_043, "到店购入口凭证已撤销");
ErrorCode INSTORE_MEMBER_PROFILE_INVALID = error(1_050_001_044, "到店购会员资料无效");
ErrorCode INSTORE_MEMBER_PROFILE_UNAVAILABLE = error(1_050_001_045, "到店购会员资料暂时不可用");
ErrorCode INSTORE_ENTRY_URL_UNAVAILABLE = error(1_050_001_046, "到店购入口地址暂时不可用");
```

上下文合同使用两个互斥记录，避免 nullable 大对象：

```java
public interface ProjectCallContextAccessor {
    BuyerContext requireBuyerContext();
    ChannelContext requireChannelContext();

    record BuyerContext(Long projectId, Long buyerId, String buyerUserId) {
        public BuyerContext {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(buyerId, "buyerId");
            if (buyerUserId == null || buyerUserId.isBlank()) {
                throw new IllegalArgumentException("buyerUserId must not be blank");
            }
        }
    }
    record ChannelContext(Long projectId, String channelCode) {
        public ChannelContext {
            Objects.requireNonNull(projectId, "projectId");
            if (channelCode == null || channelCode.isBlank()) {
                throw new IllegalArgumentException("channelCode must not be blank");
            }
        }
    }
}
```

`ProjectCallContextConfiguration` 用 `@ConditionalOnMissingBean(ProjectCallContextAccessor.class)` 注册默认实现；两个方法都直接 `throw exception(BUYER_AUTH_INVALID)`。不得读取 DTO、普通 Header、租户 ThreadLocal 或把 `buyerUserId` 转为 `Long`。

用 `ApplicationContextRunner` 验证默认 Bean 的两个 `require*Context()` 都抛 001，并验证测试提供自定义 `ProjectCallContextAccessor` 时默认 Bean 不再注册。

- [ ] **Step 5: 运行 API 契约和 server 编译确认 GREEN**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
  -DskipTests=false \
  -Dtest=InstoreMemberEntryApiContractTest,SupplyChainErrorMappingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=ProjectCallContextConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 两个 API 测试类和上下文配置测试全部 PASS，server 模块能够编译默认上下文配置。

- [ ] **Step 6: 提交契约切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/security \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/security
git commit -m "feat(supply-chain): define instore member entry contracts"
```

### Task 2: 增加 current/frozen 路由和两个出站 Port

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolver.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationProfileMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationProfileRetiredException.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerMemberProfilePort.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlPort.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/configuration/InstoreEntryPortConfiguration.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstorePaymentConvergenceService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolverTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerMemberProfilePortContractTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/rtmart/RtmartEntryUrlPortContractTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/configuration/InstoreEntryPortConfigurationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstorePaymentConvergenceTest.java`

- [ ] **Step 1: 写 current/frozen 路由失败测试**

新增用例必须覆盖：current 只接受 Profile ACTIVE + currentVersion PUBLISHED；frozen 接受 PUBLISHED/SUPERSEDED；RETIRED 抛专用异常；DRAFT/VALIDATED 拒绝。

```java
when(profileMapper.selectByProjectId(101L)).thenReturn(activeProfileWithCurrent(800L, 101L, 900L));
when(versionMapper.selectById(900L)).thenReturn(publishedVersion(900L, 800L));
assertThat(resolver.resolveCurrent(101L, MEMBER_PROFILE).profileVersionId()).isEqualTo(900L);

when(versionMapper.selectById(901L)).thenReturn(version(901L, 800L, SUPERSEDED));
assertThat(resolver.resolveFrozen(101L, 901L, MEMBER_PROFILE).profileVersionId()).isEqualTo(901L);

when(versionMapper.selectById(902L)).thenReturn(version(902L, 800L, RETIRED));
assertThatThrownBy(() -> resolver.resolveFrozen(101L, 902L, MEMBER_PROFILE))
        .isInstanceOf(BuyerIntegrationProfileRetiredException.class);
```

- [ ] **Step 2: 写两个 Port 的失败契约测试**

`BuyerMemberProfilePort` 的命令只接受 MEMBER_PROFILE 路由和 String 用户号；`RtmartEntryUrlPort` 的命令不得出现 appKey/privateKey/sign/nonce：

```java
var command = new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), "001234");
assertThat(command.buyerUserId()).isEqualTo("001234");
assertThat(command.route().sourceSystem()).isEqualTo("AIFULI_MALL");

assertThat(recordFields(RtmartEntryUrlPort.HomeUrlCommand.class))
        .contains("projectId", "channelCode", "companyCode", "longitude", "latitude", "token")
        .doesNotContain("appKey", "privateKey", "sign", "nonce", "timestamp");
```

- [ ] **Step 3: 运行路由与 Port 测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=BuyerIntegrationRouteResolverTest,BuyerMemberProfilePortContractTest,RtmartEntryUrlPortContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 current/frozen API 和两个 Port 不存在。

- [ ] **Step 4: 实现确定的路由语义**

`BuyerIntegrationProfileMapper` 增加 Lambda 查询：

```java
default BuyerIntegrationProfileDO selectByProjectId(Long projectId) {
    return selectOne(new LambdaQueryWrapper<BuyerIntegrationProfileDO>()
            .eq(BuyerIntegrationProfileDO::getProjectId, projectId));
}
```

Resolver 公共方法固定为：

```java
BuyerIntegrationRoute resolveCurrent(Long projectId, BuyerIntegrationCapabilityCode capability);
BuyerIntegrationRoute resolveFrozen(Long projectId, Long profileVersionId,
                                    BuyerIntegrationCapabilityCode capability);
```

二者共用私有 `resolveVersion`，都要求档案 ACTIVE，但版本状态集合不同：current 仅 PUBLISHED，frozen 为 PUBLISHED/SUPERSEDED。RETIRED 在读取能力前抛 `BuyerIntegrationProfileRetiredException`；现有 CASHIER 两个生产调用点改为 `resolveFrozen`，不保留语义含糊的 `resolve` 调用。

- [ ] **Step 5: 实现 Port 记录类型和失败关闭默认 Bean**

核心合同为：

```java
public interface BuyerMemberProfilePort {
    MemberProfileResult resolve(ResolveMemberCommand command);
    record ResolveMemberCommand(BuyerIntegrationRoute route, String buyerUserId) {
        public ResolveMemberCommand {
            if (route == null || route.capability() != MEMBER_PROFILE) {
                throw new IllegalArgumentException("route capability must be MEMBER_PROFILE");
            }
            if (buyerUserId == null || buyerUserId.isBlank()) {
                throw new IllegalArgumentException("buyerUserId must not be blank");
            }
        }
    }
    record MemberProfileResult(MemberResolutionStatus status, String mobile, String userName) {
        public MemberProfileResult {
            Objects.requireNonNull(status, "status");
        }
    }
    enum MemberResolutionStatus { FOUND, NOT_FOUND, UNAVAILABLE }
}

public interface RtmartEntryUrlPort {
    EntryUrlResult getHomeUrl(HomeUrlCommand command);
    EntryUrlResult getDetailUrl(DetailUrlCommand command);
    record HomeUrlCommand(Long projectId, String channelCode, String companyCode,
                          String longitude, String latitude, String token) {
        public HomeUrlCommand {
            Objects.requireNonNull(projectId, "projectId");
            requireText(channelCode, "channelCode");
            requireText(companyCode, "companyCode");
            requireText(longitude, "longitude");
            requireText(latitude, "latitude");
            requireText(token, "token");
        }
    }
    record DetailUrlCommand(Long projectId, String channelCode, String companyCode,
                            String tradeId, String token) {
        public DetailUrlCommand {
            Objects.requireNonNull(projectId, "projectId");
            requireText(channelCode, "channelCode");
            requireText(companyCode, "companyCode");
            requireText(tradeId, "tradeId");
            requireText(token, "token");
        }
    }
    record EntryUrlResult(String entryUrl) {
        public EntryUrlResult {
            requireText(entryUrl, "entryUrl");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
```

`InstoreEntryPortConfiguration` 使用两个 `@ConditionalOnMissingBean`：默认会员 Port 返回 `UNAVAILABLE`，默认 URL Port 抛带固定消息的 `IllegalStateException`。默认 Bean 不伪造手机号、姓名或 URL；真实 Adapter 后续可无侵入替换。

`InstoreEntryPortConfigurationTest` 用 `ApplicationContextRunner` 断言默认两个 Bean 失败关闭，并分别注入自定义 Port，确认条件 Bean 被替换且没有重复 Bean。

- [ ] **Step 6: 运行目标测试和 CASHIER 回归确认 GREEN**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=BuyerIntegrationRouteResolverTest,BuyerMemberProfilePortContractTest,RtmartEntryUrlPortContractTest,InstoreEntryPortConfigurationTest,InstoreChannelCommandFacadeTest,InstorePaymentConvergenceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 7: 提交路由与 Port 切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstorePaymentConvergenceService.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade
git commit -m "feat(supply-chain): resolve frozen instore member routes"
```

### Task 3: 用 Liquibase 018 固化凭证项目、档案和撤销状态

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/018-instore-member-entry-route.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreEntryCredentialDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialStatus.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreCredentialRevokeReason.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreEntryCredentialMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreMemberEntryRouteMigrationIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`

- [ ] **Step 1: 写 017→018 真实 MySQL 迁移失败测试**

测试使用独占数据库并先 `liquibase.update(17, ...)`，插入四种历史凭证后执行剩余迁移，断言：

```java
assertRequiredColumn(jdbc, "sc_instore_entry_credential", "project_id");
assertNullableColumn(jdbc, "sc_instore_entry_credential", "member_profile_version_id");
assertNullableColumn(jdbc, "sc_instore_entry_credential", "company_code");
assertThat(statusOf("IEC_ACTIVE")).isEqualTo((short) 40);
assertThat(revokeReasonOf("IEC_ACTIVE")).isEqualTo("MIGRATION_UNFROZEN_ROUTE");
assertThat(statusOf("IEC_PROCESSING")).isEqualTo((short) 40);
assertThat(statusOf("IEC_USED")).isEqualTo((short) 20);
assertThat(statusOf("IEC_EXPIRED")).isEqualTo((short) 30);
```

另建绑定缺失凭证，断言永久列创建前迁移失败；新 ACTIVE 缺版本或 companyCode 时 CHECK 拒绝。

- [ ] **Step 2: 运行迁移测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreMemberEntryRouteMigrationIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: FAIL，原因是 018 和新列尚不存在。

- [ ] **Step 3: 编写失败关闭的 018 SQL**

SQL 必须先创建临时 guard，验证每条凭证唯一关联非空 `project_id` 的稳定绑定，再做永久 DDL。永久迁移按以下顺序执行：

```sql
ALTER TABLE sc_instore_entry_credential
    ADD COLUMN project_id BIGINT NULL COMMENT '凭证所属采购项目内部主键' AFTER binding_id,
    ADD COLUMN member_profile_version_id BIGINT NULL
        COMMENT '签发时冻结的MEMBER_PROFILE档案版本内部主键' AFTER project_id,
    ADD COLUMN company_code VARCHAR(64) NULL
        COMMENT '接口①返回和旅通入口使用的企业组快照' AFTER member_profile_version_id,
    ADD COLUMN revoked_at DATETIME(3) NULL COMMENT '凭证撤销时间，UTC' AFTER used_at,
    ADD COLUMN revoke_reason VARCHAR(64) NULL COMMENT '稳定撤销原因' AFTER revoked_at;

UPDATE sc_instore_entry_credential iec
JOIN sc_instore_channel_user_binding iub ON iub.id = iec.binding_id
SET iec.project_id = iub.project_id;

UPDATE sc_instore_entry_credential
SET status = 40,
    revoked_at = UTC_TIMESTAMP(3),
    revoke_reason = 'MIGRATION_UNFROZEN_ROUTE',
    processing_started_at = NULL,
    updated_at = UTC_TIMESTAMP(3),
    version = version + 1
WHERE status IN (10, 15);
```

随后 `project_id` 改 NOT NULL，旧状态 CHECK 替换为 `(10,15,20,30,40)`，增加：

```sql
CHECK (status NOT IN (10, 15)
       OR (member_profile_version_id IS NOT NULL AND company_code IS NOT NULL));
CHECK ((status = 40 AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
       OR (status <> 40 AND revoked_at IS NULL AND revoke_reason IS NULL));
```

增加 `(project_id,status,expires_at)`、`(member_profile_version_id,status)` 索引，并在 YAML 注册唯一 `018-instore-member-entry-route` changeSet。

- [ ] **Step 4: 同步 DO、枚举、Mapper 撤销 CAS**

DO 增加 `projectId/memberProfileVersionId/companyCode/revokedAt/revokeReason`，注释逐字对齐 DDL。状态枚举追加 `REVOKED((short) 40)`；撤销原因枚举仅含：

```java
ENTRY_URL_CALL_FAILED,
MEMBER_PROFILE_RETIRED,
MIGRATION_UNFROZEN_ROUTE
```

Mapper 增加：

```java
default int revoke(Long id, long expectedVersion,
                   InstoreCredentialRevokeReason reason, LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
            .set(InstoreEntryCredentialDO::getStatus, REVOKED.getCode())
            .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
            .set(InstoreEntryCredentialDO::getRevokedAt, now)
            .set(InstoreEntryCredentialDO::getRevokeReason, reason.name())
            .set(InstoreEntryCredentialDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreEntryCredentialDO::getId, id)
            .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
            .in(InstoreEntryCredentialDO::getStatus, ACTIVE.getCode(), PROCESSING.getCode()));
}
```

- [ ] **Step 5: 运行迁移与 Liquibase 幂等测试确认 GREEN**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreMemberEntryRouteMigrationIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: 空库执行 18 个 changeSet，017→018 迁移正确，二次启动执行 0 个新 changeSet。

- [ ] **Step 6: 提交迁移切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreMemberEntryRouteMigrationIntegrationTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java
git commit -m "feat(supply-chain): persist instore member entry route"
```

### Task 4: 把稳定会员绑定彻底切到项目作用域

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolverTest.java`

- [ ] **Step 1: 把现有测试改成项目键并先确认 RED**

目标调用为：

```java
resolver.resolveOrCreateStableBinding(
        1001L, 101L, "opaque-user", "RT_MART", memberRoute("tenant-a"));
```

覆盖两个项目共享 `tenant-a + opaque-user` 不合并、已有绑定跨 buyer 失败、停用失败、并发 DuplicateKey 只按项目键回读、路由切换 CAS 更新快照，以及 `selectByTenantUser` 从未调用。

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false -Dtest=InstoreChannelUserBindingResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 Resolver 仍接收来源租户四元组。

- [ ] **Step 2: 增加路由快照 CAS Mapper 方法**

```java
default int updateMemberRouteSnapshot(Long id, long expectedVersion,
                                      String sourceSystem, String externalTenantId,
                                      LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreChannelUserBindingDO>()
            .set(InstoreChannelUserBindingDO::getSourceSystem, sourceSystem)
            .set(InstoreChannelUserBindingDO::getExternalTenantId, externalTenantId)
            .set(InstoreChannelUserBindingDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreChannelUserBindingDO::getId, id)
            .eq(InstoreChannelUserBindingDO::getVersion, expectedVersion));
}
```

- [ ] **Step 3: 实现项目化 Resolver**

新签名必须校验 `memberRoute.capability() == MEMBER_PROFILE` 且 route.projectId 等于参数。查询和 DuplicateKey 回读只使用：

```java
bindingMapper.selectByProjectUser(projectId, buyerUserId, channelCode);
```

创建时写入 `buyerId/projectId/buyerUserId/channelCode` 和 route 的 `sourceSystem/externalTenantId`。复用时校验全部业务字段；路由快照不同则执行一次 CAS，影响行数不是 1 抛 `INSTORE_CONCURRENT_UPDATE`。`BindingSnapshot` 固定包含：

```java
record BindingSnapshot(Long bindingId, Long buyerId, Long projectId,
                       String sourceSystem, String externalTenantId,
                       String buyerUserId, String channelCode, long version) {}
```

- [ ] **Step 4: 运行 Resolver 测试确认 GREEN**

重复 Step 1 命令。Expected: 全部 PASS，Mockito 验证生产路径不调用 deprecated 方法。

- [ ] **Step 5: 提交绑定切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolverTest.java
git commit -m "refactor(supply-chain): bind instore members by project"
```

### Task 5: 实现冻结路由的一次性凭证完整状态机

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialServiceTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialIntegrationTest.java`

- [ ] **Step 1: 重写 Service 单测锁定新合同**

签发合同：

```java
IssuedCredential issueHome(Long buyerId, Long projectId, String buyerUserId,
                           String channelCode, BuyerIntegrationRoute memberRoute,
                           String companyCode);
IssuedCredential issueDetail(Long buyerId, Long projectId, String buyerUserId,
                             String channelCode, BuyerIntegrationRoute memberRoute,
                             String companyCode, Long instoreOrderId);
```

消费合同：

```java
ClaimedCredential claimForMemberLookup(Long projectId, String channelCode, String rawToken);
void markLookupSucceeded(Long credentialId, long processingVersion);
void releaseAfterLookupFailure(Long credentialId, long processingVersion);
void revoke(Long credentialId, long expectedVersion, InstoreCredentialRevokeReason reason);
```

测试捕获插入 DO，断言冻结字段存在；项目/渠道不匹配、用途脏值和 REVOKED 都在 claim CAS 前失败；释放在过期时转 EXPIRED；所有 CAS 影响行数不是 1 均返回 `INSTORE_CONCURRENT_UPDATE`。

- [ ] **Step 2: 运行 Service 测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false -Dtest=InstoreEntryCredentialServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: 实现签发和 claim 快照**

签发先调用项目化 Binding Resolver，再写：

```java
.projectId(projectId)
.memberProfileVersionId(memberRoute.profileVersionId())
.companyCode(companyCode)
.status(InstoreEntryCredentialStatus.ACTIVE.getCode())
```

`IssuedCredential` 必须返回 `credentialId/credentialNo/credentialVersion/token/purpose/instoreOrderId/expiresAt`，以便 URL 外呼失败后按原版本撤销。`ClaimedCredential` 必须携带 `projectId/memberProfileVersionId/companyCode/buyerUserId/channelCode/purpose/instoreOrderId`，不再要求请求提交 `sourceSystem/externalTenantId` 或 expectedPurpose。

claim 顺序固定为：摘要查询 → 项目/渠道/用途校验 → 终态判断 → 过期 CAS → ACTIVE→PROCESSING CAS。REVOKED 返回 043；HOME 和 DETAIL 都是合法会员查询用途。

- [ ] **Step 4: 实现释放、消费和撤销**

这些写方法保持单条 CAS，并用 `@Transactional(transactionManager = "supplyChainTransactionManager", propagation = REQUIRES_NEW)` 明确补偿事务。`revoke` 允许从 ACTIVE 或 PROCESSING 进入 REVOKED；`markLookupSucceeded` 只允许 PROCESSING→USED；释放只允许 PROCESSING→ACTIVE/EXPIRED。

- [ ] **Step 5: 更新真实 MySQL 并发测试**

测试先插入两个项目、两个 MEMBER_PROFILE 档案版本，再签发：

```java
IssuedCredential first = service.issueHome(
        1001L, 101L, "opaque-user", "RT_MART", memberRoute(101L, 901L), "COMPANY_A");
ConcurrentResults<ClaimedCredential> claims = concurrent(2,
        () -> service.claimForMemberLookup(101L, "RT_MART", first.token()));
assertThat(claims.successes()).hasSize(1);
assertThat(claims.failures()).hasSize(1);
```

再断言同一外部租户的另一个项目不能消费 token、URL 失败撤销后不能 claim、两枚 token 相互独立、数据库只有 32 字节摘要。

- [ ] **Step 6: 运行单测与真实 MySQL 测试确认 GREEN**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false -Dtest=InstoreEntryCredentialServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreEntryCredentialIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

- [ ] **Step 7: 提交凭证状态机切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialService.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialServiceTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialIntegrationTest.java
git commit -m "feat(supply-chain): freeze instore member credentials"
```

### Task 6: 建立 HOME 与订单详情的只读校验边界

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerProjectAccessResolver.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/dal/mysql/SupplyChainOrderMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstorePaymentFlowMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/dataobject/SupplierChannelBindingDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/mysql/SupplierChannelBindingMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryAccessService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerProjectAccessResolverTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryAccessServiceTest.java`

- [ ] **Step 1: 写项目和详情读取失败测试**

覆盖：项目不存在/非启用/不属于 buyer；companyCode 无 RT_MART 启用绑定；orderNo 不存在；订单类型非 INSTORE；项目、buyer、buyerUserId 任一不匹配；详情缺失；支付流水缺失或 tradeId 空。订单状态不作为拒绝条件。

```java
DetailEntrySnapshot detail = service.requireDetail(
        "SCO_1", 101L, 1001L, "opaque-user");
assertThat(detail).isEqualTo(new DetailEntrySnapshot(
        77L, "RT_MART", "COMPANY_A", "TRADE_1"));
```

- [ ] **Step 2: 运行读取服务测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=BuyerProjectAccessResolverTest,InstoreEntryAccessServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: 实现 Mapper Lambda 查询**

```java
default SupplyChainOrderDO selectByOrderNo(String orderNo) {
    return selectOne(new LambdaQueryWrapper<SupplyChainOrderDO>()
            .eq(SupplyChainOrderDO::getOrderNo, orderNo));
}

default InstorePaymentFlowDO selectByInstoreOrder(Long instoreOrderId) {
    return selectOne(new LambdaQueryWrapper<InstorePaymentFlowDO>()
            .eq(InstorePaymentFlowDO::getInstoreOrderId, instoreOrderId));
}
```

- [ ] **Step 4: 实现项目和 INSTORE 读取服务**

`BuyerProjectAccessResolver.requireActive(projectId,buyerId)` 只返回 `BuyerProjectStatus.ACTIVE` 且属于 buyer 的项目快照；项目缺失、停用或归属不符统一返回 `BUYER_AUTH_INVALID`，避免泄露项目事实。`InstoreEntryAccessService` 可依赖 Mapper，但 Facade 不可依赖 Mapper；它提供：

```java
void requireEnabledCompany(String channelCode, String companyCode);
DetailEntrySnapshot requireDetail(String orderNo, Long projectId,
                                  Long buyerId, String buyerUserId);
```

DETAIL 加载顺序为总订单 → INSTORE 详情 → 唯一支付流水；只使用 `SupplyChainOrderTypes.ORDER_TYPE_INSTORE` 和 `InstoreChannelCode.RT_MART.name()` 常量。缺失映射到 029/031/032，归属冲突映射到 028，禁止新增业务魔法值。

同步把 `SupplierChannelBindingDO/Mapper` 的“O2O 专用”旧注释修正为“O2O 与 INSTORE 共用的渠道商户绑定”，不改变表结构或查询语义。

- [ ] **Step 5: 运行读取服务测试确认 GREEN**

重复 Step 2 命令。

- [ ] **Step 6: 提交读取边界切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerProjectAccessResolver.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/dataobject/SupplierChannelBindingDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/dal/mysql/SupplierChannelBindingMapper.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/dal/mysql/SupplyChainOrderMapper.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerProjectAccessResolverTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryAccessServiceTest.java
git commit -m "feat(supply-chain): validate instore buyer entry access"
```

### Task 7: 编排买家 HOME/DETAIL 最终入口 URL

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerEntryFacade.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerEntryApiImpl.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerEntryFacadeTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerEntryApiImplTest.java`

- [ ] **Step 1: 写 HOME/DETAIL Facade 失败测试**

HOME 顺序用 Mockito `InOrder` 锁定：项目校验 → companyCode 校验 → `resolveCurrent(MEMBER_PROFILE)` → 凭证签发 → URL Port。DETAIL 断言只把 `orderNo` 交给读取服务，并用返回的订单快照 companyCode/tradeId 调 Port。

```java
assertThat(facade.getHomeEntry(new HomeEntryCommand(
        101L, 1001L, "opaque-user", "COMPANY_A", "121.1", "31.2")))
        .extracting(InstoreEntryUrlRespDTO::getEntryUrl)
        .isEqualTo("https://rtmart.example/home");
```

URL Port 抛异常时断言：使用刚签发的 `credentialId + credentialVersion` 和 `ENTRY_URL_CALL_FAILED` 撤销；返回 046；第二次业务调用签发新 token。校验失败时不签发、不外呼。

- [ ] **Step 2: 写 ApiImpl 上下文失败测试**

ApiImpl 测试断言未注入上下文时传播 001；成功时将 `BuyerContext` 与 DTO 转换成 Facade 命令，日志不含 `buyerUserId` 或 token。Facade 命令固定为：

```java
record HomeEntryCommand(Long projectId, Long buyerId, String buyerUserId,
                        String companyCode, String longitude, String latitude) {}
record DetailEntryCommand(Long projectId, Long buyerId, String buyerUserId,
                          String orderNo) {}
```

- [ ] **Step 3: 运行 Facade 和 ApiImpl 测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=InstoreBuyerEntryFacadeTest,InstoreBuyerEntryApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: 实现无外部事务的买家入口 Facade**

Facade 类本身不加 `@Transactional`。HOME 调用：

```java
projectAccessResolver.requireActive(command.projectId(), command.buyerId());
entryAccessService.requireEnabledCompany(RT_MART.name(), command.companyCode());
BuyerIntegrationRoute route = routeResolver.resolveCurrent(command.projectId(), MEMBER_PROFILE);
IssuedCredential issued = credentialService.issueHome(
        command.buyerId(), command.projectId(), command.buyerUserId(), RT_MART.name(),
        route, command.companyCode());
EntryUrlResult result = entryUrlPort.getHomeUrl(new HomeUrlCommand(
        command.projectId(), RT_MART.name(), command.companyCode(),
        command.longitude(), command.latitude(), issued.token()));
```

DETAIL 先校验项目，再以 `orderNo + projectId + buyerId + buyerUserId` 取得 `DetailEntrySnapshot`，随后解析当前 MEMBER_PROFILE 版本并调用 `issueDetail`。URL 命令只使用快照的 `channelCode/companyCode/tradeId/instoreOrderId`，不读取 DTO 中不存在的 tradeId/companyCode。捕获 URL 外呼异常后先撤销、再构造 046 `ServiceException`，通过 `initCause` 保留原异常；日志只记录项目、渠道、凭证业务号、用途、耗时和错误分类，不记录 token 或最终 URL 参数。

- [ ] **Step 5: 实现本地 RPC Adapter**

`@RestController @Primary @Validated` 的 ApiImpl 从 `ProjectCallContextAccessor.requireBuyerContext()` 取上下文，构造 Facade 命令并 `return success(...)`。ApiImpl 不注入 Resolver、Service 或 Mapper。

- [ ] **Step 6: 运行目标测试确认 GREEN**

重复 Step 3 命令。

- [ ] **Step 7: 提交买家入口切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerEntryFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerEntryApiImpl.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerEntryFacadeTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreBuyerEntryApiImplTest.java
git commit -m "feat(supply-chain): issue routed instore entry urls"
```

### Task 8: 编排旅通接口①会员资料解析

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacade.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelMemberApiImpl.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacadeTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelMemberApiImplTest.java`

- [ ] **Step 1: 写会员编排状态矩阵失败测试**

测试命令只含可信渠道上下文和原 token：

```java
record ResolveMemberCommand(Long projectId, String channelCode, String token) {}
```

覆盖矩阵：

| 路由/Port 结果 | 凭证结果 | API 错误/响应 |
|---|---|---|
| frozen + FOUND + mobile | USED | 返回原 buyerUserId/mobile/userName/companyCode |
| FOUND + mobile 空 | ACTIVE 或 EXPIRED | 044 |
| NOT_FOUND | ACTIVE 或 EXPIRED | 044 |
| UNAVAILABLE/null/异常 | ACTIVE 或 EXPIRED | 045，异常保留 cause |
| frozen 版本 RETIRED | REVOKED | 043，不调用 Port |
| 其他路由解析失败 | ACTIVE 或 EXPIRED | 045 |
| markUsed/release/revoke CAS 失败 | 不返回会员资料 | 041 |

并断言 Port 收到的身份三元组来自冻结 route：

```java
assertThat(portCommand.route().sourceSystem()).isEqualTo("AIFULI_MALL");
assertThat(portCommand.route().externalTenantId()).isEqualTo("tenant-a");
assertThat(portCommand.buyerUserId()).isEqualTo("001234");
```

- [ ] **Step 2: 写渠道 ApiImpl 失败测试**

断言 ApiImpl 只读取 `ChannelContext(projectId,channelCode)`，DTO 只提供 token；入口日志不打印 token，响应 `userCode` 不转换为 Long。

- [ ] **Step 3: 运行会员 Facade 和 ApiImpl 测试确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=InstoreChannelMemberFacadeTest,InstoreChannelMemberApiImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: 实现 claim→外呼→消费/释放编排**

Facade 类不加事务，固定顺序：

```java
ClaimedCredential claimed = credentialService.claimForMemberLookup(
        command.projectId(), command.channelCode(), command.token());
BuyerIntegrationRoute route = routeResolver.resolveFrozen(
        claimed.projectId(), claimed.memberProfileVersionId(), MEMBER_PROFILE);
MemberProfileResult result = memberProfilePort.resolve(
        new ResolveMemberCommand(route, claimed.buyerUserId()));
```

FOUND 且手机号非空时先 `markLookupSucceeded`，成功后才组装 DTO：

```java
response.setUserCode(claimed.buyerUserId());
response.setMobile(result.mobile());
response.setUserName(result.userName());
response.setCompanyCode(claimed.companyCode());
```

RETIRED 捕获后用 `MEMBER_PROFILE_RETIRED` 撤销 PROCESSING；其他路由/Port 异常调用 `releaseAfterLookupFailure`。手机号、姓名、token、完整外部正文不得进日志；第三方调用只记录路由摘要、结果分类和耗时。

- [ ] **Step 5: 实现渠道 RPC Adapter**

`InstoreChannelMemberApiImpl` 仅依赖 ContextAccessor 和 Facade，取得渠道上下文后委派并 `success(response)`；不得从 token 之外的 DTO 字段恢复 projectId/channelCode。

- [ ] **Step 6: 运行目标测试确认 GREEN**

重复 Step 3 命令。

- [ ] **Step 7: 提交会员解析切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelMemberApiImpl.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelMemberFacadeTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/rpc/InstoreChannelMemberApiImplTest.java
git commit -m "feat(supply-chain): resolve routed instore members"
```

### Task 9: 补齐端到端门禁、架构约束和联调文档

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreMemberEntryFlowIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`
- Modify: `docs/集成测试.md`
- Modify: `docs/供应链中台INSTORE到店购渠道接入实现总结.md`

- [ ] **Step 1: 写测试 Adapter 驱动的真实 MySQL 纵向测试**

测试配置导入真实 Mapper/Service/Facade，提供两个确定性 Test Adapter：

```java
@Bean
BuyerMemberProfilePort memberPort() {
    return command -> new MemberProfileResult(FOUND, "13800000000", "测试会员");
}

@Bean
RtmartEntryUrlPort entryUrlPort() {
    return new RtmartEntryUrlPort() {
        public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
            return new EntryUrlResult("https://rtmart.test/home?opaque=1");
        }
        public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
            return new EntryUrlResult("https://rtmart.test/detail?opaque=1");
        }
    };
}
```

用数据库准备 project/profile/version/capability/binding/company/order/payment flow，验证：HOME 返回 URL 后 token 摘要可被接口①消费；DETAIL API 只提交 `orderNo`，Test Adapter 收到订单 `tradeId`；两个项目共享 SaaS 租户时不能跨项目消费。

- [ ] **Step 2: 增加 ArchitectureTest 规则**

规则必须检查：

```java
noClasses().that().resideInAPackage("..application.facade..")
        .should().dependOnClassesThat().resideInAnyPackage("..dal..");
noClasses().that().resideInAPackage("..api.instore..")
        .should().dependOnClassesThat().resideInAnyPackage("..integration..", "..dal..");
noClasses().that().resideInAnyPackage("..dal..", "..infrastructure.outbox..", "..infrastructure.timer..")
        .should().dependOnClassesThat().haveSimpleName("InstoreMemberRespDTO");
noClasses().that().resideInAnyPackage("..application..", "..application.facade..")
        .should().callMethod(InstoreChannelUserBindingMapper.class, "selectByTenantUser",
                String.class, String.class, String.class, String.class);
```

保留既有 `buyer_user_id_is_never_long`；方法调用规则与 Resolver Mockito 测试共同保证新生产路径不调用 `selectByTenantUser`。

- [ ] **Step 3: 运行纵向、契约与架构门禁**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=InstoreMemberEntryApiContractTest,BuyerMemberProfilePortContractTest,RtmartEntryUrlPortContractTest,SupplyChainModuleArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreMemberEntryFlowIntegrationTest,InstoreEntryCredentialIntegrationTest,InstoreMemberEntryRouteMigrationIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: 所列单元/契约/架构测试全 PASS；四个真实 MySQL 测试全 PASS；空库为 18 个 changeSet。

- [ ] **Step 4: 更新联调与实现总结**

`docs/集成测试.md` 的 INSTORE 章节记录：项目与 MEMBER_PROFILE 前置数据、HOME、DETAIL、接口①、会员超时同 token 重试、URL 超时新 token、跨项目隔离的操作步骤和 SQL 验证点。`docs/供应链中台INSTORE到店购渠道接入实现总结.md` 把 Schema 更新为 013～018，并将真实 SaaS/旅通 Adapter、认证和退款保留在待完成列表。

- [ ] **Step 5: 运行供应链 API 与 server 全量单测**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
  -DskipTests=false test

mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false test
```

Expected: BUILD SUCCESS，无失败、错误或跳过的供应链必跑测试。

- [ ] **Step 6: 执行最终静态检查**

```bash
git diff --check
rg -n "Long\.valueOf\(.*buyerUserId|Long\.parseLong\(.*buyerUserId|selectByTenantUser" \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java
git diff --unified=0 74e7f97 -- \
  aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main \
  | rg '^\+.*(TODO|TBD|FIXME)'
```

Expected: `git diff --check` 无输出；第一条搜索只允许 deprecated Mapper 方法声明及其兼容注释，不得出现新业务调用；最后一条搜索无输出，即本切片没有新增未完成生产逻辑。

- [ ] **Step 7: 提交门禁与文档**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test \
  docs/集成测试.md \
  docs/供应链中台INSTORE到店购渠道接入实现总结.md
git commit -m "test(supply-chain): verify instore member entry flow"
```

## 实施完成后的下一顺序

本计划通过全部门禁后，下一优先切片仍为：INSTORE 退款串行 → 按原 `refundCaseNo` 权威查询 → 校验实际退款金额 → `BuyerAccountReturnService` 幂等返还。真实 SaaS 会员 Adapter、项目认证和 `aifuli-channel-rtmart-server` 不得夹带进本计划。
