# 平台全面去 Druid 换装 Hikari 详细设计规范

- **编制日期**：2026-10-09
- **设计状态**：`DESIGN_PENDING_REVIEW`（待评审）
- **关联规范**：
  - `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`（R01 预算契约与 R02 数据源包装规范）
  - `docs/superpowers/specs/2026-10-08-r3-04-readonly-deblocking-analysis.md`（R3-04 只读解阻分析与 B03 异步日志阻断项）
  - `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md`（全系统日志规范）
  - `docs/集成测试.md`（集成测试与数据源联调）

---

## 1. 背景与目标

### 1.1 背景与核心痛点
仓库由早期单体/商城底座拆分而来，长期依赖 `com.alibaba:druid:1.2.28` 作为数据库连接池。随着系统向独立进程微服务演进、JDK 21 虚拟线程落地以及高可靠不可变账本（B0 阶段）的推进，依赖 Druid 暴露出严重的设计恶味与技术阻断：
1. **抽象泄露与设计恶味**：供应链的 `BudgetRoutingDataSource` 与 `BudgetConnectionWrapper` 直接强转并依赖 `DruidPooledConnection`、`DruidConnectionHolder` 等 Druid 私有实现类，破坏面向对象依赖倒置原则（DIP）；
2. **引发 B03 日志死结**：Druid 1.2.28 采用类级私有静态日志 `private static final Log LOG`，其后台建连线程 `Druid-ConnectionPool-Create-<hash>` 打印原始异常与密码 URL，无法绑定业务 `MDC(traceId)`，无池标识，直接导致 R3-04 只读解阻分析中 B03 门禁陷入死锁；
3. **JDK 21 虚拟线程兼容性劣势**：Druid 内部大量采用 `synchronized` 关键字，在虚拟线程高并发环境下会导致载体线程固定（Carrier Thread Pinning）；
4. **历史包袱**：框架中残留通过正则替换 `common.js` 过滤网页广告的 `DruidAdRemoveFilter`，以及各微服务暴露的 `/druid/**` 监控端点，维护成本高。

### 1.2 改造目标
1. **彻底切除 Maven 依赖**：从全仓（`aifuli-dependencies`、`aifuli-spring-boot-starter-mybatis` 等）完全删除所有 Druid 相关依赖，引入 Spring Boot 官方第一公民 **HikariCP**；
2. **旧代码原位桩化与文档归档（方案 A）**：将现存与 Druid 深度绑定的代码原封不动归档至文档库，源码原位保留 `@Deprecated(forRemoval = true)` 桩代码，方法体直接熔断抛错，杜绝编译错误并防止业务链路调用；
3. **业务链路 100% 切入 Hikari + 标准 JDBC 4.1+**：构建全新的、纯面向标准 JDBC 规范的预算控制数据源（`StandardBudgetRoutingDataSource` 与 `StandardBudgetConnectionWrapper`），以标准 `connection.abort(executor)` 优雅实现脏连接物理淘汰，彻底闭环 B03 日志阻断项。

---

## 2. 架构决策记录（ADR）

### ADR-1：全面采用 HikariCP 替代 Druid
- **决策内容**：全平台八大独立进程统一采用 HikariCP 作为默认数据库连接池。
- **技术依据**：
  - HikariCP 是 Spring Boot 3.5.x 官方原生默认连接池，基于无锁 CAS（`ConcurrentBag`）与 `ReentrantLock` 构建，对 JDK 21 虚拟线程完全友好；
  - HikariCP 原生支持实例级 Logger（命名格式为 `com.zaxxer.hikari.pool.HikariPool.<poolName>`），支持显式命名池，日志可被结构化框架精确拦截与归属。

### ADR-2：旧 Druid 资产原位桩化与文档归档（方案 A）
- **决策内容**：
  - 彻底删除 Maven 中的 Druid jar；
  - 为避免原包路径出现类缺失（Class-Not-Found）引发不可控连锁反应，原 Java 类保留但清空一切 Druid import，添加 `@Deprecated(since = "2026-10-09", forRemoval = true)`，方法体统一抛出 `UnsupportedOperationException`；
  - 完整原始实现代码迁移至 `docs/superpowers/specs/archive/deprecated-druid/` 归档。

### ADR-3：预算控制连接淘汰采用标准 JDBC 4.1 `connection.abort(executor)`
- **决策内容**：废弃原 `pooled.disable()` + `discardConnection(holder)` 的非标机制，全面采用标准 JDBC 4.1 的 `connection.abort(Runnable::run)`。
- **技术依据**：
  - 当 Hikari 代理连接执行 `abort()` 时，Hikari 内部自动调用 `pool.evictConnection(this)`，异步强杀物理底层套接字并从池中永久淘汰该物理连接，绝不放回池中复用；
  - 该机制 100% 满足 R02.6 规范中“恢复失败立即物理淘汰、严禁回池污染”的要求，且完全面向标准 JDBC 规范。

### ADR-4：借此彻底闭环 B03 异步建连日志阻断项
- **决策内容**：供应链连接池显式设置 `poolName = "supply-chain-pool"`。
- **技术依据**：
  - 所有建连、借出超时均抛出标准 `SQLTransientConnectionException`，同步暴露于事务或调用者线程，由 Spring 事务边界统一捕获并打上业务 `traceId`；
  - 后台线程均带有池名标识，SLF4J 日志可以精确过滤，不再存在无池归属的匿名静态日志，解除 B03 阻断项。

---

## 3. 历史资产归档与作废契约（方案 A）

### 3.1 归档清单与 SHA-256 校验和
原深度依赖 Druid 内部类的 3 个核心基础设施源文件已原样归档至 `docs/superpowers/specs/archive/deprecated-druid/`：

| 归档源文件 | 归档相对路径 | 原始代码 SHA-256 | 职责说明 |
| :--- | :--- | :--- | :--- |
| `BudgetRoutingDataSource.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetRoutingDataSource.java` | `3221f4af89711f56f11bac2c3a2cbb8172fde2969d602ee05dfc34701e00d30b` | 历史基于 Druid 的预算路由数据源实现 |
| `BudgetConnectionWrapper.java` | `docs/superpowers/specs/archive/deprecated-druid/BudgetConnectionWrapper.java` | `55645a2e8ec5720e281e8266ce311378963ad29c94ebfb57bb608f40b842325d` | 历史基于 DruidPooledConnection 的连接包装器 |
| `MySqlSocketExtractor.java` | `docs/superpowers/specs/archive/deprecated-druid/MySqlSocketExtractor.java` | `25a39b2b3a4a87ec4d490e324bdc1ef8e9f4b170c42be420e7ffeb15d1b3a37c` | 历史针对 MySQL 驱动反射抽取 Socket 的工具类 |

### 3.2 源码原位桩代码（Stub）契约
在 `aifuli-module-supply-chain-server` 的原包路径下（`com.aifuli.module.supplychain.infrastructure.configuration`），保留同名桩类，契约如下：
1. **编译期纯净**：类内绝对不含任何 `import com.alibaba.druid.*`；
2. **类级废弃标注**：
   ```java
   @Deprecated(since = "2026-10-09", forRemoval = true)
   ```
3. **运行时熔断**：所有构造器与公开方法体统一切断并抛出异常：
   ```java
   throw new UnsupportedOperationException(
       "该类已作废：Druid 组件已完全移除，业务链路改由 Hikari 标准数据源接管，严禁调用。"
       + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md"
   );
   ```

---

## 4. 全新供应链 Hikari 数据源与预算控制链路设计

### 4.1 数据源构建与配置（`SupplyChainDataSourceConfiguration`）
```java
@Bean("supplyChainDataSource")
DataSource supplyChainDataSource(SupplyChainDataSourceProperties properties) {
    HikariDataSource dataSource = properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    // 显式指定连接池名称，天然支撑日志隔离与池身份识别（彻底解决 B03）
    dataSource.setPoolName("supply-chain-pool");
    // R02 物理建连及借出队列超时：严格沿用 1500ms
    dataSource.setConnectionTimeout(properties.getConnectTimeoutMillis());
    // 物理会话固定 UTC+8，保证本地时区一致性
    dataSource.setConnectionInitSql("SET time_zone = '+08:00'");
    // 标准探活超时
    dataSource.setValidationTimeout(1000);

    return new StandardBudgetRoutingDataSource(dataSource);
}
```

### 4.2 预算控制路由数据源（`StandardBudgetRoutingDataSource`）
- **接口定位**：实现标准 `javax.sql.DataSource`，持有底层 `HikariDataSource`。
- **无预算上下文（`BudgetContext.current() == null`）**：
  - 纯粹透明委派 `rawDataSource.getConnection()`，供 HTTP 普通常规请求、管理端查询、Liquibase 初始化使用，零额外开销。
- **有预算上下文（`BudgetContext.current() != null`）**：
  1. **借前准入核验**：调用 `context.checkAdmission()`，若 $R < 3500\text{ms}$ 或已取消，立即拒绝；
  2. **有界队列排队**：调用 `rawDataSource.getConnection()`，由于底层已配置 `connectionTimeout = 1500`，Hikari 原生保证最多排队 1500ms，超时抛出标准 `SQLTransientConnectionException`；
  3. **借后首检（防迟到连接）**：
     - 若 `context.isCancelled()` 或当前可用纳秒不足准入门槛，或向下取整可用执行秒数 $< 1$；
     - 判定该连接为“迟到连接（Late Connection）”，立即调用 `conn.abort(Runnable::run)` 彻底销毁并淘汰，绝不暴露给事务管理器；
     - 抛出 `BudgetTimeoutException`；
  4. **首检通过**：
     - 计算执行期限 $E = \min(\text{commandDeadline} - C, acquiredNano + 6\text{s})$；
     - 构造并返回 `StandardBudgetConnectionWrapper(conn, context, executionDeadlineE)`。

### 4.3 预算控制连接包装器（`StandardBudgetConnectionWrapper`）
- **接口定位**：实现标准 `java.sql.Connection`，包装底层从 Hikari 获取的 `Connection`。
- **初始化与网络超时注入**：
  - 计算初始网络超时毫秒数并设置：
    ```java
    delegate.setNetworkTimeout(Runnable::run, socketTimeoutMs);
    ```
- **SESSION 锁等待与执行守卫**：
  - 提供 `ensureSessionLockWaitTimeout(int targetSec)`，通过当前连接执行 `SET SESSION innodb_lock_wait_timeout = min(2, q)`；
  - 拦截 `createStatement()` 与 `prepareStatement()`，返回原有的 `BudgetStatementWrapper` / `BudgetPreparedStatementWrapper`（在每次 `execute*` 前执行 `q` 计算与守卫）。
- **退出与异常淘汰协议（`close()`）**：
  ```java
  @Override
  public void close() throws SQLException {
      boolean cleanupSuccess = true;
      try {
          // 1. 恢复 SESSION innodb_lock_wait_timeout 为默认 50
          if (sessionTimeoutInstalled && !delegate.isClosed()) {
              try (Statement s = delegate.createStatement()) {
                  s.execute("SET SESSION innodb_lock_wait_timeout = " + originalSessionTimeout);
              } catch (Throwable e) {
                  cleanupSuccess = false;
                  log.warn("恢复 SESSION innodb_lock_wait_timeout 失败: {}", e.getMessage());
              }
          }
          // 2. 恢复 networkTimeout
          try {
              delegate.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
          } catch (Throwable e) {
              cleanupSuccess = false;
              log.warn("恢复 networkTimeout 失败: {}", e.getMessage());
          }
      } finally {
          if (!cleanupSuccess) {
              context.markCleanupFailed();
              try {
                  // 标准 JDBC 4.1 abort: 触发 Hikari pool.evictConnection，强制关闭物理 Socket 并不回池复用
                  delegate.abort(Runnable::run);
              } catch (Throwable t) {
                  log.error("物理连接 abort 异常: {}", t.getMessage());
              }
          } else {
              delegate.close(); // 正常健康回池
          }
      }
  }
  ```

---

## 5. 通用底座清洗与配置收敛

### 5.1 POM 依赖调整
1. **`aifuli-dependencies/pom.xml`**：
   - 彻底删除 `<druid.version>1.2.28</druid.version>`；
   - 彻底删除 `<dependencyManagement>` 中的 `druid-spring-boot-3-starter`。
2. **`aifuli-framework/aifuli-spring-boot-starter-mybatis/pom.xml`**：
   - 彻底移除 `druid-spring-boot-3-starter`；
   - 显式引入 `org.springframework.boot:spring-boot-starter-jdbc`（直接集成 HikariCP）。
3. **`aifuli-framework/aifuli-spring-boot-starter-test/pom.xml`**：
   - 移除测试基类（`BaseDbUnitTest` 等）对 `DruidDataSourceAutoConfigure.class` 的引用。

### 5.2 自动配置与安全链清理
1. **`AifuliDataSourceAutoConfiguration.java`**：
   - 移除 `DruidStatProperties.class` 的注入与 `druidAdRemoveFilterFilter` 的 Bean 声明；
2. **`DruidAdRemoveFilter.java`**：
   - 标注 `@Deprecated(forRemoval = true)`，切断 Web 过滤器链路；
3. **`SecurityConfiguration.java`（各 Server）**：
   - 清除所有 `registry.requestMatchers("/druid/**").permitAll();` 配置。

---

## 6. 测试与门禁验证计划

1. **编译期门禁**：
   - 执行 `mvn clean compile`，确保全仓在**零 Druid jar** 条件下 100% 编译成功；
2. **架构约束门禁（ArchUnit）**：
   - 运行 `SupplyChainModuleArchitectureTest`，断言供应链基础设施及全模块代码中**没有任何类引用 `com.alibaba.druid.**`**；
3. **数据源与连接池单元/集成测试**：
   - 迁移 `ClaimRecoveryIntegrationTest`：将 `unwrap(DruidDataSource.class)` 改为 `unwrap(HikariDataSource.class)`，验证连接池容量与超时生效；
   - 新增 `HikariDataSourceConfigurationTest`，验证 `supply-chain-pool` 配置、1500ms 超时和上海时区会话初始化；
   - 验证 `StandardBudgetRoutingDataSourceTest`：覆盖预算上下文下的准入通过、准入拒绝、迟到连接 abort、会话超时恢复以及恢复失败触发 abort 淘汰。

---

## 7. 既有设计规范与状态更新记录

1. **《目录可靠消费前置设计》（`2026-10-05-catalog-reliable-consumption-prerequisite-design.md`）**：
   - 将原 §7.2.1 的 **R02 规范**（原题“唯一Druid池的稳定预算包装与connect1500”）的底层实现全面对齐为本设计的 Hikari + 标准 JDBC 4.1+ 契约；
2. **《R3-04 只读解阻分析》（`2026-10-08-r3-04-readonly-deblocking-analysis.md`）**：
   - 明确标注：由于底层连接池从 Druid 迁移至 HikariCP，原由 Druid 类级静态 Logger 和后台建连线程导致的 **B03 阻断项已完全消除**，为 R3-04 的最终解阻与发版提供了坚实基础。
