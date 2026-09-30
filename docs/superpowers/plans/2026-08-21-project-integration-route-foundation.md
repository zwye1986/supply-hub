# Project Integration Route Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立采购项目与版本化 SaaS 集成路由的最小可运行基础，使应用层可以用 `projectId + profileVersionId + capability` 可信解析 `sourceSystem + externalTenantId`。

**Architecture:** `tradecore.buyer` 拥有 BuyerProject、项目能力和项目应用身份；`integration.buyer` 拥有项目集成档案、能力版本和 `BuyerIntegrationRouteResolver`。业务唯一性仍由 projectId 决定，externalTenantId 只从固化档案版本解析，不进入采购商侧 DTO。

**Tech Stack:** Java 21, Spring Boot, MyBatis Plus, Liquibase, MySQL 8.4, JUnit 5, Mockito, AssertJ, Testcontainers.

---

## 范围边界

本计划只交付收银台所依赖的项目路由基础。不在本计划中修改 `BuyerCashierPort`、O2O/到店购入站 DTO、订单表或 HMAC 验签；这些在下一个「BuyerCashierPort V2 与到店购建单」计划中使用本切片的 Resolver。

## 文件结构

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/016-project-integration-route.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerProjectStatus.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/dataobject/BuyerProjectDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/mysql/BuyerProjectMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationCapabilityCode.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRoute.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolver.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationProfileDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationProfileVersionDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationCapabilityDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationProfileMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationProfileVersionMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationCapabilityMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerProjectIntegrationRouteMigrationIntegrationTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolverTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`
- Modify: `docs/集成测试.md`

### Task 1: 用真实 MySQL 锁定 016 DDL 契约

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerProjectIntegrationRouteMigrationIntegrationTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/016-project-integration-route.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`

- [x] **Step 1: 先写失败的迁移测试**

测试用独占 MySQL 8.4 空库执行全部 Liquibase，插入 Buyer A 下 P1/P2 两个项目，并验证：

```java
assertThat(tableNames(jdbc)).contains(
        "sc_buyer_project", "sc_project_capability",
        "sc_project_application", "sc_project_app_credential_version");
assertThat(columnNames(jdbc, "sc_buyer_integration_profile"))
        .contains("project_id");
assertThat(columnNames(jdbc, "sc_buyer_integration_capability"))
        .contains("source_system", "external_tenant_id");

insertProject(jdbc, 101L, 1L, "P1", "Project 1");
insertProject(jdbc, 102L, 1L, "P2", "Project 2");
insertPublishedRoute(jdbc, 101L, "tenant-shared", "CASHIER");
insertPublishedRoute(jdbc, 102L, "tenant-shared", "CASHIER");
assertThat(countRoutes(jdbc, "tenant-shared")).isEqualTo(2);

assertThatThrownBy(() -> insertPublishedRoute(jdbc, 101L, null, "MEMBER_PROFILE"))
        .hasRootCauseInstanceOf(SQLException.class);
assertThatThrownBy(() -> insertSecondProfileForProject(jdbc, 101L))
        .isInstanceOf(DataIntegrityViolationException.class);
```

`SupplyChainLiquibaseIntegrationTest` 同步期望 changeSet 数为 `16`，并断言上述四张新表与三个路由列存在。

- [x] **Step 2: 运行测试并确认 RED**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=BuyerProjectIntegrationRouteMigrationIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: FAIL，原因是 `sc_buyer_project` 等表和 016 changeSet 尚不存在。

- [x] **Step 3: 新增前向迁移 016**

016 必须实现以下完整约束：

```sql
CREATE TABLE sc_buyer_project (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购项目内部主键',
    project_code VARCHAR(32) NOT NULL COMMENT '采购项目全局永久编码',
    project_name VARCHAR(128) NOT NULL COMMENT '采购项目名称',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    catalog_mode VARCHAR(16) NOT NULL COMMENT '目录模式：MANAGED或INDEPENDENT',
    status SMALLINT NOT NULL COMMENT '项目状态：0草稿，10启用，20暂停，90归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '项目乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_project_code (project_code),
    KEY idx_sc_buyer_project_buyer_status (buyer_id, status),
    CONSTRAINT ck_sc_buyer_project_catalog_mode CHECK (catalog_mode IN ('MANAGED', 'INDEPENDENT')),
    CONSTRAINT ck_sc_buyer_project_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商下独立认证与配置的采购项目';

CREATE TABLE sc_project_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目能力内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键',
    capability_code VARCHAR(32) NOT NULL COMMENT '项目能力：STANDARD_PRODUCT、O2O、INSTORE',
    status SMALLINT NOT NULL COMMENT '能力状态：10启用，20停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_capability (project_id, capability_code),
    CONSTRAINT ck_sc_project_capability_code CHECK (capability_code IN ('STANDARD_PRODUCT', 'O2O', 'INSTORE')),
    CONSTRAINT ck_sc_project_capability_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购项目已开通业务场景';

CREATE TABLE sc_project_application (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目应用身份内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键',
    app_id VARCHAR(128) NOT NULL COMMENT '项目全局唯一稳定应用标识',
    status SMALLINT NOT NULL COMMENT '应用状态：10启用，20暂停，90归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_application_project (project_id),
    UNIQUE KEY uk_sc_project_application_app (app_id),
    CONSTRAINT ck_sc_project_application_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购项目入站稳定应用身份';

CREATE TABLE sc_project_app_credential_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目应用凭证版本内部主键',
    application_id BIGINT NOT NULL COMMENT '项目应用身份内部主键',
    key_version INT NOT NULL COMMENT '调用方携带的密钥版本号',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥系统引用，禁止保存明文appKey',
    masked_hint VARCHAR(128) NULL COMMENT '管理页可见的脱敏提示',
    status VARCHAR(16) NOT NULL COMMENT '密钥状态：CURRENT、GRACE、REVOKED、EXPIRED',
    valid_from DATETIME(3) NOT NULL COMMENT '密钥生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '密钥失效时间，UTC',
    active_slot VARCHAR(16) NULL COMMENT '活动轮换位：CURRENT、GRACE，非活动版本为NULL',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_app_credential_version (application_id, key_version),
    UNIQUE KEY uk_sc_project_app_credential_slot (application_id, active_slot),
    CONSTRAINT ck_sc_project_app_credential_status CHECK (status IN ('CURRENT', 'GRACE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_sc_project_app_credential_slot CHECK (
        (status IN ('CURRENT', 'GRACE') AND active_slot = status)
        OR (status IN ('REVOKED', 'EXPIRED') AND active_slot IS NULL)),
    CONSTRAINT ck_sc_project_app_credential_time CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目appKey密钥引用与无停机轮换版本';
```

随后使用临时 guard 确认 `sc_buyer_integration_profile` 无历史数据；如果有数据则失败关闭，因为已确认本设计不承担历史迁移。guard 通过后：

```sql
ALTER TABLE sc_buyer_integration_profile
    DROP INDEX uk_sc_buyer_integration_profile_buyer,
    ADD COLUMN project_id BIGINT NOT NULL COMMENT '采购项目内部主键' AFTER buyer_id,
    ADD UNIQUE KEY uk_sc_buyer_integration_profile_project (project_id);

ALTER TABLE sc_buyer_integration_capability
    DROP CHECK ck_sc_buyer_integration_capability,
    ADD COLUMN source_system VARCHAR(32) NULL COMMENT 'SaaS外部路由来源系统' AFTER contract_version,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL COMMENT 'SaaS外部租户路由标识' AFTER source_system,
    ADD CONSTRAINT ck_sc_buyer_integration_capability CHECK (
        capability_code IN ('MEMBER_PROFILE', 'CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    ADD CONSTRAINT ck_sc_buyer_integration_external_route CHECK (
        (capability_code IN ('MEMBER_PROFILE', 'CASHIER')
            AND source_system IS NOT NULL AND external_tenant_id IS NOT NULL)
        OR (capability_code NOT IN ('MEMBER_PROFILE', 'CASHIER')
            AND source_system IS NULL AND external_tenant_id IS NULL));
```

- [x] **Step 4: 运行迁移测试并确认 GREEN**

重复 Step 2 命令。Expected: PASS。

- [x] **Step 5: 提交 DDL 与迁移测试**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerProjectIntegrationRouteMigrationIntegrationTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java
git commit -m "feat(supply-chain): add project integration route schema"
```

### Task 2: 以 TDD 实现不可变集成路由解析

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolverTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationCapabilityCode.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRoute.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolver.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationProfileDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationProfileVersionDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/dataobject/BuyerIntegrationCapabilityDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationProfileMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationProfileVersionMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/dal/mysql/BuyerIntegrationCapabilityMapper.java`

- [x] **Step 1: 先写 Resolver 失败测试**

```java
@ExtendWith(MockitoExtension.class)
class BuyerIntegrationRouteResolverTest {
    @Mock BuyerIntegrationProfileMapper profileMapper;
    @Mock BuyerIntegrationProfileVersionMapper versionMapper;
    @Mock BuyerIntegrationCapabilityMapper capabilityMapper;
    @InjectMocks BuyerIntegrationRouteResolver resolver;

    @Test
    void resolvesExternalTenantOnlyFromRequestedPublishedProfileVersion() {
        when(versionMapper.selectById(900L)).thenReturn(publishedVersion(900L, 800L));
        when(profileMapper.selectById(800L)).thenReturn(activeProfile(800L, 101L));
        when(capabilityMapper.selectByProfileVersionAndCode(900L, "CASHIER"))
                .thenReturn(enabledCashier("AIFULI_MALL", "tenant-a"));

        BuyerIntegrationRoute route = resolver.resolve(
                101L, 900L, BuyerIntegrationCapabilityCode.CASHIER);

        assertThat(route.projectId()).isEqualTo(101L);
        assertThat(route.profileVersionId()).isEqualTo(900L);
        assertThat(route.sourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(route.externalTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void rejectsProfileOwnedByAnotherProject() {
        when(versionMapper.selectById(900L)).thenReturn(publishedVersion(900L, 800L));
        when(profileMapper.selectById(800L)).thenReturn(activeProfile(800L, 202L));

        assertThatThrownBy(() -> resolver.resolve(
                101L, 900L, BuyerIntegrationCapabilityCode.CASHIER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project ownership");
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void rejectsNonPublishedVersionAndDisabledCapability() {
        when(versionMapper.selectById(900L)).thenReturn(draftVersion(900L, 800L));
        assertThatThrownBy(() -> resolver.resolve(
                101L, 900L, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not published");
    }
}
```

- [x] **Step 2: 运行测试并确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=BuyerIntegrationRouteResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，因为 Resolver、Route、DO 和 Mapper 尚不存在。

- [x] **Step 3: 实现最小领域类与 Resolver**

```java
public enum BuyerIntegrationCapabilityCode {
    MEMBER_PROFILE, CASHIER, ORDER_EVENT, FULFILLMENT_EVENT, CATALOG_EVENT
}

public record BuyerIntegrationRoute(
        Long projectId,
        Long profileVersionId,
        BuyerIntegrationCapabilityCode capability,
        String sourceSystem,
        String externalTenantId,
        String transportMode,
        String adapterCode,
        String contractVersion,
        Long credentialSetId) {
    public BuyerIntegrationRoute {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(profileVersionId, "profileVersionId");
        Objects.requireNonNull(capability, "capability");
        if ((capability == BuyerIntegrationCapabilityCode.MEMBER_PROFILE
                || capability == BuyerIntegrationCapabilityCode.CASHIER)
                && (sourceSystem == null || sourceSystem.isBlank()
                || externalTenantId == null || externalTenantId.isBlank())) {
            throw new IllegalArgumentException("SaaS route requires sourceSystem and externalTenantId");
        }
    }
}
```

Resolver 必须依次校验：版本存在且 `status=10`、档案存在且 `status=10`、档案 `projectId` 与请求一致、能力存在且 `status=10`。状态值通过封闭枚举表达，不在 Resolver 中散落魔法数字。所有 DO 字段注释与 009/016 DDL COMMENT 保持一致。

- [x] **Step 4: 运行 Resolver 测试并确认 GREEN**

重复 Step 2 命令。Expected: PASS。

- [x] **Step 5: 提交 Resolver 切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerIntegrationRouteResolverTest.java
git commit -m "feat(supply-chain): resolve project integration routes"
```

### Task 3: 增加 BuyerProject 持久化映射与架构门禁

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/domain/BuyerProjectStatus.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/dataobject/BuyerProjectDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/dal/mysql/BuyerProjectMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [x] **Step 1: 先写失败的 Mapper/架构测试**

`BuyerProjectIntegrationRouteMigrationIntegrationTest` 通过 Spring Context 取得 `BuyerProjectMapper`，插入并按 projectCode 读回 P1；ArchitectureTest 新增：

```java
@ArchTest
static final ArchRule integration_buyer_does_not_access_scenario_persistence = noClasses()
        .that().resideInAPackage("..integration.buyer..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..border.o2o.dal..", "..border.instore.dal..");
```

- [x] **Step 2: 运行并确认 RED**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=SupplyChainModuleArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: BuyerProject 类尚不存在时编译失败，或 Mapper 集成断言失败。

- [x] **Step 3: 实现 BuyerProject DO/Mapper/状态枚举**

```java
public enum BuyerProjectStatus {
    DRAFT((short) 0), ACTIVE((short) 10), SUSPENDED((short) 20), ARCHIVED((short) 90);
    private final short code;
}

@TableName("sc_buyer_project")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProjectDO {
    @TableId(type = IdType.AUTO) private Long id;
    private String projectCode;
    private String projectName;
    private Long buyerId;
    private String catalogMode;
    private Short status;
    @Version private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}

@Mapper
public interface BuyerProjectMapper extends BaseMapperX<BuyerProjectDO> {
    default BuyerProjectDO selectByProjectCode(String projectCode) {
        return selectOne(BuyerProjectDO::getProjectCode, projectCode);
    }
}
```

- [x] **Step 4: 运行单测、架构测试和路由集成测试**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=BuyerIntegrationRouteResolverTest,SupplyChainModuleArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=BuyerProjectIntegrationRouteMigrationIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: PASS。

- [x] **Step 5: 提交 BuyerProject 映射和架构门禁**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerProjectIntegrationRouteMigrationIntegrationTest.java
git commit -m "feat(supply-chain): map buyer project identity"
```

### Task 4: 更新联调文档并运行供应链门禁

**Files:**
- Modify: `docs/集成测试.md`

- [x] **Step 1: 更新集成测试基线**

在采购商项目化章节记录：016 changeSet、空库门禁、两项目共用 externalTenantId 但业务不合并、MEMBER_PROFILE/CASHIER 必须拥有完整外部路由、Resolver 拒绝跨项目 profileVersionId。

- [x] **Step 2: 运行全部供应链单元测试**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test
```

Expected: 供应链 `*Test` 全部 PASS。

- [x] **Step 3: 运行本切片 MySQL 门禁**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=BuyerProjectIntegrationRouteMigrationIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: PASS，Liquibase changeSet 数为 16，第二次启动无重复执行。

- [x] **Step 4: 差异与实现自检**

```bash
git diff --check
rg -n "TBD|TODO" \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer
git status --short
```

Expected: `git diff --check` 无输出；本切片新增代码无裸 TODO。

- [x] **Step 5: 提交文档与最终验证记录**

```bash
git add docs/集成测试.md
git commit -m "test(supply-chain): document project integration route gate"
```

## 后续切片入口

本计划通过后，下一计划直接使用 `BuyerIntegrationRouteResolver`：

1. `BuyerCashierPort` 四项命令携带 `BuyerIntegrationRoute`，`buyerUserId` 改为 `String`。
2. `queryPayment` 使用原 `outTradeNo`；`refund/queryRefund` 使用原 `refundCaseNo`。
3. 退款立即结果和查询结果在成功时必须携带实际退款金额。
4. 到店购建单事务固化 `projectId/cashierProfileVersionId`，事务后解析路由并返回 `cashierUrl/outTradeNo`。
