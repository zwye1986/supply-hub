# BPM、CRM、IM、公众号与报表模块移除设计

## 1. 背景与目标

仓库不再交付 BPM、CRM、IM、微信公众号运营和独立报表能力。本次变更完整移除以下顶层 Maven 模块及其运行时入口：

- `aifuli-module-bpm`
- `aifuli-module-crm`
- `aifuli-module-im`
- `aifuli-module-mp`
- `aifuli-module-report`

目标是让 Maven Reactor、运行配置、网关配置和部署示例均不再声明上述服务，避免残留无效依赖、路由与启动提示。

## 2. 移除范围

### 2.1 源码与构建

- 删除五个顶层模块目录，包括各自的 `api`、`server`、测试和资源文件。
- 从根 `pom.xml` 的 `<modules>` 中移除五个模块。
- 从 `aifuli-server/pom.xml` 删除五个模块的注释依赖块。
- 从 `aifuli-dependencies/pom.xml` 删除仅供 BPM 使用的 Flowable 版本与依赖管理，以及仅供独立报表模块使用的 JimuReport/JimuBI 版本与依赖管理。

微信公众号 SDK 的版本管理不整体删除：`aifuli-module-system` 仍使用 `wx-java-mp-spring-boot-starter` 提供微信登录能力，该共享依赖不属于本次裁剪范围。

### 2.2 运行与部署配置

- 删除网关中 BPM、CRM、IM、公众号和报表服务的业务路由及 OpenAPI 聚合项。
- 删除 `aifuli-server` 中 Flowable 配置、相关 Mapper 日志级别和已移除模块的 404 占位处理。
- 删除公众号开放回调的匿名访问白名单和 CRM 专用角色枚举，避免公共模块残留已下线能力的专用配置。
- 删除 Docker Compose 示例中的对应服务定义。
- 删除专用于 BPM/Flowable 的达梦数据库补丁源码目录 `sql/dm/flowable-patch`。

### 2.3 公共框架与说明

- 删除公共启动 Banner 和缺表异常处理中针对已移除模块的专用分支。
- 更新当前项目 README 和服务合并说明，不再把五个模块列为当前能力或候选服务。
- 历史全量建库 SQL 作为数据库迁移历史保留，不在本次结构裁剪中重写；后续数据库初始化方案应通过新的增量基线单独治理。

## 3. 不变量与兼容边界

- `product`、`promotion`、`trade` 仍由 `MallServerApplication` 聚合运行，本次不得改变商城聚合边界。
- `system`、`infra`、`member`、`supply-chain` 和电影模块的 Maven 依赖、网关路由与部署配置保持不变。
- `system` 模块使用的微信公众号登录 SDK 与 `wx.*` 登录配置保持不变。
- 不修改用户已有的 `docs/集成测试.md` 工作区变更。

## 4. 验证门禁

1. 五个顶层模块目录均不存在。
2. Maven 有效模型中不再包含五个模块或它们的 API/Server 构件。
3. 网关 YAML 和 Docker Compose YAML 可被解析，且不再声明五个服务。
4. 仓库生产代码、POM 和运行配置中不存在五个模块的包名、构件名或服务名残留。
5. 执行根项目 Maven 校验；若全仓库存在与本次无关的既有构建问题，需记录准确失败点。
