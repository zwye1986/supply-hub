# 平台全面去 Druid 换装 Hikari 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从全仓彻底移除 Druid Maven 依赖并以 HikariCP 替代，旧 Druid 深绑定代码原位桩化作废并文档归档，供应链预算控制链路重构为纯标准 JDBC + Hikari 实现。

**Architecture:** 采用方案 A（原位桩代码作废 + 原实现归档）。新预算链路由 `StandardBudgetRoutingDataSource` → `StandardBudgetConnectionWrapper` → `StandardBudgetStatementWrapper/PreparedStatementWrapper/CallableStatementWrapper` 四层构成；脏连接淘汰走基础设施适配协议（Hikari `evictConnection` + 驱动 `abort`）；超时语义严格分离（驱动 `connectTimeout=1500` 与池借用 `connectionTimeout=3000`）。

**Tech Stack:** Spring Boot 3.5.14（HikariCP 6.3.3）、dynamic-datasource-spring-boot3-starter 4.5.0、MySQL Connector/J、MyBatis Plus、JUnit 5 + AssertJ + Mockito + Testcontainers、ArchUnit。

**设计依据:** `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`（已双轴评审通过）。

---

## 文件结构总览

### 新建（供应链模块，包 `com.aifuli.module.supplychain.infrastructure.configuration`）
| 文件 | 职责 |
| :--- | :--- |
| `StandardBudgetRoutingDataSource.java` | 预算路由数据源：借前准入、借后首检、迟到连接淘汰（evictAndAbort）、池生命周期（DisposableBean/AutoCloseable） |
| `StandardBudgetConnectionWrapper.java` | 预算连接包装器：networkTimeout 注入/恢复、SESSION 锁等待 control 通道、commit 守卫、cleanup 模式、恢复失败淘汰协议 |
| `StandardBudgetStatementWrapper.java` | 预算 Statement 包装器：执行前 q 守卫、observedMinPositive 记忆 |
| `StandardBudgetPreparedStatementWrapper.java` | 预算 PreparedStatement 包装器 |
| `StandardBudgetCallableStatementWrapper.java` | 预算 CallableStatement 包装器 |

### 新建（测试，同包 test 目录）
| 文件 | 职责 |
| :--- | :--- |
| `StandardBudgetConnectionWrapperTest.java` | 连接包装器纯单元测试（Mockito） |
| `StandardBudgetStatementWrapperTest.java` | Statement 包装器纯单元测试 |
| `StandardBudgetPreparedStatementWrapperTest.java` | PreparedStatement 包装器纯单元测试 |
| `StandardBudgetCallableStatementWrapperTest.java` | CallableStatement 包装器纯单元测试 |
| `StandardBudgetRoutingDataSourceEvictionTest.java` | 路由数据源纯单元测试（Mockito，含淘汰协议与生命周期） |
| `StandardBudgetRoutingDataSourceTest.java` | 路由数据源真实 MySQL 测试（Testcontainers，由旧测试迁移） |
| `DeprecatedDruidStubTest.java` | 旧桩类作废契约测试（反射断言 @Deprecated + 构造熔断） |

### 修改（供应链模块）
| 文件 | 变更 |
| :--- | :--- |
| `configuration/SupplyChainDataSourceConfiguration.java` | 数据源装配改为 HikariDataSource + StandardBudgetRoutingDataSource |
| `configuration/BudgetRoutingDataSource.java` | 原位桩化作废 |
| `configuration/BudgetConnectionWrapper.java` | 原位桩化作废 |
| `configuration/BudgetStatementWrapper.java` | 原位桩化作废 |
| `configuration/BudgetPreparedStatementWrapper.java` | 原位桩化作废 |
| `configuration/MySqlSocketExtractor.java` | 原位桩化作废 |
| `test/.../BudgetDataSourceConfigurationTest.java` | 重写为 Hikari 装配断言 |
| `test/.../BudgetRoutingDataSourceTest.java` | 删除（由 StandardBudgetRoutingDataSourceTest 替代） |
| `test/.../ClaimRecoveryIntegrationTest.java` | `unwrap(DruidDataSource)` 改 `unwrap(HikariDataSource)`，证据输出迁移 |
| `test/.../architecture/SupplyChainModuleArchitectureTest.java` | 新增 3 条 ArchUnit 规则 |

### 修改（框架）
| 文件 | 变更 |
| :--- | :--- |
| `aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml` | 增加 spring-boot-starter-jdbc（Task 1）；删除 druid-spring-boot-3-starter（Task 8） |
| `aifuli-framework/.../datasource/core/filter/DruidAdRemoveFilter.java` | 原位桩化作废 |
| `aifuli-framework/.../datasource/config/AifuliDataSourceAutoConfiguration.java` | 删除 DruidStatProperties 与过滤器 Bean |
| `aifuli-framework/aifuli-spring-boot-starter-test/.../BaseDbUnitTest.java` | 删除 DruidDataSourceAutoConfigure 引用 |
| `aifuli-framework/aifuli-spring-boot-starter-test/.../BaseDbAndRedisUnitTest.java` | 删除 DruidDataSourceAutoConfigure 引用 |

### 修改（根依赖与各进程配置）
| 文件 | 变更 |
| :--- | :--- |
| `aifuli-dependencies/pom.xml` | 删除 druid.version 与 dependencyManagement 条目（Task 8） |
| system/infra/member/aifuli-server 的 `application-local.yaml`、aifuli-server `application-dev.yaml` | 删除 druid 监控配置段，dynamic.druid → dynamic.hikari |
| `script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/{system,infra,member}-server-dev.yaml` | 同上 |
| system/infra/member 的 `src/test/resources/application-unit-test.yaml` | druid 段 → hikari 段 |
| system/infra/member 的 `SecurityConfiguration.java` | 删除 `/druid/**` 白名单 |

### 新建（文档与部署）
| 文件 | 职责 |
| :--- | :--- |
| `docs/superpowers/plans/2026-10-09-druid-to-hikari-migration.md` | 本计划 |
| `script/deploy/supplyhub-test/2026-10-09-druid-monitor-offline.sql` | Druid 监控菜单下线 SQL |

---

## Task 1: 框架引入 spring-boot-starter-jdbc（HikariCP 进入 classpath，Druid 暂存）

**Files:**
- Modify: `aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml:66-70`

- [ ] **Step 1: 在 mybatis starter 中插入 spring-boot-starter-jdbc 依赖**

在 `aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml` 中，将以下旧文本：

```xml
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid-spring-boot-3-starter</artifactId>
        </dependency>
```

替换为（**本任务暂保留 Druid，保证旧代码继续编译；Task 8 再删除**）：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <!-- TODO(2026-10-09)：druid-spring-boot-3-starter 为迁移过渡期临时保留，
             将在 Task 8 与 aifuli-dependencies 中的依赖管理一并彻底移除。
             详见 docs/superpowers/plans/2026-10-09-druid-to-hikari-migration.md -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid-spring-boot-3-starter</artifactId>
        </dependency>
```

> 版本无需声明：根 POM 经 `spring-boot-dependencies` 3.5.14 BOM 管理，HikariCP 版本为 6.3.3。

- [ ] **Step 2: 验证供应链服务模块编译通过且 HikariCP 已进入依赖树**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am compile -q
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server dependency:tree -Dincludes=com.zaxxer:HikariCP
```

Expected: 第一条命令 `BUILD SUCCESS`；第二条命令输出含 `com.zaxxer:HikariCP:jar:6.3.3`。

- [ ] **Step 3: Commit**

```bash
git add aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml
git commit -m "依赖: mybatis starter 引入 spring-boot-starter-jdbc 为 Hikari 迁移做准备"
```

---

## Task 2: 新建 StandardBudgetConnectionWrapper（TDD）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetConnectionWrapper.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetConnectionWrapperTest.java`

- [ ] **Step 1: 编写失败测试**

创建 `StandardBudgetConnectionWrapperTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准预算连接包装器单元测试。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#43-预算控制连接包装器standardbudgetconnectionwrapper
 */
class StandardBudgetConnectionWrapperTest {

    private Connection delegate;
    private BudgetContext context;
    @SuppressWarnings("unchecked")
    private Consumer<Connection> evictor;
    private long executionDeadlineE;

    @BeforeEach
    void setUp() throws SQLException {
        delegate = mock(Connection.class);
        when(delegate.getNetworkTimeout()).thenReturn(30000);
        evictor = mock(Consumer.class);
        long now = System.nanoTime();
        context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        executionDeadlineE = now + TimeUnit.SECONDS.toNanos(6);
    }

    private StandardBudgetConnectionWrapper newWrapper() {
        return new StandardBudgetConnectionWrapper(delegate, context, executionDeadlineE, evictor);
    }

    @Test
    void constructorInstallsBudgetNetworkTimeout() throws SQLException {
        newWrapper();

        verify(delegate).getNetworkTimeout();
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate).setNetworkTimeout(any(), timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isBetween(1, 7000);
    }

    @Test
    void ensureSessionLockWaitTimeoutUsesControlChannelOnce() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(rawStatement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(50);

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.ensureSessionLockWaitTimeout(2);

        verify(rawStatement).execute("SET SESSION innodb_lock_wait_timeout = 2");
        // 二次调用不重复安装
        wrapper.ensureSessionLockWaitTimeout(2);
        verify(delegate, times(1)).createStatement();
    }

    @Test
    void closeRestoresSessionAndNetworkTimeoutHealthyReturn() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(delegate.isClosed()).thenReturn(false);

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.ensureSessionLockWaitTimeout(2);
        wrapper.close();

        verify(rawStatement).execute("SET SESSION innodb_lock_wait_timeout = 50");
        verify(delegate).setNetworkTimeout(any(), eq(30000));
        verify(delegate).close();
        assertThat(context.isCleanupFailed()).isFalse();
    }

    @Test
    void closeRestoreFailureMarksCleanupFailedAndEvicts() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(delegate.isClosed()).thenReturn(false);
        doThrow(new SQLException("network down")).when(delegate).setNetworkTimeout(any(), anyInt());

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.ensureSessionLockWaitTimeout(2);
        wrapper.close();

        assertThat(context.isCleanupFailed()).isTrue();
        verify(evictor).accept(delegate);
        verify(delegate).close();
    }

    @Test
    void isClosedProbeFailureMarksCleanupFailedAndEvicts() throws SQLException {
        when(delegate.isClosed()).thenThrow(new SQLException("connection lost"));

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.close();

        assertThat(context.isCleanupFailed()).isTrue();
        verify(evictor).accept(delegate);
        verify(delegate).close();
    }

    @Test
    void commitGuardRejectsExpiredBudget() {
        StandardBudgetConnectionWrapper wrapper =
                new StandardBudgetConnectionWrapper(delegate, context, System.nanoTime() - 1000, evictor);

        assertThatThrownBy(wrapper::commit).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).commit();
    }

    @Test
    void rollbackEntersCleanupModeAndBypassesGuard() throws SQLException {
        StandardBudgetConnectionWrapper wrapper =
                new StandardBudgetConnectionWrapper(delegate, context, System.nanoTime() - 1000, evictor);

        wrapper.rollback();

        verify(delegate).rollback();
        assertThat(wrapper.isCleanupMode()).isTrue();
        // cleanup 模式下 Statement 创建不再被 E 守卫拒绝
        assertThat(wrapper.createStatement()).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=StandardBudgetConnectionWrapperTest -DfailIfNoTests=false -q`
Expected: FAIL，编译错误 `cannot find symbol: class StandardBudgetConnectionWrapper`。

- [ ] **Step 3: 编写最小实现**

创建 `StandardBudgetConnectionWrapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import lombok.extern.slf4j.Slf4j;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 预算感知 Connection 包装器（Hikari + 标准 JDBC 链路）。
 *
 * <p>依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#43-预算控制连接包装器standardbudgetconnectionwrapper}：</p>
 * <ul>
 *   <li>借用即保存驱动原始 networkTimeout 并安装预算限制 min(7000ms, 剩余预算)；</li>
 *   <li>SESSION innodb_lock_wait_timeout 的读取与 SET 走同一物理连接的封闭非递归 control 通道，不触发外层包装器；</li>
 *   <li>rollback 进入独占 cleanup 模式，回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）；</li>
 *   <li>退出恢复 SESSION 与 networkTimeout，任一恢复失败即 markCleanupFailed 并触发基础设施淘汰协议（evictConnection + abort），严禁重跑（R02.6 等价契约）。</li>
 * </ul>
 */
@Slf4j
public class StandardBudgetConnectionWrapper implements Connection {

    private final Connection delegate;
    private final BudgetContext context;
    private final long executionDeadlineE;
    private final Consumer<Connection> evictor;
    private final int originalNetworkTimeout;

    private volatile boolean sessionTimeoutInstalled = false;
    private int originalSessionTimeout = 50;
    private volatile boolean cleanupMode = false;

    public StandardBudgetConnectionWrapper(Connection delegate,
                                           BudgetContext context,
                                           long executionDeadlineE,
                                           Consumer<Connection> evictor) {
        this.delegate = delegate;
        this.context = context;
        this.executionDeadlineE = executionDeadlineE;
        this.evictor = evictor;

        int origTimeout = 0;
        try {
            origTimeout = delegate.getNetworkTimeout();
        } catch (Throwable e) {
            log.warn("读取原始 networkTimeout 失败，按 0 处理: {}", e.getMessage());
        }
        this.originalNetworkTimeout = origTimeout;

        try {
            long remaining = Math.min(executionDeadlineE - System.nanoTime(),
                    context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - System.nanoTime());
            int socketTimeoutMs = (int) Math.min(7000L, Math.max(1L, remaining / 1_000_000L));
            delegate.setNetworkTimeout(Runnable::run, socketTimeoutMs);
        } catch (Throwable e) {
            log.warn("无法安装初始 socket timeout: {}", e.getMessage());
        }
    }

    public boolean isCleanupMode() {
        return cleanupMode;
    }

    /**
     * SESSION innodb_lock_wait_timeout 安装：固定读取原值 + 固定正整数 SET。
     * 全部经同一物理连接执行，绝不触发外层 Statement 包装器（封闭非递归 control 通道）。
     */
    public synchronized void ensureSessionLockWaitTimeout(int targetSec) {
        if (sessionTimeoutInstalled) {
            return;
        }
        try (Statement s = delegate.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                if (rs.next()) {
                    originalSessionTimeout = rs.getInt(1);
                }
            }
            s.execute("SET SESSION innodb_lock_wait_timeout = " + targetSec);
            sessionTimeoutInstalled = true;
        } catch (SQLException e) {
            log.warn("设置 SESSION innodb_lock_wait_timeout 失败: {}", e.getMessage());
        }
    }

    /** 业务 setup / Statement 创建前守卫；cleanup 模式放行（R01.5 双模式分离）。 */
    private void checkGuard() {
        if (cleanupMode) {
            return;
        }
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("连接操作前检测到已取消或中断");
        }
        if (System.nanoTime() >= executionDeadlineE) {
            throw new BudgetTimeoutException("连接操作前检测到已超过预算执行期限 E");
        }
    }

    /** commit 前最后收紧：MyBatis flush 之后的 beforeCommit 守卫（R01.6）。 */
    private void checkCommitGuard() {
        if (cleanupMode) {
            return;
        }
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("commit 前检测到已取消或中断");
        }
        long now = System.nanoTime();
        if (now >= executionDeadlineE || now >= context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS) {
            throw new BudgetTimeoutException("commit 前检测到预算耗尽，拒绝提交");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(delegate.createStatement(), this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(delegate.createStatement(resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(
                delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, autoGeneratedKeys),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, columnIndexes),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, columnNames),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(
                delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(delegate.prepareCall(sql), this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(delegate.prepareCall(sql, resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(
                delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkGuard();
        delegate.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        checkCommitGuard();
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        // 进入独占 cleanup 模式：回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）
        cleanupMode = true;
        delegate.rollback();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        cleanupMode = true;
        delegate.rollback(savepoint);
    }

    @Override
    public void close() throws SQLException {
        cleanupMode = true;
        Throwable cleanupFailure = null;
        boolean alreadyClosed;
        try {
            alreadyClosed = delegate.isClosed();
        } catch (Throwable t) {
            alreadyClosed = false;
            cleanupFailure = t;
        }
        if (!alreadyClosed) {
            if (sessionTimeoutInstalled) {
                try (Statement s = delegate.createStatement()) {
                    s.execute("SET SESSION innodb_lock_wait_timeout = " + originalSessionTimeout);
                } catch (Throwable t) {
                    cleanupFailure = appendSuppressed(cleanupFailure, t);
                }
            }
            try {
                delegate.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            } catch (Throwable t) {
                cleanupFailure = appendSuppressed(cleanupFailure, t);
            }
        }
        if (cleanupFailure != null) {
            // 任一恢复失败即标记清理失败并物理淘汰，严禁事务重跑（R02.6 等价契约）
            context.markCleanupFailed();
            log.error("[预算连接关闭] 连接状态恢复失败，触发物理淘汰与池剔除", cleanupFailure);
            try {
                evictor.accept(delegate);
            } catch (Throwable t) {
                log.warn("[预算连接关闭] 物理淘汰异常: {}", t.getMessage());
            } finally {
                try {
                    delegate.close();
                } catch (Throwable t) {
                    log.warn("[预算连接关闭] 淘汰后代理 close 异常: {}", t.getMessage());
                }
            }
            return;
        }
        try {
            delegate.close();
        } catch (SQLException e) {
            context.markCleanupFailed();
            throw e;
        }
    }

    private static Throwable appendSuppressed(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkGuard();
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkGuard();
        delegate.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return delegate.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkGuard();
        delegate.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        delegate.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkGuard();
        delegate.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkGuard();
        return delegate.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkGuard();
        return delegate.setSavepoint(name);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkGuard();
        delegate.releaseSavepoint(savepoint);
    }

    @Override
    public Clob createClob() throws SQLException {
        return delegate.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return delegate.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return delegate.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return delegate.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        delegate.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        delegate.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return delegate.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return delegate.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return delegate.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkGuard();
        delegate.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return delegate.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        delegate.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkGuard();
        delegate.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return delegate.isWrapperFor(iface);
    }
}
```

> 注意：本文件引用 `StandardBudgetStatementWrapper` / `StandardBudgetPreparedStatementWrapper` / `StandardBudgetCallableStatementWrapper`，它们在 Task 3、Task 4 中创建。因此本任务 Step 4 的编译验证方式改为：只运行 `test-compile` 校验测试编译，并在 Task 4 完成后统一运行全量单元测试。为避免破坏编译连续性，请将 Task 2/3/4 视为一个连续提交单元——**Task 2 的提交在 Task 3、Task 4 完成之后执行**（见各任务末的提交说明）。

- [ ] **Step 4: 编译验证（不运行测试）**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile -q`
Expected: 失败——编译错误提示 `StandardBudgetStatementWrapper` 等类不存在（这正是 Task 3/4 待创建的类）。确认报错仅缺这三个类后继续。

- [ ] **Step 5: 暂不提交，直接进入 Task 3（提交动作延后至 Task 4 完成后）**

---

## Task 3: 新建 StandardBudgetStatementWrapper（基于归档旧类变换）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetStatementWrapper.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetStatementWrapperTest.java`

- [ ] **Step 1: 编写失败测试**

创建 `StandardBudgetStatementWrapperTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准预算 Statement 包装器单元测试。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系
 */
class StandardBudgetStatementWrapperTest {

    private Statement delegate;
    private StandardBudgetConnectionWrapper connectionWrapper;
    private BudgetContext context;
    private long executionDeadlineE;

    @BeforeEach
    void setUp() {
        delegate = mock(Statement.class);
        connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        executionDeadlineE = now + TimeUnit.SECONDS.toNanos(6);
    }

    private StandardBudgetStatementWrapper newWrapper() {
        return new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, executionDeadlineE);
    }

    @Test
    void executeInstallsBoundedQueryTimeoutAndSessionLockWait() throws SQLException {
        StandardBudgetStatementWrapper wrapper = newWrapper();

        wrapper.executeQuery("SELECT 1");

        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate).setQueryTimeout(timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isBetween(1, 5);
    }

    @Test
    void expiredBudgetRejectsExecutionBeforeDelegate() throws SQLException {
        StandardBudgetStatementWrapper wrapper =
                new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, System.nanoTime() - 1000);

        assertThatThrownBy(() -> wrapper.execute("SELECT 1")).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).execute(anyString());
    }

    @Test
    void observedMinPositiveQueryTimeoutIsRespected() throws SQLException {
        StandardBudgetStatementWrapper wrapper = newWrapper();

        wrapper.setQueryTimeout(3);
        wrapper.execute("SELECT 1");

        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate, atLeastOnce()).setQueryTimeout(timeoutCaptor.capture());
        assertThat(timeoutCaptor.getAllValues()).allMatch(v -> v >= 1 && v <= 3);
    }

    @Test
    void cleanupModeSkipsBudgetGuard() throws SQLException {
        when(connectionWrapper.isCleanupMode()).thenReturn(true);
        StandardBudgetStatementWrapper wrapper =
                new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, System.nanoTime() - 1000);

        wrapper.execute("SELECT 1");

        verify(delegate).execute("SELECT 1");
        verify(delegate, never()).setQueryTimeout(anyInt());
    }

    @Test
    void getConnectionReturnsWrapper() throws SQLException {
        assertThat(newWrapper().getConnection()).isSameAs(connectionWrapper);
    }
}
```

- [ ] **Step 2: 从归档文件生成实现（确定性变换）**

复制归档文件 `docs/superpowers/specs/archive/deprecated-druid/BudgetStatementWrapper.java` 的全部内容到新路径
`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetStatementWrapper.java`，
然后对其执行以下**逐字替换**（替换顺序无关）：

| # | 旧文本（归档内容） | 新文本 |
| :--- | :--- | :--- |
| 1 | `/**\n * 预算感知 Statement 包装器。\n * 依据 R01 规范：执行前守卫 queryTimeout = floor(min(5s, E-now, commandDeadline-C-now)/1s)，\n * 保留观察到的最小正限制，不足 1 秒拒绝执行。\n */` | `/**\n * 预算感知 Statement 包装器（Hikari + 标准 JDBC 链路）。\n * 依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系}：\n * 执行前守卫 queryTimeout = floor(min(5s, E-now, commandDeadline-C-now)/1s)，\n * 保留观察到的最小正限制，不足 1 秒拒绝执行；cleanup 模式跳过预算守卫。\n */` |
| 2 | `public class BudgetStatementWrapper implements Statement {` | `public class StandardBudgetStatementWrapper implements Statement {` |
| 3 | `protected final BudgetConnectionWrapper connectionWrapper;` | `protected final StandardBudgetConnectionWrapper connectionWrapper;` |
| 4 | `public BudgetStatementWrapper(Statement delegate,\n                                  BudgetConnectionWrapper connectionWrapper,` | `public StandardBudgetStatementWrapper(Statement delegate,\n                                  StandardBudgetConnectionWrapper connectionWrapper,` |
| 5 | `    protected void beforeExecute() throws SQLException {\n        if (context.isCancelled()) {` | `    protected void beforeExecute() throws SQLException {\n        // cleanup 模式（回滚/恢复阶段）跳过预算守卫（R01.5 双模式分离）\n        if (connectionWrapper.isCleanupMode()) {\n            return;\n        }\n        if (context.isCancelled()) {` |

其余方法体（executeQuery/executeUpdate/execute/executeBatch/close/getQueryTimeout/setQueryTimeout/unwrap 等全部委托）**逐字保留**，不做任何改动。

- [ ] **Step 3: 编译验证**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile -q`
Expected: 失败——编译错误提示 `StandardBudgetPreparedStatementWrapper` 与 `StandardBudgetCallableStatementWrapper` 不存在（Task 4 待创建）。确认报错仅缺这两个类后继续。

- [ ] **Step 4: 暂不提交，直接进入 Task 4**

---

## Task 4: 新建 StandardBudgetPreparedStatementWrapper 与 StandardBudgetCallableStatementWrapper

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetPreparedStatementWrapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetCallableStatementWrapper.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetPreparedStatementWrapperTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetCallableStatementWrapperTest.java`

- [ ] **Step 1: 编写失败测试**

创建 `StandardBudgetPreparedStatementWrapperTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 标准预算 PreparedStatement 包装器单元测试。
 */
class StandardBudgetPreparedStatementWrapperTest {

    @Test
    void executeInstallsGuardThenDelegates() throws Exception {
        PreparedStatement delegate = mock(PreparedStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetPreparedStatementWrapper wrapper = new StandardBudgetPreparedStatementWrapper(
                delegate, connectionWrapper, context, now + TimeUnit.SECONDS.toNanos(6));
        wrapper.executeUpdate();

        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        verify(delegate).executeUpdate();
    }

    @Test
    void expiredBudgetRejectsBeforeDelegate() throws Exception {
        PreparedStatement delegate = mock(PreparedStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetPreparedStatementWrapper wrapper = new StandardBudgetPreparedStatementWrapper(
                delegate, connectionWrapper, context, now - 1000);

        assertThatThrownBy(wrapper::execute).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).execute();
    }
}
```

创建 `StandardBudgetCallableStatementWrapperTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 标准预算 CallableStatement 包装器单元测试。
 */
class StandardBudgetCallableStatementWrapperTest {

    @Test
    void executeAppliesGuardAndDelegates() throws Exception {
        CallableStatement delegate = mock(CallableStatement.class);
        StandardBudgetConnectionWrapper connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));

        StandardBudgetCallableStatementWrapper wrapper = new StandardBudgetCallableStatementWrapper(
                delegate, connectionWrapper, context, now + TimeUnit.SECONDS.toNanos(6));

        wrapper.execute();
        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        verify(delegate).execute();

        wrapper.registerOutParameter(1, java.sql.Types.INTEGER);
        verify(delegate).registerOutParameter(1, java.sql.Types.INTEGER);
    }
}
```

- [ ] **Step 2: 从归档文件生成 PreparedStatement 包装器实现（确定性变换）**

复制归档文件 `docs/superpowers/specs/archive/deprecated-druid/BudgetPreparedStatementWrapper.java` 的全部内容到
`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetPreparedStatementWrapper.java`，
然后执行以下**逐字替换**：

| # | 旧文本（归档内容） | 新文本 |
| :--- | :--- | :--- |
| 1 | `/**\n * 预算感知 PreparedStatement 包装器。\n */` | `/**\n * 预算感知 PreparedStatement 包装器（Hikari + 标准 JDBC 链路）。\n * 设计见 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系}。\n */` |
| 2 | `public class BudgetPreparedStatementWrapper extends BudgetStatementWrapper implements PreparedStatement {` | `public class StandardBudgetPreparedStatementWrapper extends StandardBudgetStatementWrapper implements PreparedStatement {` |
| 3 | `    public BudgetPreparedStatementWrapper(PreparedStatement delegate,\n                                          BudgetConnectionWrapper connectionWrapper,` | `    public StandardBudgetPreparedStatementWrapper(PreparedStatement delegate,\n                                          StandardBudgetConnectionWrapper connectionWrapper,` |

其余方法体（executeQuery/executeUpdate/execute 调用 beforeExecute 后委托 preparedDelegate，以及全部 setter 委托）**逐字保留**。

- [ ] **Step 3: 新建 CallableStatement 包装器实现（完整代码）**

创建 `StandardBudgetCallableStatementWrapper.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

/**
 * 预算感知 CallableStatement 包装器（Hikari + 标准 JDBC 链路）。
 *
 * <p>execute* 守卫由父类 {@link StandardBudgetPreparedStatementWrapper} 提供，
 * 本类仅负责 CallableStatement 特有参数/结果方法的透明委托。</p>
 * 设计见 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系}。
 */
public class StandardBudgetCallableStatementWrapper extends StandardBudgetPreparedStatementWrapper
        implements CallableStatement {

    private final CallableStatement callableDelegate;

    public StandardBudgetCallableStatementWrapper(CallableStatement delegate,
                                                  StandardBudgetConnectionWrapper connectionWrapper,
                                                  BudgetContext context,
                                                  long executionDeadlineE) {
        super(delegate, connectionWrapper, context, executionDeadlineE);
        this.callableDelegate = delegate;
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        callableDelegate.registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        callableDelegate.registerOutParameter(parameterIndex, sqlType, scale);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        callableDelegate.registerOutParameter(parameterIndex, sqlType, typeName);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
        callableDelegate.registerOutParameter(parameterName, sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        callableDelegate.registerOutParameter(parameterName, sqlType, scale);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        callableDelegate.registerOutParameter(parameterName, sqlType, typeName);
    }

    @Override
    public boolean wasNull() throws SQLException {
        return callableDelegate.wasNull();
    }

    @Override
    public String getString(int parameterIndex) throws SQLException {
        return callableDelegate.getString(parameterIndex);
    }

    @Override
    public String getString(String parameterName) throws SQLException {
        return callableDelegate.getString(parameterName);
    }

    @Override
    public boolean getBoolean(int parameterIndex) throws SQLException {
        return callableDelegate.getBoolean(parameterIndex);
    }

    @Override
    public boolean getBoolean(String parameterName) throws SQLException {
        return callableDelegate.getBoolean(parameterName);
    }

    @Override
    public byte getByte(int parameterIndex) throws SQLException {
        return callableDelegate.getByte(parameterIndex);
    }

    @Override
    public byte getByte(String parameterName) throws SQLException {
        return callableDelegate.getByte(parameterName);
    }

    @Override
    public short getShort(int parameterIndex) throws SQLException {
        return callableDelegate.getShort(parameterIndex);
    }

    @Override
    public short getShort(String parameterName) throws SQLException {
        return callableDelegate.getShort(parameterName);
    }

    @Override
    public int getInt(int parameterIndex) throws SQLException {
        return callableDelegate.getInt(parameterIndex);
    }

    @Override
    public int getInt(String parameterName) throws SQLException {
        return callableDelegate.getInt(parameterName);
    }

    @Override
    public long getLong(int parameterIndex) throws SQLException {
        return callableDelegate.getLong(parameterIndex);
    }

    @Override
    public long getLong(String parameterName) throws SQLException {
        return callableDelegate.getLong(parameterName);
    }

    @Override
    public float getFloat(int parameterIndex) throws SQLException {
        return callableDelegate.getFloat(parameterIndex);
    }

    @Override
    public float getFloat(String parameterName) throws SQLException {
        return callableDelegate.getFloat(parameterName);
    }

    @Override
    public double getDouble(int parameterIndex) throws SQLException {
        return callableDelegate.getDouble(parameterIndex);
    }

    @Override
    public double getDouble(String parameterName) throws SQLException {
        return callableDelegate.getDouble(parameterName);
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        return callableDelegate.getBigDecimal(parameterIndex, scale);
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        return callableDelegate.getBigDecimal(parameterIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String parameterName) throws SQLException {
        return callableDelegate.getBigDecimal(parameterName);
    }

    @Override
    public byte[] getBytes(int parameterIndex) throws SQLException {
        return callableDelegate.getBytes(parameterIndex);
    }

    @Override
    public byte[] getBytes(String parameterName) throws SQLException {
        return callableDelegate.getBytes(parameterName);
    }

    @Override
    public Date getDate(int parameterIndex) throws SQLException {
        return callableDelegate.getDate(parameterIndex);
    }

    @Override
    public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        return callableDelegate.getDate(parameterIndex, cal);
    }

    @Override
    public Date getDate(String parameterName) throws SQLException {
        return callableDelegate.getDate(parameterName);
    }

    @Override
    public Date getDate(String parameterName, Calendar cal) throws SQLException {
        return callableDelegate.getDate(parameterName, cal);
    }

    @Override
    public Time getTime(int parameterIndex) throws SQLException {
        return callableDelegate.getTime(parameterIndex);
    }

    @Override
    public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        return callableDelegate.getTime(parameterIndex, cal);
    }

    @Override
    public Time getTime(String parameterName) throws SQLException {
        return callableDelegate.getTime(parameterName);
    }

    @Override
    public Time getTime(String parameterName, Calendar cal) throws SQLException {
        return callableDelegate.getTime(parameterName, cal);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        return callableDelegate.getTimestamp(parameterIndex);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        return callableDelegate.getTimestamp(parameterIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String parameterName) throws SQLException {
        return callableDelegate.getTimestamp(parameterName);
    }

    @Override
    public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
        return callableDelegate.getTimestamp(parameterName, cal);
    }

    @Override
    public Object getObject(int parameterIndex) throws SQLException {
        return callableDelegate.getObject(parameterIndex);
    }

    @Override
    public Object getObject(String parameterName) throws SQLException {
        return callableDelegate.getObject(parameterName);
    }

    @Override
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        return callableDelegate.getObject(parameterIndex, type);
    }

    @Override
    public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        return callableDelegate.getObject(parameterName, type);
    }

    @Override
    public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        return callableDelegate.getObject(parameterIndex, map);
    }

    @Override
    public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
        return callableDelegate.getObject(parameterName, map);
    }

    @Override
    public Ref getRef(int parameterIndex) throws SQLException {
        return callableDelegate.getRef(parameterIndex);
    }

    @Override
    public Ref getRef(String parameterName) throws SQLException {
        return callableDelegate.getRef(parameterName);
    }

    @Override
    public Blob getBlob(int parameterIndex) throws SQLException {
        return callableDelegate.getBlob(parameterIndex);
    }

    @Override
    public Blob getBlob(String parameterName) throws SQLException {
        return callableDelegate.getBlob(parameterName);
    }

    @Override
    public Clob getClob(int parameterIndex) throws SQLException {
        return callableDelegate.getClob(parameterIndex);
    }

    @Override
    public Clob getClob(String parameterName) throws SQLException {
        return callableDelegate.getClob(parameterName);
    }

    @Override
    public Array getArray(int parameterIndex) throws SQLException {
        return callableDelegate.getArray(parameterIndex);
    }

    @Override
    public Array getArray(String parameterName) throws SQLException {
        return callableDelegate.getArray(parameterName);
    }

    @Override
    public URL getURL(int parameterIndex) throws SQLException {
        return callableDelegate.getURL(parameterIndex);
    }

    @Override
    public URL getURL(String parameterName) throws SQLException {
        return callableDelegate.getURL(parameterName);
    }

    @Override
    public void setURL(String parameterName, URL val) throws SQLException {
        callableDelegate.setURL(parameterName, val);
    }

    @Override
    public void setNull(String parameterName, int sqlType) throws SQLException {
        callableDelegate.setNull(parameterName, sqlType);
    }

    @Override
    public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        callableDelegate.setNull(parameterName, sqlType, typeName);
    }

    @Override
    public void setBoolean(String parameterName, boolean x) throws SQLException {
        callableDelegate.setBoolean(parameterName, x);
    }

    @Override
    public void setByte(String parameterName, byte x) throws SQLException {
        callableDelegate.setByte(parameterName, x);
    }

    @Override
    public void setShort(String parameterName, short x) throws SQLException {
        callableDelegate.setShort(parameterName, x);
    }

    @Override
    public void setInt(String parameterName, int x) throws SQLException {
        callableDelegate.setInt(parameterName, x);
    }

    @Override
    public void setLong(String parameterName, long x) throws SQLException {
        callableDelegate.setLong(parameterName, x);
    }

    @Override
    public void setFloat(String parameterName, float x) throws SQLException {
        callableDelegate.setFloat(parameterName, x);
    }

    @Override
    public void setDouble(String parameterName, double x) throws SQLException {
        callableDelegate.setDouble(parameterName, x);
    }

    @Override
    public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        callableDelegate.setBigDecimal(parameterName, x);
    }

    @Override
    public void setString(String parameterName, String x) throws SQLException {
        callableDelegate.setString(parameterName, x);
    }

    @Override
    public void setBytes(String parameterName, byte[] x) throws SQLException {
        callableDelegate.setBytes(parameterName, x);
    }

    @Override
    public void setDate(String parameterName, Date x) throws SQLException {
        callableDelegate.setDate(parameterName, x);
    }

    @Override
    public void setTime(String parameterName, Time x) throws SQLException {
        callableDelegate.setTime(parameterName, x);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
        callableDelegate.setTimestamp(parameterName, x);
    }

    @Override
    public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
        callableDelegate.setDate(parameterName, x, cal);
    }

    @Override
    public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
        callableDelegate.setTime(parameterName, x, cal);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
        callableDelegate.setTimestamp(parameterName, x, cal);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
        callableDelegate.setObject(parameterName, x, targetSqlType);
    }

    @Override
    public void setObject(String parameterName, Object x) throws SQLException {
        callableDelegate.setObject(parameterName, x);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {
        callableDelegate.setObject(parameterName, x, targetSqlType, scale);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
        callableDelegate.setAsciiStream(parameterName, x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
        callableDelegate.setBinaryStream(parameterName, x, length);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
        callableDelegate.setCharacterStream(parameterName, reader, length);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
        callableDelegate.setAsciiStream(parameterName, x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
        callableDelegate.setBinaryStream(parameterName, x, length);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        callableDelegate.setCharacterStream(parameterName, reader, length);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        callableDelegate.setAsciiStream(parameterName, x);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        callableDelegate.setBinaryStream(parameterName, x);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        callableDelegate.setCharacterStream(parameterName, reader);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
        callableDelegate.setNCharacterStream(parameterName, value, length);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        callableDelegate.setNCharacterStream(parameterName, value);
    }

    @Override
    public void setNClob(String parameterName, NClob value) throws SQLException {
        callableDelegate.setNClob(parameterName, value);
    }

    @Override
    public void setClob(String parameterName, Clob x) throws SQLException {
        callableDelegate.setClob(parameterName, x);
    }

    @Override
    public void setBlob(String parameterName, Blob x) throws SQLException {
        callableDelegate.setBlob(parameterName, x);
    }

    @Override
    public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        callableDelegate.setNClob(parameterName, reader, length);
    }

    @Override
    public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        callableDelegate.setClob(parameterName, reader, length);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        callableDelegate.setBlob(parameterName, inputStream, length);
    }

    @Override
    public void setNClob(String parameterName, Reader reader) throws SQLException {
        callableDelegate.setNClob(parameterName, reader);
    }

    @Override
    public void setClob(String parameterName, Reader reader) throws SQLException {
        callableDelegate.setClob(parameterName, reader);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        callableDelegate.setBlob(parameterName, inputStream);
    }

    @Override
    public void setNString(String parameterName, String value) throws SQLException {
        callableDelegate.setNString(parameterName, value);
    }

    @Override
    public void setRowId(String parameterName, RowId x) throws SQLException {
        callableDelegate.setRowId(parameterName, x);
    }

    @Override
    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        callableDelegate.setSQLXML(parameterName, xmlObject);
    }

    @Override
    public String getNString(int parameterIndex) throws SQLException {
        return callableDelegate.getNString(parameterIndex);
    }

    @Override
    public String getNString(String parameterName) throws SQLException {
        return callableDelegate.getNString(parameterName);
    }

    @Override
    public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        return callableDelegate.getNCharacterStream(parameterIndex);
    }

    @Override
    public Reader getNCharacterStream(String parameterName) throws SQLException {
        return callableDelegate.getNCharacterStream(parameterName);
    }

    @Override
    public Reader getCharacterStream(int parameterIndex) throws SQLException {
        return callableDelegate.getCharacterStream(parameterIndex);
    }

    @Override
    public Reader getCharacterStream(String parameterName) throws SQLException {
        return callableDelegate.getCharacterStream(parameterName);
    }

    @Override
    public NClob getNClob(int parameterIndex) throws SQLException {
        return callableDelegate.getNClob(parameterIndex);
    }

    @Override
    public NClob getNClob(String parameterName) throws SQLException {
        return callableDelegate.getNClob(parameterName);
    }

    @Override
    public RowId getRowId(int parameterIndex) throws SQLException {
        return callableDelegate.getRowId(parameterIndex);
    }

    @Override
    public RowId getRowId(String parameterName) throws SQLException {
        return callableDelegate.getRowId(parameterName);
    }

    @Override
    public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        return callableDelegate.getSQLXML(parameterIndex);
    }

    @Override
    public SQLXML getSQLXML(String parameterName) throws SQLException {
        return callableDelegate.getSQLXML(parameterName);
    }
}
```

- [ ] **Step 4: 运行 Task 2/3/4 的全部新测试**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dtest='StandardBudgetConnectionWrapperTest,StandardBudgetStatementWrapperTest,StandardBudgetPreparedStatementWrapperTest,StandardBudgetCallableStatementWrapperTest' \
  -DfailIfNoTests=false -q
```

Expected: 全部通过（`BUILD SUCCESS`）。

- [ ] **Step 5: Commit（含 Task 2/3/4 全部产物）**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetConnectionWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetStatementWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetPreparedStatementWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetCallableStatementWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetConnectionWrapperTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetStatementWrapperTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetPreparedStatementWrapperTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetCallableStatementWrapperTest.java
git commit -m "供应链: 新建标准 JDBC 预算控制包装器链路（Connection/Statement/PreparedStatement/CallableStatement）"
```

---

## Task 5: 新建 StandardBudgetRoutingDataSource（TDD）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSource.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSourceEvictionTest.java`

- [ ] **Step 1: 编写失败测试**

创建 `StandardBudgetRoutingDataSourceEvictionTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准预算路由数据源纯单元测试（淘汰协议与生命周期）。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#42-预算控制路由数据源standardbudgetroutingdatasource
 */
class StandardBudgetRoutingDataSourceEvictionTest {

    private HikariDataSource raw;
    private StandardBudgetRoutingDataSource routing;

    @BeforeEach
    void setUp() {
        raw = mock(HikariDataSource.class);
        routing = new StandardBudgetRoutingDataSource(raw);
    }

    @Test
    void nonBudgetContextPassesThroughRawConnection() throws SQLException {
        BudgetContext.clear();
        Connection conn = mock(Connection.class);
        when(raw.getConnection()).thenReturn(conn);

        assertThat(routing.getConnection()).isSameAs(conn);
    }

    @Test
    void shortDeadlineIsRejectedBeforeBorrowing() {
        long now = System.nanoTime();
        BudgetContext.set(BudgetContext.create(now, now + TimeUnit.MILLISECONDS.toNanos(2000)));
        try {
            assertThatThrownBy(routing::getConnection)
                    .isInstanceOf(BudgetTimeoutException.class)
                    .hasMessageContaining("3500");
            verify(raw, never()).getConnection();
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void cancelledDuringBorrowTriggersEvictionAndAbort() throws SQLException {
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        Connection conn = mock(Connection.class);
        when(raw.getConnection()).thenAnswer(invocation -> {
            context.cancel();
            return conn;
        });
        BudgetContext.set(context);
        try {
            assertThatThrownBy(routing::getConnection).isInstanceOf(BudgetTimeoutException.class);
            verify(raw).evictConnection(conn);
            verify(conn).abort(any());
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void healthyBudgetBorrowReturnsWrappedConnection() throws SQLException {
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        Connection conn = mock(Connection.class);
        when(conn.getNetworkTimeout()).thenReturn(30000);
        when(raw.getConnection()).thenReturn(conn);
        BudgetContext.set(context);
        try (Connection borrowed = routing.getConnection()) {
            assertThat(borrowed).isInstanceOf(StandardBudgetConnectionWrapper.class);
            verify(conn).setNetworkTimeout(any(), anyInt());
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void destroyClosesRawPoolIdempotently() {
        when(raw.isClosed()).thenReturn(false).thenReturn(true);

        routing.destroy();
        routing.destroy();

        verify(raw, times(1)).close();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=StandardBudgetRoutingDataSourceEvictionTest -DfailIfNoTests=false -q`
Expected: FAIL，编译错误 `cannot find symbol: class StandardBudgetRoutingDataSource`。

- [ ] **Step 3: 编写最小实现**

创建 `StandardBudgetRoutingDataSource.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 供应链中台预算路由数据源（Hikari + 标准 JDBC 链路）。
 *
 * <p>依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#42-预算控制路由数据源standardbudgetroutingdatasource}：</p>
 * <ul>
 *   <li>无预算上下文时透明委派底层 Hikari 池，保持非预算原语义；</li>
 *   <li>有预算上下文时：借前准入核验（R &lt; 3500ms 拒绝）、借后首检（取消/超期/floor 不足 1 秒即判定迟到连接），
 *       迟到连接触发基础设施淘汰协议（Hikari evictConnection + 驱动 abort），绝不暴露给事务管理器；</li>
 *   <li>实现 {@link DisposableBean} 与 {@link AutoCloseable}，负责唯一池的幂等关闭。</li>
 * </ul>
 */
public class StandardBudgetRoutingDataSource implements DataSource, DisposableBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StandardBudgetRoutingDataSource.class);

    private final HikariDataSource rawDataSource;

    public StandardBudgetRoutingDataSource(HikariDataSource rawDataSource) {
        this.rawDataSource = Objects.requireNonNull(rawDataSource, "rawDataSource must not be null");
    }

    public HikariDataSource getRawDataSource() {
        return rawDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            return rawDataSource.getConnection();
        }
        return getBudgetConnection(context);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            return rawDataSource.getConnection(username, password);
        }
        return getBudgetConnection(context);
    }

    private Connection getBudgetConnection(BudgetContext context) throws SQLException {
        // 1. 借前准入核验：R < 3500ms 或 floor((R-C)/1s) < 1 直接拒绝
        context.checkAdmission();

        // 2. 借出连接：池借用等待由 Hikari connectionTimeout 保障，预算截断由借后首检兜底
        Connection rawConn = rawDataSource.getConnection();

        // 3. 借后首检（防迟到连接）：取消、超期、floor 不足 1 秒均判定迟到，触发淘汰协议
        long remaining = context.remainingNanos();
        long floorSec = (long) Math.floor((remaining - BudgetContext.CLEANUP_RESERVE_NANOS) / 1_000_000_000.0);
        if (context.isCancelled() || remaining < BudgetContext.ADMISSION_THRESHOLD_NANOS || floorSec < 1) {
            evictAndAbort(rawConn);
            throw new BudgetTimeoutException("借连接返回后检测到已超期或取消，拒绝暴露给事务管理器: remainingMs="
                    + (remaining / 1_000_000L));
        }

        // 4. 首检通过：计算执行期限 E = min(commandDeadline - C, acquiredNano + 6s)
        long acquiredNano = System.nanoTime();
        long executionDeadlineE = Math.min(context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS,
                acquiredNano + 6_000_000_000L);

        return new StandardBudgetConnectionWrapper(rawConn, context, executionDeadlineE, this::evictAndAbort);
    }

    /**
     * 基础设施级淘汰协议：先经 Hikari 公开 API 将池条目标记 REMOVED 并回收容量，
     * 再对驱动底座执行标准 abort 强杀底层 Socket，绝不放回池中复用。
     */
    public void evictAndAbort(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            rawDataSource.evictConnection(conn);
        } catch (Throwable t) {
            log.warn("[连接池淘汰] Hikari evictConnection 失败: {}", t.getMessage());
        } finally {
            try {
                conn.abort(Runnable::run);
            } catch (Throwable t) {
                log.error("[连接池淘汰] 物理连接 abort 异常", t);
            }
        }
    }

    @Override
    public void destroy() {
        if (!rawDataSource.isClosed()) {
            rawDataSource.close();
        }
    }

    @Override
    public void close() {
        destroy();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return rawDataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        rawDataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        rawDataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return rawDataSource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return rawDataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        // HikariDataSource 不实现 java.sql.Wrapper，显式支持受控诊断解包
        if (iface.isInstance(rawDataSource)) {
            return iface.cast(rawDataSource);
        }
        throw new SQLException("无法解包为 " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(rawDataSource);
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=StandardBudgetRoutingDataSourceEvictionTest -DfailIfNoTests=false -q`
Expected: 全部通过（`BUILD SUCCESS`）。

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSource.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSourceEvictionTest.java
git commit -m "供应链: 新建标准预算路由数据源（借前准入/借后首检/evictConnection+abort 淘汰协议/池生命周期）"
```

---

## Task 6: 数据源装配切换 Hikari 并迁移全部旧测试

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainDataSourceConfiguration.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetDataSourceConfigurationTest.java`
- Delete: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetRoutingDataSourceTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSourceTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/ClaimRecoveryIntegrationTest.java`

- [ ] **Step 1: 切换 SupplyChainDataSourceConfiguration 为 Hikari**

修改 `SupplyChainDataSourceConfiguration.java`：

1. 删除导入 `import com.alibaba.druid.pool.DruidDataSource;`，新增 `import com.zaxxer.hikari.HikariDataSource;`；
2. 删除 `import java.util.List;`（本文件唯一用途是旧 initSqls，替换后不再使用）；
3. 将 `supplyChainDataSource` Bean 方法替换为：

```java
    @Bean("supplyChainDataSource")
    DataSource supplyChainDataSource(SupplyChainDataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // 显式命名连接池：日志隔离与池身份识别（B03 解阻验证见
        // docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#adr-5b03异步建连日志解阻定位客观声明）
        dataSource.setPoolName("supply-chain-pool");
        // 驱动级物理 TCP 建连超时：经 Connector/J connectTimeout 属性注入（R02 获审 1500ms）
        dataSource.addDataSourceProperty("connectTimeout", String.valueOf(properties.getConnectTimeoutMillis()));
        // 池借用等待超时：常规健康值，预算链路借前/借后单独截断
        dataSource.setConnectionTimeout(3000L);
        // 每个物理连接都固定 UTC+8，CURRENT_TIMESTAMP(3) 才能稳定表示上海本地时间。
        // 设计见 docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#21-存储类型与时区。
        dataSource.setConnectionInitSql("SET time_zone = '+08:00'");
        dataSource.setValidationTimeout(1000L);
        return new StandardBudgetRoutingDataSource(dataSource);
    }
```

> 说明：`initializeDataSourceBuilder().build()` 经无参构造 + 属性绑定创建 HikariDataSource，池在首次 `getConnection()` 时惰性启动，因此本方法在数据库不可达时不会立即抛异常；SpringLiquibase 在启动阶段执行 DDL 时仍会按既有方式失败，保持启动失败快速可见。

- [ ] **Step 2: 重写 BudgetDataSourceConfigurationTest**

将 `BudgetDataSourceConfigurationTest.java` 全文替换为：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 R02 获审 connect-timeout-millis: 1500 配置校验
 * 与统一资源键 StandardBudgetRoutingDataSource 包装实例共享。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#41-数据源装配与生命周期管理supplychaindatasourceconfiguration
 */
class BudgetDataSourceConfigurationTest {

    @Test
    void connectTimeoutPropertiesDefaultAndValidation() {
        SupplyChainDataSourceProperties props = new SupplyChainDataSourceProperties();
        assertThat(props.getConnectTimeoutMillis()).isEqualTo(1500);

        // 允许显式设置为 1500
        props.setConnectTimeoutMillis(1500);
        assertThat(props.getConnectTimeoutMillis()).isEqualTo(1500);

        // 显式其它值（含0、负数、其它正数）必须严格拒绝
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1500");
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(3000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supplyChainDataSourceIsStandardBudgetRoutingAndSharedAcrossComponents() {
        SupplyChainDataSourceProperties properties = new SupplyChainDataSourceProperties();
        properties.setUrl("jdbc:mysql://localhost:3306/test?useSSL=false");
        properties.setUsername("root");
        properties.setPassword("root");
        properties.setDriverClassName("com.mysql.cj.jdbc.Driver");
        properties.setConnectTimeoutMillis(1500);

        SupplyChainDataSourceConfiguration config = new SupplyChainDataSourceConfiguration();
        DataSource dsBean = config.supplyChainDataSource(properties);

        assertThat(dsBean).isInstanceOf(StandardBudgetRoutingDataSource.class);
        StandardBudgetRoutingDataSource routingDs = (StandardBudgetRoutingDataSource) dsBean;
        HikariDataSource rawHikari = routingDs.getRawDataSource();
        assertThat(rawHikari).isNotNull();
        assertThat(rawHikari.getPoolName()).isEqualTo("supply-chain-pool");
        assertThat(rawHikari.getConnectionTimeout()).isEqualTo(3000L);
        // 驱动级 connectTimeout 以数据源属性注入（Connector/J 生效路径）
        assertThat(rawHikari.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("1500");

        // 验证事务管理器绑定统一包装实例资源键
        SupplyChainTransactionObservationManager txManager = config.supplyChainTransactionManager(dsBean);
        assertThat(txManager.getDataSource()).isSameAs(dsBean);

        routingDs.close();
    }
}
```

- [ ] **Step 3: 删除旧测试并新建真实 MySQL 测试**

删除 `BudgetRoutingDataSourceTest.java`，创建 `StandardBudgetRoutingDataSourceTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 R01 / R02 预算借用、超时守卫、参数安装与恢复/淘汰协议（Hikari + Testcontainers 真实 MySQL）。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#6-测试与多维门禁验证计划
 */
class StandardBudgetRoutingDataSourceTest {

    static MySQLContainer<?> mysql;
    static HikariDataSource rawHikari;
    static StandardBudgetRoutingDataSource routingDataSource;

    @BeforeAll
    static void init() {
        mysql = new MySQLContainer<>("mysql:8.4");
        mysql.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl() + "?useSSL=false&autoReconnect=false");
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("supply-chain-test-pool");
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(3000);
        config.addDataSourceProperty("connectTimeout", "1500");
        rawHikari = new HikariDataSource(config);

        routingDataSource = new StandardBudgetRoutingDataSource(rawHikari);
    }

    @AfterAll
    static void cleanup() {
        if (routingDataSource != null) {
            routingDataSource.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void nonBudgetContextReturnsNormalConnection() throws Exception {
        BudgetContext.clear();
        try (Connection conn = routingDataSource.getConnection()) {
            assertThat(conn).isNotNull();
            assertThat(BudgetContext.current()).isNull();
        }
    }

    @Test
    void budgetContextEnforcesAdmissionAndInstallGuards() throws Exception {
        long now = System.nanoTime();
        BudgetContext ctx = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        BudgetContext.set(ctx);
        try {
            try (Connection conn = routingDataSource.getConnection()) {
                assertThat(conn).isInstanceOf(StandardBudgetConnectionWrapper.class);

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                    // 验证 queryTimeout 受到 <= 5s 约束
                    assertThat(stmt.getQueryTimeout()).isBetween(1, 5);
                }
            }
            assertThat(ctx.isCleanupFailed()).isFalse();
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void budgetContextLateConnectionIsRejected() {
        long now = System.nanoTime();
        // 截止时间仅剩 2000ms（< 3500ms 准入门槛）
        BudgetContext expiredCtx = BudgetContext.create(now, now + TimeUnit.MILLISECONDS.toNanos(2000));
        BudgetContext.set(expiredCtx);
        try {
            assertThatThrownBy(() -> routingDataSource.getConnection())
                    .isInstanceOf(BudgetTimeoutException.class)
                    .hasMessageContaining("3500");
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void evictedConnectionIsNeverReusedByPool() throws Exception {
        BudgetContext.clear();
        String evictedThreadId;
        try (Connection conn = routingDataSource.getConnection()) {
            // 记录底层 MySQL 会话身份（物理连接可观测证据）
            try (Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(rs.next()).isTrue();
                evictedThreadId = rs.getString(1);
            }
            // 直接触发基础设施淘汰协议：Hikari evictConnection + 驱动 abort
            routingDataSource.evictAndAbort(conn);
        }

        // 后续借出的连接绝不复用被淘汰的物理会话（设计 §6.3.1）
        try (Connection conn = routingDataSource.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotEqualTo(evictedThreadId);
        }
    }
}
```

- [ ] **Step 4: 迁移 ClaimRecoveryIntegrationTest 的池证据**

修改 `ClaimRecoveryIntegrationTest.java`：
1. 新增导入 `com.zaxxer.hikari.HikariDataSource;`；
2. 将：

```java
            var pool = dataSource.unwrap(com.alibaba.druid.pool.DruidDataSource.class);
```

替换为：

```java
            var pool = dataSource.unwrap(HikariDataSource.class);
```

3. 将证据输出行：

```java
                    + " druidMaxActive=" + pool.getMaxActive() + " druidMaxWaitMs=" + pool.getMaxWait()
```

替换为：

```java
                    + " hikariPoolName=" + pool.getPoolName()
                    + " hikariMaxPoolSize=" + pool.getMaximumPoolSize()
                    + " hikariConnectionTimeoutMs=" + pool.getConnectionTimeout()
```

> `connectTimeoutMs` 证据读取驱动 PropertySet 中的 `connectTimeout`，经本配置以数据源属性注入后仍有效。

- [ ] **Step 5: 运行供应链模块全部单元测试**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`（含 Testcontainers 用例；Docker 未运行时 Testcontainers 用例失败属环境问题，需确认 Docker 可用）。若 Druid 依赖仍在 classpath，属预期（Task 8 移除）。

- [ ] **Step 6: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainDataSourceConfiguration.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetDataSourceConfigurationTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/StandardBudgetRoutingDataSourceTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/ClaimRecoveryIntegrationTest.java
git rm aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetRoutingDataSourceTest.java
git commit -m "供应链: 数据源装配切换 Hikari 并迁移旧预算路由测试与池证据输出"
```

---

## Task 7: 旧 Druid 深绑定代码原位桩化作废 + 框架清理 + ArchUnit 门禁

**Files:**
- Modify（桩化）: `BudgetRoutingDataSource.java`、`BudgetConnectionWrapper.java`、`BudgetStatementWrapper.java`、`BudgetPreparedStatementWrapper.java`、`MySqlSocketExtractor.java`（包 `com.aifuli.module.supplychain.infrastructure.configuration`）
- Modify（桩化）: `aifuli-framework/aifuli-spring-boot-starter-mybatis/src/main/java/com/aifuli/framework/datasource/core/filter/DruidAdRemoveFilter.java`
- Modify: `aifuli-framework/aifuli-spring-boot-starter-mybatis/src/main/java/com/aifuli/framework/datasource/config/AifuliDataSourceAutoConfiguration.java`
- Modify: `aifuli-framework/aifuli-spring-boot-starter-test/src/main/java/com/aifuli/framework/test/core/ut/BaseDbUnitTest.java`
- Modify: `aifuli-framework/aifuli-spring-boot-starter-test/src/main/java/com/aifuli/framework/test/core/ut/BaseDbAndRedisUnitTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/DeprecatedDruidStubTest.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [ ] **Step 1: 桩化 5 个供应链旧类**

对 `BudgetRoutingDataSource.java`、`BudgetConnectionWrapper.java`、`BudgetStatementWrapper.java`、`BudgetPreparedStatementWrapper.java`、`MySqlSocketExtractor.java` 五个文件，**逐个全文替换**为以下模板（替换 `{类名}` 与 `{归档文件名}` 为对应值）：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

/**
 * 已作废（Druid 组件已完全移除）。
 *
 * @deprecated 业务链路已改由 Hikari + 标准 JDBC 预算控制链路（StandardBudgetRoutingDataSource 等）接管，严禁调用。
 * 原实现已归档于 {@code docs/superpowers/specs/archive/deprecated-druid/{归档文件名}.java}。
 */
@Deprecated(since = "2026-10-09", forRemoval = true)
public class {类名} {

    public static final String DEPRECATION_NOTICE =
            "该类已作废：Druid 组件已完全移除，业务链路改由 Hikari 标准数据源接管，严禁调用。"
            + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md";

    public {类名}() {
        throw new UnsupportedOperationException(DEPRECATION_NOTICE);
    }
}
```

对应关系：

| 文件 | {类名} | {归档文件名} |
| :--- | :--- | :--- |
| `BudgetRoutingDataSource.java` | `BudgetRoutingDataSource` | `BudgetRoutingDataSource` |
| `BudgetConnectionWrapper.java` | `BudgetConnectionWrapper` | `BudgetConnectionWrapper` |
| `BudgetStatementWrapper.java` | `BudgetStatementWrapper` | `BudgetStatementWrapper` |
| `BudgetPreparedStatementWrapper.java` | `BudgetPreparedStatementWrapper` | `BudgetPreparedStatementWrapper` |
| `MySqlSocketExtractor.java` | `MySqlSocketExtractor` | `MySqlSocketExtractor` |

> 注意：`MySqlSocketExtractor` 归档版本为 `public final class` + 私有构造；桩化版本统一为模板形态（public 无参构造抛错），不保留 final。

- [ ] **Step 2: 桩化框架 DruidAdRemoveFilter**

将 `aifuli-framework/aifuli-spring-boot-starter-mybatis/src/main/java/com/aifuli/framework/datasource/core/filter/DruidAdRemoveFilter.java` 全文替换为：

```java
package com.aifuli.framework.datasource.core.filter;

/**
 * 已作废（Druid 组件已完全移除，/druid 监控端点整体下线）。
 *
 * @deprecated 原实现依赖 {@code com.alibaba.druid.util.Utils}，已随 Druid 依赖移除而作废，严禁注册或调用。
 * 原实现已归档于 {@code docs/superpowers/specs/archive/deprecated-druid/DruidAdRemoveFilter.java}。
 */
@Deprecated(since = "2026-10-09", forRemoval = true)
public class DruidAdRemoveFilter {

    public static final String DEPRECATION_NOTICE =
            "该类已作废：Druid 组件已完全移除，/druid 监控端点整体下线，严禁调用。"
            + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md";

    public DruidAdRemoveFilter() {
        throw new UnsupportedOperationException(DEPRECATION_NOTICE);
    }
}
```

- [ ] **Step 3: 清理 AifuliDataSourceAutoConfiguration**

将 `AifuliDataSourceAutoConfiguration.java` 全文替换为：

```java
package com.aifuli.framework.datasource.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 数据库配置类
 *
 * <p>2026-10-09 去 Druid 迁移后不再注册任何 Druid 监控 Servlet / 过滤器，
 * 仅保留事务管理开启职责。数据源本身由 Spring Boot 默认自动配置（HikariCP）或
 * dynamic-datasource starter 装配。设计见
 * {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#53-监控端点广告过滤器与前端页面退出}。</p>
 *
 * @author 芋道源码
 */
@AutoConfiguration
@EnableTransactionManagement(proxyTargetClass = true) // 启动事务管理
public class AifuliDataSourceAutoConfiguration {

}
```

- [ ] **Step 4: 清理两个测试基类的 Druid 自动配置引用**

修改 `BaseDbUnitTest.java`：
1. 删除 `import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure;`；
2. 删除 `@Import` 块中的 `DruidDataSourceAutoConfigure.class, // Druid 自动配置类` 一行（H2 数据源由 Spring Boot `DataSourceAutoConfiguration` + HikariCP 自动装配）。

修改 `BaseDbAndRedisUnitTest.java`：执行同样的两处删除。

- [ ] **Step 5: 编写旧桩作废契约测试**

创建 `DeprecatedDruidStubTest.java`：

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.framework.datasource.core.filter.DruidAdRemoveFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证旧 Druid 深绑定类已原位桩化：@Deprecated(forRemoval=true) 且构造熔断。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#32-源码原位桩代码stub契约
 */
class DeprecatedDruidStubTest {

    private static final Class<?>[] STUBS = {
            BudgetRoutingDataSource.class,
            BudgetConnectionWrapper.class,
            BudgetStatementWrapper.class,
            BudgetPreparedStatementWrapper.class,
            MySqlSocketExtractor.class,
            DruidAdRemoveFilter.class,
    };

    @Test
    void allStubsAreDeprecatedForRemoval() {
        for (Class<?> stub : STUBS) {
            Deprecated annotation = stub.getAnnotation(Deprecated.class);
            assertThat(annotation).as(stub.getSimpleName() + " 必须标注 @Deprecated").isNotNull();
            assertThat(annotation.forRemoval()).as(stub.getSimpleName() + " 必须 forRemoval=true").isTrue();
        }
    }

    @Test
    void allStubsThrowOnInstantiation() throws Exception {
        for (Class<?> stub : STUBS) {
            Constructor<?> constructor = stub.getDeclaredConstructor();
            assertThatThrownBy(constructor::newInstance)
                    .as(stub.getSimpleName() + " 构造必须熔断")
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }
}
```

- [ ] **Step 6: 新增 3 条 ArchUnit 规则**

修改 `SupplyChainModuleArchitectureTest.java`：

1. 在导入区新增（若尚未存在）：`import org.springframework.context.annotation.Bean;` 与 `import java.util.List;`；
2. 在规则声明区（例如 `admin_api_does_not_depend_on_menu_application_code` 规则之后）新增：

```java
    /**
     * 去 Druid 迁移门禁：供应链全模块禁止引用 com.alibaba.druid。
     * 设计见 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#62-架构约束门禁archunit规则升级}。
     */
    static final ArchRule no_druid_references = noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.alibaba.druid..");

    /** 旧 Druid 深绑定类已原位桩化作废：除桩类自身外，任何类不得引用。 */
    static final ArchRule deprecated_druid_stubs_are_never_referenced = noClasses()
            .that().doNotHaveSimpleName("BudgetRoutingDataSource")
            .and().doNotHaveSimpleName("BudgetConnectionWrapper")
            .and().doNotHaveSimpleName("BudgetStatementWrapper")
            .and().doNotHaveSimpleName("BudgetPreparedStatementWrapper")
            .and().doNotHaveSimpleName("MySqlSocketExtractor")
            .should().dependOnClassesThat().haveSimpleNameIn(
                    "BudgetRoutingDataSource", "BudgetConnectionWrapper", "BudgetStatementWrapper",
                    "BudgetPreparedStatementWrapper", "MySqlSocketExtractor");

    /** 已作废桩类严禁通过 @Bean 装配进 Spring 容器。 */
    static final ArchRule deprecated_druid_stubs_are_never_assembled_as_beans = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .should().haveRawReturnType(DescribedPredicate.describe("已作废 Druid 桩类",
                    clazz -> List.of("BudgetRoutingDataSource", "BudgetConnectionWrapper",
                            "BudgetStatementWrapper", "BudgetPreparedStatementWrapper",
                            "MySqlSocketExtractor").contains(clazz.getSimpleName())));
```

3. 在测试方法区（例如 `adminApiDoesNotDependOnMenuApplicationCode` 之后）新增：

```java
    @Test
    void noDruidReferences() {
        no_druid_references.check(importedClasses);
    }

    @Test
    void deprecatedDruidStubsAreNeverReferenced() {
        deprecated_druid_stubs_are_never_referenced.check(importedClasses);
    }

    @Test
    void deprecatedDruidStubsAreNeverAssembledAsBeans() {
        deprecated_druid_stubs_are_never_assembled_as_beans.check(importedClasses);
    }
```

- [ ] **Step 7: 运行单元测试与架构测试**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`。`SupplyChainModuleArchitectureTest` 三条新规则全绿；`DeprecatedDruidStubTest` 全绿。若 `no_druid_references` 报违规，说明仍存在生产代码引用 Druid，须按报错定位清理后再继续。

- [ ] **Step 8: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetRoutingDataSource.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetConnectionWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetStatementWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/BudgetPreparedStatementWrapper.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/configuration/MySqlSocketExtractor.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/DeprecatedDruidStubTest.java \
        aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java \
        aifuli-framework/aifuli-spring-boot-starter-mybatis/src/main/java/com/aifuli/framework/datasource/core/filter/DruidAdRemoveFilter.java \
        aifuli-framework/aifuli-spring-boot-starter-mybatis/src/main/java/com/aifuli/framework/datasource/config/AifuliDataSourceAutoConfiguration.java \
        aifuli-framework/aifuli-spring-boot-starter-test/src/main/java/com/aifuli/framework/test/core/ut/BaseDbUnitTest.java \
        aifuli-framework/aifuli-spring-boot-starter-test/src/main/java/com/aifuli/framework/test/core/ut/BaseDbAndRedisUnitTest.java
git commit -m "去Druid: 旧深绑定代码原位桩化作废并新增禁引用禁装配架构门禁"
```

---

## Task 8: POM 彻底移除 Druid 依赖并验证全仓编译

**Files:**
- Modify: `aifuli-dependencies/pom.xml`
- Modify: `aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml`

- [ ] **Step 1: 删除 mybatis starter 中的 druid starter**

修改 `aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml`，将 Task 1 插入的过渡块：

```xml
        <!-- TODO(2026-10-09)：druid-spring-boot-3-starter 为迁移过渡期临时保留，
             将在 Task 8 与 aifuli-dependencies 中的依赖管理一并彻底移除。
             详见 docs/superpowers/plans/2026-10-09-druid-to-hikari-migration.md -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid-spring-boot-3-starter</artifactId>
        </dependency>
```

整体删除（保留其前面的 `spring-boot-starter-jdbc` 依赖块）。

- [ ] **Step 2: 删除根依赖管理中的 Druid**

修改 `aifuli-dependencies/pom.xml`：
1. 删除 `<druid.version>1.2.28</druid.version>` 一行；
2. 删除 `<dependencyManagement>` 中的整块：

```xml
            <dependency>
                <groupId>com.alibaba</groupId>
                <artifactId>druid-spring-boot-3-starter</artifactId>
                <version>${druid.version}</version>
            </dependency>
```

- [ ] **Step 3: 验证零 Druid 依赖树**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server dependency:tree -Dincludes=com.alibaba:druid -q
mvn -pl aifuli-framework/aifuli-spring-boot-starter-mybatis dependency:tree -Dincludes=com.alibaba:druid -q
mvn -pl aifuli-module-system/aifuli-module-system-server dependency:tree -Dincludes=com.alibaba:druid -q
mvn -pl aifuli-server dependency:tree -Dincludes=com.alibaba:druid -q
```

Expected: 四条命令均无任何 `com.alibaba:druid` 输出（输出为空即零 Druid）。

- [ ] **Step 4: 全仓编译验证（零 Druid jar 条件）**

Run:

```bash
mvn clean compile -q
```

Expected: `BUILD SUCCESS`（这是本次迁移的核心验收条件：完全无 Druid jar 时全仓编译通过）。

- [ ] **Step 5: 运行供应链模块全部单元测试与架构测试**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`，含 `SupplyChainModuleArchitectureTest`、`DeprecatedDruidStubTest` 与 Testcontainers 用例。

- [ ] **Step 6: 运行集成测试（真实 MySQL + Liquibase）**

按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 中既有 `supply-chain-integration` profile 的运行方式执行 `ClaimRecoveryIntegrationTest`。
Expected: 全绿，且证据输出包含 `hikariPoolName=supply-chain-pool`、`hikariMaxPoolSize`、`hikariConnectionTimeoutMs` 与驱动 `connectTimeoutMs=1500`。

- [ ] **Step 7: Commit**

```bash
git add aifuli-dependencies/pom.xml aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml
git commit -m "依赖: 全仓彻底移除 Druid Maven 依赖并验证零 Druid 条件下全仓编译通过"
```

---

## Task 9: 各进程配置迁移（yaml/nacos/安全白名单）

**Files:**
- Modify: `aifuli-module-system/aifuli-module-system-server/src/main/resources/application-local.yaml`
- Modify: `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-local.yaml`
- Modify: `aifuli-module-member/aifuli-module-member-server/src/main/resources/application-local.yaml`
- Modify: `aifuli-server/src/main/resources/application-local.yaml`
- Modify: `aifuli-server/src/main/resources/application-dev.yaml`
- Modify: `script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/system-server-dev.yaml`
- Modify: `script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/infra-server-dev.yaml`
- Modify: `script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/member-server-dev.yaml`
- Modify: system/infra/member 各自的 `src/test/resources/application-unit-test.yaml`
- Modify: system/infra/member 各自的 `framework/security/config/SecurityConfiguration.java`

> 范围说明：`script/nacos/.../{pay,promotion,product,trade,statistics}-server-dev.yaml` 属于已删除的 mall/pay/erp 模块的历史残留文件，不在本计划范围内，**不做修改**。网关 `GatewayServerApplication` 为纯响应式网关、无数据库连接池，**无需任何改动**；电影 `MovieServerApplication` 已有独立 HikariCP 配置，**保持原样**。

- [ ] **Step 1: 迁移 system 进程 application-local.yaml**

修改 `aifuli-module-system/aifuli-module-system-server/src/main/resources/application-local.yaml`，将 `spring.datasource` 下的整个 `druid:` 监控配置段（web-stat-filter/stat-view-servlet/filter）与 `dynamic:` 下的 `druid:` 池配置段整体替换为：

```yaml
    dynamic: # 多数据源配置
      hikari: # HikariCP 【连接池】相关的全局配置
        minimum-idle: 1 # 最小连接池数量
        max-pool-size: 20 # 最大连接池数量
        connection-timeout: 60000 # 获取连接等待超时，单位：毫秒（1 分钟）
        idle-timeout: 600000 # 连接空闲 10 分钟被回收
        max-lifetime: 1800000 # 连接最大生存 30 分钟
        connection-test-query: SELECT 1 FROM DUAL # 检测连接是否有效
        pool-name: system-pool # 连接池名称（日志与监控归属）
```

即：删除原 `druid:` 监控块与 `dynamic: druid:` 块，`primary: master` 与 `datasource:` 保持不变。

- [ ] **Step 2: 迁移 infra 与 member 进程 application-local.yaml**

对 `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-local.yaml` 执行 Step 1 的同一替换，仅 `pool-name: infra-pool`；
对 `aifuli-module-member/aifuli-module-member-server/src/main/resources/application-local.yaml` 执行同一替换，仅 `pool-name: member-pool`。

- [ ] **Step 3: 迁移 aifuli-server 两个 yaml**

对 `aifuli-server/src/main/resources/application-local.yaml`：同一替换，local 档 `minimum-idle: 1`、`pool-name: aifuli-pool`。
对 `aifuli-server/src/main/resources/application-dev.yaml`：同一替换，dev 档 `minimum-idle: 10`、`pool-name: aifuli-pool`（对应原 initial-size: 5 / min-idle: 10）。

- [ ] **Step 4: 迁移 3 个 nacos dev yaml**

对 `script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/system-server-dev.yaml`：

```yaml
    dynamic: # 多数据源配置
      hikari: # HikariCP 【连接池】相关的全局配置
        minimum-idle: 10 # 最小连接池数量
        max-pool-size: 20 # 最大连接池数量
        connection-timeout: 60000 # 获取连接等待超时，单位：毫秒（1 分钟）
        idle-timeout: 600000 # 连接空闲 10 分钟被回收
        max-lifetime: 1800000 # 连接最大生存 30 分钟
        connection-test-query: SELECT 1 FROM DUAL # 检测连接是否有效
        pool-name: system-pool # 连接池名称（日志与监控归属）
```

对 infra/member 的 nacos yaml 执行同一替换，`pool-name` 分别为 `infra-pool`/`member-pool`。同时删除三个文件中的 `druid:` 监控配置段（web-stat-filter/stat-view-servlet/filter）。

- [ ] **Step 5: 迁移 3 个 unit-test yaml**

对 system/infra/member 各自的 `src/test/resources/application-unit-test.yaml`，将：

```yaml
    druid:
      async-init: true # 单元测试，异步初始化 Druid 连接池，提升启动速度
      initial-size: 1 # 单元测试，配置为 1，提升启动速度
```

替换为：

```yaml
    hikari:
      minimum-idle: 1 # 单元测试，配置为 1，提升启动速度
```

（Hikari 无 async-init 等价项：H2 内存库启动极快，无需异步初始化；`minimum-idle: 1` 保留原“最小连接 1”意图。）

- [ ] **Step 6: 删除 3 个 SecurityConfiguration 的 /druid 白名单**

`aifuli-module-system/aifuli-module-system-server/src/main/java/com/aifuli/module/system/framework/security/config/SecurityConfiguration.java` 删除：

```java
                // Druid 监控
                registry.requestMatchers("/druid/**").permitAll();
```

`aifuli-module-infra/aifuli-module-infra-server/src/main/java/com/aifuli/module/infra/framework/security/config/SecurityConfiguration.java` 与
`aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/framework/security/config/SecurityConfiguration.java` 执行同样删除（连同其上的 `// Druid 监控` 注释行）。

- [ ] **Step 7: 编译验证受影响模块**

Run:

```bash
mvn -pl aifuli-module-system/aifuli-module-system-server,aifuli-module-infra/aifuli-module-infra-server,aifuli-module-member/aifuli-module-member-server,aifuli-server -am test-compile -q
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 8: Commit**

```bash
git add aifuli-module-system/aifuli-module-system-server/src/main/resources/application-local.yaml \
        aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-local.yaml \
        aifuli-module-member/aifuli-module-member-server/src/main/resources/application-local.yaml \
        aifuli-server/src/main/resources/application-local.yaml \
        aifuli-server/src/main/resources/application-dev.yaml \
        script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/system-server-dev.yaml \
        script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/infra-server-dev.yaml \
        script/nacos/cxb-novo-mall-dev/import/DEFAULT_GROUP/member-server-dev.yaml \
        aifuli-module-system/aifuli-module-system-server/src/test/resources/application-unit-test.yaml \
        aifuli-module-infra/aifuli-module-infra-server/src/test/resources/application-unit-test.yaml \
        aifuli-module-member/aifuli-module-member-server/src/test/resources/application-unit-test.yaml \
        aifuli-module-system/aifuli-module-system-server/src/main/java/com/aifuli/module/system/framework/security/config/SecurityConfiguration.java \
        aifuli-module-infra/aifuli-module-infra-server/src/main/java/com/aifuli/module/infra/framework/security/config/SecurityConfiguration.java \
        aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/framework/security/config/SecurityConfiguration.java
git commit -m "配置: 八进程数据源配置迁移至 Hikari 并移除 Druid 监控白名单与端点配置"
```

---

## Task 10: Druid 监控菜单下线 SQL 与文档收尾

**Files:**
- Create: `script/deploy/supplyhub-test/2026-10-09-druid-monitor-offline.sql`
- Modify: `docs/集成测试.md`
- Modify: `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`

- [ ] **Step 1: 新建监控菜单下线 SQL**

创建 `script/deploy/supplyhub-test/2026-10-09-druid-monitor-offline.sql`：

```sql
-- 2026-10-09 去 Druid 迁移：MySQL 监控菜单（Druid 控制台）整体下线。
-- 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#53-监控端点广告过滤器与前端页面退出
-- 仅停用菜单与配置项，不删除历史数据；前端配套改动在 supplychainhubweb 仓库执行。
UPDATE system_menu
SET status = 0
WHERE path = 'druid'
  AND component_name = 'InfraDruid';

-- 监控地址配置项下线（前端 druid 页面读取该 key）
DELETE FROM system_dict_data
WHERE dict_type = 'infra_config'
  AND value = 'url.druid';
```

> 注意：`system_dict_data` 表名与列名请以 system 库实际 DDL 为准（本仓库 `sql/mysql/ruoyi-vue-pro.sql` 中配置字典类型为 `infra_config`）。若部署目标字典类型不同，先 `SELECT` 确认再执行 DELETE。

- [ ] **Step 2: 前端仓库配套改动（supplychainhubweb，独立仓库，另行提交）**

在 `/Users/mac/Documents/codeSpace/supplychainhubweb` 仓库执行（不属本仓提交）：
1. 删除菜单注册中 `infra/druid/index` 路由（或按该仓库路由注册机制下线）；
2. 删除 `src/views/infra/druid/index.vue` 文件；
3. 若存在菜单 id=111 的权限项，随菜单下线一并移除。

- [ ] **Step 3: 更新 docs/集成测试.md**

在 `docs/集成测试.md` 追加新章节：

```markdown
### 2026-10-09 去 Druid 换装 Hikari 联调记录

- **范围**：全仓移除 `com.alibaba:druid` 依赖，八大进程数据源全部切换 HikariCP；
  供应链预算控制链路重建为 `StandardBudgetRoutingDataSource` 等标准 JDBC 包装器。
- **验证点**：
  1. `mvn dependency:tree -Dincludes=com.alibaba:druid` 对 mybatis starter、supply-chain-server、system-server、aifuli-server 均为空；
  2. 零 Druid jar 条件下 `mvn clean compile` 全仓通过；
  3. 供应链单元测试与架构测试全绿（`no_druid_references`、旧桩禁引用/禁装配规则）；
  4. `ClaimRecoveryIntegrationTest`（supply-chain-integration profile）证据输出含
     `hikariPoolName=supply-chain-pool`、`hikariConnectionTimeoutMs=3000` 与驱动 `connectTimeoutMs=1500`；
  5. B03 安全呈现待验：按设计 §6.5 在 Hikari 环境下验证启动建连失败/早期 Liquibase/后台重连日志
     （池名线程名、traceId、cause 链、无明文密码），验证通过后方可申请 B03 解阻。
- **前置条件**：本地 Docker（Testcontainers MySQL 8.4）；真实 MySQL 与 Liquibase 场景按
  `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 执行。
```

- [ ] **Step 4: 更新 2026-10-05 前置设计文档的状态指针**

修改 `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`：

1. 在 §7.2.1 中 `#### R02：唯一Druid池的稳定预算包装与connect1500（本次冻结规范，待实施验证）` 标题行正下方插入：

```markdown

> **2026-10-09 状态更新**：底层连接池已裁定由 Druid 迁移至 HikariCP，R02 的预算契约不变量
> （3500ms 准入、q 守卫、SESSION 隔离、双模式、恢复失败禁重跑）在 Hikari 链路上按等价语义重新落地；
> 基础设施淘汰协议由 Druid discard 改为 Hikari evictConnection + 驱动 abort。
> 详见 `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`。本节原文保留为冻结历史。
```

2. 在 §8.2.3 中段落“**B03异步日志边界待裁决：** … 保持B03 blocked，不扩大现授权。”之后追加：

```markdown

> **2026-10-09 状态更新**：因底层连接池由 Druid 迁移至 HikariCP（见
> `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`），Druid 1.2.28 类级私有静态
> Logger 的硬阻断已随组件移除而消除；B03 状态调整为 `PENDING_VERIFICATION`，待按该设计 §6.5
> 在 Hikari 环境下完成实机验证后再申请正式解阻。本节原文保留为冻结历史。
```

- [ ] **Step 5: Commit**

```bash
git add script/deploy/supplyhub-test/2026-10-09-druid-monitor-offline.sql docs/集成测试.md \
        docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md
git commit -m "文档: 去 Druid 迁移联调记录、监控菜单下线 SQL 与 R02/B03 状态指针更新"
```

---

## 最终验收清单（全部任务完成后执行）

- [ ] `mvn dependency:tree -Dincludes=com.alibaba:druid` 对全仓关键模块输出为空；
- [ ] `mvn clean compile` 全仓通过（零 Druid jar）；
- [ ] `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test` 全绿；
- [ ] `SupplyChainModuleArchitectureTest` 三条新规则全绿；
- [ ] `grep -r "com.alibaba.druid" --include="*.java" aifuli-* aifuli-dependencies` 仅命中 `docs/superpowers/specs/archive/deprecated-druid/` 归档文件（生产/测试源码零命中）；
- [ ] 归档目录 6 个文件 SHA-256 与设计文档 §3.1 表格一致；
- [ ] `ClaimRecoveryIntegrationTest`（supply-chain-integration profile）证据输出含 `hikariPoolName=supply-chain-pool` 与 `connectTimeoutMs=1500`。
