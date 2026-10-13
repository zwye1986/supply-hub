# 供应链测试环境启用 Timer 与 Outbox

## 1. 范围与授权

用户在核心链路就绪分析后明确要求解决第2项“Outbox 和 Timer 实际配置均为关闭”。本次只把 pro143 `supply-chain-server-test` 的 `aifuli.supply-chain.timer.enabled` 与 `aifuli.supply-chain.outbox.enabled` 改为 `true`，并同步仓库测试部署模板。不改镜像、Java 代码、数据库数据、项目能力授权、路由规则、商品配置或资金；不修改、删除或补标任何已有 Outbox/Timer 记录。

本文替代 `2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md` §1 第6条、§2 Nacos 建议内容、环境变量“第二道关闭保护”及 §11/§12 中“共享数据库阶段保持关闭”的约束；该前提已不成立（见 §2）。

## 2. 前提核对

- 订单主链依赖两个引擎：受理事务追加 `FULFILLMENT_OPERATION_READY`（LOCAL target `fulfillment-operation-worker`），由 Outbox 投递后安排 `OPERATION_EXECUTE` Timer，华昇执行、准备看门狗、租约看门狗与恢复也都是 Timer 任务。两者任一关闭，订单受理后停在资金冻结、不会调用华昇。
- `TimerEngineLifecycle.start` 与 `OutboxDeliveryEngine.start` 只在启动时读取开关；关闭时直接返回且不会随配置刷新再启动，因此变更须重建容器使进程重新启动。
- 生效来源：应用以 `spring.config.import` 导入 Nacos，操作系统环境变量优先级高于导入的配置数据；`AIFULI_SUPPLY_CHAIN_TIMER_ENABLED`/`AIFULI_SUPPLY_CHAIN_OUTBOX_ENABLED` 经 Spring Boot 环境变量宽松绑定映射到上述属性。故 `.env` 是实际控制层，Nacos 同步改为 `true` 只为避免两层配置矛盾。完成判定以启动日志 `TimerEngine started` 与 `OutboxDeliveryEngine started` 为准，不以配置文本推断。
- 原关闭理由是“开发和测试共用数据库，避免本地与 pro143 抢占任务”。2026-10-12 14:22 只读快照显示，应用账号在 `supply-chain-test` 上的连接全部来自 pro143（`172.19.64.105`），本机也没有运行供应链进程。应用账号无 PROCESS 权限，看不到其它账号的连接；此后若有开发实例使用该库，必须保持其 Timer/Outbox 关闭，pro143 是唯一消费者。
- Timer 已注册全部生产任务类型的处理器（`OPERATION_EXECUTE`、`RECHARGE_FREQUENCY_WAKE` 及华昇四类任务，后者由已开启的 `huasheng-fulfillment.enabled` 装配）；当前 Timer 任务为0条。

## 3. 无消费者的历史 target

当前源码中 `supplier-config-cache-invalidation`（连接器发布/退役）、`supply-availability-recalculation`（Offer 发布与上架、路由发布）、`buyer-catalog-change-fanout`（MANUAL 目录发布）只有生产者，没有 `OutboxDispatchHandler`。按 `2026-10-09-saleable-catalog-order-acceptance-design.md` §1、§6，目录可售由后台周期扫描读取当前事实，不依赖这些事件。

启用后，这类事件会抛出 `No OutboxDispatchHandler`，按既有退避重试，达到 `max_attempts=20` 后置为 DEAD 并记录 WARN。领取 SQL 不按聚合排序（`OutboxEventMapper.selectDeliverable`），失败事件不阻塞订单事件。本次接受这一结果：不 SQL 标记成功、不改 target、不新增空处理器伪称已失效缓存。删除这些生产者或补真实消费者属于后续代码决策。启用时库中只有1条此类 PENDING 事件（2026-10-11 22:52 连接器发布）。

`buyer-event-rpc` 与 `supply-chain-order-event` 已注册处理器，但当前只解析并记录日志，不主动通知分销商；投递成功不表示已通知买家。

## 4. 发布、验证与回滚

1. 备份 pro143 `/data/aifuli-test/supply-chain/.env`（权限0600），只把两行环境变量改为 `true`；通过 Nacos OpenAPI 读取当前 `supply-chain-server-test.yaml`，备份原文，只把 `timer.enabled`、`outbox.enabled` 两处改为 `true` 后按 yaml 发布，并重新读取确认其余内容逐字未变。
2. 在 Compose 目录执行 `docker compose up -d` 重建容器，镜像不变。
3. 验证：容器 healthy；启动日志出现两条引擎启动日志且无启动异常；容器环境变量与 Nacos 快照均为 `true`；只读查询看到存量孤儿事件 `attempt_count` 增加、状态为 RETRY 且错误码为 `IllegalStateException`；网关、公网订单入口无签名探针仍返回 JSON 业务码 1050001001。
4. 回滚：恢复 `.env` 备份并重建容器（环境变量层即可使引擎重新关闭），再按备份恢复 Nacos 原文。

本项完成只表示订单受理之后的异步执行引擎可运行，不表示项目能力授权、可售目录、路由和华昇真实交易已经验收。
