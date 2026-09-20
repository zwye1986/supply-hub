# 电影供给与排期底座详细设计

> **数据链路图解**：见 [全品类数据同步与采购商API拉取时序图](../../images/movie_data_sync_and_buyer_api_diagram.png)。

## 1. 范围与进程边界

本次补齐独立 `aifuli-module-movie-server` 的主数据持久化、JC/JY 只读供给接口、周期同步和 MongoDB 排期读写；不涉及锁座、出票、报价、商城接口或中台路由。继承 `docs/电影子系统与中台履约详细设计.md` 第 2、3.1、5.1 节。电影进程继续使用自己的 MySQL/MongoDB，不引入其它模块的表、Bean、多租户拦截或隐式 Feign 扫描。

六张平台表复用既有 `sql/mysql/movie/releases/1.0.0`，不修改已发布变更集。数据对象字段注释与原始 DDL 一致，主数据及 Mapping 在单个 MySQL 事务中幂等写入。禁用标准数据不会被同步自动启用，跨渠道更新不得覆盖已有映射关系。供应商缺项不构造虚假的标准编码。

## 2. 接入账号与协议

配置命名空间 `movie.supply`，账号按稳定 `accountRef` 标识，包含渠道枚举 JC/JY、配置版本、启停、URL、连接/读取超时以及环境变量形式的应用标识和密钥引用。账号可记录项目/采购商绑定引用；当前任务只采集供给，不执行账号路由。不同账号的排期独立保存；主数据 Mapping 使用供应商范围 ID，与既有六表契约保持一致。禁止复制参考仓库中的凭证或关闭 TLS 校验。

协议优先依据用户提供的佳意、今晨 API Markdown，旧 `qihaofu/qihaofuAdmin` SDK 用于补充线协议参考；冲突见第 7.5 节，未对生产供应商发起请求：

| 数据 | JC | JY |
| --- | --- | --- |
| 城市 | GET `/getAllCity?type=0`，`data[]` | POST `/api/city/list`，`data[]` |
| 热映/待映影片 | GET `/getMovies`、`/getFutureMovies`，`data[]` | POST `/api/film/hot/list`、`/api/film/soon/list`，`current` 分页，`data.records/total` |
| 城市影院 | GET `/getCinemas`，`cityId/page`，当前 `data.data[]`，兼容旧 `data[]`；固定每页1000 | POST `/api/cinema/batch/list`，`city_id`，`data[]` |
| 影院排期 | GET `/getShows`，`cinemaId/deliverMinutes`，影片→`dates[]`→`list[]` | POST `/api/show/batch/list`，`cinema_code`，`data[]` |

JC 签名：加入 partnerCode，参数键排序，值按参考实现 URL 编码（额外替换 `*`、`+`），拼接 `&key=密钥`，UTF-8 MD5 大写。JY：POST 表单，`Accept: application/json`、`Content-Type: application/x-www-form-urlencoded; charset=UTF-8`，签入 x-ca-key/nonce/timestamp，按参考 SDK 的规范串计算 HMAC-SHA256/Base64。签名头名称及 Content-Type 必须与参考源码复核一致。

HTTP 必须同时验证状态码、`success=true` 和数据结构；缺字段或失败不能解释为空列表。分页设置上限并检测重复页，失败中止当前账号的本阶段；仅成功完整响应允许撤销旧排期。请求日志只记录账号引用、渠道、接口、业务参数与耗时、错误码/数量摘要，不输出鉴权参数及完整报文，高频明细使用 debug，失败按每轮汇总。

## 3. 主数据映射

优先使用已建立的 Mapping；标准城市按行政区划码、影片和影院按专资编码建立唯一键。供应商原始 ID 与标准编码分别保存。编码变更与已有映射冲突必须报错，不能静默改绑。

以下规则按 API 文档及明确证据实现；历史假设必须单独标明：

- JC 城市以 `cityCode` 为渠道 ID、`adCode` 为行政区划码；先同步所有 JC 城市，再同步 JY。JY 使用 `JyCityRegionMapper.xml#updateinitData` 的同名匹配思路，但仅当城市名精确匹配唯一标准城市时建映射，不沿用 `MIN(city_code)` 任意选取。不做模糊匹配或删除“市”等后缀。
- JC API 明确热映/待映/排期的 `filmId` 是专资编码，旧 filmCode 已转用 filmId；同时保留原始 filmId。待映缺原始 ID 逐条报告未映射，不伪造 ID。JY 使用 `film_code`。不按影片名称合并。
- JC 影院标准编码只使用 `standardId`，缺失时只能复用明确 Mapping，不再按 cinemaId 位数猜码。请求 `/getShows` 仍使用渠道 cinemaId，与标准编码分离；JY 用 `cinema_code` 拉排期，保存原始 `cinema_id`。
- 无法唯一解析的记录跳过并汇总缺失类型和有限条原始 ID；可人工补齐既有 Mapping 后重跑。含未映射场次的影院快照不做删除，避免不完整转换被误判为供应商撤场。

## 4. 排期文档与一致性

集合保持 `cinema_schedule`；v2 主键为渠道、账号引用、原始影院与供应商 showId 的无歧义编码组合，保存 `connectorAccountRef/configVersion/supplierCinemaId/supplierFilmId/schemaVersion`。使用标准影院编码、影片编码、NFKC 后去标点空白的影厅名、保留小数的 Instant 计算 showGroupId 的 MD5，各分量独立编码。供应商时间按 Asia/Shanghai 解释后存 BSON Date，不依赖主机默认时区。

所有已知价格用 BigDecimal 验证非负并精确约束两位小数，明确写为 Decimal128；JY 成本取 settle_price，JC 基础成本取 minSettlementPrice，禁止用 minSalePrice 冒充采购成本。JC `disableFlag` 仅表示特惠购票不可用，原样保留标记；特惠不可用时基础成本置空，但保留快票选项与顶层快票价格，不据此误删整场供给。JY 分区价由适配器解析专有分隔格式；JC 按已知字段白名单转换，取消字段名包含 price/fee 等泛化推断。未知字符串/标识保留，不用于报价。原始引用不对 C 端暴露。

以账号+影院为完整快照刷新范围。外呼前取得刷新 revision，完整解析及映射后，将版本条件更新、批量 upsert、差集清理纳入单个 Mongo 事务。失败不会留下部分提交的场次；提交结果未知上抛后由新轮次重新拉取，不承诺 MySQL/MongoDB 分布式原子事务。真正成功的空响应会清除此账号该影院的排期。过期、标准数据禁用及影院 Mapping 禁用的场次不入下一轮快照。保留 createTime，刷新 updateTime；token 差集清理避免超大 `$nin` 参数。拓扑、失锁在途窗口及迁移要求见第 7.2 节。

TTL 索引仍为 `{stopSellTime:1}, expireAfterSeconds:0`，只做容量回收。所有在售读取显式加入 `stopSellTime > now`，且必须指定账号引用，避免混用不同账号价格。部署脚本增加账号+影院刷新索引；应用不自动创建索引或修改数据库结构。

## 5. 同步调度与恢复

### XXL-JOB 迁移（2026-09-20）

五类 Job 改为 XXL-JOB BEAN Handler，复用 `aifuli-spring-boot-starter-job`，Handler 名依次为 `movieCitySyncJob`、`movieCinemaSyncJob`、`movieHotFilmSyncJob`、`movieSoonFilmSyncJob`、`movieScheduleSyncJob`。移除电影专属 Spring 调度器和 `@Scheduled`；保留 `movie.supply.sync.enabled` 与各任务 enabled，开关控制 Handler Bean 注册，修改后重启生效。`xxl.job.enabled` 默认跟随同步总开关，执行器默认 AppName 为 `movie-server`，地址、令牌、端口、注册 IP 与日志路径通过环境变量配置。

调度计划统一由 XXL-JOB 管理；五类任务的执行时间均由使用者在任务管理中自行配置，代码不预设周期；调度中心 JVM 使用 Asia/Shanghai。排期不再提供应用内 fixedDelay 语义。删除应用中的 cron、zone、delay、initial-delay 配置，不再由应用启动自动触发。每个 Handler 只配置一个任务，路由选 FIRST，阻塞策略选丢弃后续调度，失败重试次数为 0、超时为 0、过期策略不补偿；单轮串行外呼及既有类别锁不变。

新增电影任务执行边界，记录 Handler 与调度编号；失败仍累计 `movie.supply.sync.task.failures`，沿因果链保留有界异常类型、SQL 错误码和栈位置，使用 `XxlJobHelper.handleFail` 显式回传失败，不把携带第三方报文的原始异常交给 XXL-JOB 日志。Service 手工调用仍抛出原始异常。移除 Spring 调度器后此边界承接原来的失败监控与脱敏职责，不改变服务和持久化逻辑。

仅影响独立电影进程的任务执行及执行器监听端口，不修改共享 starter、供应链启动/RPC/事务/安全配置，不触发供应链模块架构规则变更。上线前停止旧 Spring 调度实例、完成初始化并验证执行器连通，再在调度中心启用任务。下文原 Spring 调度计划与线程池说明为迁移前记录，以本节及 README 中的 XXL-JOB 配置为准。

调度入口统一放在 `com.aifuli.module.movie.job` 包，拆为五个 Job，分别调用 `service.sync.MovieSupplySyncService` 的单一数据类别入口。原 `MovieSupplySyncJob` 删除。任务仅负责触发，协议、持久化与业务编排留在 service 层。供应商旧代码只用于协议与行为核对，不沿用其任务组织和实现缺陷。

| 独立任务 | Service 入口 | 配置子节点 | 初始默认计划 |
| --- | --- | --- | --- |
| MovieCitySyncJob | synchronizeCities | city | 每日 02:00 |
| MovieCinemaSyncJob | synchronizeCinemas | cinema | 每日 02:40 |
| MovieHotFilmSyncJob | synchronizeHotFilms | hot-film | 每两小时整点 |
| MovieSoonFilmSyncJob | synchronizeSoonFilms | soon-film | 每日 02:20 |
| MovieScheduleSyncJob | synchronizeSchedules | schedule | 每轮完成后等待 10 分钟，启动等待 1 分钟 |

以上时间是新系统可调整的初始值，不是旧系统线上频率的还原。配置根为 `movie.supply.sync`；总开关默认 false，每个任务有独立 enabled，前四项使用 cron 和统一的 `zone=Asia/Shanghai`，排期使用 fixedDelay 与 initialDelay，支持独立频率和停用。任务失败由下一次自身计划或手工重跑恢复，不隐式重跑所有主数据。删除 master-delay/master-initial-delay；原 `MOVIE_SCHEDULE_SYNC_DELAY` 环境变量保留为新 schedule.delay 的默认来源，旧平铺的 schedule-delay/schedule-initial-delay 配置路径移除。变更需同时停止旧版本调度实例，不混跑不同锁协议。

日常调度不承担首次初始化：`synchronizeMasterData()` 保留为内部手工初始化入口，按城市→待映→热映→影院顺序调用独立入口，失败或锁竞争时中断，避免假成功；执行成功后可手工调用 synchronizeSchedules。该入口不注册定时任务，不绕过锁。城市初始化先 JC 后 JY；独立影院任务依赖已有城市映射，不顺带拉城市。首次部署必须先完成初始化，不把 cron 的先后时间当作依赖完成保障。

由电影专属 `ThreadPoolTaskScheduler` 提供五个线程，保证长时间主数据任务不占住唯一调度线程；每轮内部账号/影院请求仍为串行，暂不引入全国影院并发外呼。锁按电影库+数据类别区分 CITY/CINEMA/FILM/SCHEDULE，热映和待映共用 FILM 锁防止标准影片并发更新，其余类别可同时执行。抢锁使用 GET_LOCK 零等待，忙时抛出明确失败留待下次计划，不占用连接等待整个轮次。专属连接持锁至结束并显式释放，守卫在写入前检查所有权。电影 MySQL 连接池建议至少 10 个连接，为四个锁持有连接、业务事务及竞争请求留容量。

拆分后的并发边界：城市任务只写城市/映射，影院任务只写影院/映射，两个影片任务共锁且只写影片/映射；排期解析影片只读，有 Mapping 时校验 Mapping，无 Mapping 时允许按已有标准专资编码解析，不在读路径新建 Mapping。标准记录也不存在时本影院失败并保留旧排期。现有“热映优先且不回退”的状态投影没有足够业务依据，保留为待确认问题，不擅自改成另一套来源优先级，详见审查记录。

每轮按 city/cinema/hot-film/soon-film/schedule 分别汇总成功、失败、未映射数量和有界示例，失败向调度器抛出并保留原因。没有城市或影院映射时影院/排期入口显式报告前置数据缺失，不把空遍历当作初始化完成。不向 HTTP 开放无鉴权触发端点。本轮不增加订单补偿、余额任务，也不新增指定城市/影院同步入口。

## 6. 发布与验证

显式执行既有 Liquibase 迁移和 mongosh 索引脚本，配置独立库及环境密钥引用，再启动调度。当前仅修改电影进程，不触发供应链模块的依赖方向或数据所有权变化。按照用户要求不运行构建，按照仓库约定跳过电影模块单测；通过静态结构检查、差异审查和 `docs/集成测试.md` 的真实联调清单交付，真实数据库与供应商验收另行执行，不将静态检查标记为联调通过。

## 7. 2026-09-17 审查修订（优先于前文冲突描述）

### 7.1 证据等级与待确认语义

新电影中台是迭代优化。旧系统调用成功只证明某次输入可用，不能证明完整接口契约。完整问题清单、原代码位置、触发条件及影响见 [审查记录](2026-09-17-movie-supply-review.md)。

- 可保留的线协议兼容：JC SDK 排序及 MD5（空格特殊编码仍有冲突）；JY SDK 的表单、签名头及 HMAC 规范串；JY 用 cinema_code 查排期；JC 嵌套影片/日期/场次；disableFlag 仅适用于特惠。
- 历史假设而非协议事实：JY 同名城市自动绑定、渠道内多账号共用 Mapping、标准影片永久热映优先。用户提供文档后已按第 7.5 节解决 JC filmId 专资语义、移除 cinemaId 缺码回退、明确影院页容量；仍存在文档示例形态/签名/停售字段冲突，未用新猜测填补。
- 不改变五类任务、独立进程/库、安全链、手工初始化与周期任务的边界。MasterDataService 仍是六表事务边界，不为类大小增加接口。排期领域转换移到 service.sync 的 MovieScheduleConverter；CinemaScheduleDocument 只声明持久化集合与键，不再依赖供应商、配置和 MySQL 数据对象。

### 7.2 锁、快照与并发边界

MySQL GET_LOCK 是会话锁，连接池 close 只是归还代理。取得结果未知、守卫失效或释放失败必须淘汰 Hikari 物理连接，保留原异常和清理异常；锁 SQL 设置超时；中断或失锁立即停止整轮，不能降级为某影院失败继续外呼。每批 Mongo 写、删除及提交前检查守卫。守卫检查不是跨库 fencing：检查后仍存在正在执行的 I/O，不能声称失锁瞬间撤销在途操作。

为消除旧任务在 Mongo 多批写入/删除中交错破坏另一轮，新增 `cinema_schedule_refresh` 技术集合，以渠道/账号/供应商影院构成无歧义键。**在外呼之前**原子增加 revision，并冻结为本次刷新凭据；所有场次 upsert、差集删除和 revision 条件更新放入同一 Mongo 事务。新 revision 会使旧事务发生条件不匹配或写冲突，旧任务不得用旧响应重新申请 revision。事务失败保持原快照；提交超时视为结果未知，上抛后由新一轮重新拉取，不清空旧数据。成功空响应在事务内清空指定范围。

该改动要求 MongoDB 副本集或支持事务的分片集群，使用 primary、snapshot 读关注及 majority 写关注；不支持事务时明确失败，禁止退回非原子写入。排期轮次在任何供应商外呼前检查拓扑和两个集合是否已存在，避免全国逐院重复失败及应用隐式建集合；在售读取固定 primary/majority。事务内不外呼供应商、不等待限流。技术集合必须由部署脚本预建；应用不执行 DDL。单影院事务范围限制全国批量事务开销。当前 MySQL 主数据仍每条独立事务；不承诺 MySQL/Mongo 跨库原子提交。主数据仅更新采集字段，禁止把读出的映射标准 ID、标准编码、人工状态等整对象回写。

排期 ID 改为版本化、分量 Base64URL 编码的渠道+账号+原始影院+showId 组合，避免假设 showId 在账号所有影院全局唯一；配置版本仍是快照属性和查询约束。旧键排期只在新格式查询中被忽略，下一次成功影院事务清理同范围旧键；不做不可逆全库清空。showGroupId 保留总体设计的影厅归一算法，但改用无歧义分量并保留时间小数，不因拼接分隔符或截秒额外碰撞；名称归一仍只是候选聚合，不能代替锁座前物理核验。

### 7.3 协议转换、请求容量与诊断

- 金额转换取消字段子串及纯数字键推断。仅转换渠道各自白名单金额（JC settlementPrice_8/15/30，JY price/settle_price 等）；其它数字保留精确数值，字符串、标识原样保留，不把未知项用于报价。JY 特殊字符串先由适配器按协议解析；JC 嵌套 JSON 仅解包顶层约定结构，非空普通字符串不能当空供给。
- JC 快票 settlementQuickPrice 等已知顶层价格单独保留，disableFlag 不删除全场；禁用特惠的无效基础成本不阻断快票采集。标准成本不取 minSalePrice。已知金额严格非负、两位精度；未知 JSON 浮点数用 Decimal128 保存精度但不强行宣称是人民币。
- JY total 必须整轮一致、记录数恰好达到 total；变动、超总数、重复页、提前空页皆失败。JC 按文档定长页推导短页结束，重复页与页数上限失败不能解释为空结果；末页样本仍待验收。
- 账号间、五类任务间共享进程内账号请求间隔；间隔可配且可中断，不自动重试未知结果。不因串行影院循环就宣称存在全局限流，跨进程总配额需部署侧限制。轮次日志记录耗时和汇总；调度边界累计 Micrometer `movie.supply.sync.task.failures`，保留后续计划用于重跑，只输出安全异常摘要。服务方法仍向手工调用方抛出原始因果链；供应商异常原因不直接进入普通调度日志，Jackson 禁止附带原始响应片段。
- 全国耗时模型为 `账号数 × 影院数 × (平均响应耗时 + 请求间隔 + 单影院数据库耗时)`（更精确取间隔与响应的重叠），固定延迟另加在轮次之后。以一账号一万影院、平均每院 0.3 秒计，单渠道串行仅 HTTP 已需约 50 分钟；两账号约 100 分钟。30 秒超时连续故障可达每账号 83 小时，须监控及中断，不能凭十分钟 delay 宣称十分钟全国刷新。先测吞吐、QPS 和连接池，再另行设计分片并发。

### 7.4 迁移与验证约束

停掉所有旧调度实例后部署新代码/索引；确认 Mongo 事务拓扑后先单影院离线联调，再扩大范围。副本集条件、快照提交未知、失锁在途窗口、历史业务假设均是未验收风险。保持现有暂存区原样，重构只写工作区，不提交、不推送、不重新暂存；不运行 Maven、编译或构建，不连接生产数据库和供应商。电影模块按仓库规范跳过自动化单测，本次不修改供应链代码，因此无供应链测试变更范围；静态解析只能报告语法解析结果。

### 7.5 用户提供 API 文档后的契约校正

依据 `C:/Users/23088/Desktop/markdown/佳意电影API接口文档.md` 与 `C:/Users/23088/Desktop/markdown/今晨娱先森电影API接口文档.md`，本节取代前文仅依据旧实现的对应描述，先更新设计再调整代码：

1. JC §3.1 的 adCode 为必填标准城市码，缺失失败，不走同名补码；§4.1/4.2/4.7 明确 filmId 是专资编码，filmCode 已转用 filmId；因此影片与排期以 filmId 为标准码，不能反向优先旧 filmCode。待映可能尚无 filmId：记录进入未映射失败汇总，不能以片名或旧 filmCode 伪造供应商 ID，其他有效记录仍可同步。JC §4.4/4.5 区分 cinemaId 和 standardId，删除“八位 cinemaId 当国标”回退；缺 standardId 时只允许已有明确 Mapping。
2. JC §4.4 的当前影院列表是 `data.data[]`，兼容旧 SDK 的 `data[]`，不接受其它泛化包装；嵌套 success 若存在必须明确成功。每页固定 1000，基于该定长约定推导短页结束，满页继续请求，重复/超过固定页容量或上限失败。该结束推导需要联调样本最终验收。
3. JC §1.4 要求 `Accept-Encoding: gzip`，客户端显式解压；影院 GET 带表单 Content-Type。默认请求间隔改为 1100ms，为每分钟 60 次的最低等级留余量；适用于两个账号示例的保守初值，不宣称 JY 也规定该 RPM。文档允许的 JC 档位仅 8/15/30，配置验证只接受这些不重复值。
4. JY §2.6 的 area_price 不是 JSON，而是 `area:1,price:55.00,settle_price:55.00;...`。由 JY 适配器按分号拆分区、逗号拆字段、首个冒号拆键值，检查必填/重复分区与字段，保留分区原始 ID，price/settle_price 精确转换人民币元，错误使影院快照失败。影厅 ID 读取 hallCode。JY §1.4 明确相对图片需补 CDN 与尺寸，采用文档示例 `_80x80.jpg`，已经绝对的 HTTP(S) 地址保持原值。
5. JC §4.7 快票白名单应包含 settlementPrice_8/15/30，分区内相同字段同样转换；8/15/30 是猫淘直连/快捷/优选订单类型，不再当作统一出票分钟 SLA。旧设计 settle_8 等不是供应商字段，不再以示例冒充协议。基础成本仍来自 minSettlementPrice；disableFlag 仅特惠语义保持。
6. 当前成功 code 分别 JC=1、JY=200，除了 success=true 还核对业务码，避免互相矛盾的 envelope 被接受。JY 按文档 current/total 分页，current 若返回须匹配请求。

尚存冲突：JC 城市/影片/排期示例为单对象、旧 SDK 为数组；签名空格描述为 %20、旧 Java SDK 为 %2B；排期文档未列 stopPurchase。暂保持现有数组与 Java SDK 兼容规则，缺停售字段仍失败，禁止从“临场35分钟”自行推断全部购票方式的停售时间。城市同名绑定、账号间主数据命名空间、影片状态冲突/回退仍没有正式规则，已再次提出问题。JY 明确快慢票账号/场次不能混用，排期的账号隔离是必要约束，但不能据此证明三类主数据 ID 也必然共享或必然不同。
