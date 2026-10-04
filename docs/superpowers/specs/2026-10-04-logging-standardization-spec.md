# 微服务日志记录与链路追踪开发规范

> 日期：2026-10-04  
> 状态：正式发布 / 团队开发准则  
> 适用范围：全仓库所有微服务（`gateway`、`supply-chain-server`、`member-server`、`system-server`、`infra-server`、`movie-server`、`taobao-flash-server`、`aifuli-server`）

---

## 1. 目标与背景

随着平台全面解耦为 8 个独立微服务进程，并完成与 ELK 集中式日志系统（Elasticsearch + Logstash + Kibana）的接入，日志不仅用于本地研发调试，更是生产监控、链路排障、审计追踪与告警的核心数据源。
为避免日志风格混乱、异常堆栈重复刷屏、异步/定时任务链路断裂等问题，制定本规范。全仓研发与 AI 辅助编码必须严格遵守本规范。

---

## 2. 核心规范细则

### 2.1 日志形态规范：方括号动作标签 + 键值对占位符

所有业务日志输出一律遵循统一的结构化语义前缀，禁止散落无前缀或纯自然语言描述：

```java
// 规范模板：[模块/动作标签][业务简述] key1={}, key2={}
log.info("[createOrder][创建订单成功] orderNo={}, buyerId={}, amount={}, status={}",
        order.getOrderNo(), order.getBuyerId(), order.getAmount(), order.getStatus());
```

1. **第一层方括号 `[动作标签]`**：通常为方法名或核心动作标识（如 `[createOrder]`、`[refundSync]`、`[tokenRefresh]`），便于在 Kibana / ES 中使用短语过滤或快速定位代码行；
2. **第二层方括号 `[业务简述]`**：使用简洁中文明确当前执行的业务结果（如 `[创建订单成功]`、`[核销凭证就绪]`、`[库存扣减完成]`）；
3. **占位符命名空间**：统一使用 SLF4J 参数化占位符 `{}`，采用 `key={}` 形式（如 `orderNo={}`、`status={}`），严禁使用字符串拼接（`+`）。

---

### 2.2 敏感数据策略：暂不实施日志脱敏

经架构推演与技术评估，当前阶段**暂不实施**基于注解或全局正则的日志脱敏，避免引入额外的对象序列化与性能开销。
- 研发人员无需在日志打印时手工调用脱敏逻辑，保持研发效率与排障精确度；
- 核心账号凭据（如供应商 API 密钥签名私钥、支付平台通讯私钥）严禁直接打印在日志正文中。

---

### 2.3 异常日志分级与防重复打印规范（职责单一原则）

严禁在同一异常传播链路上“底层打一次、业务层打一次、全局处理器又打一次”，导致 ELK 索引被海量相同堆栈刷满。

1. **预期内业务异常 (`ServiceException`)**：
   - 业务逻辑校验失败（如库存不足、余额不足、状态已流转等）属于系统正常业务分支；
   - **严禁使用 `log.error` 打印完整异常堆栈**；
   - 统一由外层 `GlobalExceptionHandler` 捕获后记录单行 `WARN` 业务摘要并返回前端，不污染系统健康度监控。
2. **向上抛出的异常（无需在 catch 中打完整堆栈）**：
   - 若当前层处理不了、决定继续将异常向调用方或外层抛出（`throw e` 或 `throw new ServiceException(..., e)`），**当前层不得调用 `log.error("...", e)` 打印堆栈**，防止堆栈逐层放大；
   - 可以在上抛前打一行 `log.warn("[operation][步骤失败，向上抛出] orderNo={}", orderNo)` 记录当时业务参数。
3. **本地终结/吞掉的系统异常（必须打印完整堆栈）**：
   - 若业务在 `catch` 块中对系统异常（如 `SQLException`、`IOException`、`NullPointerException`）进行兜底降级并不再上抛，**必须在此处打印完整 ERROR 堆栈与因果链**：
     ```java
     try {
         remoteRpcPort.notifyResult(notice);
     } catch (Exception e) {
         // 本地降级重试或入库，必须记录完整堆栈与入参
         log.error("[notifyResult][通知下游失败，降级写入重试队列] noticeId={}", notice.getId(), e);
         retryQueue.push(notice);
     }
     ```
4. **最外层兜底**：
   - 未被捕获的意外系统异常，由 SpringMVC 全局异常处理器 `GlobalExceptionHandler` 或网关 `GlobalExceptionHandler` 统一打印完整 `ERROR` 堆栈，并分配 `traceId`。

---

### 2.4 异步多线程与后台任务（Job/MQ）的 TraceId 规范

SLF4J MDC 基于底层 `ThreadLocal` 实现，在跨线程池调度及无 HTTP 请求头驱动的后台任务中极易丢失或产生线程复用污染。

1. **Spring 线程池透传（`TaskDecorator` 自动透传）**：
   - 所有声明的 `ThreadPoolTaskExecutor` 统一挂载 `MdcTaskDecorator`；
   - 在任务入队前捕获父线程当前的 MDC 快照（含 `traceId`），在子线程运行前写入 MDC，并在 `finally` 块中严格执行 `MDC.clear()`，彻底杜绝线程复用污染。
2. **通用工具包装（`CompletableFuture` / 自建线程）**：
   - 业务代码显式使用自建线程或 `CompletableFuture` 时，使用 `MdcTaskDecorator.wrap(runnable)` 包装任务：
     ```java
     CompletableFuture.runAsync(MdcTaskDecorator.wrap(() -> {
         // 子线程内无缝继承父线程 traceId
         doAsyncTask();
     }), executor);
     ```
3. **后台定时任务（XXL-Job / Scheduled）与 MQ 消费者**：
   - 无入站 HTTP 请求头，任务入口必须在方法开始处生成全新唯一的 `traceId`（如 `IdUtil.fastSimpleUUID()`）注入 MDC；
   - **必须在 `finally` 块中执行 `MDC.remove(TracerUtils.MDC_KEY_TRACE_ID)` 或 `MDC.clear()`**，防止定时任务线程池将上一次执行的 traceId 污染到下一次运行。

---

### 2.5 外部与第三方系统（HTTP/Feign/SDK）调用日志规范

微服务调用外部系统（如大润发、京东 VOP、银行支付网关、短信通道等），统一遵循**两段式 / 三段式语义规范**：

1. **请求发起日志（Request）**：
   - 在外呼前记录：`LOG.info("[第三方系统标识][接口名] request param1={}, param2={}", ...)`；
   - 记录关键业务标识（外部流水号、商户号、订单号、操作类型），用于与第三方厂商对账排障。
2. **响应成功日志（Response）**：
   - 在接收到成功报文后记录：`LOG.info("[第三方系统标识][接口名] response httpStatus={}, remoteCode={}, reason={}, elapsedMs={}", httpStatus, remoteCode, reason, elapsedMs)`；
   - 必须记录调用耗时 `elapsedMs`，方便评估第三方接口延迟与性能波动。
3. **调用失败日志（Failure）**：
   - 在捕获超时、HTTP 状态码非 2xx 或第三方业务失败码时记录：`LOG.error("[第三方系统标识][接口名] failure error={}, remoteCode={}, elapsedMs={}", e.getMessage(), remoteCode, elapsedMs, e)`；
4. **高频路径防风暴保护**：
   - 对高频轮询任务（如每秒轮询外部订单状态），正常无变化状态降级为 `DEBUG` 或按每 50/100 次采样输出，故障时按错误码聚合，严防打爆 TCP 缓冲区与 ES 磁盘。

---

## 3. 架构组件清单与对照表

| 组件名称 | 所在模块 | 核心功能 |
| :--- | :--- | :--- |
| `TracerUtils` | `aifuli-common` | 集中声明 `MDC_KEY_TRACE_ID = "traceId"`，兼容 SkyWalking Agent 与 MDC 兜底读取 |
| `TraceFilter` | `aifuli-spring-boot-starter-monitor` | Servlet 过滤器，双向透传 `trace-id` 请求头与响应头，双写 MDC并在 `finally` 清理 |
| `MdcTaskDecorator` | `aifuli-spring-boot-starter-monitor` | 异步线程池 `TaskDecorator`，实现跨线程父子上下文自动透传与 `finally` 隔离清理 |
| `TraceRequestInterceptor`| `aifuli-spring-boot-starter-rpc` | Feign 请求拦截器，RPC 出站时自动将 MDC 中的 `traceId` 注入下游请求头 |
| `AccessLogFilter` | `aifuli-gateway` | 网关全局过滤器，生成/透传 `traceId` 并写入结构化网关访问日志 |
| `logback-elk-appender.xml`| `aifuli-spring-boot-starter-monitor` | 统一 ELK Logstash TCP 直连配置模版（支持有界 RingBuffer 与 neverBlock 保护） |
