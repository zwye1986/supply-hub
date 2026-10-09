# 平台全面去 Druid 换装 Hikari 详细设计规范

- **编制日期**：2026-10-09（修订版）
- **设计状态**：`DESIGN_PENDING_REVIEW`（修订后待评审）
- **关联规范**：
  - `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`（§7.2.1 R01 预算契约与 R02 数据源包装规范，§8.2.3 B03 异步日志边界）
  - `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md`（全系统日志规范，特别是 §2.3 异常记录责任与 §2.5 终结边界）
  - `docs/集成测试.md`（集成测试与数据源联调）

---

## 1. 背景与目标

### 1.1 背景与核心痛点
仓库由早期单体/商城底座拆分而来，长期依赖 `com.alibaba:druid:1.2.28` 作为数据库连接池。随着系统向独立进程微服务演进、模块物理边界收敛以及高可靠不可变账本（B0 阶段）的推进，依赖 Druid 暴露出严重的设计恶味与技术阻断：
1. **抽象泄露与设计恶味**：供应链的 `BudgetRoutingDataSource` 与 `BudgetConnectionWrapper` 直接强转并依赖 `DruidPooledConnection`、`DruidConnectionHolder` 等 Druid 私有实现类，破坏面向对象依赖倒置原则（DIP）；
2. **引发 B03 日志阻断**：Druid 1.2.28 内部采用类级私有不可控的静态日志 `private static final Log LOG`，其后台建连线程 `Druid-ConnectionPool-Create-<hash>` 打印原始异常与连接信息，无池标识，且无法绑定业务 `MDC(traceId)`，导致在 `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md` §8.2.3 中 B03 门禁陷入静态死锁；
3. **历史维护负担**：框架中残留通过正则替换 `common.js` 过滤网页广告的 `DruidAdRemoveFilter`，以及各微服务暴露的 `/druid/**` 监控端点，增加了不必要的维护与安全暴露面。

### 1.2 改造目标
1. **彻底切除 Maven 依赖**：从全仓（`aifuli-dependencies`、`aifuli-spring-boot-starter-mybatis` 等）完全删除所有 Druid 相关依赖，引入 Spring Boot 官方推荐并维护的默认连接池 **HikariCP**；
2. **旧代码原位桩化与文档归档（方案 A 完整版）**：将现存与 Druid 深度绑定的 5 个供应链预算类及 1 个通用 Filter 完整归档至文档库，源码原位保留 `@Deprecated(since = "2026-10-09", forRemoval = true)` 桩代码，方法体直接抛出 `UnsupportedOperationException`，杜绝编译错误并防止业务链路调用；
3. **构建面向标准 JDBC 4.1+ 与 Hikari 基础设施适配的全新预算控制链路**：构建全新的标准预算控制体系（`StandardBudgetRoutingDataSource`、`StandardBudgetConnectionWrapper` 及配套 Statement 包装器），严格对齐原 R01/R02 预算契约；
4. **规范连接淘汰机制与超时语义**：通过基础设施适配层调用 Hikari 公开的 `evictConnection()` API 并结合驱动标准 `abort()` 实现脏连接的可靠物理淘汰与池容量回收；严格区分驱动物理建连超时（TCP `connectTimeout`）与池借用等待超时（Hikari `connectionTimeout`）；
5. **重塑 B03 异步日志解阻路径**：利用 Hikari 遵循标准 SLF4J 规范、线程名带池标识以及建连异常同步抛出给借用线程的特性，解除原 Druid 类级静态 Logger 的硬阻断，将其转为在 Hikari 环境下可实机验证的安全呈现与日志链路。

---

## 2. 架构决策记录（ADR）

### ADR-1：全面采用 HikariCP 替代 Druid
- **决策内容**：全平台八大独立进程统一采用 HikariCP 作为默认数据库连接池。
- **技术依据**：
  - HikariCP 是 Spring Boot 官方默认连接池，基于无锁 CAS（`ConcurrentBag`）与 `ReentrantLock` 构建，在高并发环境下性能卓越；
  - 供应链独立进程（`SupplyChainServerApplication`）采用独立配置的 `HikariDataSource`；
  - 使用 `dynamic-datasource-spring-boot3-starter` 的进程（System, Infra, Member 等）无缝切换至其内置的 HikariCP 支持。

### ADR-2：旧 Druid 资产原位桩化与文档归档（方案 A 完整版）
- **决策内容**：
  - 彻底删除 Maven 中的 Druid jar；
  - 归档并桩化 6 个核心历史类（供应链 5 个预算协作类 + 框架 1 个广告过滤器）；
  - 原 Java 类保留在原包路径下，但清空一切 Druid import，添加 `@Deprecated(since = "2026-10-09", forRemoval = true)`，所有构造方法与业务方法统一抛出 `UnsupportedOperationException`；
  - ArchUnit 架构测试增加强制门禁：业务代码严禁调用作废桩类，Spring 容器严禁装配作废桩类。

### ADR-3：分层连接淘汰协议（Eviction Protocol）与物理关闭
- **决策内容**：纠正“JDBC `connection.abort()` 会自动触发 Hikari 池淘汰”的错误假设，采用分层的淘汰机制：
  1. **业务与事务包装层（`StandardBudgetConnectionWrapper`）**：面向标准 `java.sql.Connection`，负责维护预算计时与 SESSION 状态。一旦发生不可恢复异常或清理失败（SESSION 锁超时恢复失败、networkTimeout 恢复失败），标记 `context.markCleanupFailed()`，禁止事务重跑，并触发底层淘汰机制；
  2. **基础设施适配层（Eviction Adapter）**：
     - 调用 Hikari 的公开淘汰能力：`hikariDataSource.evictConnection(rawConnection)`。该方法将对应 `PoolEntry` 标记为 `STATE_REMOVED`，从池中永久剔除，并安全关闭物理 Socket；
     - 双重安全防线：同时对底层驱动连接执行 `rawConnection.abort(Runnable::run)`，强杀底层的 Socket 读写；
     - 代理连接生命周期受控收敛：在 `finally` 块中无论清理成功与否均确保代理引用得到安全关闭，彻底杜绝 PoolEntry 悬挂与池容量泄漏；
  3. **可观测关闭证据**：必须在测试中验证原物理连接会话（如 MySQL thread_id）绝不再被后续借出复用，且 Hikari 连接池活跃连接数清零、池容量自动补足。

### ADR-4：超时语义严格分离（池借用等待 vs 物理建连超时）
- **决策内容**：纠正“Hikari `connectionTimeout` 等价于驱动物理建连超时”的错误假设，对两类超时进行严格的语义和配置分离：
  1. **驱动物理建连超时（TCP / Socket Connect Timeout）**：
     - 必须通过数据源底层属性明确注入 MySQL Connector/J 的连接参数：
       `dataSource.addDataSourceProperty("connectTimeout", String.valueOf(connectTimeoutMillis));`（默认 1500ms）；
     - 控制底层 TCP 三次握手和初始 Socket 建立；明确说明 DNS 解析、TLS 握手、MySQL 身份鉴权以及服务器端握手初始化不受单一 TCP connectTimeout 硬截止限制，属于有界开销；
  2. **池借用等待超时（Hikari connectionTimeout）**：
     - 全局 Hikari 连接池保留常规健康的借用等待时间（如 3000ms 或根据多数据源全局配置），保障管理端 HTTP 查询、Liquibase 初始化、时钟同步等非预算链路的平稳运行；
     - **预算链路专项控制**：
       - 借前核验：`StandardBudgetRoutingDataSource.getConnection()` 进入前先核验 `context.checkAdmission()`（$R < 3500\text{ms}$ 门槛截断）；
       - 借后首检（防迟到连接）：连接借出后立即计算可用纳秒及可用执行秒数 $q$，若发现超时或已取消（迟到连接），立即触发淘汰协议（`evictConnection` + `abort`）销毁连接，抛出 `BudgetTimeoutException`，绝不暴露给事务管理器。

### ADR-5：B03 异步建连日志解阻定位（客观声明）
- **决策内容**：撤回“换用 Hikari 后 B03 已完全消除”的虚假陈述，客观调整 B03 状态为：`PENDING_VERIFICATION`（解除 Druid 框架层私有静态日志死锁，待在 Hikari 环境下实机验证安全呈现与日志归属）。
- **技术依据**：
  - HikariCP 移除了 Druid 1.2.28 的类级不可控静态 Logger，后台建连线程具有明确的池名线程名（`<poolName> connection adder`），SLF4J 日志可以精确过滤与归属；
  - 借用线程超时和建连失败均会作为异常直接抛给借用方线程，便于事务入口捕获并打印包含业务 `traceId` 的结构化日志；
  - 后续须对 Spring Boot 启动阶段、Liquibase 早期建连、后台自动重连的日志呈现与堆栈行为进行专门测试验证。

---

## 3. 历史资产归档与作废契约（方案 A 完整版）

### 3.1 归档清单与 SHA-256 校验和
深度依赖 Druid 内部类的 5 个核心预算协作类及 1 个广告过滤器已完整归档至 `docs/superpowers/specs/archive/deprecated-druid/`：

| 归档源文件 | 归档相对路径 | 原始代码 SHA-256 | 职责说明 |
| :--- | :--- | :--- | :--- |
| `BudgetRoutingDataSource.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetRoutingDataSource.java` | `3221f4af89711f56f11bac2c3a2cbb8172fde2969d602ee05dfc34701e00d30b` | 历史基于 Druid 的预算路由数据源实现 |
| `BudgetConnectionWrapper.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetConnectionWrapper.java` | `55645a2e8ec5720e281e8266ce311378963ad29c94ebfb57bb608f40b842325d` | 历史基于 DruidPooledConnection 的连接包装器 |
| `BudgetStatementWrapper.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetStatementWrapper.java` | `7e5e3b2f40f0a63f66320702cf75043f1470167626f5f0a62cadb9aec029c02c` | 历史强绑定旧 Connection 包装器的 Statement 包装器 |
| `BudgetPreparedStatementWrapper.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetPreparedStatementWrapper.java` | `3a88a81845a53cb0d399a9ac0f19c454944e15f2fb693e50ae62310820a94e75` | 历史强绑定旧 Connection 包装器的 PreparedStatement 包装器 |
| `MySqlSocketExtractor.java` | `docs/superpowers/specs/archive/deprecated-druid/MySqlSocketExtractor.java` | `25a39b2b3a4a87ec4d490e324bdc1ef8e9f4b170c42be420e7ffeb15d1b3a37c` | 历史针对 MySQL 驱动反射抽取 Socket 的工具类 |
| `DruidAdRemoveFilter.java` | `docs/superpowers/specs/archive/deprecated-druid/DruidAdRemoveFilter.java` | `bcd2b3ff0e0a92e700bf3cb7c85df58498c4fd8a8d3872d77d4178161e7487d7` | 历史依赖 Druid Utils 的 Web 广告过滤器 |

### 3.2 源码原位桩代码（Stub）契约
在原源码路径下保留同名类，严格遵守以下契约：
1. **编译期纯净**：源码内部绝对不含任何 `import com.alibaba.druid.*`；
2. **类级废弃标注**：
   ```java
   @Deprecated(since = "2026-10-09", forRemoval = true)
   ```
3. **运行时硬熔断**：所有构造器与公开方法体统一抛出 `UnsupportedOperationException`：
   ```java
   throw new UnsupportedOperationException(
       "该类已作废：Druid 组件已完全移除，业务链路改由 Hikari 标准数据源接管，严禁调用。"
       + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md"
   );
   ```
4. **容器与业务隔离**：严禁在 Spring 配置类中声明这些旧类的 Bean；ArchUnit 测试强制断言业务代码不调用、不依赖这些废弃类。

---

## 4. 全新供应链 Hikari 数据源与预算控制链路设计

### 4.1 数据源装配与生命周期管理（`SupplyChainDataSourceConfiguration`）
```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplyChainDataSourceProperties.class)
public class SupplyChainDataSourceConfiguration {

    @Bean("supplyChainDataSource")
    public StandardBudgetRoutingDataSource supplyChainDataSource(SupplyChainDataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        
        // 1. 显式指定连接池名称，天然支撑日志隔离与池身份识别
        dataSource.setPoolName("supply-chain-pool");
        
        // 2. 驱动级物理 TCP 建连超时：通过 MySQL Connector/J 专用属性注入
        dataSource.addDataSourceProperty("connectTimeout", 
                String.valueOf(properties.getConnectTimeoutMillis()));
        
        // 3. 池借用等待超时：配置适度借用等待（例如 3000ms），预算链路单独进行防迟到截断
        dataSource.setConnectionTimeout(3000L);
        
        // 4. 会话时区固定 UTC+8，保证本地一致性
        dataSource.setConnectionInitSql("SET time_zone = '+08:00'");
        dataSource.setValidationTimeout(1000L);

        // 5. 包装为标准预算路由数据源
        return new StandardBudgetRoutingDataSource(dataSource);
    }
}
```

#### 数据源生命周期与单池资源所有权
- `StandardBudgetRoutingDataSource` 实现 `org.springframework.beans.factory.DisposableBean` 与 `AutoCloseable`；
- 在 Spring 容器关闭（`destroy()`）或其 `close()` 被调用时，显式且幂等委派底层 `HikariDataSource.close()`，确保连接池后台线程（如 `supply-chain-pool connection adder`、管家线程等）安全优雅停机；
- 确认单池身份：`supplyChainDataSource` 作为供应链独立进程中唯一的数据库连接池 Bean，作为 `supplyChainTransactionManager`、MyBatis `SqlSessionFactory`、数据库时钟服务以及 Liquibase 的唯一数据源资源键。

---

### 4.2 预算控制路由数据源（`StandardBudgetRoutingDataSource`）

```java
public class StandardBudgetRoutingDataSource implements DataSource, DisposableBean, AutoCloseable {

    private final HikariDataSource rawDataSource;

    public StandardBudgetRoutingDataSource(HikariDataSource rawDataSource) {
        this.rawDataSource = Objects.requireNonNull(rawDataSource, "rawDataSource must not be null");
    }

    @Override
    public Connection getConnection() throws SQLException {
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            // 无预算上下文（HTTP 常规查询、Liquibase 初始化、系统时钟等）：透明直通
            return rawDataSource.getConnection();
        }

        // 有预算上下文（不可变账本事务、高可靠前置链路）
        // 1. 借前准入核验：R < 3500ms 立即拒绝
        context.checkAdmission();

        // 2. 借出连接
        Connection conn = rawDataSource.getConnection();

        // 3. 借后首检（防迟到连接）
        long acquiredNano = System.nanoTime();
        if (context.isCancelled() || !context.hasSufficientNano(acquiredNano) || context.availableSeconds(acquiredNano) < 1) {
            // 判定为迟到连接，立即触发淘汰协议，绝不暴露给事务管理器
            evictAndAbort(conn);
            throw new BudgetTimeoutException("获取连接耗时过长，剩余预算不足以执行最小事务，已安全销毁连接");
        }

        // 4. 首检通过：计算执行期限 E = min(commandDeadline - C, acquiredNano + 6s)
        long executionDeadlineE = context.calculateExecutionDeadlineE(acquiredNano);

        return new StandardBudgetConnectionWrapper(conn, this::evictAndAbort, context, executionDeadlineE);
    }

    /**
     * 基础设施级淘汰协议：通知 Hikari 淘汰 PoolEntry 并 abort 驱动底座
     */
    public void evictAndAbort(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            // 1. 调用 Hikari 公开 API 安全移出连接池并关闭物理套接字
            rawDataSource.evictConnection(conn);
        } catch (Throwable t) {
            log.warn("[连接池淘汰] Hikari evictConnection 失败: {}", t.getMessage());
        } finally {
            try {
                // 2. 双重兜底：驱动级 abort 强杀底层 Socket
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
    
    // 其余标准 DataSource 接口透明委派 rawDataSource
}
```

---

### 4.3 预算控制连接包装器（`StandardBudgetConnectionWrapper`）

#### 完整对齐原 R01/R02 契约不变量
1. **网络超时注入与原值保存**：
   - 连接初次借出时，保存底层驱动的原始 `networkTimeout`；
   - 计算并设置动态预算网络超时：`delegate.setNetworkTimeout(Runnable::run, calculatedBudgetTimeoutMs)`；
2. **SESSION 锁等待隔离与保护通道**：
   - 保存连接原始的 `innodb_lock_wait_timeout`（通常为 50s）；
   - 在首次执行业务前，通过内部受保护的**非递归专用 control 通道**执行：
     `SET SESSION innodb_lock_wait_timeout = min(2, q)`；
   - 专用 control 通道直接通过底层物理连接执行 SQL，不走外层 Statement 包装器的预算拦截，防止循环递归；
3. **双运行模式**：
   - **业务模式（NORMAL）**：所有 Statement 执行均受到 $q = \lfloor \min(5\text{s}, E - \text{now}, \text{commandDeadline} - C - \text{now}) / 1\text{s} \rfloor$ 守卫约束；不足 1 秒拒绝；
   - **清理模式（CLEANUP）**：在事务回滚（`rollback()`）或异常退出恢复时切换为 CLEANUP 模式，允许回滚操作在有界合理时间内完成，避免回滚本身因超时被阻断；
4. **Statement 协作链拦截**：
   - 拦截 `createStatement()`、`prepareStatement()`、`prepareCall()`，分别包装为 `StandardBudgetStatementWrapper`、`StandardBudgetPreparedStatementWrapper` 和 `StandardBudgetCallableStatementWrapper`；
5. **提交前与 MyBatis flush 守卫**：
   - 在 `commit()` 执行前再次核验剩余预算，若不足以安全提交，抛出异常中断事务；
6. **退出与异常淘汰协议（`close()`）**：
   遵循全系统日志规范 §2.3，保留完整的因果链和异常 Suppressed，严禁吞异常：

```java
@Override
public void close() throws SQLException {
    Throwable cleanupFailure = null;

    try {
        // 1. 恢复 SESSION innodb_lock_wait_timeout 为原始值
        if (sessionTimeoutInstalled && !delegate.isClosed()) {
            try {
                restoreSessionLockWaitTimeout();
            } catch (Throwable t) {
                cleanupFailure = t;
            }
        }

        // 2. 恢复 networkTimeout 为原始值
        if (!delegate.isClosed()) {
            try {
                delegate.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            } catch (Throwable t) {
                if (cleanupFailure == null) {
                    cleanupFailure = t;
                } else {
                    cleanupFailure.addSuppressed(t);
                }
            }
        }
    } finally {
        if (cleanupFailure != null) {
            // 标记清理失败：禁止当前事务进行任何重跑！
            context.markCleanupFailed();
            log.error("[预算连接关闭] 连接状态恢复失败，触发物理淘汰与池剔除", cleanupFailure);
            try {
                // 触发底层淘汰协议（Hikari evictConnection + 驱动 abort）
                evictor.accept(delegate);
            } finally {
                // 受控安全关闭代理引用，避免 PoolEntry 悬挂
                try {
                    delegate.close();
                } catch (Throwable t) {
                    log.warn("[预算连接关闭] 淘汰后代理 close 异常: {}", t.getMessage());
                }
            }
        } else {
            // 清理完全成功：正常健康归还连接池
            try {
                delegate.close();
            } catch (SQLException e) {
                context.markCleanupFailed();
                throw e;
            }
        }
    }
}
```

---

### 4.4 全新 Statement 包装器体系
构建全新且类型一致的 Statement 包装器，替代原有与旧 Connection 绑定的 Statement 包装器：
- `StandardBudgetStatementWrapper`
- `StandardBudgetPreparedStatementWrapper`
- `StandardBudgetCallableStatementWrapper`

**统一执行前守卫规则（`beforeExecute()`）**：
1. 从 `context` 和 `executionDeadlineE` 计算可用执行秒数：
   $$q = \left\lfloor \frac{\min(5\text{s}, E - \text{now}, \text{commandDeadline} - C - \text{now})}{1\text{s}} \right\rfloor$$
2. 维护调用方已设的正限制：
   若业务显式设置过 `queryTimeout > 0`，记录 `observedMinPositive = min(observedMinPositive, queryTimeout)`；
3. 计算最终生效超时：$t = \min(observedMinPositive > 0 ? observedMinPositive : q, q)$；
4. 若 $t < 1$，直接抛出 `BudgetTimeoutException("剩余预算不足 1 秒，拒绝执行 SQL")`；
5. 调用 `delegate.setQueryTimeout(t)` 守卫底层 SQL 执行。

---

## 5. 全仓八进程配置迁移与旧资产退出

### 5.1 POM 依赖彻底切除与引入
1. **`aifuli-dependencies/pom.xml`**：
   - 彻底删除 `<druid.version>1.2.28</druid.version>`；
   - 彻底删除 `<dependencyManagement>` 中的 `druid-spring-boot-3-starter`；
2. **`aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml`**：
   - 彻底移除 `druid-spring-boot-3-starter`；
   - 显式引入 `org.springframework.boot:spring-boot-starter-jdbc`（引入 HikariCP）；
3. **`aifuli-framework/aifuli-spring-boot-starter-test/pom.xml`**：
   - 移除测试对 `DruidDataSourceAutoConfigure.class` 的引用。

### 5.2 多进程连接池配置迁移映射矩阵
全仓 8 个独立进程的连接池配置按实际形态精准迁移，严禁机械全局替换：

| 进程名称 | 架构形态 | 数据库依赖 | 连接池配置迁移目标与配置键前缀 | 备注说明 |
| :--- | :--- | :--- | :--- | :--- |
| `SupplyChainServerApplication` | 独立微服务 | 独立库（`sc_*`） | `SupplyChainDataSourceConfiguration` 独立装配 HikariDataSource；YAML 前缀 `spring.datasource.*` | 独立连接池，显式 `supply-chain-pool`，受 R01/R02 预算控制 |
| `SystemServerApplication` | 独立微服务 | System 库 | 迁移为 `spring.datasource.dynamic.hikari.*`（如 `connection-timeout: 60000`, `max-pool-size: 20`, `minimum-idle: 1 或 10`） | 使用 Dynamic DataSource，删除旧 `druid` 配置段 |
| `InfraServerApplication` | 独立微服务 | Infra 库 | 迁移为 `spring.datasource.dynamic.hikari.*` | 同上，删除旧 `druid` 监控配置段 |
| `MemberServerApplication` | 独立微服务 | Member 库 | 迁移为 `spring.datasource.dynamic.hikari.*` | 同上 |
| `AifuliServerApplication` | 聚合进程 | System+Infra | 迁移为 `spring.datasource.dynamic.hikari.*` | 同上 |
| `TaobaoFlashChannelApplication` | 独立渠道进程 | 渠道库 | 迁移为标准 Hikari 配置 | 独立进程 |
| `MovieServerApplication` | 独立业务进程 | 电影库 | 保持现有的独立 HikariCP 配置 | 原已具备独立连接池配置，保持隔离 |
| `GatewayServerApplication` | 独立网关进程 | **无数据库** | **不添加任何数据库连接池依赖与配置** | 纯响应式网关，严禁引入连接池 |

### 5.3 监控端点、广告过滤器与前端页面退出
1. **`AifuliDataSourceAutoConfiguration`**：清理全部 Druid 配置项（`DruidStatProperties`）、监控 Servlet、StatFilter 及 WebStatFilter 注册；
2. **`DruidAdRemoveFilter`**：原位桩化作废，断开所有 Spring Web Filter 注册；
3. **各微服务 `SecurityConfiguration`**：彻底删除所有 `registry.requestMatchers("/druid/**").permitAll();` 白名单配置；
4. **前端管理端（`supplychainhubweb`）下线与重定向**：
   - 标记下线 `src/views/infra/druid/index.vue`；
   - 针对部署 SQL 中的菜单和数据字典项 `url.druid`，在 `script/deploy` 中提供迁移与禁用 SQL；若用户访问原监控入口，重定向至通用系统监控仪表盘或提示功能已下线。

---

## 6. 测试与多维门禁验证计划

### 6.1 编译期与依赖树门禁
- 运行 `mvn dependency:tree`，强制验证全仓所有模块（包括 server 与 test 模块）的编译期与运行期依赖树中 **`com.alibaba:druid*` 的依赖出现次数严格为 0**；
- 运行 `mvn clean compile`，在零 Druid jar 的环境下确保全仓 100% 编译通过。

### 6.2 架构约束门禁（ArchUnit 规则升级）
在 `SupplyChainModuleArchitectureTest` 中新增并强化以下架构规则：
1. **零 Druid 引用规则**：断言供应链以及全仓代码绝对不 import `com.alibaba.druid..*`；
2. **旧桩禁调用规则**：断言所有业务代码（非旧桩本身）严禁依赖、实例化或调用被标记为 `@Deprecated(since = "2026-10-09", forRemoval = true)` 的 6 个旧作废类；
3. **容器装配隔离规则**：断言 Spring 配置类与组件扫描中严禁出现以旧桩类作为类型的 `@Bean` 或 `@Component`。

### 6.3 淘汰协议与防污染单元/集成测试
1. **物理会话不可复用断言**：在测试中模拟连接清理失败，触发 `evictAndAbort()`，获取 MySQL 连接的底层 thread_id；断言后续多次从连接池借出的新连接中，**绝对不会再次出现已被淘汰的 thread_id**；
2. **连接池容量恢复断言**：断言淘汰发生后，HikariCP 的活跃连接数（activeConnections）正确减少，且在继续借用时能够创建新连接并使总连接数恢复至配置值，证明不存在连接池泄漏；
3. **迟到连接截断测试**：模拟获取连接耗时超过可用预算，断言抛出 `BudgetTimeoutException`，且迟到的物理连接被立即销毁淘汰，不进入事务。

### 6.4 超时语义分离测试
1. 验证 MySQL Connector/J 能够正确读取 `connectTimeout=1500`；构造黑洞 IP 测试物理建连在 1500ms 左右产生网络超时；
2. 验证非预算请求（如管理端普通分页查询）在并发压力下能够正常使用 Hikari 的常规 `connectionTimeout` 进行队列排队，不被 1500ms 短等待误杀。

### 6.5 B03 独立安全呈现验证（Hikari 环境下重跑）
在 Hikari 环境下对启动建连失败、Liquibase 早期建连、后台自动重连的日志呈现与堆栈行为进行专门测试：
- 构造数据库不可达及认证异常；
- 验证日志中池名（`supply-chain-pool`）正确呈现在线程名与消息参数中；
- 验证调用方事务线程记录的错误日志包含完整的业务 `traceId` 与 `cause` 链；
- 验证框架层未将未脱敏的数据库密码明文打印至日志。

---

## 7. 关联设计规范与状态更新记录

1. **《目录可靠消费前置设计》（`docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`）**：
   - §7.2.1 **R01/R02 规范**：底层实现由原“Druid 专有实现”修订为本规范设计的“HikariCP + 标准 JDBC 4.1+ 契约”，所有不变量（3500ms 准入、q 超时计算、SESSION 锁等待隔离、双模式、不可回池污染）严格保持一致；
   - §8.2.3 **B03 异步日志边界**：原因 Druid 1.2.28 私有类级静态 Logger 导致的硬阻断已通过切换为 Hikari 得到消除；B03 状态更新为 `PENDING_VERIFICATION`，待在本规范第 6.5 节的验证门禁下完成实机验证后申请正式解阻。
