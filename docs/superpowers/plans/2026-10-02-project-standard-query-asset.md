# 项目查单与资产查询核心实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用subagent-driven-development逐任务实施，规格通过后进行质量审查。本文是认证基础之后的Task2，不开放测试环境接口。

**目标：** 实现可信项目下的订单一致查询、卡券授权交付、独立审计和数据库增量；保留后续HTTP接入边界。

**设计：** `docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md`全文，及上位标准HTTP设计§5。分层和详细语义以前者为准。

## Task 2：查询、资产与独立审计

### 文件责任

API目录`aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/`：

- 新增封闭`api/fulfillment/dto/ProjectStandardOrderQueryRespDTO`、`ProjectStandardOrderAssetsRespDTO`及资产条目DTO。摘要字段遵循主设计§5.1，资产使用§5.2字段，不暴露内部id/供应商/target/用户。仅复用无敏感字段的既有状态枚举；资产safe-toString不含资产字段值。
- `error/SupplyChainErrorCodeConstants.java`新增未受理、资产类型不支持、终态不可查询等稳定名称/编号与重试映射，复用已有订单不存在；内部一致性与密码/审计故障返回系统异常，不伪装业务错误。先核对已占用编号，禁止散落魔法值。
- 对应新`*ContractTest`验证响应精确字段、null与时间、无明文toString/内部字段。

Server目录`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`：

- `application/facade/ProjectStandardOrderQueryFacade.java`可信快照+业务号校验委派，无DAL。
- `border/fulfillment/application/ProjectStandardOrderQueryService.java`独立代理RR只读快照，查回执/总订单/请求及资产元数据、归属/状态与响应转换。
- 同应用包独立资产编排/审计服务，Spring代理REQUIRES_NEW写审计；可拆为`ProjectStandardAssetQueryService`、`ProjectStandardAssetAuditService`及安全事件快照/封闭载荷解码器，单类按职责拆分，不创建重复状态机。
- `border/fulfillment/domain/`封闭资产类型、审计principal/原因常量（如已有则复用）及安全业务异常；不依赖Spring/MyBatis的纯解码逻辑单独测试。
- 在既有`FulfillmentAcceptanceReceiptMapper`/订单Mapper必要时新增Lambda项目键查询，不修改原买方键写入/内部旧查询合同。
- `SensitiveAccessAuditDO`新增项目/订单/凭证版本及同步注释。审计Mapper沿BaseMapperX，新增SQL仅DDL/必要复杂XML，不用注解SQL。
- 新增`src/main/resources/db/changelog/releases/1.0.0/050-project-standard-asset-audit.sql`及主changelog登记；旧010及全部此前SQL不变。

测试目录对应新`ProjectStandardOrderQueryServiceTest`、资产编排/解码/审计`*Test`，及真实`ProjectStandardOrderQueryIntegrationTest`、`ProjectStandardAssetAuditIntegrationTest`；更新`SupplyChainLiquibaseIntegrationTest`的精确DDL断言，保留原门禁。

### 实施与验证

- [ ] 先写可编译的项目查询/审计行为RED，验证旧语义无法按项目隔离/审计独立保存，不以missing-class编译错误当RED。新类可先最小failclosed骨架，然后运行行为测试。记录命令和原始新鲜报告。
- [ ] 实现查询§2：RR同一读视图，严格项目/分销商/CARD与所有双向引用，NULL/其他项目统一不存在；ACCEPTED缺订单、孤立订单、非法状态内部故障。未建单PROCESSING/拒绝可返回真实回执。
- [ ] 实现§3/§4：直充assetAvailable=false；按可交付资产真实元数据判定卡券，全部引用校验。待交付空数组hint、MANUAL提示、终态/不支持/未受理明确业务错误；SUCCEEDED无资产内部故障。
- [ ] 封闭解码现有真实密文格式，保持AAD，旧cardCode映射券码/链接兼容；未知字段不直接转交付字段、非字符串不asText吞掉，冲突同义字段失败。字节finally清零、cause保留但不日志原始异常/明文，无部分交付。资产DTO安全toString。
- [ ] 审计独立REQUIRES_NEW批量追加，实际app/project/buyer/keyVersion/订单/资源，用户摘要NULL。每调用查询事件+每实际解密事件，拒绝与失败持久化。audit故障拒绝明文，保留双重故障cause/suppressed。
- [ ] 实施050增量、DO注释和主changelog，旧审计主体约束不丢失；新列COMMENT逐字匹配设计，无跨库/外键。
- [ ] 单元/Contract/架构门禁先绿，再真实MySQL门禁：同买方跨项目同号、NULL归属、所有引用错配、并发一致快照、审计外层回滚不丢/故障无明文、过期隔离不解密、无资金/Timer/供应商副作用。不得只用Mock验证事务。

API门禁按实际类名指定，命名Contract不会自动跑：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
-Dtest=ProjectStandardOrderQueryContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

服务器门禁根据实际拆分类名补齐，不缩掉必要覆盖：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
-Psupply-chain-integration \
-Dtest=ProjectStandardOrderQueryServiceTest,ProjectStandardAssetQueryServiceTest,ProjectStandardAssetPayloadDecoderTest,SupplyChainModuleArchitectureTest \
-Dsurefire.failIfNoSpecifiedTests=false \
-Dit.test=ProjectStandardOrderQueryIntegrationTest,ProjectStandardAssetAuditIntegrationTest,SupplyChainLiquibaseIntegrationTest \
-Dfailsafe.failIfNoSpecifiedTests=false verify
```

完整应用若依赖限频密钥，测试运行进程注入随机32B测试密钥，不写生产默认/持久化密钥/输出指纹；本阶段只读功能不能为方便测试绕过频率CLOSED门禁。Maven串行；仅跳过供应链之外单测，禁止-DskipTests。

- [ ] 证据保存`/tmp/project-standard-query-asset`：RED/GREEN命令、实际新鲜XML/count/hash、源提交及DDL基线hash。只记已执行测试，不把核心应用测试称为真实外部HTTP/供应商联调。
- [ ] 中文注释带设计章节链接，入口debug采样、无吞异常/魔法值，自审并仅提交owned代码/测试/050文件，中文提交。
- [ ] 规格审查通过→质量审查通过，有问题由原实施者修复并复评；root记录计划和`docs/集成测试.md`。随后进入报价/身份移除与共享受理阶段。

### 本阶段范围

不新增Controller或测试Controller、Gateway/共享Web日志、不改报价/受理/资金/路由/限购、不改旧标准buyerUserId列/DTO、不改前端。HTTP no-store和全链日志保护作为后续开放必需门禁，未完成前不得宣称资产HTTP可对外使用。root编辑设计/待办/集成文档，实施者不暂存这些文件。
