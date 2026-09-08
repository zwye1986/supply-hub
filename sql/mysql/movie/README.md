# 电影子系统数据库初始化

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
| `mall/mall-db.changelog-master.yaml` | 商城电影扩展 Liquibase 独立入口 | 已有 `trade_order` 的商城库 |
| `mall/001-trade-order-movie.sql` | `trade_order_movie` | 商城库，包含租户边界 |
| `mongodb/001-cinema-schedule-indexes.js` | `cinema_schedule` 的 4 个业务索引 | 独立电影 MongoDB 库 |

电影主数据不增加商城 `tenant_id`、审计人或逻辑删除列，后续 DO 不应直接继承会隐含这些列的商城基类。
`trade_order_movie` 保留设计要求的租户、审计和逻辑删除字段；供应商及成本列是商城投影，不是履约权威事实。

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

商城表单独实施：将上述连接环境改为商城库，使用以下入口执行同样的 `validate`、`update-sql`、`update` 步骤：

```powershell
liquibase --search-path=sql/mysql/movie/mall --changelog-file=mall-db.changelog-master.yaml validate
liquibase --search-path=sql/mysql/movie/mall --changelog-file=mall-db.changelog-master.yaml update-sql
liquibase --search-path=sql/mysql/movie/mall --changelog-file=mall-db.changelog-master.yaml update
```

商城变更要求目标库已存在 `trade_order`，否则停止。不要把整个目录递归作为一个迁移入口执行。

电影 Server 的 Maven 资源规则仅把电影入口与 `releases/` 打包到 `db/movie/`；SQL 不在资源目录重复维护。
两个 changelog 均指定固定 `logicalFilePath`，CLI 与 classpath 使用同一变更身份。应用配置中 `spring.liquibase.enabled=false`、
`spring.sql.init.mode=never`；正常应用启动不执行 DDL，已执行变更不得修改原文件，只能追加版本变更。

## MongoDB 索引

在仓库根目录使用 `mongosh`，连接 URI 必须选中独立电影库，例如 `aifuli_movie`；凭证通过环境变量注入：

```powershell
mongosh $env:MOVIE_MONGODB_URI --file sql/mysql/movie/mongodb/001-cinema-schedule-indexes.js
```

使用 `db.cinema_schedule.getIndexes()` 验证 4 个业务索引及默认 `_id` 索引，其中 `stopSellTime` 索引应为 `expireAfterSeconds: 0`。
同一索引脚本可以重复执行；脚本不写入排期样例。MongoDB 金额必须用 Decimal128，所有在售查询仍要过滤 `stopSellTime > now`。

## 本次范围与后续工作

本次仅落地设计已经给出完整 DDL 的 7 张表。TODO（[详细设计](../../../docs/电影子系统与中台履约详细设计.md)第 2.3、3.2、3.4、4.4 节）：
补齐并确认供应商多账号、项目绑定、配置版本、报价批次、Inbox/操作幂等和恢复检查点的物理模型后，再追加变更。
供应链中台的电影详情、通用尝试及路由改造必须在中台独立变更集中实施。

上述命令是后续人工部署说明，本次工程初始化没有实际执行数据库迁移或应用启动。
