# 标准订单HTTP认证基础实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为标准下单、查单与资产查询建立精确操作注册、按操作项目状态授权、无用户身份签名和可信项目上下文，保留INSTORE认证语义。

**Architecture:** 复用项目HMAC过滤器、密钥解析、nonce与统一操作注册表。API模块只保存操作契约及封闭状态策略，服务端凭证服务读取供应链自有项目状态；控制器后续通过新的标准商品上下文访问器获得可信身份，Facade不读取DAL。

**Tech Stack:** Java21、Spring Boot、既有HMAC-SHA256与MyBatis Plus、JUnit/Mockito/ArchUnit、真实MySQL8.4/Testcontainers。

## 本阶段与主线顺序

依据`docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md`§2/§2.1。

1. 本阶段完成认证基础，独立规格/质量审查通过后进入查询。
2. 项目归属的查单一致快照与资产授权/独立审计；接口严格DTO及错误语义。
3. 内部报价永久冻结适配、标准商品身份移除与项目永久键/路由键数据库增量。
4. 共享受理下单、手机限购/备注、既有资金/路由/限频接入。
5. 完整三接口Controller、Gateway精确路由及原始字节透传、全链敏感日志保护和自动化闭环。

各后续阶段在执行前完善对应详细实施计划。上述是主线顺序，本文件只执行下面Task1，不将认证注册表测试表述为三接口业务已开放。用户暂缓的四项真实环境工作见`docs/待办列表.md`，本阶段不执行。

## Task 1：操作、项目状态授权与可信标准上下文

### 文件职责

API根目录：`aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/`。

- 修改`main/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperations.java`：三精确路径/操作码常量、STANDARD_PRODUCT能力、项目状态策略及兼容构造。
- 修改`main/java/com/aifuli/module/supplychain/api/auth/ProjectAppAuthSigner.java`：仅标准操作省略两个scope身份头，签名串保持原九字段。
- 修改`test/java/com/aifuli/module/supplychain/api/auth/ProjectAuthOperationsContractTest.java`、`ProjectAppAuthSignerTest.java`；需要时扩展同目录`ProjectAppAuthFeignInterceptorTest.java`。

Server根目录：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/`。

- 修改`main/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppCredentialService.java`：新增三参数`authenticate(appId,keyVersion,operationCode)`，从注册表取得项目策略；旧两参数仍只允许ACTIVE。
- 修改同目录`ProjectAppAuthFilter.java`：精确路径/operation匹配后调用三参数认证，保留原签名、能力、nonce、scope处理顺序。
- 修改`main/java/com/aifuli/module/supplychain/infrastructure/security/ProjectCallContextAccessor.java`及`infrastructure/auth/ProjectCallContextAccessorImpl.java`：增加标准快照和操作一致性校验；三型既有访问器不变。
- 更新`ProjectAppAuthConfiguration.java`涉及四前缀的注释，并测试由注册表派生新前缀；不新增外部依赖。
- 修改`test/java/com/aifuli/module/supplychain/infrastructure/auth/ProjectAppCredentialServiceTest.java`、`ProjectAppAuthFilterTest.java`、`ProjectCallContextAccessorImplTest.java`、`ProjectAppAuthConfigurationTest.java`与真实`ProjectAppAuthFlowIntegrationTest.java`。

### 1. 先写真实失败测试

- [ ] 在现有可编译契约测试中，先断言三路径存在，观察旧注册表返回空造成业务断言失败。另验证旧十操作集合独立精确覆盖全部INSTORE接口，不再把整个注册表强制等同于INSTORE集合。

```java
assertThat(ProjectAuthOperations.findByMethodAndPath("POST",
        "/open-api/supply-chain/project/v1/orders/query")).isPresent();
assertThat(ProjectAuthOperations.findByMethodAndPath("GET",
        "/open-api/supply-chain/project/v1/orders/query")).isEmpty();
```

新增测试矩阵：三操作POST、精确路径、1.0.0、NONE、STANDARD_PRODUCT；未知子路径/错误方法不存在；13个码/路径唯一且索引不可变，所有路径被前缀覆盖。原十操作逐字段保持原值。标准签名8个头、签名串scope空、body字节改变签名变化，非空用户/渠道scope拒绝；旧INSTORE仍10个头。

- [ ] 运行API RED并归档新鲜XML和原日志，不能将旧target报告或编译错误算业务RED。

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
-Dtest=ProjectAuthOperationsContractTest,ProjectAppAuthSignerTest,ProjectAppAuthFeignInterceptorTest \
-Dsurefire.failIfNoSpecifiedTests=false test
```

### 2. 统一登记与签名输出

- [ ] 在注册表定义下列稳定契约及默认策略，避免在过滤器或访问器重复维护路径/操作字面量。

```java
public static final String STANDARD_ORDERS_PATH = "/open-api/supply-chain/project/v1/orders";
public static final String STANDARD_ORDER_CREATE = "STANDARD_ORDER_CREATE";
public static final String STANDARD_ORDER_QUERY = "STANDARD_ORDER_QUERY";
public static final String STANDARD_ORDER_ASSET_QUERY = "STANDARD_ORDER_ASSET_QUERY";
public static final String CAPABILITY_STANDARD_PRODUCT = "STANDARD_PRODUCT";
public enum ProjectStatePolicy { ACTIVE_ONLY, ACTIVE_OR_SUSPENDED }
```

Operation新增`projectStatePolicy`组成分量。旧五参数构造继续默认INSTORE与ACTIVE_ONLY，旧六参数默认ACTIVE_ONLY；完整构造的策略和必要契约字段不允许null。增加三标准定义，QUERY使用ACTIVE_OR_SUSPENDED，其余ACTIVE_ONLY，前缀追加STANDARD_ORDERS_PATH。状态编码判断留在服务端使用已存在状态枚举。

SDK通过注册操作的STANDARD_PRODUCT能力决定标准scope头省略；不要改变标准签名canonicalString的最后两个空字段，不要删除INSTORE的身份头或把其输出改为8个。

### 3. 凭证服务与过滤器

- [ ] 补充服务端状态与过滤器行为测试后实现三参数入口。完整状态矩阵：ACTIVE三标准操作允许认证，SUSPENDED仅QUERY允许；DRAFT/CLOSED/ARCHIVED/null及未知编码全部拒绝。旧两参数与原INSTORE任何操作在SUSPENDED仍拒绝。应用停用、凭证吊销/过期、能力停用不因QUERY策略放宽。

三参数入口用`findByOperationCode`解析不可变定义，未知操作返回既有BUYER_AUTH_INVALID；内部复用既有认证链，将项目ACTIVE检查替换为封闭策略。允许ACTIVE或策略允许的SUSPENDED；其他状态一律失败。旧两参数入口走ACTIVE_ONLY，不暴露外部自选策略。

过滤器在路径/头中的操作及版本匹配后使用：

```java
credentialService.authenticate(headers.appId(), headers.keyVersion(), operation.operationCode());
```

签名先于能力/nonce，能力先于nonce；scope校验及ThreadLocal清理沿既有链路。修改旧Mockito stub适应新真实入口，不删原十操作回归，不以伪造身份上下文替代真实生产链。日志仅安全类别、appId/keyVersion及操作，不含密钥/secretRef/body/scope值。

### 4. 标准可信上下文

- [ ] 先测试无认证、旧NONE回调上下文、其他标准操作、无效expectedOperation均失败；正确当前操作返回五项可信值，没有用户/渠道字段。

在ProjectCallContextAccessor增加默认失败关闭方法（便于保留原SPI实现的编译兼容性），并由真实实现覆盖：

```java
default StandardProductContext requireStandardProductContext(String expectedOperationCode) {
    throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
            com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID);
}
record StandardProductContext(Long projectId, Long buyerId, String appId,
                              int keyVersion, String operationCode) { }
```

快照构造需校验正数ID/版本与非空字符串，日志toString只输出项目/分销商/版本/操作；禁止包含appid和任何密钥/用户scope。真实访问器先读取认证快照，验证NONE、STANDARD_PRODUCT及expected操作完全相同，再构造。不得从DTO、普通头、租户上下文或BuyerResolver补身份。旧ProjectContext/ChannelContext/BuyerContext方法保持原合同。

### 5. 验证与范围限制

- [ ] 串行执行下面逻辑/架构与真实MySQL认证回归；API ContractTest命名按Maven现有配置单独用`-Dtest`指定，不遗漏。

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
-Psupply-chain-integration \
-Dtest=ProjectAppCredentialServiceTest,ProjectAppAuthFilterTest,ProjectCallContextAccessorImplTest,ProjectAppAuthConfigurationTest,SupplyChainModuleArchitectureTest \
-Dsurefire.failIfNoSpecifiedTests=false \
-Dit.test=ProjectAppAuthFlowIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```

真实完整应用测试须在Maven进程环境注入测试专用随机32字节身份密钥，引用`env:AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY`，不输出/保存密钥或指纹，不改生产默认配置。原四组API真实认证仍验证；新增标准操作拒绝矩阵可先以缺能力场景验证，无Controller阶段不能把404当已完成业务。不得新增测试Controller冒充标准生产接口。

证据目录`/tmp/standard-order-http-auth`：记录真实命令、基线/实现提交、新鲜XML来源/hash、原日志、RED原因与GREEN精确范围。只跳过供应链之外模块测试，禁止-DskipTests；单个工作树Maven不得并发。

本阶段不修改Gateway/Web日志、Controller、DDL、报价/受理/资金或前端；这些由后续对应任务处理，不对外宣布真实下单开放。不要新增Feign/服务扫描/进程聚合，不削弱ArchUnit。

### 6. 自审、提交与两级审查

- [ ] 重要中文注释链接主设计§2.1，核对新增操作与三种状态策略没有散落字面量；无无上下文TODO或吞异常。
- [ ] 仅暂存上述生产/测试文件，以中文提交，例如`建立标准订单HTTP项目认证与操作上下文`；不暂存root正在更新的设计/待办/集成文档。
- [ ] 规格独立审查通过后再做质量独立审查；有问题由原实施者修复并复评。root按实际证据更新计划与`docs/集成测试.md`，随后进入查询与资产阶段。
