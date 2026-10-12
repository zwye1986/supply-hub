# 项目标准商品接入能力管理

## 1. 已确认范围

用户确认独立授予方案 A，复用项目查询/编辑权限，不新增菜单。项目详情的“应用凭证”改为“应用接入”，同页分开显示凭证和业务接入能力。开通应用只发 AppId/AppKey，不隐式授权；项目类型（企业福利等）不是能力。

本期只提供 STANDARD_PRODUCT 开启/关闭。O2O 与 INSTORE 只读展示现状，不提供写入入口，不改变其现有鉴权。启用要求项目 ACTIVE；关闭不限项目状态。能力开启不校验应用、商品、价格、路由是否齐备，这些仍由凭证/订单准入负责。提示运营：开启能力不等于商品可售。

关闭后允许已开通过能力的项目查订单摘要，禁止下单和卡密读取。从未开通（没有能力行）的项目三个接口都拒绝。停用记录不得删除或为从未开通项目补造 DISABLED 行；否则会意外授予查询权。项目暂停仍只可查单，归档/凭证吊销仍全部拒绝；在途履约、资金、幂等、项目归属隔离不改变。标准商品三个操作仍同属 STANDARD_PRODUCT，只有服务端登记的 STANDARD_ORDER_QUERY 可接受能力 status=20。

## 2. 持久化与并发

复用 infrastructure.auth 的 ProjectCapabilityDO/Mapper（管理应用层沿现有发码服务的模式依赖它，认证基础设施不得反向依赖 tradecore）。扩充现有 DO 的 version/createdAt/updatedAt，不改能力表既有 DDL。能力编码封闭为 STANDARD_PRODUCT/O2O/INSTORE；管理服务只写标准品常量，不接受任意能力路径或 JSON。

新增 Liquibase 063-project-capability-audit，sc_project_capability_audit 追加记录 projectId、capabilityCode、beforeStatus（首次为空）、afterStatus、capabilityVersion、operatorId、reason（关闭必填1..256字）、occurredAt（Asia/Shanghai）。与能力变更同一 supplyChainTransactionManager 事务提交，只有 insert，不提供更改/删除入口。唯一(projectId,capabilityCode,capabilityVersion)；操作人从登录态取，不从请求取，无跨模块表查询。

写入按项目行锁→能力当前读，首次并发也通过同一项目行锁串行化。请求 expectedVersion=null 表示尚无能力行，已有行必须携带实际非负版本；创建 version=0，后续更新 version+1 且 WHERE version=expectedVersion CAS。旧页面版本不符拒绝并提示刷新；同当前版本重复开启/关闭无变化、不重复审计。关闭必须 confirmed=true 且有原因，包括无变化请求。不存在的能力关闭保持未开通，不插入。首次开启、停用、再启用始终保留同一行和创建时间。

审计是权威记录。业务成功日志在事务提交确认后记录，包含项目、目标状态、操作者和版本，不记录原因正文或密钥。未更改只 DEBUG，无吞异常、无重复堆栈。

## 3. HTTP 合同

管理路径（由框架添加 /admin-api）：

- GET /supply-chain/projects/{projectCode}/capabilities：PROJECT_QUERY_EXPRESSION，返回三项有序列表，每项 {capabilityCode, status, version, lastOperatorId, lastChangedAt}。
- POST /supply-chain/projects/{projectCode}/capabilities/standard-product/enable：PROJECT_UPDATE_EXPRESSION，正文 {expectedVersion: Long|null}。
- POST /supply-chain/projects/{projectCode}/capabilities/standard-product/disable：同编辑权限，正文 {expectedVersion: Long|null, confirmed: boolean, reason: string}。

status 封闭为 NOT_ENABLED（无行）、ENABLED（10）、DISABLED（20）；version 无行为空，否则为当前值。lastOperatorId/lastChangedAt 读取最新审计，旧能力无审计时为空，不编造开通人和时间。写操作返回 {changed: boolean}；成功后前端重新 GET。expectedVersion 小于0、未确认关闭/空原因/超256字拒绝。版本冲突使用新稳定业务错误，项目不存在/状态沿用现有错误。

lastChangedAt 的 JSON 合同为 `yyyy-MM-dd HH:mm:ss` 字符串（Asia/Shanghai），无审计时为 null。仅该投影使用 JsonFormat，不更改共享时间戳序列化规则，避免前端直接显示 epoch 毫秒值。

鉴权：签名成功后读能力当前行，不缓存。STANDARD_ORDER_QUERY 接受存在且10/20的标准商品行；其它操作仍只接受10。未知状态不放行。查询仍校验应用/密钥/项目状态/nonce和订单归属，不因“历史能力”放宽这些条件。本轮不提供在途请求全局撤销屏障：关闭提交后开始授权判断的请求按新状态拒绝，已通过授权的请求按既有事务继续。

## 4. 前端与安全交互

前端仓库 /Users/mac/codespace/supplychainhubweb。保持现有风格，在 ProjectApplicationCredentials.vue 同页新增“接入能力”区，展示名称、状态、最近操作人ID/时间和动作。标准商品按钮用项目编辑权限；只读能力无按钮。未启用项目禁用开启按钮，已启用能力的关闭仍可用。项目查询权限加载列表，不扩大菜单。

已关闭状态在列表旁持续说明“禁止新下单和卡密读取，仅保留订单摘要查询；不取消在途订单”，不只在关闭确认弹框中提示。

关闭使用显式确认弹框并填写原因，说明“停止新下单和卡密读取，保留已开通项目的订单摘要查询；不取消在途订单”。提交期间防连点；项目切换/卸载后旧响应和待确认弹框不得操作新项目。读失败不展示可写的伪默认状态；写失败不乐观切换。并发冲突重新加载当前状态后让用户重新确认，不自动重发；网络失败结果未确认，查询状态，不重新提交。保留一次性密钥展示和清理逻辑，不写浏览器存储。

## 5. 验证与交付

先红后绿：签名 HTTP 证明停用后仅摘要查单允许、从未开通三项拒绝；管理真实 MySQL/Liquibase/事务证明首次开启、关闭、再启用、版本冲突、无变化、状态限制、审计回滚和并发不覆盖。权限通过真实 Spring Security 方法代理证明查询权限不能写，未登录不能写。运行供应链 auth 受影响单测、Contract/Architecture 门禁以及既有标准订单 HTTP 集成回归。

前端使用现有 SFC harness 验证按钮/确认/版本提交/晚响应；格式/lint/类型检查及运行预览，查看未开通、已开启、已关闭、暂停项目和关闭弹框实际截图。预览挡板仅用于UI验证，不冒充真实前后端联调。记录 docs/集成测试.md 并更新 AMP_PROGRESS.md。

本轮实施仅本地修改与验证；未获发布、推送或测试环境业务授权写入许可，pro143 的能力行不在本轮自动授予。完整订单主链仍需后续可售配置和首笔真实交易验收。
