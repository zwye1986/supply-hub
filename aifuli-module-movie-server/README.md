# 电影专业子系统

独立启动类：`com.aifuli.module.movie.MovieServerApplication`。默认服务名 `movie-server`，默认端口 `58191`。
模块只扫描 `com.aifuli.module.movie`，不加入商城聚合应用；平台主数据没有商城 `tenant_id`。

当前完成工程初始化，业务 RPC、同步任务、供应商协议和出票流程尚未实现。安全配置仅开放 `/actuator/health`，其余请求拒绝访问。

## 本地配置

默认启用 `local`，关闭 Nacos 配置与注册。先准备独立 MySQL 库 `aifuli_movie`、MongoDB 库 `aifuli_movie` 和 Redis，再配置以下环境变量：

| 环境变量 | 用途与默认值 |
| --- | --- |
| `MOVIE_SERVER_PORT` | HTTP 端口，默认 `58191` |
| `MOVIE_MYSQL_URL` | JDBC 地址，默认本机 MySQL 的 `aifuli_movie` |
| `MOVIE_MYSQL_USERNAME` / `MOVIE_MYSQL_PASSWORD` | 电影数据库账号；默认用户名 `movie`，密码为空占位 |
| `MOVIE_MONGODB_URI` | 完整连接 URI，默认 `mongodb://127.0.0.1:27017/aifuli_movie`；必须指定电影库 |
| `MOVIE_REDIS_HOST` / `MOVIE_REDIS_PORT` | Redis 地址，默认 `127.0.0.1:6379` |
| `MOVIE_REDIS_DATABASE` / `MOVIE_REDIS_PASSWORD` | Redis 逻辑库与密码，默认 `0` 与空占位 |

使用 IDE 将 `src/main/resources` 和 `../sql/mysql/movie` 的已声明 Maven 资源导入运行配置，执行独立启动类。
Liquibase 默认关闭，先按 [数据库初始化说明](../sql/mysql/movie/README.md)执行显式迁移。
应用健康检查会连接 MySQL、MongoDB 和 Redis；缺失基础设施时不能将健康状态视为验收通过。

## 服务注册

设置 `SPRING_PROFILES_ACTIVE=dev` 后启用 Nacos，并通过 `MOVIE_NACOS_SERVER_ADDR`、`MOVIE_NACOS_NAMESPACE`、
`MOVIE_NACOS_GROUP`、`MOVIE_NACOS_USERNAME`、`MOVIE_NACOS_PASSWORD` 指定配置中心与注册中心。
数据标识为 `movie-server-dev.yaml`；缺少专属配置时使用基础配置中的 `MOVIE_*` 环境变量。
此骨架尚未提供 test/prod 配置，部署前按环境补齐独立连接与发布配置。

## 数据与后续实现

SQL 唯一源目录为 `sql/mysql/movie/`，构建资源规则仅收录电影变更入口与 `releases/`，不收录商城表。
应用不开启自动建表或 MongoDB 自动建索引；正式迁移由独立发布流程执行。
基础 MyBatis Plus 用于后续 Mapper 开发，当前尚未创建数据对象、Mapper 或分页插件配置。

TODO（[详细设计](../docs/电影子系统与中台履约详细设计.md)第 2.3、3.2、3.4、4.2、4.4 节）：先补齐尚未定义的供应商账号、报价、操作记录 DDL，
再实现主数据及排期同步、实时验座、报价提交、幂等履约与系统间鉴权。外部 API 在实际消费时显式注册，不能扫描全部 Feign 接口。

人工验收与排障步骤见 [集成测试记录](../docs/集成测试.md#电影子系统工程初始化验收2026-09-07)。
