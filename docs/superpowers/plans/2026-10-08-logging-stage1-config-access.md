# 全系统日志规范阶段一：七进程配置接入对齐 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让除 `movie-server` 外的 7 个独立进程统一具备本地 `traceId` 输出与共享 ELK 推送配置，并由真实根配置测试锁定。

**Architecture:** 复用 monitor starter 已有的 `logback-elk-appender.xml` 共享片段，各进程只在自己的 `logback-spring.xml` 中声明 pattern、include 与 root 挂载条件，并在 `application-dev.yaml` 追加开关配置。测试通过子进程加载各进程**真实**根 Logback 配置，验证顶层 JSON、本地文件与启停行为，不做文本断言。

**Tech Stack:** Java 21、Spring Boot 3.5.x、Logback 1.5.x、logstash-logback-encoder 8.0、JUnit 5 + AssertJ

**环境前置条件（重要）：**
本机默认环境可能是 JDK 17，构建与测试前必须确保使用 JDK 21，执行命令前加 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`，否则 Maven 编译会直接抛出 `Fatal error compiling: 错误: 不支持发行版本 21`。

**依据规范:** `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md` §6.1、§10.1；`AGENTS.md` 第 6 条

---

## 范围与不做事项

**本轮范围（阶段一）**

- 为 `infra-server`、`member-server`、`aifuli-server` 补齐本地 `traceId` pattern 与共享 ELK include。
- 为 `taobao-flash-channel` 新建 `logback-spring.xml`（当前完全没有根配置，依赖 Spring Boot 默认，无 traceId、无 ELK）。
- 为 4 个进程补 `application-dev.yaml` 的 `aifuli.logging.elk` 配置；`taobao-flash-channel` 需新建该文件。
- 扩展 `LogbackElkAppenderConfigurationTest`，用真实根配置覆盖新增 4 个进程。

**明确不做（不得顺手加入）**

- 不改 `movie-server`：2026-10-08 用户决定暂不修改，其 pom 未声明 monitor starter，接入会引入新依赖与安全/数据源风险。
- 不改任何 Java 业务代码、不改 `TracerUtils`/`TraceFilter` 读取优先级、不改 Servlet MDC 恢复语义——这些属于阶段二框架修正。
- 不改 `member-server` 的 `platform-api.WUSHENG.inbound/outbound` 专用 logger 与独立文件：2026-10-08 用户决定保持现状并显式记录，两者继续 `additivity="false"`，不进入集中式平台。
- 不新增 `logging.file.name`。`supply-chain-server` 与 `aifuli-server` 的 dev 配置已各自声明，`taobao-flash-channel` 本轮沿用 Spring Boot 默认 `LOG_FILE` 行为，与现有 `supply-chain-server` 一致；统一文件路径属独立事项。
- 不做真实环境部署、Logstash/ES 联调与 Kibana 验证——属阶段三，需部署审批。

---

## 变更文件清单

| 文件 | 动作 | 职责 |
| :--- | :--- | :--- |
| `aifuli-framework/aifuli-spring-boot-starter-monitor/src/test/java/com/aifuli/framework/tracer/config/LogbackElkAppenderConfigurationTest.java` | Modify | 参数化覆盖 7 个进程真实根配置 |
| `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/logback-spring.xml` | Modify | traceId pattern + ELK include + root 挂载 |
| `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-dev.yaml` | Modify | 追加 `aifuli.logging.elk` |
| `aifuli-module-member/aifuli-module-member-server/src/main/resources/logback-spring.xml` | Modify | 同上；**保留** WUSHENG 专用 logger 不变 |
| `aifuli-module-member/aifuli-module-member-server/src/main/resources/application-dev.yaml` | Modify | 追加 `aifuli.logging.elk` |
| `aifuli-server/src/main/resources/logback-spring.xml` | Modify | traceId pattern + ELK include + root 挂载 |
| `aifuli-server/src/main/resources/application-dev.yaml` | Modify | 在**已有** `aifuli:` 节点下追加 `logging.elk` |
| `aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/logback-spring.xml` | Create | 新建根配置（当前缺失） |
| `aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/application-dev.yaml` | Create | 新建 dev 配置，仅含注册中心 + ELK 开关 |
| `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md` | Modify | §1.3 现状更新、§3.4 显式记录 WUSHENG 决策、movie 例外 |
| `docs/集成测试.md` | Modify | §8.12 追加本轮执行记录 |

---

## Task 1: 验证 aifuli-server 与 taobao-flash 的 ELK 依赖可达

**Files:**
- 无（仅验证）

- [ ] **Step 1: 确认 aifuli-server 类路径含 monitor starter 与 encoder**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B -ntp -pl aifuli-server -am dependency:tree -Dincludes=com.aifuli:aifuli-spring-boot-starter-monitor,net.logstash.logback:logstash-logback-encoder
```

Expected: 在 `[INFO] --- dependency:3.7.0:tree ... @ aifuli-server ---` 段落下，输出包含：
```
com.aifuli:aifuli-server:jar:...
\- com.aifuli:aifuli-module-system-server:jar:...:compile
   \- com.aifuli:aifuli-spring-boot-starter-monitor:jar:...:compile
      \- net.logstash.logback:logstash-logback-encoder:jar:...:compile
```
确认 monitor starter 与 encoder 均通过 `system-server` 传递依赖进入。注：不能加 `-q`，否则 dependency:tree 的 INFO 输出会被完全屏蔽。

- [ ] **Step 2: 确认 taobao-flash-channel 直接声明 monitor starter**

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-channel-taobao-flash-server -am dependency:tree -Dincludes=com.aifuli:aifuli-spring-boot-starter-monitor,net.logstash.logback:logstash-logback-encoder
```

Expected: 在 `aifuli-channel-taobao-flash-server` 段落下，输出包含：
```
com.aifuli:aifuli-channel-taobao-flash-server:jar:...
\- com.aifuli:aifuli-spring-boot-starter-monitor:jar:...:compile
   \- net.logstash.logback:logstash-logback-encoder:jar:...:compile
```

- [ ] **Step 3: 确认现有 dev 配置基线，供新增配置对齐**

Run:

```bash
grep -A5 'logging:' aifuli-module-system/aifuli-module-system-server/src/main/resources/application-dev.yaml
```

Expected: 输出

```yaml
aifuli:
  logging:
    elk:
      enabled: true
      host: 172.19.64.105
      port: 50000
```

后续所有新增配置必须与此完全一致（端点、缩进、注释风格），不得引入环境变量占位符。

---

## Task 2: 扩展真实根配置测试覆盖新增 4 个进程（RED）

**Files:**
- Modify: `aifuli-framework/aifuli-spring-boot-starter-monitor/src/test/java/com/aifuli/framework/tracer/config/LogbackElkAppenderConfigurationTest.java:44-60`

- [ ] **Step 1: 扩展参数化用例与模块路径映射**

将 `@CsvSource` 与 `switch` 改为：

```java
    // movie-server 2026-10-08 决定暂不接入，故不在本参数化列表中；接入时另起用例并同步设计文档。
    @ParameterizedTest(name = "{0}/{1}/enabled={2}")
    @CsvSource({"gateway,dev,true", "gateway,dev,false", "gateway,local,false", "gateway,local,true",
            "system,dev,true", "system,dev,false", "system,local,false", "system,local,true",
            "supplychain,dev,true", "supplychain,dev,false", "supplychain,local,false", "supplychain,local,true",
            "infra,dev,true", "infra,dev,false", "infra,local,false", "infra,local,true",
            "member,dev,true", "member,dev,false", "member,local,false", "member,local,true",
            "aifuli,dev,true", "aifuli,dev,false", "aifuli,local,false", "aifuli,local,true",
            "taobaoflash,dev,true", "taobaoflash,dev,false", "taobaoflash,local,false", "taobaoflash,local,true"})
    void shouldLoadCompleteBootConfiguration(String service, String profile, boolean enabled) throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(repository.resolve("AGENTS.md"))) {
            repository = repository.getParent();
            if (repository == null) {
                throw new IllegalStateException("找不到仓库根 AGENTS.md");
            }
        }
        String module = switch (service) {
            case "gateway" -> "aifuli-gateway";
            case "system" -> "aifuli-module-system/aifuli-module-system-server";
            case "supplychain" -> "aifuli-module-supply-chain/aifuli-module-supply-chain-server";
            case "infra" -> "aifuli-module-infra/aifuli-module-infra-server";
            case "member" -> "aifuli-module-member/aifuli-module-member-server";
            case "aifuli" -> "aifuli-server";
            case "taobaoflash" -> "aifuli-module-supply-chain/aifuli-channel-taobao-flash-server";
            default -> throw new IllegalArgumentException("未知服务标识: " + service);
        };
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test \
  -Dtest=LogbackElkAppenderConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAILURE。`infra`、`member`、`aifuli`、`taobaoflash` 的 `dev/true` 组合失败，失败原因为本地文件断言不含 `[probe-trace]`（pattern 缺 `%X{traceId}`）；`taobaoflash` 的 `dev/true` 还会在读取 `logback-spring.xml` 时抛 `FileNotFoundException`。既有 `gateway`、`system`、`supplychain` 12 组必须仍然通过——若它们也失败，说明基线已被破坏，先修复再继续。

- [ ] **Step 3: 提交测试（保留 RED 证据）**

```bash
git add aifuli-framework/aifuli-spring-boot-starter-monitor/src/test/java/com/aifuli/framework/tracer/config/LogbackElkAppenderConfigurationTest.java
git commit -m "扩展真实根配置测试覆盖 infra、member、aifuli、taobao-flash 四个进程"
```

---

## Task 3: infra-server 配置接入对齐

**Files:**
- Modify: `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/logback-spring.xml`
- Modify: `aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-dev.yaml`

- [ ] **Step 1: 用完整内容替换 logback-spring.xml**

```xml
<configuration>
    <!-- 参考 org/springframework/boot/logging/logback/defaults.xml 配置，优化 CONSOLE_LOG_PATTERN、FILE_LOG_PATTERN -->
    <!-- 格式化输出：%d 表示日期，%thread 表示线程名，%-5level：级别从左显示 5 个字符宽度，%msg：日志消息，%n是换行符 -->
    <!-- CONSOLE_LOG_PATTERN 相比 FILE_LOG_PATTERN 多了 highlight、cyan 等高亮 -->
    <!-- [%X{traceId}] 为日志关联编号，ELK 不可用时本地文件仍可按编号排障；规范见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md -->
    <property name="CONSOLE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{50}:%L) - [%X{traceId}] %msg%n"/>
    <property name="FILE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50}:%L - [%X{traceId}] %msg%n"/>

    <!-- 控制台 Appender -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">　　　　　
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <!-- 文件 Appender -->
    <!-- 参考 Spring Boot 的 file-appender.xml 编写 -->
    <appender name="FILE"  class="ch.qos.logback.core.rolling.RollingFileAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>${FILE_LOG_PATTERN}</pattern>
        </encoder>
        <!-- 日志文件名 -->
        <file>${LOG_FILE}</file>
        <!-- 滚动策略：基于【每天 + 大小】创建日志文件 -->
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_FILE}.%d{yyyy-MM-dd}.%i.log</fileNamePattern> <!-- 日志文件输出的文件名 -->
            <maxHistory>30</maxHistory> <!-- 日志文件的保留天数 -->
            <maxFileSize>10MB</maxFileSize> <!-- 日志文件，到达多少容量，进行滚动 -->
        </rollingPolicy>
    </appender>
    <!-- 异步写入日志，提升性能 -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <discardingThreshold>0</discardingThreshold> <!-- 不丢失日志。默认的，如果队列的 80% 已满,则会丢弃 TRACT、DEBUG、INFO 级别的日志 -->
        <queueSize>512</queueSize> <!-- 更改默认的队列的深度，该值会影响性能。默认值为 256 -->
        <appender-ref ref="FILE"/>
    </appender>

    <!-- 引入共享 ELK Logstash TCP Appender 配置 -->
    <include resource="logback-elk-appender.xml"/>

    <!-- SkyWalking Appender：GRPC 日志收集，实现日志中心 -->
    <!--
    <appender name="SKYWALKING" class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.log.GRPCLogClientAppender">
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <layout class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdPatternLogbackLayout">
                <pattern>[%tid] ${FILE_LOG_PATTERN}</pattern>
            </layout>
        </encoder>
    </appender>
    -->

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <!-- 本地环境下，如果不想【FILE】打印日志，可以注释掉本行 -->
        <appender-ref ref="ASYNC"/>
    </root>

    <!-- 仅在非本地环境且显式开启时挂载 TCP Appender -->
    <springProfile name="dev,test,prod">
        <if condition='property("ELK_ENABLED").equalsIgnoreCase("true")'>
            <then>
                <root level="INFO">
                    <appender-ref ref="LOGSTASH_TCP"/>
                </root>
            </then>
        </if>
    </springProfile>

</configuration>
```

- [ ] **Step 2: 在 application-dev.yaml 末尾追加 ELK 配置**

在文件末尾追加（该文件当前无顶层 `aifuli:` 节点）：

```yaml

--- #################### 日志与 ELK 集成配置 ####################

# 集中式日志开关与端点：仅 dev 环境开启；test/prod 由部署配置（Nacos 或环境变量）提供，不在仓库内写死端点。
aifuli:
  logging:
    elk:
      enabled: true
      host: 172.19.64.105
      port: 50000
```

- [ ] **Step 3: 运行 infra 组合确认 GREEN**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test \
  -Dtest=LogbackElkAppenderConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `infra` 4 组全部通过；`member`、`aifuli`、`taobaoflash` 仍为 RED（尚未接入）。

- [ ] **Step 4: 提交**

```bash
git add aifuli-module-infra/aifuli-module-infra-server/src/main/resources/logback-spring.xml \
        aifuli-module-infra/aifuli-module-infra-server/src/main/resources/application-dev.yaml
git commit -m "接入 infra-server 集中式日志配置并补齐本地 traceId 输出"
```

---

## Task 4: member-server 配置接入对齐（保留 WUSHENG 专用通道）

**Files:**
- Modify: `aifuli-module-member/aifuli-module-member-server/src/main/resources/logback-spring.xml`
- Modify: `aifuli-module-member/aifuli-module-member-server/src/main/resources/application-dev.yaml`

- [ ] **Step 1: 更新 logback-spring.xml 的 pattern 并引入共享片段**

将第 5、6 行替换为：

```xml
    <!-- [%X{traceId}] 为日志关联编号，ELK 不可用时本地文件仍可按编号排障；规范见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md -->
    <property name="CONSOLE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{50}:%L) - [%X{traceId}] %msg%n"/>
    <property name="FILE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50}:%L - [%X{traceId}] %msg%n"/>
```

在 `ASYNC` appender 之后、平台开放接口 appender 之前插入：

```xml
    <!-- 引入共享 ELK Logstash TCP Appender 配置；仅 root 走集中式平台，下方专用报文 logger 不受影响 -->
    <include resource="logback-elk-appender.xml"/>
```

在文件末尾 `</configuration>` 之前插入：

```xml
    <!-- 仅在非本地环境且显式开启时挂载 TCP Appender -->
    <springProfile name="dev,test,prod">
        <if condition='property("ELK_ENABLED").equalsIgnoreCase("true")'>
            <then>
                <root level="INFO">
                    <appender-ref ref="LOGSTASH_TCP"/>
                </root>
            </then>
        </if>
    </springProfile>

</configuration>
```

**不要修改** `PLATFORM_API_WUSHENG_INBOUND*`、`PLATFORM_API_WUSHENG_OUTBOUND*` appender 及其两个 `<logger ... additivity="false">` 节点。它们按 2026-10-08 决策继续只写本地独立文件。

- [ ] **Step 2: 在 application-dev.yaml 末尾追加 ELK 配置**

```yaml

--- #################### 日志与 ELK 集成配置 ####################

# 集中式日志开关与端点：仅 dev 环境开启；test/prod 由部署配置（Nacos 或环境变量）提供，不在仓库内写死端点。
aifuli:
  logging:
    elk:
      enabled: true
      host: 172.19.64.105
      port: 50000
```

- [ ] **Step 3: 运行 member 组合确认 GREEN**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test \
  -Dtest=LogbackElkAppenderConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `member` 4 组通过；`aifuli`、`taobaoflash` 仍 RED。

- [ ] **Step 4: 提交**

```bash
git add aifuli-module-member/aifuli-module-member-server/src/main/resources/logback-spring.xml \
        aifuli-module-member/aifuli-module-member-server/src/main/resources/application-dev.yaml
git commit -m "接入 member-server 集中式日志配置并保持华昇专用报文通道独立"
```

---

## Task 5: aifuli-server 配置接入对齐

**Files:**
- Modify: `aifuli-server/src/main/resources/logback-spring.xml`
- Modify: `aifuli-server/src/main/resources/application-dev.yaml`

- [ ] **Step 1: 用完整内容替换 logback-spring.xml**

内容与 Task 3 Step 1 的 XML 完全一致（`aifuli-server` 现有文件含同样的 SkyWalking 注释块，替换后结构不变，仅 pattern、include 与 root 挂载为新增）。

- [ ] **Step 2: 在已有 `aifuli:` 节点下追加 ELK 配置**

`aifuli-server/src/main/resources/application-dev.yaml` 第 150 行已有顶层 `aifuli:` 节点。将

```yaml
aifuli:
  pay:
```

改为

```yaml
aifuli:
  # 集中式日志开关与端点：仅 dev 环境开启；test/prod 由部署配置（Nacos 或环境变量）提供，不在仓库内写死端点。
  logging:
    elk:
      enabled: true
      host: 172.19.64.105
      port: 50000
  pay:
```

注意：该文件第 124 行已存在顶层 `logging.file.name`，与 `aifuli.logging.elk` 是不同键路径，不得合并或覆盖。

- [ ] **Step 3: 运行 aifuli 组合确认 GREEN**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test \
  -Dtest=LogbackElkAppenderConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `aifuli` 4 组通过；`taobaoflash` 仍 RED。

- [ ] **Step 4: 提交**

```bash
git add aifuli-server/src/main/resources/logback-spring.xml \
        aifuli-server/src/main/resources/application-dev.yaml
git commit -m "接入 aifuli-server 集中式日志配置并补齐本地 traceId 输出"
```

---

## Task 6: taobao-flash-channel 新建根配置与 dev 配置

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/logback-spring.xml`
- Create: `aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/application-dev.yaml`

- [ ] **Step 1: 新建 logback-spring.xml**

该进程此前没有根 Logback 配置，依赖 Spring Boot 默认输出，本步建立与其它进程一致的结构（无 SkyWalking 注释块、无 `logging.file.name`，`LOG_FILE` 沿用 Spring Boot 默认行为，与 `supply-chain-server` 一致）：

```xml
<configuration>
    <!-- 参考 org/springframework/boot/logging/logback/defaults.xml 配置，优化 CONSOLE_LOG_PATTERN、FILE_LOG_PATTERN -->
    <!-- 格式化输出：%d 表示日期，%thread 表示线程名，%-5level：级别从左显示 5 个字符宽度，%msg：日志消息，%n是换行符 -->
    <!-- CONSOLE_LOG_PATTERN 相比 FILE_LOG_PATTERN 多了 highlight、cyan 等高亮 -->
    <!-- [%X{traceId}] 为日志关联编号，ELK 不可用时本地文件仍可按编号排障；规范见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md -->
    <property name="CONSOLE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %cyan(%logger{50}:%L) - [%X{traceId}] %msg%n"/>
    <property name="FILE_LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50}:%L - [%X{traceId}] %msg%n"/>

    <!-- 控制台 Appender -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <!-- 文件 Appender -->
    <!-- 参考 Spring Boot 的 file-appender.xml 编写 -->
    <appender name="FILE"  class="ch.qos.logback.core.rolling.RollingFileAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>${FILE_LOG_PATTERN}</pattern>
        </encoder>
        <!-- 日志文件名 -->
        <file>${LOG_FILE}</file>
        <!-- 滚动策略：基于【每天 + 大小】创建日志文件 -->
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_FILE}.%d{yyyy-MM-dd}.%i.log</fileNamePattern> <!-- 日志文件输出的文件名 -->
            <maxHistory>30</maxHistory> <!-- 日志文件的保留天数 -->
            <maxFileSize>10MB</maxFileSize> <!-- 日志文件，到达多少容量，进行滚动 -->
        </rollingPolicy>
    </appender>
    <!-- 异步写入日志，提升性能 -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <discardingThreshold>0</discardingThreshold> <!-- 不丢失日志。默认的，如果队列的 80% 已满,则会丢弃 TRACT、DEBUG、INFO 级别的日志 -->
        <queueSize>512</queueSize> <!-- 更改默认的队列的深度，该值会影响性能。默认值为 256 -->
        <appender-ref ref="FILE"/>
    </appender>

    <!-- 引入共享 ELK Logstash TCP Appender 配置 -->
    <include resource="logback-elk-appender.xml"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <!-- 本地环境下，如果不想【FILE】打印日志，可以注释掉本行 -->
        <appender-ref ref="ASYNC"/>
    </root>

    <!-- 仅在非本地环境且显式开启时挂载 TCP Appender -->
    <springProfile name="dev,test,prod">
        <if condition='property("ELK_ENABLED").equalsIgnoreCase("true")'>
            <then>
                <root level="INFO">
                    <appender-ref ref="LOGSTASH_TCP"/>
                </root>
            </then>
        </if>
    </springProfile>

</configuration>
```

- [ ] **Step 2: 新建 application-dev.yaml**

该模块 `application.yaml` 通过 `optional:classpath:application-${spring.profiles.active}.yaml` 导入，故新建 dev 文件即可生效：

```yaml
--- #################### 注册中心 + 配置中心相关配置 ####################

spring:
  cloud:
    nacos:
      server-addr: 47.117.130.100:8848 # Nacos 服务器地址
      username: nacos # Nacos 账号
      password: "!2026NacosAdmin@Qhf." # Nacos 密码，! 开头必须使用引号避免 YAML 解析为 tag
      discovery: # 【注册中心】配置项
        namespace: aifuli-mall-dev # 命名空间 ID：aifuli-mall-dev
        group: DEFAULT_GROUP # 使用的 Nacos 配置分组，默认为 DEFAULT_GROUP
        metadata:
          version: 1.0.0 # 服务实例的版本号，可用于灰度发布
      config: # 【配置中心】配置项
        namespace: aifuli-mall-dev # 命名空间 ID：aifuli-mall-dev
        group: DEFAULT_GROUP # 使用的 Nacos 配置分组，默认为 DEFAULT_GROUP

--- #################### 日志与 ELK 集成配置 ####################

# 集中式日志开关与端点：仅 dev 环境开启；test/prod 由部署配置（Nacos 或环境变量）提供，不在仓库内写死端点。
aifuli:
  logging:
    elk:
      enabled: true
      host: 172.19.64.105
      port: 50000
```

- [ ] **Step 3: 运行 taobaoflash 组合确认 GREEN**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test \
  -Dtest=LogbackElkAppenderConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部 28 组 PASS，0 failures / 0 errors / 0 skipped。

- [ ] **Step 4: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/logback-spring.xml \
        aifuli-module-supply-chain/aifuli-channel-taobao-flash-server/src/main/resources/application-dev.yaml
git commit -m "为淘宝闪购渠道服务建立根日志配置并接入集中式日志开关"
```

---

## Task 7: 框架与受影响模块回归门禁

**Files:**
- 无（仅运行验证）

- [ ] **Step 1: 运行 monitor starter 全量单测**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-monitor -am test
```

Expected: BUILD SUCCESS，0 failures / 0 errors / 0 skipped。覆盖 `TraceFilterTest`、`MdcTaskDecoratorTest`、`TracerUtilsTest`、`LoggingElkPropertiesTest`、`TraceRequestInterceptorTest` 等。

- [ ] **Step 2: 运行 rpc starter 回归（TraceRequestInterceptor）**

Run:

```bash
mvn -B -ntp -pl aifuli-framework/aifuli-spring-boot-starter-rpc -am test
```

Expected: BUILD SUCCESS，含 `TraceRequestInterceptorTest` 2 项。

- [ ] **Step 3: 运行网关回归**

Run:

```bash
mvn -B -ntp -pl aifuli-gateway -am test
```

Expected: BUILD SUCCESS，含 `GatewayTracePropagationTest`、`GatewayReactorMdcLifecycleTest`。

- [ ] **Step 4: 运行供应链模块门禁**

Run:

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，含 `SupplyChainModuleArchitectureTest` 全部门禁。本轮未改供应链 server 代码，但 `aifuli-channel-taobao-flash-server` 是同 reactor 模块，需确认无回归。

- [ ] **Step 5: 运行淘宝闪购渠道模块测试**

Run:

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-channel-taobao-flash-server -am test
```

Expected: BUILD SUCCESS。确认新增 `logback-spring.xml` 未影响该模块既有测试。

---

## Task 8: 更新规范现状与决策记录

**Files:**
- Modify: `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md`
- Modify: `docs/集成测试.md`

- [ ] **Step 1: 更新规范 §1.3 现状描述**

将 §1.3 中描述现状的两条替换为：

```markdown
- 七进程（除 `movie-server`）根 Logback 配置均已引用共享 ELK 片段，本地 pattern 均包含 MDC `traceId`；`movie-server` 于 2026-10-08 决定暂不接入，接入时须先评审其依赖与安全/数据源影响。
- gateway、system、supply-chain、infra、member、aifuli、taobao-flash 的 `application-dev.yaml` 已声明 `aifuli.logging.elk`；test/prod 端点由部署配置提供，不在仓库内写死。
- member 的 `platform-api.WUSHENG.inbound/outbound` 于 2026-10-08 决策保持现状：继续 `additivity="false"` 只写本地独立文件，不进入集中式平台。
```

- [ ] **Step 2: 在规范 §3.4 补充专用通道决策**

在该节末尾追加：

```markdown
2026-10-08 决策：member 的华昇开放接口专用报文通道保持现有实现（`additivity="false"`、独立滚动文件、30 天历史），**不接入集中式日志平台**。理由：其报文为完整请求/响应，超出本规范的常规事件字段范围。后续若需集中检索，应另行设计脱敏与体积口径，不得在本次接入中顺带开启。
```

- [ ] **Step 3: 在 §10.1 记录 movie-server 例外**

在 §10.1 段末追加：

```markdown
2026-10-08 决策：`movie-server` 暂不纳入本轮接入。理由：该模块使用基础 Spring Boot 组件、pom 未声明 monitor starter，接入会引入新依赖并需评估安全、数据源与任务执行语义影响。其接入按同一 §10.1 流程单独执行。
```

- [ ] **Step 4: 在集成测试文档 §8.12 追加执行记录**

在 §8.12 末尾追加以下小节（数值按实际执行结果填写，不得预填）：

```markdown
#### 8.12.4 阶段一配置接入执行记录（2026-10-08）

范围：除 `movie-server` 外 7 个独立进程的根 Logback 配置与 dev ELK 开关接入；不含 Java 业务代码、框架编号修正与真实环境联调。

1. 根配置测试扩展：`LogbackElkAppenderConfigurationTest` 覆盖 7 进程 × 4 组合 = 28 组，`dev/true` 断言真实 TCP JSON 顶层 `traceId`/`app_name`/`env`，`dev/false` 与 `local/*` 断言无连接。
2. RED 证据：新增 4 进程接入前的失败 XML 与子进程日志，失败原因为本地文件不含 `[probe-trace]`（pattern 缺 `%X{traceId}`）与 `taobaoflash` 缺根配置。
3. GREEN 结果：（填写实际数量与失败数）。
4. 回归范围：（填写 monitor、rpc、gateway、供应链、淘宝闪购各模块实际执行结果）。
5. 未覆盖：test/prod 端点配置、真实 Logstash/ES 写入、Kibana 检索、前端响应头可见性；均属阶段三。
```

- [ ] **Step 5: 提交文档**

```bash
git add docs/superpowers/specs/2026-10-04-logging-standardization-spec.md 'docs/集成测试.md'
git commit -m "记录七进程日志配置接入现状、movie 例外与华昇专用通道决策"
```

---

## 完成定义

- [ ] `LogbackElkAppenderConfigurationTest` 28 组全部通过，且探针证据落盘于 `aifuli-framework/aifuli-spring-boot-starter-monitor/target/elk-probe-results/`。
- [ ] 7 个进程的 `logback-spring.xml` 均含 `%X{traceId}`、共享片段 include 与 `springProfile` 条件挂载。
- [ ] 7 个进程的 dev 配置均含与既有试点一致的 `aifuli.logging.elk` 端点。
- [ ] monitor、rpc、gateway、供应链、淘宝闪购模块回归全部 BUILD SUCCESS。
- [ ] 规范与集成测试文档已记录现状、例外与决策，未把未验证内容写成已通过。

## 回滚

每个任务独立提交且只改配置文件，回滚时按 `git revert <commit>` 逐个撤销即可，无需回滚代码。测试扩展可保留，便于后续接入 `movie-server` 时复用。