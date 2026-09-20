# 电影子系统数据库初始化

> **2026-09-17 订单设计调整**：电影订单改为电影库 `movie_order` 与中台库统一订单双端落库，详见 [独立订单结构设计](../../../docs/superpowers/specs/2026-09-17-movie-order-storage-design.md)。下述六表仍是供给底座，订单新表尚未加入 changelog；`mall/` 历史入口已废弃，不得按旧说明部署电影订单。

依据 [电影子系统与中台履约详细设计](../../../docs/电影子系统与中台履约详细设计.md)第 2.0～2.2、5.1 节。
所有 SQL 使用 UTF-8 无 BOM，逐表保留设计的字段、索引和注释。不包含清表、删表、初始业务数据或数据库账号。

## 目录与数据库归属

| 路径 | 对象 | 执行目标 |
| --- | --- | --- |
| `movie-db.changelog-master.yaml` | 电影 Liquibase 独立入口 | 独立电影 MySQL 库，默认 `aifuli_movie` |
| `releases/1.0.0/001-movie-city.sql` | `movie_city` | 电影库 |
| `releases/1.0.0/002-movie-city-mapping.sql` | `movie_city_mapping` | 电影库 |
| `releases/1.0.0/003-movie-film.sql` | `movie_film` | 电影库 |
| `releases/1.0.0/004-movie-film-mapping.sql` | `movie_film_mapping` | 电影库 |
| `releases/1.0.0/005-movie-cinema.sql` | `movie_cinema` | 电影库 |
| `releases/1.0.0/006-movie-cinema-mapping.sql` | `movie_cinema_mapping` | 电影库 |
| `mall/` | 已废弃的商城扩展历史脚本 | 不属于当前电影部署范围，不执行 |
| `mongodb/001-cinema-schedule-indexes.js` | `cinema_schedule` 的 6 个业务索引（含账号隔离） | 独立电影 MongoDB 库 |

电影主数据不增加商城 `tenant_id`、审计人或逻辑删除列，后续 DO 不应直接继承会隐含这些列的商城基类。
新电影业务订单显式校验采购商、项目及项目用户归属，不使用商城订单、租户拦截或逻辑删除规则替代交易隔离。

## MySQL 显式迁移

1. 由环境管理员准备电影专属 MySQL 库及迁移账号；应用运行账号与迁移账号分离。
2. 在仓库根目录操作，准备 Liquibase CLI 与 MySQL JDBC 驱动，通过安全环境注入
   `LIQUIBASE_COMMAND_URL`、`LIQUIBASE_COMMAND_USERNAME`、`LIQUIBASE_COMMAND_PASSWORD`，确保 URL 指向电影库。
3. 依次执行结构校验、生成待审 SQL、执行迁移：

```powershell
liquibase --search-path=sql/mysql/movie --changelog-file=movie-db.changelog-master.yaml validate
liquibase --search-path=sql/mysql/movie --changelog-file=movie-db.changelog-master.yaml update-sql
liquibase --search-path=sql/mysql/movie --changelog-file=movie-db.changelog-master.yaml update
```

再次执行 `update` 不应新增已执行变更。6 张表各自对应一个 changeset；MySQL DDL 不可依赖事务回滚，
故显式设置 `runInTransaction: false`。同名表已经存在但没有迁移记录时会报错停止，须核对真实结构并制定前向修复，
不得盲目删除表、标记已执行或使用 `CREATE TABLE IF NOT EXISTS` 掩盖结构差异。

历史商城扩展 SQL 不作为电影部署入口。文件保留供历史追溯，不修改已发布 changeset，也不自动删除可能存在的历史表。后续新增电影订单表与中台表分别走各自独立前向迁移。

电影 Server 的 Maven 资源规则仅把电影入口与 `releases/` 打包到 `db/movie/`；SQL 不在资源目录重复维护。
现行电影 changelog 指定固定 `logicalFilePath`，CLI 与 classpath 使用同一变更身份。应用配置中 `spring.liquibase.enabled=false`、
`spring.sql.init.mode=never`；正常应用启动不执行 DDL，已执行变更不得修改原文件，只能追加版本变更。

## MongoDB 索引

在仓库根目录使用 `mongosh`，连接 URI 必须选中独立电影库，例如 `aifuli_movie`；凭证通过环境变量注入：

```powershell
mongosh $env:MOVIE_MONGODB_URI --file sql/mysql/movie/mongodb/001-cinema-schedule-indexes.js
```

使用 `db.cinema_schedule.getIndexes()` 验证 6 个业务索引及默认 `_id` 索引，其中 `stopSellTime` 索引应为 `expireAfterSeconds: 0`。
同一索引脚本可以重复执行；脚本不写入排期样例。MongoDB 金额必须用 Decimal128，所有在售查询仍要过滤 `stopSellTime > now`。

## 本次范围与后续工作

当前有效电影迁移仅包含 6 张供给表。订单物理字段已在 [独立订单设计](../../../docs/superpowers/specs/2026-09-17-movie-order-storage-design.md) 补充；TODO（该设计第 1.1、8 节）：
用户支付、积分扣减和用户退款明确不在系统范围内；核对项目身份、采购账户指派、供应商账号绑定及历史配置可恢复性后，依据订单字段设计生成前向变更。
供应链中台的电影详情、通用尝试及路由改造必须在中台独立变更集中实施。

上述命令是后续人工部署说明，本次工程初始化没有实际执行数据库迁移或应用启动。

2026-09-16 已补齐六张电影表的持久化及供给同步实现，见 `docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md`。
既有 MySQL changeset 未修改；MongoDB 索引脚本增补账号刷新及在售查询索引，并在脚本结束校验 TTL。
如曾手工写入旧格式排期，先停止旧写入器；新查询要求 `connectorAccountRef/configVersion`，旧文档不会参与供给并由 TTL 回收。

## 2026-09-17 排期事务升级补充

本节优先于原单机 MongoDB/非事务刷新说明。升级前停止全部旧电影同步实例；MongoDB 必须使用支持事务的副本集或分片集群，在独立电影数据库执行 `mongodb/001-cinema-schedule-indexes.js`。脚本检查拓扑、预建 `cinema_schedule_refresh`，并补充账号级在售查询索引；不改变既有六个 MySQL changeset。

排期 v2 键增加供应商原始影院 ID，使用无歧义分量编码，查询排除旧 schemaVersion。下一次对应影院成功事务清除旧格式记录，失败保持旧记录但新格式查询不使用它们；不执行全库清空。TTL 仍只负责容量回收，停售判断仍是查询显式条件。

本次审查只修改脚本，**没有执行迁移**。真实副本集事务、断连回滚、提交未知及索引 explain 的验收清单见 `docs/集成测试.md`「电影供给审查重构验收（2026-09-17）」。
