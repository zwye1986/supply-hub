# INSTORE 租户身份与一次性凭证迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以前向 `014` 迁移把 INSTORE 稳定租户用户绑定与短期入口凭证分离，并用数据库约束锁死一笔到店购详情只有一条支付卫星。

**Architecture:** 保留 `013` 的匿名用户与明文 token 列作为可空兼容列，新增租户四元组唯一绑定和只存 SHA-256 摘要的凭证表。凭证通过 `ACTIVE → PROCESSING → USED` 三阶段 CAS 消费，会员查询明确失败时可在未过期前释放；本切片只把现有 Lifecycle 的建单入口迁移到新租户身份快照，资金、退款、API 与渠道协议留给后续切片。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Liquibase SQL、MySQL 8.4、JUnit 5、Mockito、AssertJ、Testcontainers、Maven Surefire/Failsafe。

---

## 范围边界与文件结构

本计划只实现设计文档落地顺序第 1 项，并产出可独立验证的持久化与领域能力。以下内容不进入本计划：完整建单指纹、资金冻结键修复、支付合并 CAS、真实账户返还、INSTORE API、SaaS 收银台 Adapter 和旅通渠道服务。

新增文件及职责：

- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql`：前向回填、兼容列迁移、凭证表和支付 1:1 约束。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialPurpose.java`：`HOME/DETAIL` 封闭用途。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialStatus.java`：`ACTIVE/PROCESSING/USED/EXPIRED` 封闭状态。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreEntryCredentialDO.java`：凭证持久化模型，只含摘要、不含明文。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreEntryCredentialMapper.java`：凭证读取及四个条件更新。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialProperties.java`：默认十分钟有效期配置。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialService.java`：签发、抢占、成功消费和失败释放。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProductionInvariantsMigrationIntegrationTest.java`：真实 MySQL 验证 013→014 回填与失败关闭。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialIntegrationTest.java`：真实 MySQL 验证并发单次消费与凭证相互独立。

修改文件及职责：

- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`：注册 014。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java` 与 `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java`：补齐已批准的稳定错误。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/identity/BusinessNoPrefixes.java`：加入 `IEC_` 凭证业务号前缀。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelUserBindingDO.java`：增加来源租户字段，旧匿名/token 字段继续可空兼容。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelOrderDO.java`：增加来源租户快照。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java`：新增租户四元组查询，保留 Lifecycle 暂用的旧匿名查询。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java`：只负责稳定绑定，不再签发或解析 token。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`：建单改用来源租户身份并写入非空快照。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolverTest.java`：覆盖租户作用域和并发首创建。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java`：覆盖租户快照和兼容建单回归。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/domain/InstoreStatusEnumsTest.java`：覆盖凭证状态码和用途。
- `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`：空库升级断言 014 对象存在。
- `docs/集成测试.md`：记录实际测试类、命令和验证结果。

---

### Task 1: 固化凭证枚举、业务号和稳定错误目录

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialPurpose.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialStatus.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/identity/BusinessNoPrefixes.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/domain/InstoreStatusEnumsTest.java`

- [ ] **Step 1: 先写枚举和错误映射失败测试**

在 `InstoreStatusEnumsTest` 增加：

```java
@ParameterizedTest
@EnumSource(InstoreEntryCredentialStatus.class)
void entryCredentialStatusRoundTripsByCode(InstoreEntryCredentialStatus status) {
    assertThat(InstoreEntryCredentialStatus.fromCode(status.getCode())).isEqualTo(status);
}

@Test
void entryCredentialPurposeIsClosed() {
    assertThat(InstoreEntryCredentialPurpose.values())
            .containsExactly(InstoreEntryCredentialPurpose.HOME, InstoreEntryCredentialPurpose.DETAIL);
    assertThatThrownBy(() -> InstoreEntryCredentialStatus.fromCode((short) 99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown instore entry credential status");
}
```

在 `SupplyChainErrorMappingTest` 的 `CsvSource` 增加：

```java
"1050001035,QUERY_BY_BUSINESS_KEY",
"1050001036,DO_NOT_RETRY",
"1050001037,DO_NOT_RETRY",
"1050001038,DO_NOT_RETRY",
"1050001039,DO_NOT_RETRY",
"1050001040,MANUAL",
"1050001041,QUERY_BY_BUSINESS_KEY",
"1050001042,MANUAL"
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='SupplyChainErrorMappingTest,InstoreStatusEnumsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败，提示 `InstoreEntryCredentialStatus`、`InstoreEntryCredentialPurpose` 和新错误码尚不存在。

- [ ] **Step 3: 实现两个封闭枚举**

`InstoreEntryCredentialPurpose.java`：

```java
package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购入口凭证用途：首页与详情严格隔离。
 *
 * <p>设计见 docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口。</p>
 */
public enum InstoreEntryCredentialPurpose {
    HOME,
    DETAIL
}
```

`InstoreEntryCredentialStatus.java`：

```java
package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购入口凭证状态；PROCESSING 表示已有唯一请求正在查询会员资料。
 *
 * <p>设计见 docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreEntryCredentialStatus {
    ACTIVE((short) 10),
    PROCESSING((short) 15),
    USED((short) 20),
    EXPIRED((short) 30);

    private final short code;

    public static InstoreEntryCredentialStatus fromCode(short code) {
        for (InstoreEntryCredentialStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore entry credential status: " + code);
    }
}
```

- [ ] **Step 4: 增加业务号和错误常量**

在 `BusinessNoPrefixes` 增加：

```java
/** 到店购入口凭证稳定业务编号。 */
public static final String IEC = "IEC_";
```

并把 `IEC` 加入 `ALL`。

在 `SupplyChainErrorCodeConstants` 增加：

```java
ErrorCode INSTORE_IDEMPOTENCY_CONFLICT = error(1_050_001_035, "到店购永久业务键对应的请求内容冲突");
ErrorCode INSTORE_CREDENTIAL_INVALID = error(1_050_001_036, "到店购入口凭证无效或用途不匹配");
ErrorCode INSTORE_CREDENTIAL_EXPIRED = error(1_050_001_037, "到店购入口凭证已过期");
ErrorCode INSTORE_CREDENTIAL_ALREADY_USED = error(1_050_001_038, "到店购入口凭证已使用");
ErrorCode INSTORE_TENANT_SCOPE_CONFLICT = error(1_050_001_039, "到店购请求与采购商租户作用域不一致");
ErrorCode INSTORE_REFUND_AMOUNT_MISMATCH = error(1_050_001_040, "到店购实际退款金额与申请金额不一致");
ErrorCode INSTORE_CONCURRENT_UPDATE = error(1_050_001_041, "到店购状态已被并发请求推进");
ErrorCode INSTORE_ACCOUNT_RETURN_CONFLICT = error(1_050_001_042, "到店购账户返还幂等键对应的金额冲突");
```

对应加入 `RETRY_DIRECTIVES`：

```java
entry(INSTORE_IDEMPOTENCY_CONFLICT, RetryDirective.QUERY_BY_BUSINESS_KEY),
entry(INSTORE_CREDENTIAL_INVALID, RetryDirective.DO_NOT_RETRY),
entry(INSTORE_CREDENTIAL_EXPIRED, RetryDirective.DO_NOT_RETRY),
entry(INSTORE_CREDENTIAL_ALREADY_USED, RetryDirective.DO_NOT_RETRY),
entry(INSTORE_TENANT_SCOPE_CONFLICT, RetryDirective.DO_NOT_RETRY),
entry(INSTORE_REFUND_AMOUNT_MISMATCH, RetryDirective.MANUAL),
entry(INSTORE_CONCURRENT_UPDATE, RetryDirective.QUERY_BY_BUSINESS_KEY),
entry(INSTORE_ACCOUNT_RETURN_CONFLICT, RetryDirective.MANUAL)
```

保留 `INSTORE_TOKEN_INVALID`，直到后续 API 切片删除旧 token 路径，避免本切片破坏现有 Lifecycle 测试。

- [ ] **Step 5: 运行测试确认通过**

Run: 与 Step 2 相同。

Expected: `SupplyChainErrorMappingTest` 和 `InstoreStatusEnumsTest` 全部通过。

- [ ] **Step 6: 提交任务 1**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialPurpose.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/domain/InstoreEntryCredentialStatus.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/identity/BusinessNoPrefixes.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/domain/InstoreStatusEnumsTest.java
git commit -m "feat(supply-chain): add INSTORE credential domain codes"
```

---

### Task 2: 新增 014 前向迁移和空库门禁

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`

- [ ] **Step 1: 扩充空库 Liquibase 测试并确认先失败**

把 `SupplyChainLiquibaseIntegrationTest` 的表断言扩为：

```java
assertThat(firstStartup.tableNames())
        .contains("sc_buyer", "sc_supply_chain_order", "sc_timer_task",
                "sc_sensitive_access_audit", "sc_instore_entry_credential");
```

在 `snapshot` 中增加以下查询：

```java
List<String> columnNames = queryStrings(connection, """
        SELECT CONCAT(LOWER(TABLE_NAME), '.', LOWER(COLUMN_NAME))
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
        ORDER BY TABLE_NAME, ORDINAL_POSITION
        """);
List<String> uniqueIndexes = queryStrings(connection, """
        SELECT DISTINCT CONCAT(LOWER(TABLE_NAME), '.', LOWER(INDEX_NAME)) AS index_name
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND NON_UNIQUE = 0
        ORDER BY index_name
        """);
```

返回值构造改为：

```java
return new DatabaseSnapshot(tableNames, columnNames, uniqueIndexes,
        collations, importedKeys, duplicateChangeSets, changeSetCount);
```

`DatabaseSnapshot` 改为：

```java
private record DatabaseSnapshot(
        List<String> tableNames,
        List<String> columnNames,
        List<String> uniqueIndexes,
        List<String> collations,
        List<String> importedKeys,
        List<String> duplicateChangeSets,
        long changeSetCount) {
}
```

并在首次启动断言：

```java
assertThat(firstStartup.columnNames())
        .contains("sc_instore_channel_user_binding.source_system",
                "sc_instore_channel_user_binding.external_tenant_id",
                "sc_instore_channel_order.source_system",
                "sc_instore_channel_order.external_tenant_id");
assertThat(firstStartup.uniqueIndexes())
        .contains("sc_instore_payment_flow.uk_sc_instore_flow_order");
```

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplyChainLiquibaseIntegrationTest \
  verify
```

Expected: FAIL，缺少 `sc_instore_entry_credential` 或 014 列。

- [ ] **Step 2: 注册 014 changeSet**

在 `db.changelog-1.0.0.yaml` 的 013 后追加：

```yaml
  - changeSet:
      id: 014-instore-production-invariants
      author: supply-chain
      changes:
        - sqlFile:
            path: 014-instore-production-invariants.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

- [ ] **Step 3: 写入完整前向 SQL**

`014-instore-production-invariants.sql` 使用以下结构；不得修改 `013`：

```sql
-- INSTORE 生产身份与凭证不变量前向迁移。
-- 设计依据：docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构liquibase-013-instore-channelsql--014-instore-production-invariantssql

CREATE TEMPORARY TABLE sc_instore_014_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT ck_sc_instore_014_guard CHECK (valid = 1)
);

INSERT INTO sc_instore_014_guard(valid)
SELECT CASE
    WHEN EXISTS (
        SELECT 1
        FROM sc_instore_channel_user_binding iub
        LEFT JOIN sc_buyer_tenant_binding btb
          ON btb.buyer_id = iub.buyer_id
         AND btb.current_flag = 1
         AND btb.status = 10
        GROUP BY iub.id
        HAVING COUNT(btb.id) <> 1
    ) THEN 0
    WHEN EXISTS (
        SELECT 1
        FROM sc_instore_payment_flow ipf
        GROUP BY ipf.instore_order_id
        HAVING COUNT(*) > 1
    ) THEN 0
    ELSE 1
END;

DROP TEMPORARY TABLE sc_instore_014_guard;

ALTER TABLE sc_instore_channel_user_binding
    ADD COLUMN source_system VARCHAR(32) NULL
        COMMENT '采购商来源系统编码，与external_tenant_id共同确定SaaS租户' AFTER buyer_id,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL
        COMMENT '来源系统中的SaaS商城租户唯一标识' AFTER source_system;

UPDATE sc_instore_channel_user_binding iub
JOIN sc_buyer_tenant_binding btb
  ON btb.buyer_id = iub.buyer_id
 AND btb.current_flag = 1
 AND btb.status = 10
SET iub.source_system = btb.source_system,
    iub.external_tenant_id = btb.external_tenant_id;

ALTER TABLE sc_instore_channel_user_binding
    DROP INDEX uk_sc_instore_user_binding_buyer,
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL
        COMMENT '采购商来源系统编码，与external_tenant_id共同确定SaaS租户',
    MODIFY COLUMN external_tenant_id VARCHAR(128) NOT NULL
        COMMENT '来源系统中的SaaS商城租户唯一标识',
    MODIFY COLUMN channel_user_id VARCHAR(128) NULL
        COMMENT '013兼容匿名渠道用户标识；生产路径不再读写',
    MODIFY COLUMN token VARCHAR(256) NULL
        COMMENT '013兼容明文token；生产路径禁止读写',
    MODIFY COLUMN token_status SMALLINT NULL
        COMMENT '013兼容token状态；生产路径禁止读写',
    MODIFY COLUMN token_expires_at DATETIME(3) NULL
        COMMENT '013兼容token失效时间；生产路径禁止读写',
    ADD UNIQUE KEY uk_sc_instore_user_binding_tenant
        (source_system, external_tenant_id, buyer_user_id, channel_code),
    ADD KEY idx_sc_instore_user_binding_tenant_status
        (source_system, external_tenant_id, channel_code, status);

ALTER TABLE sc_instore_channel_user_binding
    COMMENT='采购商来源租户内C端用户的到店购稳定绑定';

ALTER TABLE sc_instore_channel_order
    ADD COLUMN source_system VARCHAR(32) NULL
        COMMENT '受理时固化的采购商来源系统编码' AFTER buyer_id,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL
        COMMENT '受理时固化的SaaS商城租户唯一标识' AFTER source_system;

UPDATE sc_instore_channel_order ico
JOIN sc_instore_channel_user_binding iub
  ON iub.id = ico.channel_user_binding_id
SET ico.source_system = iub.source_system,
    ico.external_tenant_id = iub.external_tenant_id;

ALTER TABLE sc_instore_channel_order
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL
        COMMENT '受理时固化的采购商来源系统编码',
    MODIFY COLUMN external_tenant_id VARCHAR(128) NOT NULL
        COMMENT '受理时固化的SaaS商城租户唯一标识',
    MODIFY COLUMN channel_user_id VARCHAR(128) NULL
        COMMENT '013兼容匿名渠道用户标识快照；生产路径不再读写',
    ADD KEY idx_sc_instore_order_tenant_user
        (source_system, external_tenant_id, buyer_user_id, created_at);

CREATE TABLE sc_instore_entry_credential (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '到店购入口凭证内部主键',
    credential_no VARCHAR(32) NOT NULL COMMENT '到店购入口凭证稳定业务编号',
    binding_id BIGINT NOT NULL COMMENT '稳定用户绑定主键，逻辑引用sc_instore_channel_user_binding.id',
    token_digest BINARY(32) NOT NULL COMMENT '入口token原文的SHA-256摘要；禁止保存原文',
    purpose VARCHAR(16) NOT NULL COMMENT '凭证用途：HOME首页，DETAIL订单详情',
    instore_order_id BIGINT NULL COMMENT 'DETAIL凭证绑定的到店购详情主键；HOME为空',
    status SMALLINT NOT NULL COMMENT '凭证状态：10 ACTIVE，15 PROCESSING，20 USED，30 EXPIRED',
    expires_at DATETIME(3) NOT NULL COMMENT '凭证失效时间，UTC',
    processing_started_at DATETIME(3) NULL COMMENT '凭证被会员查询请求独占的时间，UTC',
    used_at DATETIME(3) NULL COMMENT '会员资料成功返回后的凭证消费时间，UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '凭证状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '凭证创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '凭证最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_instore_credential_no (credential_no),
    UNIQUE KEY uk_sc_instore_credential_digest (token_digest),
    KEY idx_sc_instore_credential_binding (binding_id, status, expires_at),
    KEY idx_sc_instore_credential_expiry (status, expires_at),
    KEY idx_sc_instore_credential_order (instore_order_id, purpose),
    CONSTRAINT ck_sc_instore_credential_purpose CHECK (
        (purpose = 'HOME' AND instore_order_id IS NULL)
        OR (purpose = 'DETAIL' AND instore_order_id IS NOT NULL)
    ),
    CONSTRAINT ck_sc_instore_credential_status CHECK (status IN (10, 15, 20, 30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='到店购首页与详情独立短期一次性凭证';

ALTER TABLE sc_instore_payment_flow
    ADD UNIQUE KEY uk_sc_instore_flow_order (instore_order_id),
    COMMENT='到店购支付1:1兼容卫星；每个到店购详情至多一行';
```

- [ ] **Step 4: 运行空库迁移测试**

Run: 与 Step 1 相同。

Expected: PASS；空库执行 001→014，两次启动 changeSet 数量不变。

- [ ] **Step 5: 提交任务 2**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java
git commit -m "feat(supply-chain): add INSTORE production invariant migration"
```

---

### Task 3: 验证 013→014 回填、失败关闭和支付卫星唯一性

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProductionInvariantsMigrationIntegrationTest.java`

- [ ] **Step 1: 写真实 MySQL 升级测试**

测试使用本类独占的 MySQL 8.4 Testcontainer；无 Docker 时只接受单独的空库参数
`supply-chain.migration-test.jdbc-*`，禁止复用其它 IntegrationTest 数据库。单个测试先让歧义数据触发 guard，修正歧义后在同一数据库完成升级和支付唯一性验证，既不依赖建库权限，也不在外部数据库执行 `dropAll`。

容器与 DataSource 初始化固定为：

```java
static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("supply_chain_migration")
        .withUsername("supply_chain")
        .withPassword("supply_chain");

private static final String EXTERNAL_JDBC_URL =
        System.getProperty("supply-chain.migration-test.jdbc-url");

@BeforeAll
static void startContainer() {
    if (EXTERNAL_JDBC_URL == null) {
        MYSQL.start();
    }
}

@AfterAll
static void stopContainer() {
    if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
        MYSQL.stop();
    }
}

private static DataSource dataSource() {
    String url = EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    String username = EXTERNAL_JDBC_URL != null
            ? System.getProperty("supply-chain.migration-test.jdbc-username", "root")
            : MYSQL.getUsername();
    String password = EXTERNAL_JDBC_URL != null
            ? System.getProperty("supply-chain.migration-test.jdbc-password", "")
            : MYSQL.getPassword();
    return new DriverManagerDataSource(url, username, password);
}
```

通过 Liquibase Java API 先执行 13 个 changeSet，写入 013 兼容数据，再执行剩余 changeSet：

```java
private static void updateTo013(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase(
                "db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(13, new Contexts(), new LabelExpression());
        }
    }
}

private static void updateRemaining(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase(
                "db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
```

单个测试必须按以下顺序验证三个结果：

```java
@Test
void failsClosedThenBackfillsAndEnforcesPaymentSatelliteUniqueness() throws Exception {
    DataSource dataSource = dataSource();
    updateTo013(dataSource);
    insertBuyerTenantAndLegacyBinding(dataSource, 1L, "AIFULI_MALL", "tenant-a");
    insertCurrentTenant(dataSource, 1L, "PARTNER_MALL", "tenant-b", 2L);

    assertThatThrownBy(() -> updateRemaining(dataSource))
            .hasRootCauseInstanceOf(SQLException.class);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'sc_instore_channel_user_binding'
              AND COLUMN_NAME = 'source_system'
            """, Integer.class)).isZero();
    jdbc.update("""
            UPDATE sc_buyer_tenant_binding
            SET current_flag = NULL, status = 20,
                valid_to = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3)
            WHERE id = 2
            """);

    updateRemaining(dataSource);

    assertThat(jdbc.queryForMap("""
            SELECT source_system, external_tenant_id
            FROM sc_instore_channel_user_binding
            WHERE binding_no = 'IUB_LEGACY'
            """))
            .containsEntry("source_system", "AIFULI_MALL")
            .containsEntry("external_tenant_id", "tenant-a");

    insertPaymentFlow(jdbc, "IPF_A", 99L, "trade-a", "out-a", 1);
    assertThatThrownBy(() -> insertPaymentFlow(jdbc, "IPF_B", 99L, "trade-b", "out-b", 2))
            .isInstanceOf(DuplicateKeyException.class);
}
```

013 造数 helper 固定为：

```java
private static void insertBuyerTenantAndLegacyBinding(
        DataSource dataSource, long buyerId,
        String sourceSystem, String externalTenantId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
            INSERT INTO sc_buyer(
                id, buyer_code, buyer_name, short_name, buyer_type,
                status, version, created_at, updated_at)
            VALUES (?, 'BUYER_MIGRATION', '迁移采购商', '迁移采购商', 'SAAS_MALL',
                    10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, buyerId);
    insertCurrentTenant(dataSource, buyerId, sourceSystem, externalTenantId, 1L);
    jdbc.update("""
            INSERT INTO sc_instore_channel_user_binding(
                binding_no, buyer_id, buyer_user_id, channel_code,
                channel_user_id, token, token_status, token_expires_at,
                status, version, created_at, updated_at)
            VALUES ('IUB_LEGACY', ?, 'opaque-user', 'RT_MART',
                    'legacy-anonymous', 'legacy-plaintext-token', 10,
                    DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE),
                    10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, buyerId);
}

private static void insertCurrentTenant(
        DataSource dataSource, long buyerId,
        String sourceSystem, String externalTenantId, long bindingId) {
    new JdbcTemplate(dataSource).update("""
            INSERT INTO sc_buyer_tenant_binding(
                id, buyer_id, source_system, external_tenant_id,
                binding_version, valid_from, valid_to, current_flag,
                status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, UTC_TIMESTAMP(3), NULL, 1,
                    10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, bindingId, buyerId, sourceSystem, externalTenantId);
}
```

`insertPaymentFlow` 必须填满 013 的非空列，并使用 32 字节摘要：

```java
private static void insertPaymentFlow(JdbcTemplate jdbc, String flowNo, long instoreOrderId,
                                      String tradeId, String outTradeNo, int sequence) {
    jdbc.update("""
            INSERT INTO sc_instore_payment_flow(
                payment_flow_no, instore_order_id, order_id, flow_sequence,
                channel_code, trade_id, user_code, pay_amount, order_type,
                pay_status, out_trade_no, request_fingerprint, version,
                created_at, updated_at)
            VALUES (?, ?, 100, ?, 'RT_MART', ?, 'opaque-user', 1000, 1,
                    0, ?, ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """, flowNo, instoreOrderId, sequence, tradeId, outTradeNo, new byte[32]);
}
```

- [ ] **Step 2: 运行升级测试**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=InstoreProductionInvariantsMigrationIntegrationTest \
  verify
```

Expected: 3 个场景通过；歧义回填只在测试断言内失败，测试本身 PASS。

- [ ] **Step 3: 提交任务 3**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProductionInvariantsMigrationIntegrationTest.java
git commit -m "test(supply-chain): verify INSTORE 014 upgrade invariants"
```

---

### Task 4: 建立租户四元组稳定绑定并迁移建单身份快照

**Files:**

- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelUserBindingDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelOrderDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolverTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java`

- [ ] **Step 1: 把 Resolver 和 Lifecycle 测试改为来源租户身份并确认失败**

Resolver 核心测试改成：

```java
@Test
void resolveOrCreateReusesBindingOnlyInsideExactTenantScope() {
    when(bindingMapper.selectByTenantUser(
            "AIFULI_MALL", "tenant-a", "buyer-user-1", "RT_MART"))
            .thenReturn(activeBinding());

    BindingSnapshot snapshot = resolver.resolveOrCreateStableBinding(
            1001L, "AIFULI_MALL", "tenant-a", "buyer-user-1", "RT_MART");

    assertThat(snapshot.sourceSystem()).isEqualTo("AIFULI_MALL");
    assertThat(snapshot.externalTenantId()).isEqualTo("tenant-a");
    assertThat(snapshot.buyerUserId()).isEqualTo("buyer-user-1");
    verify(bindingMapper, never()).insert(any());
}

@Test
void existingBindingWithDifferentBuyerFailsTenantScopeCheck() {
    InstoreChannelUserBindingDO binding = activeBinding();
    binding.setBuyerId(2002L);
    when(bindingMapper.selectByTenantUser(
            "AIFULI_MALL", "tenant-a", "buyer-user-1", "RT_MART"))
            .thenReturn(binding);

    assertThatThrownBy(() -> resolver.resolveOrCreateStableBinding(
            1001L, "AIFULI_MALL", "tenant-a", "buyer-user-1", "RT_MART"))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
}

@Test
void newStableBindingLeavesLegacyAnonymousAndTokenColumnsNull() {
    when(bindingMapper.selectByTenantUser(any(), any(), any(), any())).thenReturn(null);
    when(businessNoGenerator.next(BusinessNoPrefixes.IUB)).thenReturn("IUB_1");
    doAnswer(invocation -> {
        InstoreChannelUserBindingDO value = invocation.getArgument(0);
        value.setId(99L);
        return 1;
    }).when(bindingMapper).insert(any());

    resolver.resolveOrCreateStableBinding(
            1001L, "AIFULI_MALL", "tenant-a", "buyer-user-1", "RT_MART");

    ArgumentCaptor<InstoreChannelUserBindingDO> captor =
            ArgumentCaptor.forClass(InstoreChannelUserBindingDO.class);
    verify(bindingMapper).insert(captor.capture());
    assertThat(captor.getValue().getChannelUserId()).isNull();
    assertThat(captor.getValue().getToken()).isNull();
    assertThat(captor.getValue().getTokenStatus()).isNull();
}
```

Lifecycle 命令工厂和快照断言改成：

```java
private static InstoreChannelLifecycle.CreatePaymentOrderCommand createCommand(
        long payAmount, long totalAmount) {
    return new InstoreChannelLifecycle.CreatePaymentOrderCommand(
            CHANNEL, ORDER_ID, TRADE_ID,
            1001L, "AIFULI_MALL", "tenant-a", "buyer-user-1",
            "buyer-user-1", payAmount, 1, totalAmount,
            "COMPANY", "MERCHANT1", "SHOP1", "Shop", "subject", TRACE);
}

@Test
void createPaymentOrderPersistsTenantScopeWithoutLegacyAnonymousSnapshot() {
    stubNewHeadCreate();
    ArgumentCaptor<InstoreChannelOrderDO> captor =
            ArgumentCaptor.forClass(InstoreChannelOrderDO.class);

    lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L));

    verify(instoreOrderMapper).insert(captor.capture());
    assertThat(captor.getValue().getSourceSystem()).isEqualTo("AIFULI_MALL");
    assertThat(captor.getValue().getExternalTenantId()).isEqualTo("tenant-a");
    assertThat(captor.getValue().getBuyerUserId()).isEqualTo("buyer-user-1");
    assertThat(captor.getValue().getChannelUserId()).isNull();
}
```

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelUserBindingResolverTest,InstoreChannelLifecycleTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败，缺少新字段、Mapper 方法和命令参数。

- [ ] **Step 2: 更新 DO 与 Mapper**

在两个 DO 增加：

```java
private String sourceSystem;
private String externalTenantId;
```

把 `InstoreChannelUserBindingDO` 类注释改为“稳定租户用户绑定；旧匿名/token 字段只为 013 兼容”。Mapper 新增：

```java
default InstoreChannelUserBindingDO selectByTenantUser(
        String sourceSystem, String externalTenantId,
        String buyerUserId, String channelCode) {
    return selectOne(new LambdaQueryWrapper<InstoreChannelUserBindingDO>()
            .eq(InstoreChannelUserBindingDO::getSourceSystem, sourceSystem)
            .eq(InstoreChannelUserBindingDO::getExternalTenantId, externalTenantId)
            .eq(InstoreChannelUserBindingDO::getBuyerUserId, buyerUserId)
            .eq(InstoreChannelUserBindingDO::getChannelCode, channelCode));
}
```

保留 `selectActiveByChannelUser` 和 `selectByBuyerUser`，加 `@Deprecated` 与中文兼容说明；本任务结束后生产建单不再调用它们，后续核心切片删除。

- [ ] **Step 3: 重写 Resolver 为稳定绑定职责**

公开方法固定为：

```java
public BindingSnapshot resolveOrCreateStableBinding(
        Long buyerId, String sourceSystem, String externalTenantId,
        String buyerUserId, String channelCode)

public BindingSnapshot resolveActiveById(Long bindingId)

public record BindingSnapshot(
        Long bindingId, Long buyerId, String sourceSystem,
        String externalTenantId, String buyerUserId,
        String channelCode, long version) {}
```

创建 DO 时写入：

```java
InstoreChannelUserBindingDO binding = InstoreChannelUserBindingDO.builder()
        .bindingNo(businessNoGenerator.next(BusinessNoPrefixes.IUB))
        .buyerId(buyerId)
        .sourceSystem(sourceSystem)
        .externalTenantId(externalTenantId)
        .buyerUserId(buyerUserId)
        .channelCode(channelCode)
        .channelUserId(null)
        .token(null)
        .tokenStatus(null)
        .tokenExpiresAt(null)
        .status(STATUS_ACTIVE)
        .version(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
```

已有绑定必须同时满足 `status=ACTIVE` 与 `buyerId` 相等；Buyer 不同抛 `INSTORE_TENANT_SCOPE_CONFLICT`，停用抛 `INSTORE_CHANNEL_USER_BINDING_MISSING`。并发插入冲突后按同一租户四元组回读，回读不到时保留原 `DuplicateKeyException` 因果链。

- [ ] **Step 4: 把 Lifecycle 最小迁移到租户身份快照**

命令身份字段固定为：

```java
public record CreatePaymentOrderCommand(
        String channelCode, String channelOrderId, String tradeId,
        Long buyerId, String sourceSystem, String externalTenantId,
        String buyerUserId, String userCode,
        long payAmount, int orderType, long totalAmount,
        String companyCode, String externalMerchantId,
        String shopCode, String shopName, String subject, String traceId) {
}
```

建单按四元组加载绑定并校验：

```java
InstoreChannelUserBindingDO binding = bindingMapper.selectByTenantUser(
        command.sourceSystem(), command.externalTenantId(),
        command.buyerUserId(), command.channelCode());
if (binding == null || !Short.valueOf((short) 10).equals(binding.getStatus())) {
    throw exception(INSTORE_CHANNEL_USER_BINDING_MISSING);
}
if (!binding.getBuyerId().equals(command.buyerId())
        || !command.buyerUserId().equals(command.userCode())) {
    throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
}
```

订单详情写入：

```java
.buyerId(binding.getBuyerId())
.sourceSystem(binding.getSourceSystem())
.externalTenantId(binding.getExternalTenantId())
.buyerUserId(binding.getBuyerUserId())
.channelCode(command.channelCode())
.channelUserBindingId(binding.getId())
.channelUserId(null)
```

同时把 INSTORE 总订单构建中的 `.skuNameSnapshot(command.subject())` 改为 `.skuNameSnapshot(null)`，与 013 已发布 CHECK 一致。当前摘要只做必要的身份字段迁移，精确实现为：

```java
static byte[] canonicalPaymentRequest(CreatePaymentOrderCommand command) {
    String canonical = command.channelCode()
            + "|" + command.sourceSystem()
            + "|" + command.externalTenantId()
            + "|" + command.channelOrderId()
            + "|" + command.tradeId()
            + "|" + command.buyerUserId()
            + "|" + command.payAmount()
            + "|" + command.externalMerchantId();
    return canonical.getBytes(StandardCharsets.UTF_8);
}
```

完整字段摘要在下一核心不变量计划完成。测试中的所有 `selectActiveByChannelUser` stub 同步替换成同参数的 `selectByTenantUser("AIFULI_MALL", "tenant-a", "buyer-user-1", CHANNEL)` stub；`createPaymentOrderRejectsBindingIdentityMismatch` 的预期错误改为 `INSTORE_TENANT_SCOPE_CONFLICT`，金额冲突、重复流水等其它场景继续使用现有错误码。

- [ ] **Step 5: 运行 Resolver 与现有 INSTORE 回归测试**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelUserBindingResolverTest,InstoreChannelLifecycleTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 两个测试类全部通过；创建的绑定和订单头都带来源租户快照，旧匿名字段为空。

- [ ] **Step 6: 提交任务 4**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelUserBindingDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelOrderDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolver.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelUserBindingResolverTest.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java
git commit -m "refactor(supply-chain): scope INSTORE identity by source tenant"
```

---

### Task 5: 实现独立凭证签发和三阶段消费

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreEntryCredentialDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreEntryCredentialMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialProperties.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialServiceTest.java`

- [ ] **Step 1: 写签发与消费失败测试**

`InstoreEntryCredentialServiceTest` 至少覆盖：

测试显式导入 `java.lang.reflect.Field`、`java.nio.charset.StandardCharsets`、`java.security.MessageDigest`，以及以下嵌套类型：

```java
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver.BindingSnapshot;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.ClaimedCredential;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.IssuedCredential;
```

```java
@Test
void issueHomePersistsOnlyDigestAndReturnsPlaintextOnce() throws Exception {
    when(bindingResolver.resolveOrCreateStableBinding(
            1001L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART"))
            .thenReturn(bindingSnapshot());
    when(businessNoGenerator.next(BusinessNoPrefixes.IEC)).thenReturn("IEC_1");
    doAnswer(invocation -> {
        InstoreEntryCredentialDO value = invocation.getArgument(0);
        value.setId(77L);
        return 1;
    }).when(credentialMapper).insert(any());

    IssuedCredential issued = service.issueHome(
            1001L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART");

    ArgumentCaptor<InstoreEntryCredentialDO> captor =
            ArgumentCaptor.forClass(InstoreEntryCredentialDO.class);
    verify(credentialMapper).insert(captor.capture());
    assertThat(issued.token()).startsWith("tk-").hasSize(67);
    assertThat(captor.getValue().getTokenDigest())
            .isEqualTo(MessageDigest.getInstance("SHA-256")
                    .digest(issued.token().getBytes(StandardCharsets.UTF_8)));
    assertThat(InstoreEntryCredentialDO.class.getDeclaredFields())
            .extracting(Field::getName)
            .doesNotContain("token");
}

@Test
void detailCredentialRequiresOrderAndCannotBeClaimedAsHome() {
    InstoreEntryCredentialDO detail = activeCredential(
            InstoreEntryCredentialPurpose.DETAIL, 55L, future());
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(detail);
    when(bindingResolver.resolveActiveById(detail.getBindingId())).thenReturn(bindingSnapshot());

    assertThatThrownBy(() -> service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-value",
            InstoreEntryCredentialPurpose.HOME, null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_INVALID.getCode());
    verify(credentialMapper, never()).claimProcessing(anyLong(), anyLong(), any());
}

@Test
void expiredCredentialReturnsExpiredError() {
    InstoreEntryCredentialDO expired = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null,
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(expired);
    when(bindingResolver.resolveActiveById(expired.getBindingId())).thenReturn(bindingSnapshot());
    when(credentialMapper.markExpired(eq(expired.getId()), eq(0L), any())).thenReturn(1);

    assertThatThrownBy(() -> service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-expired",
            InstoreEntryCredentialPurpose.HOME, null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_EXPIRED.getCode());
}

@Test
void usedCredentialReturnsAlreadyUsedError() {
    InstoreEntryCredentialDO used = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null, future());
    used.setStatus(InstoreEntryCredentialStatus.USED.getCode());
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(used);
    when(bindingResolver.resolveActiveById(used.getBindingId())).thenReturn(bindingSnapshot());

    assertThatThrownBy(() -> service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-used",
            InstoreEntryCredentialPurpose.HOME, null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED.getCode());
}

@Test
void processingCredentialReturnsConcurrentUpdate() {
    InstoreEntryCredentialDO processing = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null, future());
    processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(processing);
    when(bindingResolver.resolveActiveById(processing.getBindingId())).thenReturn(bindingSnapshot());

    assertThatThrownBy(() -> service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-processing",
            InstoreEntryCredentialPurpose.HOME, null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
}

@Test
void credentialFromAnotherTenantReturnsTenantScopeConflict() {
    InstoreEntryCredentialDO active = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null, future());
    BindingSnapshot otherTenant = new BindingSnapshot(
            11L, 1001L, "AIFULI_MALL", "tenant-b",
            "opaque-user", "RT_MART", 0L);
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);
    when(bindingResolver.resolveActiveById(active.getBindingId())).thenReturn(otherTenant);

    assertThatThrownBy(() -> service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-other-tenant",
            InstoreEntryCredentialPurpose.HOME, null))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getCode())
            .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
}

@Test
void successfulClaimCanBeMarkedUsed() {
    InstoreEntryCredentialDO active = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null, future());
    when(credentialMapper.selectByTokenDigest(any())).thenReturn(active);
    when(bindingResolver.resolveActiveById(active.getBindingId())).thenReturn(bindingSnapshot());
    when(credentialMapper.claimProcessing(eq(77L), eq(0L), any())).thenReturn(1);
    ClaimedCredential claimed = service.claimForMemberLookup(
            "AIFULI_MALL", "tenant-a", "tk-value",
            InstoreEntryCredentialPurpose.HOME, null);
    assertThat(claimed.processingVersion()).isEqualTo(1L);

    when(credentialMapper.markUsed(eq(77L), eq(1L), any())).thenReturn(1);
    service.markLookupSucceeded(77L, 1L);
}

@Test
void failedLookupReleasesUnexpiredProcessingCredential() {
    InstoreEntryCredentialDO processing = activeCredential(
            InstoreEntryCredentialPurpose.HOME, null, future());
    processing.setStatus(InstoreEntryCredentialStatus.PROCESSING.getCode());
    processing.setVersion(1L);
    when(credentialMapper.selectById(77L)).thenReturn(processing);
    when(credentialMapper.releaseProcessing(eq(77L), eq(1L), any())).thenReturn(1);
    service.releaseAfterLookupFailure(77L, 1L);
}
```

测试类的私有工厂固定为：

```java
private static InstoreEntryCredentialDO activeCredential(
        InstoreEntryCredentialPurpose purpose, Long instoreOrderId,
        LocalDateTime expiresAt) {
    return InstoreEntryCredentialDO.builder()
            .id(77L)
            .credentialNo("IEC_1")
            .bindingId(11L)
            .tokenDigest(new byte[32])
            .purpose(purpose.name())
            .instoreOrderId(instoreOrderId)
            .status(InstoreEntryCredentialStatus.ACTIVE.getCode())
            .expiresAt(expiresAt)
            .version(0L)
            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
            .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
            .build();
}

private static LocalDateTime future() {
    return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10);
}

private static BindingSnapshot bindingSnapshot() {
    return new BindingSnapshot(
            11L, 1001L, "AIFULI_MALL", "tenant-a",
            "opaque-user", "RT_MART", 0L);
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreEntryCredentialServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 编译失败，四个新类型尚不存在。

- [ ] **Step 3: 实现 DO、配置和 Mapper**

`InstoreEntryCredentialDO` 字段必须与 014 一一对应，且不得出现明文 token：

```java
@TableName("sc_instore_entry_credential")
@Data
@Builder
public class InstoreEntryCredentialDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String credentialNo;
    private Long bindingId;
    private byte[] tokenDigest;
    private String purpose;
    private Long instoreOrderId;
    private Short status;
    private LocalDateTime expiresAt;
    private LocalDateTime processingStartedAt;
    private LocalDateTime usedAt;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`InstoreEntryCredentialProperties`：

```java
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.instore.entry-credential")
public class InstoreEntryCredentialProperties {
    private Duration ttl = Duration.ofMinutes(10);
}
```

Mapper 只用 LambdaWrapper，实现以下方法：

```java
default InstoreEntryCredentialDO selectByTokenDigest(byte[] digest) {
    return selectOne(new LambdaQueryWrapper<InstoreEntryCredentialDO>()
            .eq(InstoreEntryCredentialDO::getTokenDigest, digest));
}

default int claimProcessing(Long id, long expectedVersion, LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
            .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode())
            .set(InstoreEntryCredentialDO::getProcessingStartedAt, now)
            .set(InstoreEntryCredentialDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreEntryCredentialDO::getId, id)
            .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
            .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.ACTIVE.getCode())
            .gt(InstoreEntryCredentialDO::getExpiresAt, now));
}

default int markUsed(Long id, long expectedVersion, LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
            .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.USED.getCode())
            .set(InstoreEntryCredentialDO::getUsedAt, now)
            .set(InstoreEntryCredentialDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreEntryCredentialDO::getId, id)
            .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
            .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode()));
}

default int releaseProcessing(Long id, long expectedVersion, LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
            .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.ACTIVE.getCode())
            .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
            .set(InstoreEntryCredentialDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreEntryCredentialDO::getId, id)
            .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
            .eq(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.PROCESSING.getCode())
            .gt(InstoreEntryCredentialDO::getExpiresAt, now));
}

default int markExpired(Long id, long expectedVersion, LocalDateTime now) {
    return update(null, new LambdaUpdateWrapper<InstoreEntryCredentialDO>()
            .set(InstoreEntryCredentialDO::getStatus, InstoreEntryCredentialStatus.EXPIRED.getCode())
            .set(InstoreEntryCredentialDO::getProcessingStartedAt, null)
            .set(InstoreEntryCredentialDO::getUpdatedAt, now)
            .setSql("version = version + 1")
            .eq(InstoreEntryCredentialDO::getId, id)
            .eq(InstoreEntryCredentialDO::getVersion, expectedVersion)
            .in(InstoreEntryCredentialDO::getStatus,
                    InstoreEntryCredentialStatus.ACTIVE.getCode(),
                    InstoreEntryCredentialStatus.PROCESSING.getCode())
            .le(InstoreEntryCredentialDO::getExpiresAt, now));
}
```

`markUsed` 只能匹配 PROCESSING；`releaseProcessing` 只能匹配 PROCESSING 且 `expires_at > now`，目标为 ACTIVE 并清空 `processing_started_at`；`markExpired` 只允许 ACTIVE/PROCESSING 且 `expires_at <= now`。每次都增加版本并检查返回行数。

- [ ] **Step 4: 实现凭证服务**

类上使用：

```java
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InstoreEntryCredentialProperties.class)
public class InstoreEntryCredentialService {
```

关键不变量：

```java
@Transactional(transactionManager = "supplyChainTransactionManager")
public IssuedCredential issueHome(Long buyerId, String sourceSystem,
                                  String externalTenantId, String buyerUserId,
                                  String channelCode) {
    return issue(buyerId, sourceSystem, externalTenantId, buyerUserId,
            channelCode, InstoreEntryCredentialPurpose.HOME, null);
}

@Transactional(transactionManager = "supplyChainTransactionManager")
public IssuedCredential issueDetail(Long buyerId, String sourceSystem,
                                    String externalTenantId, String buyerUserId,
                                    String channelCode, Long instoreOrderId) {
    if (instoreOrderId == null) {
        throw exception(INSTORE_CREDENTIAL_INVALID);
    }
    return issue(buyerId, sourceSystem, externalTenantId, buyerUserId,
            channelCode, InstoreEntryCredentialPurpose.DETAIL, instoreOrderId);
}
```

私有 `issue` 必须：解析/创建稳定绑定；生成 32 随机字节并以 `tk-` 加 64 位小写 hex 返回；数据库只写 `sha256(rawToken)`；`expiresAt=UTC now + ttl`；状态 ACTIVE；日志只打印 `credentialNo/bindingId/purpose/expiresAt`。

`claimForMemberLookup` 顺序固定为：

1. raw token 为空 → `INSTORE_CREDENTIAL_INVALID`。
2. SHA-256 摘要查凭证，不存在 → `INSTORE_CREDENTIAL_INVALID`。
3. 加载稳定绑定并比较 `sourceSystem/externalTenantId`，不一致 → `INSTORE_TENANT_SCOPE_CONFLICT`。
4. 校验 purpose 和 DETAIL order，不一致 → `INSTORE_CREDENTIAL_INVALID`。
5. `expiresAt <= now`：尝试 `markExpired` 后抛 `INSTORE_CREDENTIAL_EXPIRED`。
6. USED → `INSTORE_CREDENTIAL_ALREADY_USED`；PROCESSING → `INSTORE_CONCURRENT_UPDATE`。
7. ACTIVE 执行 `claimProcessing`；影响行数 0 时重新加载一次并按最新状态映射，仍无法解释则抛 `INSTORE_CONCURRENT_UPDATE`。

返回值固定为：

```java
public record IssuedCredential(String credentialNo, String token,
                               InstoreEntryCredentialPurpose purpose,
                               Long instoreOrderId, LocalDateTime expiresAt) {}

public record ClaimedCredential(Long credentialId, long processingVersion,
                                Long bindingId, Long buyerId,
                                String sourceSystem, String externalTenantId,
                                String buyerUserId, String channelCode,
                                InstoreEntryCredentialPurpose purpose,
                                Long instoreOrderId) {}
```

`markLookupSucceeded` 和 `releaseAfterLookupFailure` 都必须检查 Mapper 返回 1；返回 0 抛 `INSTORE_CONCURRENT_UPDATE`。释放时若已过期则改走 `markExpired`，不能恢复 ACTIVE。

- [ ] **Step 5: 运行凭证单测与全量 INSTORE 单测**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='Instore*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 新凭证单测与原 78 个 INSTORE 基线测试全部通过，测试总数高于 78。

- [ ] **Step 6: 提交任务 5**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreEntryCredentialDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreEntryCredentialMapper.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialProperties.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialService.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialServiceTest.java
git commit -m "feat(supply-chain): add INSTORE one-time entry credentials"
```

---

### Task 6: 用真实 MySQL 验证并发消费并更新联调文档

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialIntegrationTest.java`
- Modify: `docs/集成测试.md`

- [ ] **Step 1: 写并发 IntegrationTest**

测试配置遵循 `FundReservationIntegrationTest`：Testcontainers MySQL 8.4，可由 `supply-chain.test.jdbc-*` 覆盖；`@Import` 包含 `SupplyChainDataSourceConfiguration`、`UlidGenerator`、`BusinessNoGenerator`、`InstoreChannelUserBindingResolver`、`InstoreEntryCredentialService`。

测试方法声明 `throws Exception`，并显式导入 `ClaimedCredential`、`IssuedCredential` 两个嵌套 record。单个测试方法执行以下断言：

```java
IssuedCredential first = service.issueHome(
        1001L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART");
IssuedCredential second = service.issueHome(
        1001L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART");
assertThat(first.token()).isNotEqualTo(second.token());

ConcurrentResults<ClaimedCredential> claims = concurrent(2, () ->
        service.claimForMemberLookup(
                "AIFULI_MALL", "tenant-a", first.token(),
                InstoreEntryCredentialPurpose.HOME, null));

assertThat(claims.successes()).hasSize(1);
assertThat(claims.failures()).hasSize(1);
assertThat(((ServiceException) claims.failures().getFirst()).getCode())
        .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());

ClaimedCredential winner = claims.successes().getFirst();
service.markLookupSucceeded(winner.credentialId(), winner.processingVersion());

assertThatThrownBy(() -> service.claimForMemberLookup(
        "AIFULI_MALL", "tenant-a", first.token(),
        InstoreEntryCredentialPurpose.HOME, null))
        .isInstanceOf(ServiceException.class)
        .extracting(error -> ((ServiceException) error).getCode())
        .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_ALREADY_USED.getCode());

ClaimedCredential independent = service.claimForMemberLookup(
        "AIFULI_MALL", "tenant-a", second.token(),
        InstoreEntryCredentialPurpose.HOME, null);
assertThat(independent).isNotNull();
```

再用 `JdbcTemplate` 断言：

```java
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='sc_instore_entry_credential' "
                + "AND COLUMN_NAME='token'", Integer.class)).isZero();
assertThat(jdbc.queryForObject(
        "SELECT OCTET_LENGTH(token_digest) FROM sc_instore_entry_credential "
                + "WHERE credential_no=?", Integer.class, first.credentialNo()))
        .isEqualTo(32);
```

并发 helper 必须完整放入测试类，确保两个线程在同一道闸门后同时发起 claim：

```java
private static <T> ConcurrentResults<T> concurrent(
        int taskCount, Callable<T> task) throws Exception {
    CountDownLatch ready = new CountDownLatch(taskCount);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(taskCount)) {
        List<Future<T>> futures = new ArrayList<>(taskCount);
        for (int index = 0; index < taskCount; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();

        List<T> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                successes.add(future.get());
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            }
        }
        return new ConcurrentResults<>(successes, failures);
    }
}

private record ConcurrentResults<T>(List<T> successes, List<Throwable> failures) {
}
```

- [ ] **Step 2: 运行凭证与迁移集成测试**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='SupplyChainLiquibaseIntegrationTest,InstoreProductionInvariantsMigrationIntegrationTest,InstoreEntryCredentialIntegrationTest' \
  verify
```

Expected: 三个 IntegrationTest 类全部通过；同 token 两个并发请求恰好一个 claim 成功。

- [ ] **Step 3: 更新集成测试文档**

在 `docs/集成测试.md` 第 3.3 节登记：

```markdown
自动化测试类：

- `SupplyChainLiquibaseIntegrationTest`：空库 001→014 与二次启动幂等。
- `InstoreProductionInvariantsMigrationIntegrationTest`：013→014 唯一租户回填、歧义失败关闭、支付卫星 1:1。
- `InstoreEntryCredentialIntegrationTest`：多凭证并存、单凭证并发唯一 claim、摘要-only 持久化。
```

同节补充：`InstoreProductionInvariantsMigrationIntegrationTest` 使用独占空库；无 Docker 时通过
`-Dsupply-chain.migration-test.jdbc-url`、`-Dsupply-chain.migration-test.jdbc-username`、
`-Dsupply-chain.migration-test.jdbc-password` 指向专用空库，禁止与
`supply-chain.test.jdbc-*` 共用数据库。

把实际执行日期、测试数量和结果写入文档；不得预填未运行的成功结果。

- [ ] **Step 4: 执行完整供应链验证**

Unit:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='Instore*Test,SupplyChainErrorMappingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Integration:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

Expected: 两条命令 `BUILD SUCCESS`；若 Docker 不可用，使用 `docs/集成测试.md` 已记录的外部 MySQL 参数重跑，不能把未执行标为通过。

- [ ] **Step 5: 检查变更边界和敏感数据**

Run:

```bash
git diff --check
rg -n 'getToken\(|setToken\(|token VARCHAR|log\..*token' \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql
git status --short
```

Expected: `git diff --check` 无输出；新凭证路径没有明文 token 字段或日志；仅 013 兼容 DO 字段仍匹配搜索。

- [ ] **Step 6: 提交任务 6**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreEntryCredentialIntegrationTest.java \
  docs/集成测试.md
git commit -m "test(supply-chain): verify INSTORE credential concurrency"
```

---

## 完成标准

- `014` 只做前向迁移，`013` 文件哈希不变。
- 旧 INSTORE 数据只有在每个 Buyer 恰好映射一个当前启用来源租户时才能升级；零个或多个都失败关闭。
- `(source_system, external_tenant_id, buyer_user_id, channel_code)` 是稳定绑定唯一键。
- 新凭证表没有明文 token 列，摘要固定 32 字节；HOME/DETAIL 由 CHECK 约束。
- 同一稳定绑定允许多枚 ACTIVE 凭证；同一凭证并发只有一个请求进入 PROCESSING。
- 明确查询失败可释放未过期凭证；成功后 USED，不可再次消费；崩溃遗留 PROCESSING 到期失效。
- 同一 `instore_order_id` 的第二条支付卫星由 MySQL 唯一约束拒绝。
- 原有 INSTORE 单元测试、全量供应链 IntegrationTest 和新增门禁全部通过。
