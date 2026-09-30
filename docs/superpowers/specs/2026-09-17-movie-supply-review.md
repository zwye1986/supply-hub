# 电影供给底座代码审查与重构记录

审查日期：2026-09-17。基线是本次任务开始时的暂存内容，不是 HEAD。审查覆盖 movie-server 全部实现、电影总体设计、供给详细设计、六表 DDL、Mongo 索引和 qihaofu 的供给 SDK/DTO/服务/Mapper，并在用户提供 API 文档后重新校正协议结论；没有连接供应商或数据库。所有路径若未另注明，均位于 `aifuli-module-movie-server/src/main/java/com/aifuli/module/movie/`。

当前协议证据优先采用用户提供的 `C:/Users/23088/Desktop/markdown/佳意电影API接口文档.md` 与 `C:/Users/23088/Desktop/markdown/今晨娱先森电影API接口文档.md`；文档冲突处保留问题，不用旧项目下单成功作证明。

## 1. 有证据的问题与处理

| 编号/等级 | 代码位置（基线方法） | 触发条件和实际影响 | 修复与状态 |
| --- | --- | --- | --- |
| R01/P1 | `service/sync/MovieSyncLock.java#execute/HeldLock.close` | GET_LOCK 返回前断网，或 RELEASE_LOCK 失败；池代理 close 归还连接，不保证物理会话结束，可能长期占锁或同会话重入计数叠加 | 专用持锁连接结束时通过 Hikari evictConnection 淘汰；取得结果未知也走同一路径，锁语句限时，保留清理异常。已改，真实断连验收待执行 |
| R02/P1 | `MovieSyncLock#check`、`MovieSupplySyncService#SyncSummary.failure`、`dal/mongo/CinemaScheduleRepository#replace` | 守卫异常被当单影院失败；写入前唯一一次检查通过后断连，新实例开始同步，旧实例仍多批 upsert/delete，可覆盖或删除新数据 | 失锁专用异常立即中断整轮；Mongo 外呼前申请单影院 revision，事务条件更新 revision，批写/删除/提交前检查守卫。已改；检查后在途 MySQL I/O 窗口仍不宣称消除 |
| R03/P1 | `CinemaScheduleRepository#replace` | 第二批 bulk 失败/超时；第一批新数据已对读者可见，旧数据混杂，下一轮并不一定及时到来 | 单影院批写和清理加入同一 Mongo 事务，失败回滚，提交未知交下轮新拉取处理。已改；部署新增事务拓扑要求 |
| R04/P1 | `dal/dataobject/mongo/CinemaScheduleDocument#create` | 同账号两个影院返回同一 showId；旧键只含渠道/账号/showId，后一影院覆写前一影院 | v2 键加入原始影院，各分量 Base64URL 编码；读取过滤 schemaVersion=2。已改，不依赖供应商 showId 全局唯一假设 |
| R05/P1 | `CinemaScheduleDocument#nested` | `priceType="FAST"`、`priceId="001"`、数字键表示分区或档位；旧代码按 price/settle/fee 子串或纯数字键当金额，造成异常或类型/前导零丢失 | 转换迁至 `MovieScheduleConverter`；明确字段白名单，未知字符串/标识保持类型；仅顶层约定字段解包 JSON。已改，未知选项不可直接报价 |
| R06/P1 | `integration/supplier/jc/JcMovieSupplyClient#schedules` | disableFlag=1 且旧特惠 minSettlementPrice 无效；解析在丢弃成本之前失败；或有效快票只有 settlementQuickPrice 而无 priceOption 时信息丢失 | 无效特惠不解析成本；保留顶层四个快票价格字段，仍严格检查实际保留的金额，不以特惠标记删除全场。已改 |
| R07/P1 | `service/sync/MovieMasterDataService#syncCity/syncCinema/syncFilm` | 同步读出实体后管理员修改绑定/影院归属；updateById 整对象回写旧 standard*Id/code，可能覆盖明确绑定 | Mapping 仅 Lambda 更新供应商名称；标准记录用只含采集字段的新对象更新，不回写 code、status、standardCityId。已改；不重写缺少证据的影片状态规则 |
| R08/P2 | `integration/supplier/jy/JyMovieSupplyClient#films` | 翻页 total 变小或某页超总数；旧 `size >= total` 错把不完整/不一致响应认定成功 | 固定第一页 total；总数变动、超总数均失败，恰好等于 total 才成功。已改；同 total 下集合并发变动仍须供应商游标/快照能力才能完全解决 |
| R09/P1 | `JcMovieSupplyClient#films/cinemas` | 待映同时有旧 filmCode 和当前 filmId，基线优先旧编码；影院缺 standardId 时仅凭八位原始 ID 建标准档 | JC API §4.1/4.2 明确 filmId 为已转用的专资编码，以它为准；§4.4 区分影院两个 ID，移除 cinemaId 回退。缺待映 ID 逐条计未映射，不丢整批有效影片。已改 |
| R10/P1 | `MovieSupplierHttpClient#request`、`framework/supply/MovieSupplySchedulingConfiguration` | 五任务同时执行缺账号限流，或大量快速失败持续请求；JC 文档最低等级/测试限制 60rpm | 同账号共享可中断请求间隔，默认 1100ms 留余量；不添加自动供应商重试；轮次日志增加 elapsedMs。已改；进程间或同凭证多引用的总配额仍需部署限制 |
| R11/P2 | `SupplierJson#text`、`CinemaScheduleDocument#nested` | success 响应中的记录是标量/布尔，或 areas/priceOption 是非空普通字符串；旧宽松转换可把结构错误当有效快照 | 记录要求对象，字段只允许文本/数值；分区/快票要求对象/数组，失败不发布快照。已改 |
| R12/P2 | `MovieSupplySyncService#SyncSummary.finish`、默认调度错误处理 | Jackson/HTTP/SQL 原异常携带响应、请求 URL 或参数，调度器打印整个因果链泄露数据 | 关闭 Jackson source location；调度边界累计 Micrometer 失败计数、输出有界类型/SQL 错误码/栈位置，保留下次计划用于恢复；服务仍抛出原始因果链并保留失败计数、阶段和安全样例。已改；真实敏感样本日志验收待执行 |
| R13/P2 | `CinemaScheduleDocument#create` | 不同分量含 `_`，或两个开场时刻只在小数秒不同；旧拼接、格式化到秒导致额外聚合碰撞 | 无歧义编码分量，使用 Instant 文本保留小数；保留总体设计的 NFKC 去标点规则。已改；影厅名规范化自身歧义仍需物理验座 |
| R14/P2 | `sql/mysql/movie/mongodb/001-cinema-schedule-indexes.js`、`CinemaScheduleRepository#findOnSaleGroup/findOnSaleFilmCodes` | 全国规模多账号时，旧 group/city 索引缺账号和版本前缀，需要扫描别的账号记录再过滤 | 补充与实际查询条件对应的账号 group/city 索引，部署脚本先验拓扑并预建刷新集合。已改；explain 与容量测量待执行 |
| R15/P1 | `JyMovieSupplyClient#schedules`、旧 `CinemaScheduleDocument#nested` | JY API §2.6 area_price 是分号字符串，旧实现会把整段文本当价格或无结构原值；hallCode 被误读为 hall_id | 协议层专门解析并校验 area/price/settle_price，影厅改读 hallCode；相对海报按 §1.4 补齐 CDN。已改 |
| R16/P1 | `JcMovieSupplyClient#cinemas`、`MovieSupplierHttpClient#request` | JC 文档当前影院返回 data.data，基线仅认 data 数组；缺少文档要求的 gzip 和影院 GET Content-Type，拉取失败或带宽增大 | 精确兼容两种已知影院包装、每页1000按短页结束（协议推导待验收），显式 gzip 解压、表单 Content-Type；同时核对各渠道 success/code。已改 |

## 2. 可保留的兼容点及证据

以下路径相对 `D:/Jierongjingfu-project/qihaofu/qihaofuAdmin`，参考文件只读，未复制凭证：

- JC：`qhf-admin/src/main/java/com/shengapp/util/HttpUtil.java#send/signParamsMd5/encodeUrl` 明确 partnerCode、键排序、编码后 `*→%2A`、`+→%2B` 和 MD5 大写；新实现明确 UTF-8，不沿用旧 `getBytes()` 的主机默认字符集及关闭 TLS 校验。
- JC：`com/shengapp/api/Movie.java#getCinemas/getShows` 明确 cityId/page、cinemaId/deliverMinutes；分页改依 API §4.4 固定1000条作短页终止推导。档位 8/15/30 按当前文档表示订单类型，不能简单解释为分钟 SLA。
- JY：`qhf-common/src/main/java/demo/util/SignUtil.java`、`demo/constant/ContentType.java`、`qhf-admin/src/main/java/com/qhf/web/movie/utils/MovieUtils.java#postForm/getCinemaShowList` 支持现有签名头、表单 Content-Type、签名资源串和 cinema_code 请求参数。无需为重构拆出签名策略工厂。
- JC：`com/qhf/web/movie/domain/dto/JcCinemaScheduleDto.java#ScheduleItem` 明确 disableFlag 只针对特惠、快票查看 settlementQuickPrice；不能据此全场下架。`minSalePrice` 不是采购成本。
- 标准数据启停和编码来自总体设计与六表 DDL；主数据和 Mapping 每条同一 MySQL 事务、供应商列表缺项不自动删除、在售查询显式 `stopSellTime > now`、金额 Decimal128、独立进程/库继续保持。
- 五个 Job 的职责已经足够简单，只负责触发，不以类大小为由拆分 MovieSupplySyncService 或六表事务服务。实际反向依赖问题是原 CinemaScheduleDocument 同时依赖供应商/配置/MySQL DO 并承担领域策略，现已移出。

## 3. 尚需确认，未擅自改写的规则

| 假设 | 反证或证据不足 | 需回答的具体问题及确认后的处理 |
| --- | --- | --- |
| JY 唯一同名城市自动绑定 | 旧 `JyCityRegionMapper.xml#updateinitData` 用 MIN(city_code)，服务还注明需人工数据处理；唯一字符串匹配并非行政实体证明，数据库排序规则还可能忽略大小写/尾空格 | 是否允许同名自动建正式 Mapping？不允许则停止自动绑定，改成人工明确映射或权威区划资料 |
| 同渠道所有账号共享 Mapping | DDL 唯一键只有 channel+supplierId；旧项目有多账号配置，但没有证明原始城市/影院/影片 ID 在账号、环境、协议版本之间一致 | 原始 ID 是否供应商全局稳定？是否包含测试/生产命名空间？若不是，必须前向新增账号/命名空间列和唯一键，不能只隔离 Mongo 排期 |
| 热映优先且不可自动回退 | 旧实现给两个接口各自赋状态并删除本类别缺项；没有“曾经热映永远热映”的业务证据，新实现是自身引入的不可逆投影 | 多账号冲突、重映/撤档、从接口消失时如何决策？需定义来源状态/有效期/优先级，再决定是否增加按账号保存观察事实 |
| JC 城市/影片/排期包装及停售字段 | 文档示例为单对象，旧代码为数组；文档没有 stopPurchase，但旧 DTO 和当前实现依赖它 | 请确认完整列表的真实结构及停售时间来源；当前仍要求已知数组和明确 stopPurchase，不把单对象当完整列表、不用临场35分钟推算停售 |
| JC 空格签名 | 文档 Node/PHP 示例是 %20，旧 Java SDK 特殊处理得到 %2B | 请确认服务器以哪个规则验签；当前参数均为 ID/数字列表，仍保留 Java SDK 路径，不在无证据时切换签名 |
| JC 影院短页即末页 | 文档明确固定1000条但未逐一说明末页错误码、更新期间稳定性 | 当前依定长分页推导短页结束，满页继续；需用末页样本验收，不宣称已联调 |
| JC 快票 priceOption 实际容器 | 文档字段描述为 settlementPrice_8 等键，示例又是数值数组；旧 DTO 使用 JSON 字符串 | 支持已知键和数字价格容器、保留未知字段；需确认数组档位关联、空值/无效值含义，再进入报价模块 |

已依据用户补充的 API 文档解决编码、价格、分页包装等问题；文档仍无法覆盖的上述核心业务语义已再次询问，不把“最后返回的接口赢”“热映永久优先”或“按名字合并”等替代猜测写成新规则。遗留行为在代码注释及本节明确标记，不算已修复。两份 API 样例的影片码不都为12位，因此不新增十二位正则限制；既有 DDL/DO 的“12位”注释保持一致，后续应通过前向迁移修订此历史说明。

## 4. 运维、容量与未验证风险

- 默认五线程调度、四个数据类别锁（两个影片共用 FILM）。Hikari 默认池改为 12，锁 SQL 5 秒，连接获取 10 秒、连接验证 3 秒、MySQL socket 10 秒；这些只作用于独立电影进程。实际多实例和手工并发仍需测量。禁止 MySQL 驱动 autoReconnect，以免会话身份悄悄变化。
- 单账号全国影院逐院串行。无节流时假设一万影院、每院 HTTP 0.3 秒，仅网络约50分钟；当前默认1100ms请求间隔下起点下界约183分钟/账号，两个账号串行约366分钟，另计持久化、失败和 fixedDelay。JC 文档最低60rpm，JY建议排期每小时/并发生产30测试15；不能在未申请额度、未测量前宣称全国十分钟或每小时刷新。批量 API、增量游标、分片并发须另作容量方案。
- 未新增电影 HTTP/RPC 暴露、安全放行、跨模块表访问、供应链 Bean 依赖。已阅读 `SupplyChainModuleArchitectureTest`；本次变更不改变其扫描模块或约束对象，不需要运行无关供应链测试。
- Mongo 事务必须在副本集或支持事务的分片集群验证；旧 local/dev 私有连接配置未改，若仍是 standalone 必须先由维护者准备测试拓扑。旧版本不支持新事务协议，禁止混跑。刷新版本集合无 TTL，规模约账号×影院，只保留版本不累积历史。
- revision 防止旧响应覆盖新轮次；锁守卫不能撤销已发出的 I/O，MySQL 主数据没有数据库 fencing 表，极端失锁后的在途单条事务仍可能提交采集属性。禁止宣称完全解决跨库失锁写入。
- 在售查询固定 primary/majority，但不是覆盖全国影院的同一时点快照；不同影院可处于不同刷新时间。管理员禁用主数据到下一次成功排期刷新之间仍有投影延迟，当前底座不能代替将来报价/锁座入口的实时资格和价格核验。
- 暂存区保持原始内容；工作区新增文件需与修改文件一起审阅。未构建、未运行电影单测、未执行迁移、未发起供应商外呼；静态语法结果不是编译、签名联调或事务正确性证明。

技术依据：[MySQL 会话锁语义](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)、[Hikari 连接淘汰实现](https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/HikariDataSource.java)、[Mongo 原子性与条件更新](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/)、[Java 驱动事务](https://www.mongodb.com/docs/drivers/java/sync/current/crud/transactions/)。引用只支持数据库/驱动技术事实，不支持 JC/JY 业务假设。

## 5. 静态验证及暂存区保护

- 独立临时目录安装语法解析器，禁用安装脚本，没有运行 Maven、javac、构建或项目单测。
- Java 语法解析覆盖50个文件；应用 YAML、电影 changelog YAML、POM XML 解析；57个 DO 字段注释与 DDL 对照；Mongo 脚本 `node --check`。这些检查不解析 Java 依赖/泛型/注解处理，不代表编译成功。
- 最终按仓库现有换行设置运行 `git diff --check`，没有差异格式错误；源码、应用 YAML、索引脚本为有效 UTF-8 无 BOM。未用全局换行转换覆盖用户文件。
- 电影源码未发现其它模块表引用、跨模块实现依赖或新增注解 SQL。未修改供应链实现或其构建配置。
- 本次任务前后 `git diff --cached --binary` 的 SHA-256 一致：`0BFDA715C89005B6899C7A8624665F7034B9689302C129C402A2E27A32F5CDFA`。没有提交、推送或重新暂存；原有各模块 `application-local.yaml` 工作区改动未编辑。
- 真实 MySQL/Mongo 故障注入、事务回滚/提交未知、签名、分页末页、gzip、节流及全国耗时均未执行，验收步骤位于 `docs/集成测试.md` 的2026-09-17章节。
