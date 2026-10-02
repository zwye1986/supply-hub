# 标准三接口HTTP接入与闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development，Task4核心/前端分别两级审查通过后执行；每任务完成规格再质量，有问题原实施者修复复评。实际部署四项仍暂缓。

**目标：** 生产下单/查单/资产HTTP、严格原始签名正文契约、Gateway不消费正文、全链敏感日志/no-store，真实应用数据库与供应商模拟自动化闭环。

**设计：** `docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md`全文，上位标准HTTP及Task2—4细化。精确动作已确认，不另开报价HTTP。

## Task 5：生产Controller与传输/日志保护

### 文件职责

Server `aifuli-module-supply-chain/...-server/src/main/java/com/aifuli/module/supplychain/`：

- `interfaceapi/open/project/ProjectStandardOrderController`三注册精确POST、application/json、对应可信ctx与Facade委派；无DAL或supplier。
- 同路径/应用解析包专用封闭requestDTO与`ProjectStandardHttpRequestParser`：原字节一个JSON对象、重复键/尾随拒绝、严格整数/字符串/闭包、safe参数异常，局部reader不改全局JSON/XSS。
- `infrastructure/auth/ProjectAppAuthFilter`标准根/子入口正文读取前no-store，认证边界只依API注册，不引入border/tradecore；保留旧认证。
- server application.yaml仅新增3精确permit-all，不放orders/**。
- SC测试真实parser/映射/HTTP Contract与production StandardProjectHttpClosureIntegrationTest，使用真实Facade/Mapper/事务/HMAC/内部报价/履约和本地供应商HTTP模拟，不testController或Mock核心。

Gateway：

- application.yaml一个唯一标准route id，3精确Paths AND POST，URI沿AIFULI_GATEWAY_SUPPLY_CHAIN_URI，原path不Rewrite，不顺手改既有重复admin id。
- filter/logging/AccessLogFilter标准分支在任何codec/bodyToMono/cache/decorator/buffer前直接透传chain原exchange，只安全metadata；日志对象无body/query/任意头原值，safeURL标记，debug/采样失败。
- AccessLogSanitizeUtils标准段前缀/固定类别，既有mall/普通路径规则保留。
- Gateway handler/GlobalExceptionHandler标准安全cause/suppressed/代码位置、稳定msg和no-store，不URI或reason泄漏，不为工具引入Servlet Web starter。
- `script/test/`可提交隔离生产Gateway行为/整链probe，编译真实Gateway并从其独立classpath/进程运行，不用手写proxy冒充。

共享Web `aifuli-framework/aifuli-spring-boot-starter-web/src/main/java/com/aifuli/framework/`：

- apilog/core/util/ApiAccessLogPathUtils明确standard根及子路径、context剥离，安全异常类别=原frequency或standard；相似路径不误分，旧路径继续。
- ApiAccessLogFilter在读query/body前省略、无标准responseEnable旁路、safeURL/resultmsg；日志API故障采样/安全cause不覆盖业务。
- ApiAccessLogInterceptor整体省略；web/core/handler/GlobalExceptionHandler标准所有参数/绑定/service/sql/decrypt/audit错误无敏感值及原message，平台errorDTO保护类别URL/requestParams={}、cause/suppressedsafe栈、防风暴。频率原安全路径及普通路径行为保持。
- 生产共享Web/Gateway改动用SC Contract或隔离实际类probe验证，不因为仅跳过SC外单测就省掉必要边界验证。不将Gateway依赖注入SC生产容器/造成Servlet-reactive装配冲突。

### TDD与完整验证

- [ ] RED严格数量（"1"、1.0、缺失等）、unknown/重复字段/尾随/target非字符串、原globalMapper宽松问题；真实访问日志/Gateway生产filter泄漏或重编码/响应buffer问题，用可编译实际类行为，不grep/编译错误当RED。
- [ ] Controller原bodybyte严格parser＋局部reader、可信操作ctx、最小DTO与映射，CommonResult stable/no echo。共享core不做别的业务选择。
- [ ] server精确permit/no-store及Gateway route/rawchain；原始UTF8多字节/空白/分块signature一致，响应chunk在全部完成前转发，不buffer资产；普通route行为不被新分支改变。
- [ ] SharedWeb和Gateway安全日志/异常/平台DTO/no-store含鉴权前错误，连续日志存储故障计数/采样，业务资产审计仍严格failclosed。
- [ ] 所有实际production入口完整HTTP：内部真实冻结报价→卡券下单/新nonce幂等→查单/等待assets[]→本地supplier真实Handler产生encryptedassets→资产交付audit→成功summary；MOBILE READY新下单/队列/终态→assetAvailablefalse/unsupported提示。未知READY不绕过。
- [ ] 同buyer双project同号各完整下单/查询/资产、crossproject不存在；异参业务冲突/CAS并发/拒绝普通重放/List变更accepted重放；requestId真实receiptNo/orderNo稳定，无第二资金/quote/队列/Outbox副作用。
- [ ] 项目ACTIVE/SUSPENDED/CLOSED/ARCHIVED、cap开关、credentials吊销/轮换/nonce/篡改body，普通身份头无效；每种JSON/quantity/CAS/media/query拒绝无受理副作用。
- [ ] 资产所有wait/manual/未受理/失败取消/非card/缺资产/decrypt/parse/auditfault实际响应no-store，无partialplaintext、无supplier/id/target/备注；独立audit真实存储且rollback不丢。
- [ ] 捕获真实Logger及platform访问/errorDTO，成功/非法JSON/越权/解密/SQL/审计/未知suffix/query/日志API连续故障不含phone/cardsecret/凭据/body，且安全cause/suppressed与位置仍可查。普通路径/INSTORE/限频回归。

API Contract按实际新增类独立-Dtest；server纯Test/HTTP Contract/真实Integration选actual类。完整productionApp需随机32B测试身份key仅Maven进程注入，不持久化key/摘要。真实MySQL/Liquibase profile supply-chain-integration，数据库目标仅本地自动化，不操作用户测试环境。

- [ ] Gateway用真实生产类隔离probe和实际配置本地Gateway进程→SC真实Controller验证，Nacos测试开关关闭/本地URI注入无跨服务合并；精确POST/unknown/wrongmethod路由、rawbytes/流式asset/安全异常。独立编译可`mvn -pl aifuli-gateway -am package`选择供应链之外单测范围按用户规范，不-DskipTests，不将这个构建称为SC全量门禁。
- [ ] 完整SC普通Test、API所有Contract及server profile所有Integration/Contract/27架构串行全绿，最终行为源树保存hash，新鲜XML逐类去重计数，不早期结果冒充最终HEAD，不重复跑没有实质风险的额外测试。
- [ ] 证据`/tmp/standard-project-http-closure`：RED/GREENcommands/log/XML/hash、源commit、Gateway完整probe报告/类源hash、独立process日志与敏感参数省略核验。只记录真实成功范围，代码闭环不等于真实vendor/环境联调。
- [ ] 中文注释/designlink/异常因果/日志采样/DTO注释逐项自审，owned中文commit，不stage rootdocs，不push/deploy。
- [ ] 独立规格→质量review修复闭环；root记录集成/计划，高优先级待办完成；最后独立全范围review（后端Task1起＋前端说明），不将四项低优先级人工任务勾选。

## 完成后交付

列后端/前端本地分支及实现提交、三接口字段/订单标识和关键业务结果、自动化范围与真实限制。四项实际测试环境迁移/菜单部署/页面/供应商联调继续未执行待办；内部调用方协调与历史多报价冲突处理仍需实际发布时核实。保留隔离worktree与用户现有变更，不擅自push/merge/删除分支。
