# 电影专业子系统

独立启动类：`com.aifuli.module.movie.MovieServerApplication`。默认服务名 `movie-server`，默认端口 `58191`。
模块只扫描 `com.aifuli.module.movie`，不加入商城聚合应用；平台主数据没有商城 `tenant_id`。

已实现六张主数据/Mapping 表的 DO 与 Mapper、JC/JY/LX 主数据与排期同步、多账号配置、MongoDB 快照写入和在售查询。安全配置仅开放 `/actuator/health`，其余请求拒绝访问。
业务 RPC、实时验座报价与出票流程尚未实现。

## 本地配置

默认启用 `local`，从 Nacos 命名空间 `aifuli-supplyhub-local`、分组 `DEFAULT_GROUP` 加载 `movie-server-local.yaml` 并注册服务。业务配置统一维护在 Nacos，仓库模板为 `script/deploy/movie/movie-server-local.yaml`；先将模板中的 `movie`、`xxl` 节点合并到对应配置，保留已有环境连接和账号启停状态，再启动服务。`application.yaml` 只保留基础配置，以下环境变量用于基础设施默认值；远端已有同名属性时以最终生效配置为准：

| 环境变量 | 用途与默认值 |
| --- | --- |
| `MOVIE_SERVER_PORT` | HTTP 端口，默认 `58191` |
| `MOVIE_MYSQL_URL` | JDBC 地址，默认本机 MySQL 的 `aifuli_movie` |
| `MOVIE_MYSQL_USERNAME` / `MOVIE_MYSQL_PASSWORD` | 电影数据库账号；默认用户名 `movie`，密码为空占位 |
| `MOVIE_MONGODB_URI` | 完整连接 URI，默认本机 `aifuli_movie`、`replicaSet=rs0` 并配置连接超时；必须指定独立电影库和实际副本集拓扑 |
| `MOVIE_REDIS_HOST` / `MOVIE_REDIS_PORT` | Redis 地址，默认 `127.0.0.1:6379` |
| `MOVIE_REDIS_DATABASE` / `MOVIE_REDIS_PASSWORD` | Redis 逻辑库与密码，默认 `0` 与空占位 |

使用 IDE 将 `src/main/resources` 和 `../sql/mysql/movie` 的已声明 Maven 资源导入运行配置，执行独立启动类。
Liquibase 默认关闭，先按 [数据库初始化说明](../sql/mysql/movie/README.md)执行显式迁移。
应用健康检查会连接 MySQL、MongoDB 和 Redis；缺失基础设施时不能将健康状态视为验收通过。

## 服务注册

设置 `SPRING_PROFILES_ACTIVE=dev` 后启用 Nacos，并通过 `MOVIE_NACOS_SERVER_ADDR`、`MOVIE_NACOS_NAMESPACE`、
`MOVIE_NACOS_GROUP`、`MOVIE_NACOS_USERNAME`、`MOVIE_NACOS_PASSWORD` 指定配置中心与注册中心。
数据标识为 `movie-server-dev.yaml`，业务节点可复用 `script/deploy/movie/movie-server-test.yaml` 中的 `movie`、`xxl` 配置。
`test` 环境使用命名空间 `aifuli-supplyhub-test`、分组 `test`、数据标识 `movie-server-test.yaml`，模板位于 `script/deploy/movie/`。尚未提供 prod 配置，部署前按环境补齐独立连接与发布配置。

供应商账号和 XXL-JOB 执行器参数来自 Nacos；`movie.supply.accounts` 就是供应商接入账号列表，用于配置渠道、账号启停、接口地址及凭证环境变量引用。列表按整份替换，新增账号应填写完整元信息。Nacos 导入仍为 optional，缺少远端业务配置时账号列表为空、执行器默认关闭。账号和执行器参数按发布后重启验收，只有统一停售提前量支持下次查询动态生效。五类任务的启停和执行时间全部在 XXL-JOB 调度中心管理，Nacos 不配置同步总开关或分类开关。

## 数据与后续实现

SQL 唯一源目录为 `sql/mysql/movie/`，构建资源规则仅收录电影变更入口与 `releases/`，不收录商城表。
应用不开启自动建表或 MongoDB 自动建索引；正式迁移由独立发布流程执行。
六张表通过独立 MyBatis Plus Mapper 查询，采用 Lambda 条件和影院主键游标分页，不需要分页插件；不继承租户或逻辑删除基类。

TODO（[详细设计](../docs/电影子系统与中台履约详细设计.md)第 2.3、3.2、3.4、4.2、4.4 节）：先补齐尚未定义的供应商账号、报价、操作记录 DDL，
再实现接入配置数据库管理、实时验座、报价提交、幂等履约与系统间鉴权。当前供给采集账号由进程配置管理，凭证仅通过环境变量引用读取。
外部 API 在实际消费时显式注册，不能扫描全部 Feign 接口。

人工验收与排障步骤见 [集成测试记录](../docs/集成测试.md#电影子系统工程初始化验收2026-09-07)。

## 供给与排期同步

实现说明见 [供给底座详细设计](../docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md)，证据和待确认项见 [审查记录](../docs/superpowers/specs/2026-09-17-movie-supply-review.md)，联调以 `docs/集成测试.md` 的「电影供给审查重构验收（2026-09-17）」为准。

1. 在独立电影 MySQL 库执行既有 Liquibase 六表迁移；MongoDB 必须是支持事务的副本集或分片集群，新环境依次执行 `sql/mysql/movie/mongodb/001-cinema-schedule-indexes.js` 和 `002-query-time-sale-policy.js`。前者预建集合，后者移除旧停售 TTL 并建立动态查询索引。应用只读检查前提，不执行 DDL，也不降级到非事务写入。
2. MySQL 显式使用 Hikari，默认池容量 12，可用 `MOVIE_MYSQL_POOL_SIZE` 调整，建议不少于 10。持锁连接结束时物理淘汰；连接获取 10 秒、锁语句 5 秒、驱动 socket 10 秒，禁用 autoReconnect。环境 JDBC URL 不得重新开启自动重连。
3. 设置 `MOVIE_JC_BASE_URL`、`MOVIE_JC_APP_KEY`、`MOVIE_JC_APP_SECRET` 与对应 `MOVIE_JY_*`；URL 包含完整 HTTP/HTTPS 协议。凭证不写入仓库。
4. 首次部署在 Nacos 完整账号列表中启用已配齐的 JC/JY 账号（也可保留模板占位符并设置 `MOVIE_JC_ENABLED=true`、`MOVIE_JY_ENABLED=true`），保持 XXL-JOB 调度中心的五类任务处于停止状态，重启后通过 IDE 调用 `MovieSupplySyncService.synchronizeMasterData()` 完成城市→待映→热映→影院初始化，再调用 `synchronizeSchedules()`。初始化失败或锁忙应先排障重跑，不能靠定时任务的时间间隔保证依赖已完成。
5. 配置 `MOVIE_XXL_JOB_ADMIN_ADDRESSES` 与 `MOVIE_XXL_JOB_ACCESS_TOKEN`（与调度中心一致），启用执行器 `xxl.job.enabled=true`（或保留模板占位符并设置 `MOVIE_XXL_JOB_ENABLED=true`），重启电影服务即注册全部五个 Handler。执行器 AppName 填 `movie-server`，与应用配置保持一致。注册不自动触发任务，初始化成功后再到调度中心启动周期任务。
6. 在 XXL-JOB 任务管理中创建以下五个 BEAN 任务，**执行时间由使用者自行配置**，代码不提供默认计划。执行参数留空。每个 Handler 只建立一个任务，建议路由 FIRST、阻塞策略“丢弃后续调度”、失败重试 0、超时 0、调度过期“不补偿”。调度中心 JVM 使用 `Asia/Shanghai` 时区；确认初始化完成后再启动任务。

| 任务类 | JobHandler |
| --- | --- |
| `MovieCitySyncJob` | `movieCitySyncJob` |
| `MovieCinemaSyncJob` | `movieCinemaSyncJob` |
| `MovieHotFilmSyncJob` | `movieHotFilmSyncJob` |
| `MovieSoonFilmSyncJob` | `movieSoonFilmSyncJob` |
| `MovieScheduleSyncJob` | `movieScheduleSyncJob` |

执行器 AppName 可通过 `MOVIE_XXL_JOB_APPNAME` 覆盖；监听端口默认 `19191`，可用 `MOVIE_XXL_JOB_PORT` 修改，同机多实例须各用独立端口。`MOVIE_XXL_JOB_IP` 用于指定调度中心可达的注册 IP，留空时自动识别。调度中心须能访问该执行器端口，电影实例须能访问调度中心地址；执行器不走电影 HTTP 安全链，部署时仅向调度中心开放并配置通信令牌。日志路径及保留天数分别通过 `MOVIE_XXL_JOB_LOG_PATH`、`MOVIE_XXL_JOB_LOG_RETENTION_DAYS` 配置，默认用户目录下 `logs/xxl-job/movie-server`、30 天。

五类 Job 和执行边界不设条件开关，XXL-JOB 执行器启动时统一注册全部 Handler。`movie.supply.sync` 及原 `MOVIE_*_SYNC_ENABLED` 已删除、不再生效。任务启停、Cron 和手工触发统一在调度中心操作，无需重启电影服务。原应用 cron/zone/delay/initial-delay 配置不再生效，排期不再由应用实现“完成后等待”的计划。升级时停止旧 Spring 调度实例，避免混跑。

五类任务由 XXL-JOB 分别执行，CITY/CINEMA/FILM/SCHEDULE 互斥边界不变，热映与待映共享 FILM 锁。锁忙或业务失败会回传调度失败，累计 `movie.supply.sync.task.failures`，日志仅输出安全因果链摘要；可在调度中心单独重跑失败任务。每轮内部账号和影院仍串行请求，任务触发间隔不代表全国排期刷新耗时。

主数据先同步 JC 城市；JY 同名唯一自动绑定仍是待业务确认的历史行为，不是行政实体相等的证明，详见审查记录。未映射记录在每轮汇总中显示。
JC API 明确 filmId 是专资编码；未取得 ID 的待映记录逐条报告缺失，其它有效记录可继续同步。JC 影院只接受 standardId 或已有明确 Mapping，删除 cinemaId 的八位数字回退。JY 的 film_id 与 film_code、cinema_id 与 cinema_code 独立保存。
重复同步按唯一键更新，不重复建档；供应商列表中暂时缺失的主数据不自动删除或禁用，以避免短暂缺项影响另一渠道。

同一供应商多个账号时，在 `movie.supply.accounts` 中增加独立 `account-ref`、`config-version`、应用凭证环境变量引用及 `binding-refs`。
MySQL 映射当前仍按渠道原始 ID 共享，其跨账号命名空间前提尚待确认，不宣称所有账号/环境均可安全共享。JY 文档明确快慢票账号及场次不通用，MongoDB 必须按账号隔离；查询要求显式账号及当前配置版本。账号引用一旦投入使用保持稳定；变更引用后的旧账号数据需另行安排清理，不再通过停售 TTL 回收。
更换凭证时同步更新 `config-version`，避免旧配置的成本被查询复用。

内部单类入口为 `synchronizeCities()`、`synchronizeCinemas()`、`synchronizeHotFilms()`、`synchronizeSoonFilms()`、`synchronizeSchedules()`；全量主数据入口 `synchronizeMasterData()` 仅供手工初始化，均受数据类别锁保护。未开放手工 HTTP 触发接口。
排期解析影片只读，缺少 Mapping 时可按已存在的专资编码解析，Mapping 由影片任务写入，避免并发重复插入。
标准影片当前的“热映优先不回退”仍待业务确认；本次没有把它包装成已验证的业务规则，也没有猜测另一种冲突处理策略。
失败按任务独立汇总并抛出，下次该任务计划重试；完整影院快照以刷新 revision 和 Mongo 事务发布，部分写入失败回滚，提交结果未知上抛后重新拉取。失锁、中断立即停止整轮，不能承诺撤销已经发出的数据库 I/O。
JC `disableFlag` 仅影响特惠基础成本，快票选项原样保留；本阶段数据不构成可直接下单的实时报价。

## 协议、容量及升级注意事项

- JC 影院支持文档的 `data.data[]` 和旧数组响应，每页固定 1000 条；JY 分区价按 `area:…,price:…,settle_price:…;…` 解析，不能当 JSON。JY 影厅读取 hallCode，相对海报按文档 CDN 补齐。
- JC 启用 gzip 并显式解压。账号级请求起始间隔默认 1100ms，由 `MOVIE_JC_MIN_REQUEST_INTERVAL`、`MOVIE_JY_MIN_REQUEST_INTERVAL` 配置；该默认覆盖 JC 最低 60rpm 等级。只有确认供应商配额后才调小；进程内间隔不等于集群全局配额。
- JY 建议主数据每日、排期每小时刷新，生产排期并发不超过 30、测试不超过 15；本实现同类请求串行，可按合同调整独立任务计划。JC 在最低配额下，一万影院单账号仅起始间隔下界约 183 分钟，不能保证全国十分钟新鲜度。
- v2 排期键包含渠道、账号、原始影院和 showId，查询排除旧格式；同范围旧键由对应影院成功事务清理。升级时停止全部旧调度实例、部署集合和索引、确认副本集 URI，再刷新 v2 数据；未完成刷新前可能查不到旧供给，不允许新旧版本混跑。
- JC 城市/影片/排期示例形态、空格签名仍有文档/旧 SDK 冲突：当前保留原数组/Java SDK 兼容路径。三渠道停售改为以下统一平台策略，不再依赖 JC stopPurchase 或 JY stop_sell_time。
- 本次只做静态验证，未运行构建、电影单测、数据库迁移或真实供应商调用。完整待执行故障注入清单见集成测试文档。

## LX 影乐星与动态停售配置

影乐星接入现有城市、热映、待映、影院和排期任务。设置 `MOVIE_LX_ENABLED=true`，在电影进程环境中设置
`MOVIE_LX_APP_ID`、`MOVIE_LX_APP_SECRET`；`MOVIE_LX_BASE_URL` 默认 `https://api.yinglexing.com`。
`MOVIE_LX_CONFIG_VERSION` 默认 `v1`，`MOVIE_LX_MIN_REQUEST_INTERVAL` 默认 `1100ms`，具体配额需联调确认。
LX 默认关闭，无凭证时不影响 JC/JY；Nacos 若覆盖了 `movie.supply.accounts` 列表，须在同一列表中显式追加
`script/deploy/movie/movie-server-{local,test}.yaml` 中的 `lx-default` 账号，Spring 配置列表不会自动合并。

城市同步先 JC、LX 后 JY；影院每账号每轮请求一次全国列表；排期请求使用影院专资编码。
LX 成本使用含税 `price`，`nax_price/nax_price_st` 仅是参考价；散场按开场加影片时长推算。
现有锁座、出票、订单查询、退款和回调尚未实现，本次接入范围为供给同步。

三渠道统一提前 45 分钟停售。在当前环境导入的 Nacos 数据标识 `movie-server-${spring.profiles.active}.yaml`
中发布以下配置（示例只列需合并的节点，不应替换整份配置）：

```yaml
movie:
  supply:
    stop-sell-before-start: 45m
```

运行环境必须启用 Nacos 配置及刷新。该值从 Spring Environment 每次在售查询读取一次，发布刷新后无需重启，
下一次查询立即生效；例如改为 `30m`，三渠道均按开场前 30 分钟过滤对外结果。
允许 `0m`，负值或非法时长使查询失败，不影响采集；debug 日志 `stopSellBeforeStart` 可核对查询策略。
模板直接在 Nacos 设置停售提前量初值 `45m`；修改对应属性即可动态生效。`MOVIE_STOP_SELL_BEFORE_START` 占位符已从主配置移除。

上游返回的有效排期全部入库，不因已停售而跳过；平台停售时间只在查询结果中动态计算。
禁用的城市、影院、映射或影片仍采集，通过 `supplyEnabled=false` 标记在查询时隐藏。
三类查询统一按 `showTime > 当前时间 + 提前量` 过滤，配置变化直接影响已存排期，无需重新同步。

升级前停止旧实例，在独立电影库执行 `sql/mysql/movie/mongodb/002-query-time-sale-policy.js`，
移除旧 stopSellTime TTL 并补充 showTime 查询索引；新环境依次执行 001、002，之后不要单独重跑 001 恢复旧 TTL。
脚本不删除排期；历史已被旧 TTL 删除的数据需首次重采恢复。新版本不按停售自动清理，
仍保留完整供应商快照撤场清理。详细步骤见 `docs/集成测试.md` 的「LX 影乐星与统一停售验收」。
