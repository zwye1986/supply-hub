# RENYUE、QNM、SZ_CARD 开发详细设计

> 文档编号：SC-SUP3-DDD；版本：0.2；日期：2026-10-15；状态：C-01/C-05已确认，T01公共钩子已实现；T02完整冻结与原单装配本地验证通过，证据见开发计划§12及集成测试记录；生产执行/恢复/资金闭环与供应商门禁未完成。
> 上游：[需求规格说明书](2026-10-15-renyue-qnm-szcard-requirements.md)、[概要设计](2026-10-15-renyue-qnm-szcard-solution-design.md)；下游：[开发计划](../plans/2026-10-15-renyue-qnm-szcard-development-plan.md)。
> 本文件给出实现合同；§2.2公共钩子及§3的本地冻结/装配机制之外的新类、接口、字段和策略仍为实施目标。§4的Runner/持久认领/恢复消费及§6～7回调资金闭环未接线；SRS§8的适用C项未解除时不能以本稿为生产操作授权。

## 1. 约束、术语与源码基线

### 1.1 不变量

1. 一次购买创建资格，未知仅核原单；租约到期不证明远端没有执行。
2. 请求输入和认证身份冻结；Supplier/Offer暂停只阻止新流量，不改变既有单的归属。
3. 创建受理不是充值成功；普通直充没有CONFIRM/TAKE步骤。
4. 原单成功/失败与本单资金命令在同一供应链事务收敛；相反终态只留冲突，不回退。
5. 实报缺失/差异不能伪造MATCHED；采购预留与供应商结算金额独立。
6. 协议层返回事实，不读订单DAL、不提交账本、不决定重路由。
7. 华昇协议、任务、检查点及资金规则保持；按用户开发阶段确认，QNM现有实现直接切USERKEY，不保留切换前旧协议回调/恢复，不执行开发数据清理或卡管历史订单修复。

“冻结快照”是外呼前保存的版本、非秘密身份和受保护业务数据，不是历史秘密仓库。“发送可能marker”表示存在无法否认的风险，不表示已经物理发送。“供应商可信失败”须包含协议身份和零交付合同，不能仅由非零错误码推导；用户另行确认的“三次连续查无单”属于平台风险关单例外，见§4.5，不冒充供应商零交付保证。

### 1.2 代码定位

以下相对路径前缀为`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`，记为`J/`；测试前缀为同模块`src/test/java/com/aifuli/module/supplychain/`，记为`T/`。这些缩写仅用于文档，命令中使用真实Maven模块路径。

- J/supplieradapter/metadata/AdapterMetadata.java：凭据模式/键、能力、Schema、映射模板和步骤声明；步骤声明不是解释器。
- J/tradecore/supply/application/ConnectorCredentialBindingValidator.java、ConnectorValidationService.java、ConnectorPublicationService.java：配置、绑定、发布与退役。
- J/supplieradapter/metadata/PublishedConnectorConfigurationResolver.java：新单仍只接受PUBLISHED；新增单步原单内部入口可验证PUBLISHED/RETIRED及对应10/90映射，同ID内容/凭据漂移失败关闭。
- J/border/fulfillment/application/FulfillmentOperationTicketFactory.java：保留旧协议材料与华昇入口；createForSingleStep认证原SEND检查点/归属/内容/目标后装配原单Ticket，包含冻结供价和相对恢复合同，不认领或外呼。
- J/border/fulfillment/application/FulfillmentOperationExecuteHandler.java：华昇先分派；其他EXECUTING/UNKNOWN不再Send。
- J/border/fulfillment/application/SupplierOrderLifecycle.java：资金/订单结果；普通成功当前会补造实报、华昇专属成功已有区别处理。
- J/border/fulfillment/application/huashengflow/：参考认领/恢复/Inbox不变量，不复用专属协议策略。
- J/infrastructure/security/SupplierEndpointPolicy.java：发布期基础URL护栏；运行期仍要受控DNS/出口，不能夸大为完整防DNS重绑定。

历史设计参照：一期详细设计§7.3/10/17，2026-10-03技术配置管理设计，2026-10-11 Offer生命周期，2026-10-09/12/13华昇闭环修订，2026-10-04日志规范。当前变更集末尾为067；实施时重查实际末尾，不预占迁移编号。

## 2. 注册、配置、能力和映射（FR-01～FR-05、FR-18）

### 2.1 协议注册

新增RENYUE_RECHARGE_V2、SZ_CARD_EQUITY_V1两个Metadata类型；QNM直接调整现有QnmGameOrderV1Metadata及QNM_GAME_ORDER_V1注册，不另增USERKEY适配器身份。协议类型与SupplierCode分离，不因技术调整另建假供应商。

每个新类型恰对应一个OperationExecutorPort实现和一个回调解析器，启动时检查新类型缺失/重复注册；沿用AdapterMetadataRegistry及CompositeOperationExecutor，不另建第二套执行路由。

**T01分阶段实施边界（2026-10-16）**：现有AdapterMetadataRegistry只拒绝同名Metadata，CompositeOperationExecutor仅在执行时选择首个supports匹配项，尚无上述完整性/唯一性启动门禁。因此先完成§2.2公共映射钩子，不提前注册RENYUE/SZ_CARD或缩减QNM能力。三家Metadata、Schema、协议合同与执行/回调接线及对应门禁须按逐家完成条件一并放行；不能用元数据已出现证明协议可执行。当前阶段保留华昇全部判据和QNM原合同，不涉及数据库迁移、资金动作或供应商外呼。

首期`capabilities={CREATE_RECHARGE,QUERY_ORDER,RECEIVE_CALLBACK}`；默认画像`QUERY_OR_CALLBACK/NONE/QUERY_ORDER/FORBIDDEN/SINGLE`。三家首期每单一个商品、一个手机号、一份，quantity固定1，不支持多份或自动拆单，已获用户确认；Offer数量规则须允许quantity=1，不新增min/max/step全部为1的强制限制。FIXED供价与CNY整数分已确认，调价发布新版本不改原单预期供价，禁止比例计价、实时浮动报价及厘价自动舍入，见SRS§4.1；REMOTE_ON_DEMAND按单采购、不预囤商品亦已确认，不新增逐单实时余额/库存准入，不排除商户账户预充值。余额、卡密和重放能力未实现不声明。

平台目标沿用`DIRECT_RECHARGE_TARGET`版本1及`accountType/accountValue`。2026-10-16用户最新确认三家首期仅MOBILE手机号直充，取代此前纳入QQ的选择；QQ、OTHER及卡密全部后置，三家首期不发布/受理EXTERNAL_ACCOUNT供给，不把数字QQ重解释成MOBILE。供应商商品规则核手机号格式，其他既有业务的通用EXTERNAL_ACCOUNT模型不删除。年卡等商品的权益时长不换算为购买数量；C-01范围决策已确认，不代表采购方售价、C-05资金策略、实现验收或供应商合同已获批准。

### 2.2 发布合同

公共字段沿用baseUrl、merchantAccount、callbackPath、连接/读取超时、限流/并发与凭据引用。adapter_config/extra_config仍用现有封闭Schema；首期不允许任意请求参数，尤其不允许覆盖商品、订单号、回调、价格或认证字段。

**商户限额共享范围已确认（2026-10-17）**：三家在中台内按同一供应商平台的同一真实商户共用总限流/并发额度，跨Connector、配置版本和服务实例合并，不能每处各分配一份；连接器复制、版本发布或凭据轮换不产生额外额度。不同供应商或不同商户不误并。具体请求速率/并发数及等待后的具体调度机制仍待确认；额度不足延后等待原则见§4.4，统一当前配置的生效原则见下段。本次不选算法或存储实现，也不将版本字段落库视为运行时限额已生效。用户明确不考虑与cardshop共用账号；共享账号回调分流、共存验证、跨系统额度协调退出本期，不修改cardshop账号/回调或代为开通账号。订单号及本中台回调合同仍依C-03/C-07验证；不改变华昇。验收见`docs/集成测试.md`本项目C-04第20～22项（NOT_RUN）。

**商户限额调整生效追加确认（2026-10-17）**：商户总速率/并发额度采用该共享额度域的当前统一配置，不随每笔订单或Connector版本分别冻结；调整后，新订单外呼与旧订单补查均受新限额约束，不能沿旧版本叠加额度。调低限额不取消或中断已经发出的HTTP，不据此判业务失败或动账；例如仅作说明的并发上限5调为2且已有4个请求在途时，等待在途数降至2以下才准入新请求。调高同样不绕过原单到期、租约/代次、1000次认领、日历截止及安全资格，不承诺立即查询或延长窗口。原单补查间隔、认领上限、截止及其它冻结的履约材料仍按§3.1，动态限额不授权改用最新地址、凭据或协议；HTTP超时及执行租约原合同不变。具体数值与配置管理/一致性实现尚未确定，本项不授权调整共享或生产配置，不改变华昇；验收见集成测试第22项（NOT_RUN）。

**商户额度证据状态（2026-10-17）**：用户确认目前没有仁跃、QNM、SZ_CARD供应商明确给出的商户总请求速率、并发上限或对应后台截图，具体生产数值保持待核实，不推断为无限额，也不自行补生产默认值。本地隔离测试可使用明确标为夹具的限额验证第20～22项，不以测试取值/通过结果证明供应商许可或生产容量；外部证据暂缺不阻断与其无关的本地实现，但本次不解除真实供给发布门禁。仁跃商户+单号1秒限制仍独立遵守，不能换算为该商户全部订单的总额度。

AdapterMetadata已增加默认无约束的`validateMappings(statuses, errors)`钩子，华昇保留其已有同名严格实现；验证、发布、运行时已通过此钩子调用协议检查，不再以华昇类型决定是否检查映射。三家Metadata封闭模板仍待后续实现。公共机制已独立回归，后续再调整QNM合同及增加另外两家，不能混淆重构与行为变更。

钩子只核映射的协议内容；草稿/已验证/已发布生命周期仍由原入口校验。默认无附加约束以保持现有可扩展适配器行为；华昇仅将原方法声明为覆盖，不改变十行非终态模板/空错误表。发布时华昇额外参数校验、运行时华昇能力精确匹配仍保持原作用域，不借钩子提取改变其他协议。通过隔离TEST_SET元数据和真实发布/解析入口验证非华昇也不能绕过自身合同：验证前非法事实、验证后篡改、已发布内容漂移分别拒绝，拒绝发布无当前版本/发布审计副作用；合法模板从验证到运行时可用，QNM原有追加行为仍由既有回归保护。

模板由协议状态枚举/分类合同生成，草稿只提供完整初始化、不允许追加任意映射。错误表首期为空；未知错误由封闭代码分类核原单，不凭自由文本匹配成失败。运行时核模板内容与版本，而非将factType/actionType作为账本指令。RawObservation继续记事实；本轮不宣称实现“数据库规则驱动映射”。

封闭模板至少包含：各家CREATE受理→ACCEPTED非终态；QUERY处理中→ACCEPTED，可靠成功/失败→对应事实；CALLBACK成功/失败→对应事实；未知/手工/不存在的单次协议事实保持非资金终态。§4.5的三次查无单关单由编排层检查历史与计数，不把模板中的NOT_FOUND改成供应商REJECTED。业务phase仍用CREATE/QUERY/CALLBACK，端点和原始字段单独留在事实内。

### 2.3 凭据

- RENYUE：CREDENTIAL_SET，键恰为merchantKey/privateKey；merchantAccount是MerchantNo。
- QNM：SINGLE_SLOT，商户app_key通过现有secretKey入口投影为userkey；merchantAccount是userid。
- SZ_CARD：SINGLE_SLOT，md5Key使用现有密钥槽；merchantAccount是euserId。

集继续FrozenCredentialSetResolver在独立短事务中锁集/项、核整个冻结身份集合、只解析指定键。单槽新增同等冻结检查：slotId/providerType/activeSecretVersion；注意槽的activeSecretVersion是**String**，凭据项版本是**Integer**，不得强制共用整型转换。

新槽/集+新ConnectorVersion用于轮换；旧材料和ENV实际值保留直到旧单结束。原地替换ENV值但不改版本也不被系统快照可靠发现，因此运营禁止该操作，不宣称已保存全部历史秘密。

## 3. 冻结快照、Ticket与数据存储（FR-02/06/19）

### 3.1 单步骤检查点V1

复用sc_fulfillment_operation的checkpoint_schema_version、checkpoint_key_version、checkpoint_ciphertext，不新增供应商私有订单表。JSON仅存在于信封密文内，字段封闭：

```text
schemaVersion = 1
binding = {operationNo,bOrderId,adapterType,adapterStepCode,
           supplierId,connectorVersionId,offerVersionId,configDigest}
business = {billno,externalSkuCode,quantity,targetSchemaCode,
            protectedTargetReference,callbackUrl,
            settlementUnitPriceMinorUnits,currency}
credentialIdentity = SLOT{slotId,providerType,activeSecretVersion:string}
                   或 SET{setId,items:[itemId,itemKey,providerType,activeSecretVersion:int]}
recoveryContract = {policyCode,firstQueryDelaySeconds,timeoutFirstQueryDelaySeconds,
                   processingIntervalSeconds,notFoundIntervalSeconds,notFoundConsecutiveThreshold,
                   errorBackoffSeconds,maxQueryClaims,deadlinePolicyCode,deadlineZoneId,
                   connectTimeoutMs,readWriteTimeoutMs,totalCallTimeoutMs,executionLeaseMs}
```

target引用现有受保护目标存储，不复制明文到普通字段；外呼时按冻结目标解密，不使用最新商品目标。若既有目标结构不能稳定定位，须在同受理事务将必要目标放入受保护快照，并更新字段合同后再实现。

**T02完整冻结实施合同（2026-10-17）**：检查点值对象置于`border.fulfillment.domain.singlestep`，编解码器置于`border.fulfillment.application.singlestep`；配置解析器只消费内部值投影，不依赖border DAL。V1本片只含schemaVersion、binding、business、credentialIdentity、recoveryContract五个必填根字段，不预置尚未实现的observations；观察/进度扩展须由T03/T04另定Schema，不以任意JSON扩展槽提前代替事实合同。protectedTargetReference为`{requestId,requestNo,targetSchemaVersion,targetDigest,targetKeyVersion}`，绑定既有FulfillmentRequest及其受保护目标，摘要用64位小写十六进制，不持有明文手机号。

JSON对象键递归按Unicode字段名排序，数组保留语义顺序；能力按名称、映射按协议字段组成的规范化内容、凭据项按itemKey排序。摘要输入为`{configuration,business,credentialIdentity,recoveryContract}`：configuration显式投影Connector身份/版本/协议/地址/商户/回调路径/封闭配置/能力及映射内容，以及Offer身份/版本/外部SKU/供价/币种/数量规则/执行画像/封闭履约配置；不投影行ID、审计时间、可用期、发布/退役状态、当前版本指针、商户共享限额或秘密。新增内容字段应先修订此合同，不能将整个DO序列化充当摘要。business含已冻结完整callbackUrl，后续全局回调域名调整不改原单；原版本callbackPath变更仍由configuration摘要发现。采购方价格保留在原Request/预留中，供价进入检查点，二者不能互相代替。

明文UTF-8上限65536字节，JSON深度上限12，普通字符串上限4096字符、数字上限20字符，凭据项至多16项；拒绝未知/缺失字段、null冒充必填、重复键、尾随文档、浮点转整数、字符串数字互转。AAD使用同一规范化JSON编码用途、Schema及全部原binding，含供应商和内容摘要；SEND是唯一原步骤，QUERY端点不改变AAD。信封解密/编码临时byte[]由Codec在finally擦除，解码诊断只返回固定安全说明，不附Jackson明文异常；Checkpoint的toString仅脱敏说明。冻结策略仅保存相对合同，不在受理时生成firstCreateAt、recoveryDeadlineAt或lease。

2026-10-16用户确认跨日核实期限，取代固定deadlineSeconds建议：受理时冻结截止策略及Asia/Shanghai时区；首次取得创建资格的短事务以该时区确定日期D，并同时固化首次创建时点及D+2日00:00的recovery_deadline_at。认领前不猜测创建日期；后续查询、任务重投、重启或配置变更均不得重新计时或延长期限。该日历合同尚未实现，不改变华昇既有检查点。

2026-10-16追加确认：冻结的errorBackoffSeconds=600同时适用于QUERY网络超时/连接失败，以及QUERY响应解析失败/业务状态未识别；均从对应新事实持久化起固定等待，不立即重试或递增。未知响应事实、连续NOT_FOUND计数归零、nextRecoverAt及下一Timer同事务保存，重复/旧事实不重置到期点或清掉后续计数，详见§4.4/4.5。此字段不授权CREATE异常、验签/鉴权失败或身份冲突的普通补查。

2026-10-16再次追加确认：尚无可信受理/终态且无已知安全冲突时，CREATE响应无法解析或业务状态未识别，从该次异常事实持久化起等待180秒才首次安全查询原单，复用冻结的timeoutFirstQueryDelaySeconds=180；它是对创建未知回执的独立批准，不把原始回执伪装成网络超时。事实、nextRecoverAt及下一Timer同事务保存；明确受理仍走firstQueryDelaySeconds（仁跃600秒、另两家180秒），QUERY未知响应仍走errorBackoffSeconds=600，不将不同动作的异常混为一类。各分支及安全限制见§4.4。

2026-10-17追加确认：CREATE可能发送标记已提交、执行租约过期且没有已持久化的可信受理/终态、无已知安全冲突时，从“创建结果未知”事实首次持久化起等待180秒，复用冻结的timeoutFirstQueryDelaySeconds，再安全查询原单。保留独立的租约过期事实，不伪造网络超时或回执接收；事实、nextRecoverAt及下一Timer同事务，重启/重复看门狗不重新起算或覆盖后续查询状态。此前已有创建超时/异常事实及到期点的，沿用原有效调度，不因租约过期重算。CREATE marker已提交但HTTP尚未执行也禁止再CREATE；QUERY租约过期另按下项独立确认，见§4.3/4.4。

2026-10-17随后确认：当前QUERY租约过期且没有可应用结果、无已知安全冲突时，按该次查询结果未知处理，清零连续NOT_FOUND计数，保留原预留；从该未知事实首次持久化起固定等待600秒再安全查原单，复用冻结的errorBackoffSeconds。事实、清零、nextRecoverAt及下一Timer按原单generation围栏同事务保存；该次已有有效结果/异常事实或原单已有更新调度时不另造未知、清零或重算，重启/重复看门狗/旧事实不能覆盖后续计数和到期点。不同于CREATE租约过期的180秒，不伪造网络超时事实；完整安全/截止边界见§4.4/4.5，尚未实施验收。

2026-10-17认领预算追加确认：三家每个原单冻结maxQueryClaims=1000；累计QUERY成功认领数持久化，非每日额度。重启、配置变更、结果重放及连续NOT_FOUND计数清零均不重置本累计数；认领事务回滚不消耗额度，结果事务回滚不撤销此前已提交的认领。边界与最后一次合法结果处理见§4.3/4.4，尚未实施验收。

2026-10-17商户限额生效追加确认：§2.2的商户总速率/并发额度是当前共享资源控制，不属于逐单冻结的recoveryContract；调额不能改写原检查点、内容摘要、查询累计数或截止，不因原版本退役而另分配旧额度。仅动态取商户限额，协议地址、凭据身份、商品/价格及恢复间隔仍用原冻结材料，不把资源控制更新扩展成全量配置热切换。

AAD固定包含用途`SINGLE_STEP_CHECKPOINT`、Schema、adapterType、原Operation身份/步骤、冻结版本及摘要；查询Ticket可使用QUERY_ORDER端点，但解密AAD仍是原Operation持久SEND绑定。拒绝未知Schema、JSON重复键、未知字段、类型宽松、身份/摘要不符；坏快照进入Case，不能退回首次创建。

configDigest按固定字段、长度无歧义的规范化JSON做SHA-256，覆盖原版本/适配器、地址/商户/回调、超时/恢复合同、能力/映射模板、Offer外部商品/数量/目标Schema/供价及非秘密凭据身份。发布/退役状态不参与内容摘要，实际秘密不入摘要。不能沿用只拼接版本ID的摘要来宣称已检测内容篡改。

受理事务核新类型、已发布Offer/Connector、整分价格、目标和凭据身份，保存检查点、Operation、准备watchdog及Outbox，与采购预留一起提交。首期billno沿用现有24位数字发号，不引入供应商私有发号器；C-07仍须证明供应商接受该长度/字符与唯一作用域，不擅自截短或加前缀。用户已排除与cardshop共用账号，不再要求跨系统单号共存证明。

T02受理接线由Metadata的`singleStepRecoveryContract()`显式选择，默认null保持旧协议。选择新路径后，配置/Codec/Timer/回调依赖缺失或核验失败必须抛出并回滚，不进入旧Operation兜底；只接受MOBILE、DIRECT_RECHARGE和quantity=1。新增准备任务码`SINGLE_STEP_PREPARATION_WATCHDOG`，受理同事务写到期5分钟的定位任务；消费及Case推进由T03实现。执行Timer继续由原Outbox Dispatch生成，受理不直接生成执行Timer。故障注入增加CHECKPOINT_FROZEN和PREPARATION_WATCHDOG_SCHEDULED两个阶段，独立claim留下的PROCESSING回执可保留，ACCEPTED及业务行/预留/任务必须一起回滚。

### 3.2 Ticket扩展

保留现有构造器/builder和旧CredentialMaterial。增加可选结算单价minorUnits/币种以及新协议的受保护材料入口；新入口不能把merchantKey叫appSecret。

domain已增加`ResolvedCredentialMaterial`：内部仅保存Metadata声明键的char[]，对外按键取得受控临时材料，close擦除所拥有数组，toString/序列化拒绝泄露；用于新Ticket装配入口，尚未接单步Runner，旧路径不迁入。这个模块解决仁跃多项材料与三家认证消费的真实差异，不暴露任意配置Map。

生命周期：解析→校验冻结身份→构造新Ticket→签名/HTTP→finally close。临时复制数组由复制方擦除，规范串/回执明文临时字节同样擦除；不能声称Java不可变String会立即清除。普通Controller/消息载荷不得接收或序列化Ticket和材料。

结算单价来自BOrder/Offer冻结值，不能查当前Cardshop或最新Offer，不能塞入extra_config；恢复与回调使用同快照。

**T02基础切片合同（2026-10-16）**：可以先实现与未决供应商合同无关的材料装配；不提前注册新Metadata、调用供应商或将既有QNM/华昇迁入新入口。本切片不等于§3.1受理检查点、§3.3既有单解析或AC-07完成。

- `FrozenCredentialSlotIdentity(slotId,providerType,activeSecretVersion)`保留槽版本原始String；`FrozenCredentialSlotResolver`在独立短事务当前读锁槽，核ACTIVE、ID、provider和版本全部相等后才解析，绝不用当前版本替代期待版本。成功的char[]所有权交调用者，异常不能含材料。与既有冻结集解析器一样，不在订单/资金聚合锁内调用；ENV原地改值不可检测的边界不变。
- `ResolvedCredentialMaterial.takeOwnership(requiredKeys, values)`接管解析器返回的数组，键必须与代码Metadata声明完全相等、每项非空；接管失败也擦除全部交入数组。按键回调消费临时副本，在正常或异常返回后擦除副本；不直接返回内部数组。`close()`幂等擦除接管数组，关闭后拒绝消费。材料、Ticket及其builder均禁止JSON序列化，toString不含秘密；消费回调不得保留材料副本或主动将明文写入异常/日志。
- 冻结槽/集解析器返回值穿过Spring事务提交边界后才交给工厂；如果解析已完成但短事务提交失败或完成状态不明，工厂尚未接管，解析器须在事务完成回调擦除数组。既有FrozenCredentialSetResolver仅补此异常生命周期，不改变身份、指定键或锁序合同。
- Factory增加显式`createWithFrozenCredentials`入口：调用者提供原单冻结配置、冻结槽身份（集模式使用配置内冻结项）及代码Metadata；工厂核适配器/绑定模式与互斥引用，按Metadata键解析，不再把merchantKey投影为appSecret。新Ticket只持有新材料，旧secretKey/CredentialMaterial为空；供价/币种只从BOrder冻结字段投影，不以传入Offer价回填。材料取得后的任何装配失败均擦除；成功后Ticket接管，由执行者`try (ticket)`在签名/HTTP完成或失败时关闭。旧24/27/30字段构造器和关闭行为保持兼容。本切片新入口的checkpointPayload为空，不把数据库信封密文直传为协议明文；待§3.1实现后接经认证解密的内容。
- 上述入口只承担材料与Ticket装配；调用方后续必须先验证§3.1完整检查点和§3.3配置绑定才能使用，不把本切片的参数校验当作完整原单内容摘要校验。集版本Integer、槽版本String不互转。先以工厂公开入口和真实MySQL验证轮换拒绝、锁覆盖解析、数组释放及旧协议回归，再接后续单步Runner。

T02增加`createForSingleStep`，入参为持久Operation/BOrder/Request/订单及供应商身份，不接HTTP版本/摘要。工厂先用原SEND绑定认证检查点，再核对请求引用、原billno/数量/供价/币种/归属，调用原单解析器验证实际配置内容；随后在无订单/资金事务的边界解析原目标与凭据。目标按原buyerId、buyerOrderNo、Schema及版本的既有AAD解密，复核HMAC目标摘要并用既有Schema校验MOBILE规范手机号。CREATE和QUERY均携原目标与合同，QUERY仅改变Ticket端点/operationKind，AAD不改。完整RecoveryContract位于经认证的`checkpointPayload`明文，连接/读取值从该合同投影；本批不再新增重复的Ticket恢复字段，总预算/租约仍待T03消费，不以最新超时或回调属性重建。装配失败擦除材料/临时字节，旧Ticket构造入口兼容不变。此入口只装配，不认领或外呼，不能宣称已接Runner。

T02验证使用测试专用`TEST_SINGLE_STEP`显式启用；生产Metadata的默认声明仍为空。准备任务类型只由隔离测试注册不消费的Handler以检验持久化，生产准备Handler留待T03；未注册Handler时真实PersistentTimer拒绝调度，新路径受理随事务失败关闭。没有生产协议注册/启动门禁、执行器或准备消费接线，不得据此解除三家发布门禁。原单重建测试关闭原Spring容器/连接池、重开同一隔离MySQL并重读材料，区别于复用内存对象；这不等于进程崩溃后的任务自动恢复验收。

### 3.3 既有单冻结配置读取

保留`PublishedConnectorConfigurationResolver.resolve(versionId)`用于新单准入。增加内部`resolveForExistingOrder(binding)`：binding由已存在BOrder和检查点投影，不接受HTTP任意拼装；仅新三家类型使用。

允许原版本PUBLISHED或RETIRED，拒绝DRAFT；核原供应商/版本/adapterType/配置摘要/冻结凭据以及模板内容。映射行状态按版本接受发布10或退役90，但内容必须仍符合冻结合同；不因此重新设置currentVersionId，也不让路由选退役版本。秘密停用/漂移失败关闭并告警，不拿新密钥兜底。

内部入口以已认证`SingleStepCheckpoint`作为binding投影，由Factory完成落库原单对应校验，解析器不读取订单DAL。共用解析保留`resolve`的发布10限制；原单严格按版本生命周期要求所有状态/错误映射为10或90，不能混用。`resolveForSingleStepAcceptance`读取已发布原版本、Offer及非秘密凭据身份，返回显式内容投影供冻结；原单重读同一内容投影并比对SHA-256及槽/集身份，不查currentVersionId。新单要求Offer仍为当前已发布版本，原单只要求原Offer版本确已发布且归属和连接器匹配，Offer下架/新版本不剥夺原单读取权。现有凭据守卫仍核ACTIVE及集归属，槽版本严格String等值，解析秘密前的短事务再核一次身份。

本入口保障本方案新产生的三家订单，不用于恢复QNM切换前开发期旧单；没有旧QNM排空/协议迁移任务。若需清理旧开发配置或任务，先形成不含秘密的数据范围清单并另获授权，不通过本解析器猜测或改写旧单。华昇不在本次切换该入口。

### 3.4 持久结构与迁移

直接复用Operation已有execution_generation、execution_lease_until、recovery_count、recovery_deadline_at、business_send_possible_at、side_effect_free、next_recover_at、version；复用现有恢复索引、Inbox/Timer/Outbox、BOrder金额与终结原因字段，不重复建同列索引/资金表。

首期不预定新增业务表或列；检查点V1承载新增冻结内容。新适配器/原因/任务码使用集中常量，检查其是否受现有CHECK约束。若确需扩展CHECK或存储预算，先更新本节字段COMMENT/DTO、按实际末尾追加Liquibase，再通过空库/旧库迁移测试；当前067之后的编号仅在实施时确定。不得修改历史054～067或执行生产迁移。

## 4. 共享执行与恢复接口（FR-07～FR-09/12/15/16）

### 4.1 最小接口

拟新增J/border/fulfillment/application/singlestep：

```java
// 推荐合同，不是当前已有方法；所有复杂类型均为该模块内部纯值。
SingleStepFulfillmentRunner.runExecute(String operationNo, String originTraceId);
SingleStepFulfillmentRunner.runRecovery(String operationNo, long expectedGeneration, String originTraceId);
SingleStepFulfillmentLifecycle.claim(String operationNo, SingleStepAction action, Long expectedGeneration);
SingleStepFulfillmentLifecycle.apply(SingleStepExecutionPermit permit, OperationOutcome outcome, String traceId);
```

SingleStepAction只有CREATE/QUERY。permit包含原单/Operation、action、generation、lease、冻结版本/摘要，不含秘密。claim无资格返回空值；apply返回APPLIED/DUPLICATE/STALE/CONFLICT/MANUAL等封闭结果，不返回任意资金指令。

STALE只拒绝旧执行者改写当前认领/租约，不丢弃经原绑定认证的成功事实；旧代可信终态先留受保护观察，再由当前原单锁下核实/收敛，不能当作无副作用证据。回调不依赖某次查询permit，但必须校验原单冻结绑定。

共享协议事实`SingleStepSupplierFact`实现已有FulfillmentProtocolFact，只携带source、商户/双号、目标/数量、标准观察、报告金额、协议有效性与受保护摘要；没有Mapper/Throwable/账本动作。新Adapter返回ACCEPTED/RESULT_UNKNOWN等中间事实，最终资金分类由锁后单步Lifecycle核实。

适配器元数据拟提供可选单步合同声明，默认空保持旧类型；新类型显式声明凭据消费键、回调/恢复合同及封闭失败策略。不是自由步骤列表或动态脚本解释器。

### 4.2 创建认领

```text
事务外：读取原单冻结身份，准备秘密；不存在/漂移/不满足目标则失败关闭
短事务：依既有聚合锁顺序锁原单
  仅 PENDING 且 businessSendPossibleAt为空、无可靠终态/冲突/失效资格才可CREATE
  仅MOBILE目标进入首期，并先通过原频控资格；QQ/OTHER不进入该创建路径
  generation+=1，设EXECUTING、lease及首次可能发送marker
  持久同代watchdog；提交后返回permit
事务外：单次HTTP创建，禁止客户端重试/重定向
结果短事务：核permit/generation/冻结身份，留证并apply；旧结果不回退终态
```

秘密准备不能在聚合/资金锁内获取凭据集锁；claim再次核预读身份与冻结一致。不凭一次本地异常/空attempts推导无副作用。准备在marker前失败可以在证明从未发送、无历史原单/成功事实时按本地明确失败处理，否则保留并核实。

CREATE marker一旦提交，即使HTTP尚未执行就崩溃，也不能再次CREATE；代价为可能需要人工。这是默认NONE的安全选择。lease只协调本地事实应用，不保证能取消远端已经发送的HTTP。

### 4.3 查询、看门狗与频控协作

QUERY沿同Operation/billno恢复，认领时generation+=1、recoveryCount+=1并设lease；三家每原单QUERY成功认领总上限为冻结的maxQueryClaims=1000，实际HTTP次数另外留证。只能在nextRecoverAt已到、无未过期查询资格、未达截止且累计认领数小于1000时，在同一原单围栏事务内竞争资格并递增；999可认领第1000次，不能取得第1001次资格。重复/旧任务未取得资格不耗预算；认领提交后即使尚未发HTTP便宕机也已消耗，认领回滚不消耗，结果回滚不退还已提交额度。上限限制新认领，不拒收最后一次合法认领的可信结果或迟到可信回调；在途资格及结果仍按原围栏/冲突合同校验。到限不再新增自动查询，仍未收敛则创建/关联Case并保留预留，不能仅因计数等于1000丢弃合法终态、判失败或释放资金。

看门狗携原generation，旧代或已失效任务跳过；仅当前CREATE资格确已过期、marker已提交且无更高优先级可信事实/安全冲突时，按§4.4持久化创建结果未知并等待180秒再安全QUERY，不改marker或重新创建。当前QUERY lease过期且无可应用结果、无已知安全冲突时，按查询结果未知清零连续NOT_FOUND计数，事实首次持久化后等待600秒再安全QUERY。两类事实均与到期点及下一Timer同事务；租约过期不证明HTTP已发出、发生网络超时、供应商受理或最终失败，该次已有有效事实/调度或原单已有更新调度均不被覆盖。准备watchdog在受理后5分钟检查尚未取得发送资格的死信/准备问题，进入Case；不是替代首创建执行器。

三家首期MOBILE恢复由SingleStepRunner统一拥有。已有MOBILE频控恢复触发对三家委派同入口，不再额外裸调用Query或独立动账；所有执行/查询Timer触发竞争同Operation外呼资格。QNM不另保留旧频控查单分支。结果窗口、锁序和频控释放按既有已确认规则，由可信终态/人工窗口策略推进；QQ/OTHER履约与恢复后置，不为其新增独立路径。

任务使用现有PersistentTimer，拟集中码SINGLE_STEP_QUERY/SINGLE_STEP_WATCHDOG/SINGLE_STEP_PREPARATION_WATCHDOG。查询/看门狗payload仅原operationNo、expectedGeneration、originTraceId等非秘密定位字段，idempotencyKey含任务类型/原操作/代次/到期序次。状态更新与下一任务同事务，不以内存sleep或afterCommit承担可靠恢复。本期不新增运营提前提醒Timer或待通知事件，范围调整见§4.4。

### 4.4 平台恢复窗口与待定参数（C-04部分确认，未放行）

**已确认的平台策略（2026-10-16，本次执行线程）**：三家采用回调为主、持续低频原单补查，而非只补查一次即转人工。以Asia/Shanghai的首次取得创建资格日期为D，补查窗口覆盖D+1全天，在D+2日00:00停止发起新的自动补查，不是滚动24小时。例如10月16日15:00首次创建认领，截止10月18日00:00，共33小时；10月16日23:50认领，截止相同，共24小时10分。

有效NOT_FOUND采用结果持久化后180秒的专门间隔；仅限定候选连续3次可平台风险关单，详见§4.5，不被下述正常处理中600秒或未知保留原则覆盖。

- 首次创建认领时一次性持久化截止时间，后续恢复不顺延；首次创建前的排队/频控等待不消耗本窗口，仍由原准备看门狗管理。只要具备安全查询资格，窗口内按待定低频合同补查；可信终态提前收敛，合同/身份冲突或材料不可用仍按原规则失败关闭转核实，不能为了持续补查绕过安全校验。
- **明确受理后的首次补查已确认并修订**：收到经过协议与身份校验的明确受理响应后，仁跃按协议等待10分钟（firstQueryDelaySeconds=600），QNM、SZ_CARD等待3分钟（firstQueryDelaySeconds=180）；仍无可信终态且具备查询资格才首次补查。均以明确受理响应接收时点为起点，不以平台受理、CREATE认领或供应商未提供的内部受理时刻代替。正常受理事实与nextRecoverAt同事务保存，重启/重复受理事实不顺延；提前收到可信终态则不再补查，达到日历截止也不能为凑满等待而延长窗口。本次仁跃10分钟决策取代此前三家统一3分钟目标，不再保留仁跃正常受理提前查询的差异门禁。
- **创建请求超时后的首次补查已确认**：三家从超时事实持久化起等待3分钟（timeoutFirstQueryDelaySeconds=180），再在具备安全查询资格时查询原商户/原订单；仍不重新下单、不切供、不释放预留。超时事实与nextRecoverAt同事务保存，重启/重复超时事实不能重新起算；等待到期不能绕过原CREATE/QUERY执行资格、租约、身份与材料校验或日历截止，已有可信终态则不再补查。受理时在§3.1检查点分别冻结正常受理延迟和创建超时延迟，不复用一个参数混淆二者。该次确认只涵盖创建请求超时；CREATE未知回执、CREATE租约过期及QUERY网络异常随后分别确认如下，不从一个分支自动外推其它分支。
- **创建未知回执首次补查追加确认**：三家CREATE已收到回执，但无法解析或业务状态未识别，且尚未取得可信受理/终态、没有已知安全冲突时，从该次异常事实持久化起固定等待3分钟（复用冻结的timeoutFirstQueryDelaySeconds=180），再具备安全查询资格时查原商户/原单。事实、nextRecoverAt及下一Timer同事务保存；重启、重复任务、同一或旧事实重放不重新起算，也不能覆盖后续查询形成的新到期点或计数。原预留保留，不重发CREATE、不切供、不把坏回执当作有效NOT_FOUND或最终失败；可信存在/成功证据仍优先保护，仁跃已明确受理仍走600秒而非本分支。日历截止优先、不顺延，安全问题优先：验签失败、鉴权失败、身份冲突不能借解析/状态异常获得本补查资格；已有可信终态则不再查询。此为平台策略，仍须核实逐家供应商准入；CREATE仅租约过期、没有已持久化回执的路径另按下项独立确认。
- **CREATE租约过期首次补查追加确认（2026-10-17）**：三家CREATE可能发送标记已提交，因进程中断等仅能确认当前执行租约过期，没有已持久化的可信受理/终态且无已知安全冲突时，从“创建结果未知”事实首次持久化起固定等待3分钟，复用冻结的timeoutFirstQueryDelaySeconds=180，再具备安全查询资格时查原商户/原单。起点不是CREATE认领、租约到期、进程重启或仅发现过期的时刻；事实、nextRecoverAt及下一Timer按原单generation围栏同事务保存，提交失败不得留下可见任务。marker后即使HTTP尚未执行也不再CREATE；租约过期本身不证明发送、超时、受理、NOT_FOUND或最终失败，原预留保留，不切供或释放。已有有效创建超时/异常事实及调度的沿用原到期点，不重新起算；重复看门狗、重启、旧事实不能覆盖后续QUERY到期点/连续计数。已可信受理走正常合同（仁跃600秒），已有可信终态提前收敛，存在/成功保护与验签/鉴权失败、身份冲突优先，日历截止不顺延。此180秒为平台策略，供应商准入仍需核实；只确认CREATE路径，QUERY租约过期随后另按下项600秒及清零规则确认。
- **正常处理中间隔已确认**：每单每10分钟最多补查一次，processingIntervalSeconds=600；这是首次补查之后的正常间隔，不把QNM、SZ_CARD正常受理或三家创建超时的180秒首次等待改回600秒，也不是供应商已承诺的查询额度。回调仍为及时获取终态的主路径，采购方重复查单和重复Timer不能突破同一原单资格/频率，发布前仍须核实供应商约束。
- **查询网络异常间隔已确认**：三家补查请求本身发生网络超时或连接失败后，从该次异常事实持久化起固定等待10分钟（errorBackoffSeconds=600），再在具备安全查询资格且未达日历截止时查原商户/原单；不立即连续重试，不采用未批准的递增退避，也不重新CREATE、切供或释放预留。异常事实与nextRecoverAt同事务保存，重启/重复事实不重置到期点；每次新的实际查询再次网络失败，按该次新事实重新等待600秒。已有可信终态则不再补查，等待不能绕过执行资格、租约或安全校验，不顺延日历截止。该参数在§3.1检查点冻结；本次只确认查询网络超时/连接失败，不包括验签失败、身份冲突、HTTP业务响应及其它未知结果，不据此将它们当成可普通网络重试的异常。
- **查询未知响应间隔追加确认**：三家QUERY已收到响应但报文无法解析或业务状态未识别时，从该次未知响应事实持久化起固定等待10分钟，复用冻结的errorBackoffSeconds=600；与网络异常取值相同，但分别保存原始事实，不把未知响应伪装成网络失败。事实、§4.5连续计数归零、nextRecoverAt及下一Timer同事务保存，保留预留、不立即重试、不递增、不重新CREATE或切供。每次新的实际查询再次出现此类未知响应，按新事实等待600秒；重启、重复任务或同一/旧事实重放不重置到期点或清掉后续新计数。到期还须具备安全查询资格，可信终态提前收敛，日历截止优先且不顺延。验签失败、鉴权失败、身份冲突等安全问题优先于本分支，不因同时存在解析/状态异常就获得普通补查资格；CREATE未知回执及CREATE租约过期的180秒首次补查另见上项，QUERY租约过期另按下项独立确认。
- **QUERY租约过期间隔及计数追加确认（2026-10-17）**：三家已认领的当前QUERY执行租约过期且没有可应用结果、无已知安全冲突时，按该次查询结果未知处理，连续NOT_FOUND计数归零，保留原预留。从该次未知事实首次持久化起固定等待10分钟，复用冻结的errorBackoffSeconds=600，到期且具备安全查询资格时才查原商户/原单；不从QUERY认领、lease到期、发现过期或进程重启时点起算。事实、计数归零、nextRecoverAt及下一Timer按原单generation围栏同事务保存，回滚不得留下半份清零或任务。仅租约过期不证明HTTP发送/网络超时，也不是NOT_FOUND或最终失败；未发HTTP即中断、已发但未落可应用结果均不增加有效NOT_FOUND，不重发CREATE、不切供、不提交或释放预留。该次已有有效结果/异常事实时沿其合同处理，原单已有更新调度或属于旧代任务时不另造未知、清零或重算；重启、重复看门狗/同一或旧事实重放不清掉后续新计数或重置等待。每次新认领QUERY再次中断且满足本条件，按该次新未知事实仍等待600秒，不立即/递增重试。可信终态提前收敛，可信存在永久退出标记不删除，安全冲突优先、日历截止不延长；验签/鉴权失败、身份冲突不因租约过期获得普通补查资格。此为平台策略，非供应商协议时限，逐家准入及实现验收尚待补齐；CREATE租约过期仍180秒，不能混用。
- **运营提醒退出本期（2026-10-17，覆盖此前提前预警确认）**：用户明确“不接入通知渠道，忽略”，此前首次CREATE认领满30分钟提醒运营一次的方案退出本期实施与验收；不新增提醒参数、一次性标记、专用Timer或待通知事件，也不以WARN日志替代实际通知。上文或历史记录中的预警时点、渠道、接收人及投递/送达待定项不再作为本期门禁。持续低频安全补查、1000次QUERY认领保护、日历截止后的Case及资金保留规则不变，不因取消提醒而提前转人工停查。此调整仅针对本次运营主动提醒，不修改既有频控告警、日志或采购方订单结果通知合同。集成测试第23项标记OUT_OF_SCOPE，不记为通过或尚待执行的本期功能。
- 到达截止时间后不再发起新的自动查询，仍无可信终态则进入人工Case并保留资金，不退款、不重下、不切供。已经发起的查询结果与迟到可信回调仍走原单事实/冲突校验，不因超时边界直接丢弃可信事实或自动动账。
- **QUERY认领次数保护追加确认（2026-10-17）**：仁跃、QNM、SZ_CARD统一每原单最多成功认领1000次QUERY（maxQueryClaims=1000），不是每天1000次、必须查满1000次、供应商额度或SLA。可信终态、符合§4.5的三次NOT_FOUND风险关单、日历截止均可提前结束；到次数上限仍未知只停新查询、转Case并保留预留。上限与累计数按§3.1/4.3冻结持久化，重启、配置变化、结果重放或NOT_FOUND连续计数清零不能重置；最后合法结果不因达到上限被丢弃，华昇原策略不变。
- **1000的推导边界**：最长日历窗口48小时，已确认各普通恢复分支最快后续间隔180秒，保守包络为48×60÷3=960次；逐秒枚举首次创建时刻、严格排除截止时刻且忽略HTTP/持久化耗时，首次延迟180秒最多959次、600秒最多957次。1000留出余量，避免正常跨日调度被偏小上限截断；这是节奏规则的理论核算，不是供应商许可、真实容量或功能测试证据。商户总限流/并发仍须另行确认，不用1000替代商户额度。

**商户额度不足等待追加确认（2026-10-17）**：到期QUERY因同商户总速率/并发额度暂不可用时延后调度，额度恢复后仍须校验原单到期、执行资格、日历截止、累计认领上限及安全条件，不能直接裸查原单。原预留保留，不重新CREATE、不切供；本地等待不产生供应商超时、NOT_FOUND或最终失败事实。未取得QUERY认领不消耗1000次预算，已经成功提交的认领不因等待而退还或重置。等待不顺延原日历截止；截止仍未知则转Case并保留预留，等待期间取得可信终态则沿原单合同收敛。若已提交的当前QUERY资格随后确实过期且无可应用结果，仍按本节既定QUERY未知、清零连续NOT_FOUND及首次持久化后600秒合同处理；不能借容量等待跳过租约规则。具体延期调度机制及商户数值另行确认，统一当前限额的生效原则见§2.2；本项不设新的固定重试间隔或生产额度，验收见集成测试第21项（NOT_RUN）。

**商户总限额追加确认（2026-10-17）**：§2.2的共享范围、统一当前配置的生效原则及本节额度不足延后等待原则已确认，后文“商户总限频/并发待确认”仅保留具体数值及等待后的具体调度机制，配置管理/跨实例一致性须按已确认原则实现验证，不再将上述原则列为待定。每原单1000次QUERY预算与商户总限额分别约束，不能互相替代；与cardshop共用账号及跨系统额度协调不在本期范围，C-04仍未整体解除。

**HTTP与执行租约追加确认（2026-10-17）**：用户同意三家首期平台初值为连接10000ms、读取/写入各20000ms、整次HTTP总预算30000ms、CREATE/QUERY执行租约60000ms。阶段时限不能相加突破整次预算；租约从认领时计算，发送前核剩余资格，不因排队重新获得完整时间。四个值按§3.1逐单冻结，后续联调修订只影响新单，不作为商户当前统一限额热更新。关闭客户端自动重试及重定向；超时或租约过期不证明远端未执行，不重下、不释放，仍沿已确认的事实持久化后180秒/600秒恢复合同。这是已批准的平台初值，不是供应商SLA或时延实测；可推进本地实现及隔离测试，真实供给发布仍须逐家验证。原“HTTP/lease待确认”记录以本条为准，实施边界及验收见§4.6和集成测试第24项。

**撤回的工程候选值**：仁跃60分钟/30次、QNM/SZ_CARD30分钟/20次，以及配套30/60秒处理间隔和错误退避均不作为实施默认值；此前未经确认的HTTP/lease候选以本节2026-10-17追加确认的10000/20000/30000/60000ms为准。这些不是供应商SLA或生产时延/容量实测结论；目前确认平台跨日持续期限、正常受理后仁跃600秒/QNM及SZ_CARD180秒首次等待、三家创建请求超时事实持久化后180秒首次等待、后续正常处理中600秒最小间隔，以及QUERY网络异常、响应解析失败/业务状态未识别事实持久化后固定600秒再查。CREATE未知回执/租约过期、QUERY租约过期及1000次认领保护亦已按本节追加确认；其它安全异常处置仍待补齐。此前三家正常受理统一600秒建议及随后统一180秒目标均以本节最终分供应商规则为准。

**证据边界与剩余门禁**：仁跃附件明确正常受理后10分钟无回调再查、商户+单号1秒频率限制；本次用户选择正常受理按10分钟执行，消除此前3分钟目标与附件的差异，不要求为该正常路径另取提前查询许可。该附件不能推导出创建请求超时、创建未知回执或CREATE租约过期后允许3分钟查单；这些180秒路径、QUERY网络异常及响应解析失败/业务状态未识别或QUERY租约过期未知事实后600秒再查、QNM/SZ_CARD正常首次时点仍是平台策略，发布前须核实供应商准入。对cardshop当前QNM/SZ_CARD只读核实得到回调优先及采购方请求触发补查，未找到可照搬的有界定时查询合同；其超过10分钟未决订单筛选用于告警，不是停查或失败期限。QNM回调“最多通知6次”的代码注释也不是平台查单预算。每原单1000次认领保护、HTTP/lease平台初值已确认；运营提前提醒退出本期，不再等待通知渠道等决策。商户总限频/并发的具体数值/等待调度及容量目标仍待确认，其它安全异常处置合同及供应商准入仍须补齐；C-04未整体解除，不能发布供给。

### 4.5 连续查无单的平台风险关单（主要计数规则已确认，恢复合同未完成）

2026-10-16用户先指定有效NOT_FOUND后3分钟再查、连续3次仍查无单视作最终失败，随后明确不覆盖曾可信受理/处理中/存在的订单，并确认即使没有供应商“不再执行”保证，也自动失败、释放原预留，接受迟到充值成功的损失风险。本节取代此前“三家重复NOT_FOUND一律不释放”的绝对规则；这是平台业务终态判据，不是供应商未执行证明，不解除其它协议/发布门禁，也不授权真实资金操作。

- **间隔与事实**：三家按协议校验可接受、原商户/原订单身份正确的NOT_FOUND，从该次结果持久化起等待3分钟再安全查原单（notFoundIntervalSeconds=180）；这是正常处理中600秒之外的专门分支。曾受理的订单也按此NOT_FOUND间隔继续核实，但不能因此进入三次失败分支。原始协议事实始终保留NOT_FOUND，不把通用枚举、单次404、自由文本、鉴权失败或协议坏响应直接升级为失败；逐家可计数响应仍须封闭合同/独立向量校验。
- **候选范围**：仅对全部历史中从未出现可信受理、处理中或订单存在证据的原单开放。任一可信创建响应、查询或回调证明受理/存在后，持久记录永久退出标记，后续查无单、重启、配置更新不能重新入选；已有成功、交付、扣款或终态/身份冲突同样阻止该失败释放。不能只看当前result_class或最近一次查询而丢失历史证据。
- **计数与收敛**：notFoundConsecutiveThreshold=3，计实际独立查询产生且成功持久化的有效NOT_FOUND结果，首个有效结果为第1次，前两次保留预留，第3次在候选资格及共同安全检查仍满足时，原订单最终失败、一次RELEASE本单剩余预留并停止自动查询；仍不CREATE、不切供。计数、下一到期点、永久退出标记与结果按原单围栏持久化，重启不重计，重复结果/Timer不多计。阈值与间隔在§3.1检查点冻结，不作为运营可任意填写的资金映射。
- **网络异常打断连续计数已确认（2026-10-16）**：查询网络超时/连接失败使已有NOT_FOUND连续计数归零，而非暂停或累计；异常事实、计数归零及从该次事实持久化起600秒的下一到期点同事务保存，保留预留、不重新下单或切供。例如NOT_FOUND→网络超时→NOT_FOUND→NOT_FOUND，最后只算连续2次，还需再一次有效NOT_FOUND才满足3次门槛。重启、重复任务、同一结果或已处理异常重放本身不清零、不多计，也不能因旧异常重放清掉其后新形成的计数；清零不删除原始事实或可信存在永久退出标记，不延长日历截止。解析失败/状态未识别的计数和间隔另有用户追加确认，见下项，不由网络异常规则自动外推其它未知分支。
- **解析失败/状态未识别计数及间隔已确认（2026-10-16，追加修订）**：查询已收到响应，但报文无法解析或业务状态无法识别时，同样将连续NOT_FOUND计数归零，原单保持结果未知、原预留不提交也不释放；不能把坏响应当作有效NOT_FOUND或供应商最终失败。例如NOT_FOUND→NOT_FOUND→解析失败→NOT_FOUND，最后只算连续1次；未识别状态插入亦同。用户随后确认从该次未知响应事实持久化起固定等待600秒再安全查原单，事实、计数归零、nextRecoverAt及下一Timer按原单围栏同事务保存，不立即或递增重试。重启/同一或旧事实重放不重置到期点，也不再次清掉后续新计数；保留原始证据、永久退出标记和日历截止。若响应经认证及身份校验已独立证明原单存在，仍优先永久退出候选，不因状态未识别重新入选。等待与截止/安全资格边界见§4.4，不把验签失败、鉴权失败、身份冲突或CREATE解析失败归入本次普通补查。
- **QUERY中断打断连续计数已确认（2026-10-17）**：当前QUERY租约过期且无可应用结果时，按§4.4限定条件持久化该次查询结果未知并清零，而非暂停或将认领算有效NOT_FOUND。NOT_FOUND→NOT_FOUND→QUERY中断且结果未知→NOT_FOUND，最后只算连续1次，仍保留原预留；事实/清零/首次持久化后600秒到期点/下一Timer同事务。该次已有有效结果或原单已有更新调度时不再制造未知，重启/重复看门狗/旧事实不清掉后续新计数；原始证据、可信存在永久退出标记及日历截止不变，不能将CREATE租约过期重放当成一次新QUERY中断。
- **资金与迟到事实**：释放原采购方结算价的剩余预留，不按供应商供价退款；平台关单原因与三次原始证据独立留存，不伪造供应商拒绝/零交付事实。第三次结果与可信存在/成功事实并发时，沿既有同原单锁及受保护观察校验，已持久成功或存在证据阻断释放。已经提交平台失败并释放后才到达的可信成功属于相反终态冲突，按§7.4保留证据转Case，不自动翻转终态或重新扣款；用户接受风险不等于授权自动补扣、赔付或重购。
- **截止与待定边界**：日历截止先到、尚不满足三次条件时仍转Case并保留资金，不为凑次数延长期限。QUERY网络异常、解析失败/状态未识别及限定的QUERY租约过期无可应用结果均清零计数、从对应事实持久化起固定600秒再查已确认；不同于有效NOT_FOUND的180秒。CREATE未知回执及CREATE租约过期的限定180秒首次补查已另按§4.4确认，不把它们当作有效NOT_FOUND或重复创建资格。次数/资源门禁、其它安全异常处置及逐家协议合同仍须补齐，完整恢复/自动释放接线尚未实施验证。华昇四次7014及其候选合同不变，不将本节移植为全局规则。

### 4.6 HTTP预算与租约的本地实施切片

`J/border/fulfillment/domain/SingleStepTimeoutContract.java`保存四个毫秒时限，提供§4.4已确认初值；阶段时限必须为正且不超过整次预算，租约必须大于整次预算。该纯值是后续检查点/摘要和Runner共同消费的冻结合同，不读取当前商户限额或运行期配置；不修改旧Ticket或华昇策略。

`J/supplieradapter/singlestep/SingleStepHttpCallFactory.java`只构造受约束的OkHttp Call，不解析供应商协议、读DAL、动账或承担实际外呼日志。新三家Adapter后续在实际执行边界记录规范日志、读取有界响应并认证；此基础工厂不提前注册供应商或替换旧QNM路径。

Runner后续在数据库时间下取得认领与绝对lease，将剩余资格保守转换为进程内单调截止时间后传给工厂；转换锚点须在取得数据库时间之前取样，扣除获取/移交耗时，不把进程单调值持久化。工厂按`min(冻结整次预算, 剩余资格)`设置整次超时和固定单调deadline，构造Call及真正执行时均校验未耗尽，不能以延迟execute或新建客户端重置预算。超时预算覆盖响应体读取，不只到响应头；连接/读写阶段各自还受阶段时限约束。默认关闭连接故障重试、HTTP/HTTPS重定向及认证补发，同一Call加网络发送闩锁，禁止透明第二次HTTP尝试。

**禁重放补充**：三家均为POST，工厂只接受带请求体的POST，并将最终请求体声明为one-shot，委托原编码/写入而不复制秘密。OkHttp4.12.0的`503 + Retry-After: 0`补发分支不受`retryOnConnectionFailure(false)`单独约束；须由one-shot在关闭第一次响应体之前阻止follow-up，保留原503及可读取响应体。网络闩锁只作兜底，不能靠抛弃第一次响应改成异常来通过测试。依据：[RetryAndFollowUpInterceptor](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#L107-L124)及其503分支；本地第24项同时核原状态/正文和服务器只收到一次请求。

本切片以MockWebServer验证HTTP行为与短时隔离夹具的边界，另用已批准的30秒初值执行本地持续滴流验证；短时夹具毫秒数不是生产建议。实际结果、命令与证据见集成测试第24项后的实施记录。持久化冻结、真实数据库60秒认领/结果围栏、跨实例商户额度、超时事实/Timer及资金收敛仍须在T02～T04接线后验证，不能用工厂测试宣称这些完成，也不能据此发布供给。真实DNS/出口策略仍按§9独立核实；本地Call取消不保证远端停单，也不承诺不可中断的系统调用或线程暂停能精确在墙钟30秒返回。

### 4.7 T03本地执行与恢复接线（2026-10-18）

本轮接线仍只允许Metadata显式声明单步合同的类型；生产三家未声明前不改变旧QNM或华昇。商户总限额数值/等待调度尚待确认，不设置猜测值，不解除发布门禁。T04的回调Inbox、可信终态资金收敛及三次NOT_FOUND释放不在本切片提前启用。

1. **原子认领**：Runner在聚合事务外用T02工厂准备原材料；Lifecycle在READ_COMMITTED独立短事务内，先由频控所有者按QUEUE→ENTRY→Request→Order→全部BOrder/Operation锁序竞争并越过发送边界，再校验预读冻结身份。新增频控MANDATORY入口复用原认领/发送边界的内部逻辑，使频控占用、单步generation、marker、绝对lease、首次日历截止及同代watchdog共同提交；校验或Timer失败全部回滚。旧频控公开入口的独立事务合同保持不变。
2. **运行态与冻结态分离**：受理仍写五根字段V1。首次认领升级为认证加密的V2运行信封，根为schemaVersion/frozen/progress；frozen原样保留V1，progress持久当前动作、已应用结果代次、可信存在永久标记、连续NOT_FOUND数及最后事实/事实时间。V2 AAD使用原SEND binding与V2版本；不改冻结配置内容摘要，不持久化进程单调时钟。T02工厂只提取并验证frozen，QUERY不改变AAD。运行字段严格校验，损坏或换绑失败关闭。
3. **外呼资格**：permit保存当前代、动作、冻结绑定、绝对lease及数据库取时前取样换算的保守单调截止。提交与交接耗时从同一截止扣除；发送前重新检查原代/活动原单/lease，不能获得新的60秒。既有OperationExecutorPort新增带单调截止的重载，默认拒绝而非退回不受约束的旧execute；Composite沿原分派器委派。新Adapter须消费该截止和冻结HTTP合同，现有Adapter不隐式接入。
4. **结果与迟到事实**：新增单步协议事实携原绑定、billno、目标摘要/数量、来源、认证分类、标准观察、接收时间及报文摘要；通用outcome枚举不能替代协议证据。Lifecycle核原绑定后追加不含报文/账号的观察；旧代可信存在只更新永久保护标记并留证，不改当前租约、到期点或计数。可信终态先留证转Case保留预留，等待T04，不调用通用结果入口提前动账。未知网络/回执与租约过期分别持久化；只有当前未应用代才能安排一次下一Timer，重放不重新起算。任何安全冲突停止自动执行转Case，不作为普通未知重试。
5. **恢复调度**：QUERY、watchdog使用集中任务码，payload只含原operationNo、expectedGeneration及可选originTraceId；幂等键含任务类型/原操作/代次。正常受理按响应接收时间加冻结首次延迟；未知按结果事务DB时间起算180/600秒；有效NOT_FOUND按180秒，正常处理中600秒。下一Timer取恢复到期与日历截止的较早者，到截止只开Case不发查询。1000预算在认领事务递增，结果事务回滚不归还；第1000次合法事实仍接收。频控查询委派同Runner竞争原单资格，不另裸查。准备watchdog处理无发送资格的异常，不把正常FIFO等待误判为履约失败。
6. **验证边界**：使用隔离TEST_SINGLE_STEP、真实MySQL/Liquibase/Timer行与事务外测试执行器，覆盖并发、marker后中断、租约边界、未知事实的原子调度/回滚、NOT_FOUND中断、旧代事实、预算及关闭重开容器恢复。HTTP工厂已有MockWebServer契约继续回归；不以本切片宣称真实供应商认证/日志、商户容量或资金闭环通过。

**故障与活动资格围栏补充**：材料准备失败须带预读generation，锁后代次已变则忽略；已认领执行器的非网络故障须核对完整permit和未应用结果资格，不能让旧线程把新一代执行转入Case。当前代未知/非终态事实只在原聚合仍活动时推进；原Operation的FULFILL/SEND/顺序号/幂等键、Request及Order数量须锁后复核。看门狗只在当前代未完成且到期后解密运行态；确定性的原材料/检查点损坏转Case保留原预留，数据库访问故障仍抛出回滚，不能混为业务材料损坏。重开容器后使用落库payload和运行信封恢复，不保留原Ticket/permit；正常受理首次600/180秒必须读取冻结合同，不能读取当前Metadata重新起算。

## 5. 三家协议详细合同（FR-07～FR-10/14/18）

### 5.1 RENYUE_RECHARGE_V2

创建POST表单`/api/v2/getOrderRechargeInfo`，查单POST表单`/api/v2/getCardInquiryInfo`，UTF-8。创建字段：MerchantNo←merchantAccount，ProductCode←externalSkuCode，ThirdOrderNo←billno，RechargeNum=1，RechargeNo←冻结目标，AgreedPrice←冻结分值精确转元，TimeStamp←秒，CallbackUrl←冻结URL，SignSecret。

查单字段仅MerchantNo、ThirdOrderNo、TimeStamp、SignSecret。首期不发ExpandParams/MobileNo。签名以非空参数ASCII名排序为原始k=v&串，排除协议签名字段后末尾追加MerchantKey；UTF-8/SHA256withRSA/Base64。HTTP对原始值做正确表单编码，不能先URL编码再签名。

ReturnSign是供应商用商户公钥加密的回执，商户私钥RSA/ECB/PKCS1Padding分段解密后与预期明文核对，不是普通公钥验签。卡管代码所记线上合同：

- 创建回执取**请求**字段（请求TimeStamp），排除CallbackUrl/SignSecret/ExpandParams/MobileNo，再追加MerchantKey；不是签整个响应。
- 查询回执为`RegState={值}&ThirdOrderNo={值}&MerchantKey={密钥}`，不是纯值相加。
- 回调回执为`OrderNo={值}&RechargeAccount={值}&RechargeStatus={值}&StoreNo={值}&MerchantKey={密钥}`；TimeStamp不参与认证。

文档与线上短字段集、忽略大小写比较需C-03确认向量固定，不直接照搬卡管equalsIgnoreCase作为所有目标的安全规则。首期仅MOBILE，不因此开放QQ或大小写敏感OTHER。密钥、回执明文/预期规范串不得写日志。

创建CodeRes=1000且回执有效仅ACCEPTED；未知/重复1012/1027/验签异常保持未知核实，不把所有非1000直接退款。查单先检查请求状态和回执，再RegState：01处理中、02成功、03失败、04人工。回调StoreNo是**商户单号billno**，不是商户账号；OrderNo为供应商单号，RechargeStatus=2成功/3失败。原目标和双号绑定后才推进，ACK纯文本`success`。

AgreedPrice只能精确分报价；例如884分传8.84，不自行将8.845元舍入。仁跃文档默认商户单号不做重复校验，因此NONE；只有独立合同评审才能提升幂等能力。本轮不接余额、折扣目录与专用mobileCharge。

### 5.2 QNM_GAME_ORDER_V1（开发期调整为USERKEY合同）

创建POST表单`/GameOrder/Send`；原单查询`/GameOrder/Query`。公共字段userid←merchantAccount、userkey←单槽材料、time←毫秒；创建productid、billno、number=1、account、backurl。查询按卡管合同携原billno；不凭习惯把orderno替代billno，正式可选字段需确认向量。

签名：将非空参数（含userkey，不含sign）按名排序，原始k=v&串MD5大写；不另追加&key，禁止普通请求日志输出userkey。签名后以表单库编码，覆盖包含+、&、=和中文的参数，不能复制卡管未编码字符串拼接。

响应读取ResCode/ResMes/ResContent。当前卡管的正常响应条件为`ResCode=1 且 ResMes="OK"`，不是`ResCode="OK"`；正式向量确认后固定。ResContent承载orderSn、sumPrice、orderStatus。创建成功仅受理，不因返回对象写了“成功”就提交采购资金。鉴权/协议错误优先于嵌套订单状态；其他创建码不是天然零副作用拒绝。现有Metadata中`ResCode=OK`模板应随QNM合同替换删除。

查询可信有效后orderStatus=0处理中/1成功/2失败，未识别状态未知；不存在返回NOT_FOUND观察，平台是否按连续三次关单另依§4.5，不由单次协议结果直接释放。sumPrice明确单位元，JSON数值或字符串统一精确×100，例如100、100.00、"100"均10000分；`longValueExact`拒绝非整分、负数、非有限值及溢出，不按整数/小数猜单位。

回调认证字段集沿卡管：userid、userkey、time、billno、orderno、status；固定规范串MD5大写，核userid和原billno/orderno；status=1成功、2失败，其他未知；ACK纯文本`OK`。响应/回调中无实报金额则不伪造；任意未签名扩展不能决定资金。

`/SendKm`、Cards/cardNo/cardPwd/expirationTime、BalanceQuery仅记录后续合同，不在首期能力或执行分支开放。直接调整QnmGameOrderV1Metadata、QnmSupplierAdapter、QnmSignUtils、QnmProtocolParser、QnmCallbackParser及对应测试；删除或隔离不可调用的旧customerid/timestamp/notifyurl分支，不提供旧协议容错解析。QNM类型只注册一次，连接器必须按新封闭模板重新校验；开发数据清理不由本设计自动执行。

### 5.3 SZ_CARD_EQUITY_V1

JSON POST`/api/apiCharge`、`/api/queryOrder`，Content-Type为application/json;charset=utf-8。创建字段accountNo←冻结目标、buyNum="1"、euserId←merchantAccount、euserOrderNo←billno、productCode←externalSkuCode、timestamp←毫秒字符串、callbackUrl←冻结URL、sign。查询仅euserId/euserOrderNo/timestamp/sign。

签名去sign及约定空值，按字段名排序**仅拼接值**，末尾md5Key，UTF-8 MD5小写；保留原值，不自行trim/casefold。String类型固定，拒绝JSON重复键；不把k=v工具用于该协议。

创建retCode=0受理，2未知；1按确认拒单码/证据判断，不泛化为安全切供。查询retCode=0再读orderStatus=success/fail/processing；retCode=3为客户单号不存在观察，单次不作为退款/重发证明，平台连续三次关单另依§4.5；其他状态未知或配置Case。

回调JSON字段euserOrderNo/orderNo/orderStatus/timestamp/sign及可选resultMsg/cardInfo。验签包含实际合同指定字段，核双号，ACK纯文本`success`；resultMsg文本不作为任意失败分类规则。

附件支持逐单callbackUrl，卡管当前普通创建未传属于调查事实。用户于2026-10-17明确本期不考虑与cardshop共用账号；仅须在获授权的中台测试商户实测冻结的callbackUrl生效、原单定位/验签/ACK正确，不能验证则保持未发布。不再要求卡管旧单回调共存或旧入口分流，不修改cardshop账号/固定回调；供应商回调合同和C-08实机授权仍不可省略。

卡密后续为Base64→DES/ECB/PKCS5Padding；md5Key前8字符到字节的规则仍需固定向量。首期不解析/交付cardInfo、不开ISSUE_ASSET；意外收到资产不忽略其可能交付事实，保留受保护观察转核实，不能失败释放。

## 6. 回调、Inbox与安全接口（FR-10～FR-12/21）

拟定三条精确路由：

```text
POST /callbacks/suppliers/renyue/{connectorVersionId}   form → success
POST /callbacks/suppliers/qnm-userkey/{connectorVersionId} form → OK
POST /callbacks/suppliers/sz-card/{connectorVersionId}  JSON → success
```

connectorVersionId是定位提示，不是认证凭证；固定路由到代码解析器，禁止任意adapterType动态路由。网关/服务精确POST免登录，不放行整个callbacks/**或管理接口。沿现有回调路由转发规则接入，并分别跑配置/权限合同测试。

正文建议最大1MiB，响应读取最大1MiB、解析深度16；超限拒绝，数字/字段长度按合同验证。只有仁跃供应商确认的回调格式允许相应form或JSON兼容，不“自动猜格式”。原正文只用于认证/受保护事实，不进入普通访问日志。

顺序：有限候选定位→核已存在原单与冻结身份→事务外解析秘密和认证→可信成功先按§7.4落保护观察→Inbox锁→既有聚合锁→同一结果事务→提交→ACK。最终ACK不包CommonResult，不附换行/BOM。提交失败/认证失败不成功ACK；建议认证/格式失败4xx，暂时数据库/材料不可用503，具体失败响应与供应商重投合同由C-03确认。

事件身份为固定顺序、长度无歧义的canonical JSON之SHA-256，字段为adapterType/supplierId/merchantAccount/billno/externalOrderNo/标准状态。消费名称固定为SINGLE_STEP_SUPPLIER_CALLBACK，HMAC用途独立。

payloadDigest为原认证业务事实规范化后的HMAC；覆盖数量、目标身份、实报金额和资产观察等会影响推进的字段，排除每次重投可能变化的传输timestamp/sign及无业务含义message。若传输字段确属协议业务语义则不能排除，需合同向量固定。三家没有华昇nonce，不用nonce算法；仁跃TimeStamp未签，不把其300秒窗口当可靠重放认证。

- 相同事件/同摘要且已处理：返回原ACK，不再推进。
- 相同事件/异业务摘要：持久冲突，禁止动账；不能静默覆盖或将其当普通重复。
- 相反终态：独立事件，但聚合拒绝回退，记录Case。已可靠接管冲突后可ACK防风暴；未持久接管不ACK，不能为避免重投吞证据。
- 外部单号首次由可信回调/查询绑定，随后不同单号冲突；本地相同billno不足以跳过商户与版本认证。
- 已认证的人工/未知事实可靠留证与调度后可ACK；未知状态不能被误ACK为业务成功。

并发首次Inbox插入遇唯一冲突时回滚本次claim事务，再当前读重试比较摘要；重试只限数据库幂等争用，不重新发购买。Inbox成功标记与订单/资金/通知同事务，ACK丢失重投后仍可幂等返回。

## 7. 状态、资金与异常收敛（FR-08/12～FR-16）

### 7.1 状态转换

复用SupplierOrderOperationStatus的PENDING/CLAIMED/EXECUTING/RESULT_UNKNOWN/SUCCEEDED/FAILED/MANUAL，不新增“查询失败等于订单失败”状态。

```diagram
┌─────────┐   唯一创建资格   ┌───────────┐
│ PENDING │───────────────▶│ EXECUTING │
└─────────┘                └────┬──────┘
                受理/未知/过期  │
                     ┌────────▼───────┐
                     │ PROCESSING/未知 │  BOrder处理中
                     └────────┬───────┘
                              │ 原单查询 / 认证回调
             ┌────────────────┼────────────────┐
             ▼                ▼                ▼
       ┌───────────┐    ┌───────────┐    ┌───────────┐
       │ SUCCEEDED │    │ FAILED    │    │ MANUAL    │
       │ 一次提交   │    │ 一次释放   │    │ 保留预留   │
       └───────────┘    └───────────┘    └───────────┘
```

图中PROCESSING是BOrder语义，不新增Operation枚举。受理观察可保留Operation EXECUTING并清本次lease、持久nextRecoverAt；未知用RESULT_UNKNOWN。各入口在锁后统一判断，不把outcomeClass单独当成可信资金结果。

### 7.2 结果决策矩阵

**未知结果策略已确认（2026-10-16）**：除§4.5限定的三次查无单平台风险关单例外外，超时、暂时NOT_FOUND、验签失败或无法解析等结果不明时，原预留不提交也不释放，只核原商户/原订单号，不重新下单或切供。达到C-04约定期限/异常预算后停止自动查询并转Case，转人工不自动释放；无冲突的迟到可信回调仍可按原单收敛。用户已接受额度可能较长时间占用的代价，后续明确持续低频补查覆盖次日全天，日历截止和预警/停查区别见§4.4；正常明确受理后首次等待为仁跃600秒、QNM/SZ_CARD180秒，三家创建请求超时事实持久化后180秒首次补查，后续正常间隔600秒，有效NOT_FOUND持久化后间隔180秒。QUERY网络超时/连接失败及响应解析失败/业务状态未识别均从对应事实持久化起固定600秒再查，并清零连续NOT_FOUND计数；验签失败、鉴权失败、身份冲突优先于普通补查分支，不能借解析异常绕过。CREATE未知回执/租约过期、QUERY租约过期已按§4.4确认；每原单1000次成功QUERY认领上限亦已确认，到限仍未知转Case保留预留，不丢弃最后合法结果，不因NOT_FOUND连续计数清零重置总预算。其它安全处置/资源合同仍待确认，不能把超时规则无条件套用。金额缺失/差异策略已另行确认，见§7.3。

- 可靠ACCEPTED/处理中：BOrder保持PROCESSING，预留不动，调度原单查询。
- 可靠SUCCESS：身份/数量/合同正确，无相反终态；完成直充平台交付，Operation/BOrder/Request/总单及COMMIT/Outbox同事务。无资产要求，不调用commitHuashengSuccess。
- 可靠零交付FAILED：无已知成功、资产/意外交付、已扣款或终态冲突；同事务失败、RELEASE剩余预留、Case/通知。不按供价计算退款，不启动新供应商尝试。
- NOT_FOUND：单次保持未知，结果持久化后180秒再查；仅§4.5候选连续3次有效结果且共同检查通过时平台失败并一次RELEASE，曾受理/存在者永久排除，到期未收敛转Case保留资金；不CREATE、不切供。
- 坏响应、HTTP失败、超时、验签异常或未识别业务码：未知→安全核实原单或Case，到限MANUAL，不因这些结果RELEASE、不CREATE、不切供，不把它们当成有效NOT_FOUND计数。
- 任一身份/数量/价格格式/历史事实矛盾：受保护留证并Case；价格**数值差异**独立MISMATCH，不自动等于履约失败。

“供应商无副作用”证明要求覆盖全部历史尝试。三家首期没有华昇四次7014合同；§4.5由用户明确接受风险的平台三次查无单例外，不冒充该供应商证明，也不改变华昇。除该限定例外外，具体创建拒绝码仍需C-02确认，未确认的非成功码不得自动释放。

### 7.3 资金与结算

**C-05追加风险关单例外（2026-10-16）**：用户已确认§4.5限定候选的连续三次有效NOT_FOUND可平台失败、释放原剩余预留，并接受迟到充值成功损失；这不是供应商保证零交付。下述未知保留规则须与该例外一起理解；QUERY网络异常、响应解析失败或业务状态未识别均清零连续计数、保留预留，并从对应事实持久化起固定600秒再查。其它恢复合同仍依C-04确认，不以计数或间隔规则确认宣称自动释放已实现。

**C-05基本资金与映射决策已确认（2026-10-16）**：平台受理按本单采购方结算价预留，供应商仅受理/处理中不确认扣款；可信充值成功一次提交原预留，不新增扣款；可信零交付失败且无未决扣款/成功冲突时释放原剩余预留，重复事实不重复动账。例如采购方结算1000分、预期供价884分，原预留及正常成功确认均为1000分，全败释放原1000分，不以884分计算采购方退款。本条不规定供应商商户余额扣费时点；未知/人工保留及风险关单例外见§7.2/4.5，实报缺失/差异见下文。三家采用§2.2代码提供并在发布时校验的封闭状态/错误模板，不能自由配置扣款、释放、重购或切供；新状态须确认合同、修改代码/测试并发布版本，未识别状态仍未知。华昇专属判据不变；逐家供应商可信失败、查询预算及剩余异常分支恢复合同仍依C-02/C-04，决策确认不等于实现验收。

复用FundReservationService.commitByReference/releaseByReference和原request.fundsBuyerId（已冻结项目结算方），不取当前采购商或项目新账户，不访问账户/账本DAL。共同安全检查核预留所属原单、committed/released/remaining及已交付观察；CAS失败整笔回滚。

**履约与成本核对分离已确认（2026-10-16）**：可信充值成功照常确认采购方原预留，成本实报缺失为PENDING、有效实报相等为MATCHED、有效差异为MISMATCH，不伪造实报或自动调账。例如预期884分、采购方结算1000分，实报900分或1100分均不自动改变采购方1000分结算，也不把已成功充值改为失败；保留差异待核实。仅在认证/身份及金额格式/单位合法时适用，非法金额、验签失败等仍按未知核实。

新受控单步入口按permit及协议事实验证后调用SupplierOrderLifecycle内部现有公共状态/资金职责；华昇卡密分支不因此改变，QNM不再分派卡密。针对新路径的结算处理：

- 实报为空：reported/confirmed保持空，settlementStatus=PENDING；请求AgreedPrice不能自动作为实报。
- 有效实报=冻结预期：reported/confirmed取实报，MATCHED。
- 有效实报≠预期：reported取实报，confirmed不补预期，MISMATCH；可信直充成功可以完成采购交付/扣款，差异另审，不自动调账。
- 非法金额：不是普通差异，未知/人工核实。

拟把已存在的正确金额观察职责在SupplierOrderLifecycle内部收敛成共享逻辑供新入口与华昇已有正确分支使用；不修复QNM开发期历史卡密订单，也不以新入口绕过锁、通知或预留核验。

### 7.4 成功观察与本地失败

可信远端SUCCESS先在独立短事务中按原绑定锁单、把受保护成功观察保存到检查点，再进入订单/资金结果事务；不能仅在资金事务失败后补写，因为其间另一工作者可能按失败释放。该观察不是平台成功或Inbox已消费标记，不提前通知采购方；所有失败释放入口在同一原单锁下检查它，存在则阻止释放并核实冲突。既有失败终态不被新成功观察自动翻转。

本地结果事务异常只能保留未决并调度核实，不能改成供应商FAILED；已提交的成功观察不随其回滚。独立留证失败则不尝试资金推进，返回非成功ACK/任务失败并告警，由持久watchdog继续只读核实；不宣称能在数据库不可用时持久化事实。后续可靠成功完成原单本地提交，不创建第二笔预留/采购；MANUAL仅停止自动查询，认证迟到回调仍可经原绑定/原单锁处理，无相反终态时才收敛。

### 7.5 人工Case

新增集中原因拟为SINGLE_STEP_PREPARATION_FAILED、RECOVERY_EXHAUSTED、CREDENTIAL_DRIFT、PROTOCOL_INVALID、TERMINAL_CONFLICT、LOCAL_RESULT_COMMIT_FAILED，另保留供应商原码。原因不是自由文案动作规则，正式码表与DDL/DTO保持同义。

Case只提供定位、原单观察、结算和资金摘要；现有华昇人工失败确认命令仍按其适配器约束运行，不开放给新三家。若业务需要三家人工核实/失败确认按钮，另立合同并授权，不能在本首期套用华昇判据强制释放。

### 7.6 T04公共终态与资金切片（2026-10-18）

本批按用户批准的顺序实现成功保护、一次资金收敛、三次NOT_FOUND风险关单。只使用显式启用单步的隔离适配器；回调HTTP/Inbox、三家协议接线和商户共享限额仍是后续门禁，不引入通知渠道、共享库写入或真实资金操作。

1. **事务边界**：事务外Runner统一结果入口先调用独立REQUIRES_NEW成功观察事务，提交后再进入原单结果事务。观察事务核原SEND绑定、billno、目标摘要、数量、认证和外部单号；只对合法SUCCESS保存首份完整标准事实及其generation，不依赖本地资金提交成功。独立留证失败直接向任务入口传播，不继续动账。普通生命周期入口拒绝显式单步类型，不能绕过其受保护观察与资金检查。直接调用单步结果事务但缺成功保护证据也不能完成成功。
2. **认证运行信封V3**：保留V1冻结内容和V2运行进度，增加可空的successEvidence与terminalEvidence，证据含原绑定、来源动作、generation、双号、目标摘要/数量、接收时间、响应摘要、合法实报金额和可信零交付声明。V1/V2按原AAD认证读取后只读提升为V3空证据，不伪造旧成功证明；V3采用独立版本AAD，拒绝降级。运行进度更新始终携带原证据，不因未知、看门狗或计数清零丢失成功保护；无新增表列。标准事实不保存手机号、原始报文或秘密。协议事实仍禁止通用JSON输出，仅Codec私有Mapper可编码进认证密文，不修改全局序列化保护。
3. **成功与本地故障**：成功证据先持久化trustedExists永久保护，不改变当前generation/lease/补查到期点。结果事务锁后使用已保护的原成功事实完成状态和资金；旧代可信成功可按原单恢复，不授予旧发送资格。首份保护证据中的非空外部单号也参与后续身份校验，即使BOrder尚未写入该号；冲突保留首证据转Case，重复事实不得抹掉安全冲突。结果回滚保留成功证据，持久watchdog与QUERY任务优先重试本地收敛而不再发HTTP。已有FAILED后的迟到成功只保护留证并开冲突Case，不翻转、不补扣；已成功的重复事实不重复动账。
4. **订单资金所有权**：单步Lifecycle核完整原单与证据后，在MANDATORY事务内委派SupplierOrderLifecycle既有订单/状态日志/资金职责；复用FundReservationService锁原request.fundsBuyerId与CARD_ORDER/requestNo，检查预留仍为完整RESERVED、总额等于总单冻结采购结算额、committed/released为0且remaining等于total。成功仅COMMIT原预留，可信零交付失败仅RELEASE；普通FAILED观察若无明确零交付声明则转Case保留。存在资产/意外交付、历史相反终态、已扣款或资金异常时，拒绝自动动账。结算金额完全按§7.3处理，旧QNM/华昇语义不改。
5. **原子结果事件**：终态与订单状态日志、COMMIT/RELEASE及一条既有SUPPLY_CHAIN_ORDER_CHANGED本地Outbox同事务，版本使用本次总单detailVersion，载荷只含原单编号与标准状态。当前本地派发器仅记录事件，并无采购方RPC投递实现；本批不将Outbox持久化宣称为采购方已收到通知，也不接运营通知渠道。
6. **三次查无单**：仅当前有效QUERY代的独立、已认证NOT_FOUND参与连续计数。无历史可信存在/成功、无交付或资金冲突、未到日历截止时，第3次与平台稳定原因SINGLE_STEP_NOT_FOUND_EXHAUSTED、终态证据、最终失败及一次RELEASE共同提交；前两次及永久退出候选仍按180秒补查。最后一次合法QUERY结果不因累计数已达1000而丢弃；日历截止先到则Case保留。原始第三次事实仍是NOT_FOUND，不篡改成供应商零交付证明。
7. **验收**：真实MySQL通过公开受理/Runner及原单结果入口验证1000/884非对称金额、实报缺失/884/900/1100分、原结算方冻结、重复与相反终态、成功保护后资金或Outbox失败回滚、重开容器后本地补交、第三次查无单与成功并发及已释放后迟到成功。验收核实际订单/账户/账本/事件/受保护检查点，不以mock次数或仅异常返回代替。

## 8. 商品、前端与发布操作（FR-03/17/21/22）

迁移清单至少记录channel、cardshopProductId、候选platformSku、externalSkuCode、fulfillmentType、targetSchema、原供价/单位、转换分值、审核结果/原因、审核人、清单版本、目标Offer编号。清单不含账号密钥、真实充值目标或机构售价替代成本。

默认隔离SZ_CARD外部号0的4条、100026测试品及1分成本、100755目标冲突和疑似卡密；名字含“卡”或coupon_type=url/codekey不能证明履约方式。RENYUE/QNM外部号唯一仍须验证可售性。用base_price作为供价审核来源，parentPrice别名不意味着父商品售价。

经批准记录通过现有Supplier/Connector/Offer管理写入口建草稿，发布仍受唯一外部号、目标、能力、固定数量、供价和乐观锁保护；重复执行先按追溯清单查已建Offer，不重复上架，不直接批量写生产表。

前端目录固定`/Users/mac/codespace/supplychainhubweb`：ConnectorFormDialog、CredentialItemDialog及supplier/manage/detail/utils/fulfillment.ts复用动态Metadata；必要时添加只写多行私钥、封闭模板解释、未知/结算差异说明。DTO新增可选字段不改原枚举语义。控制台/访问日志不输出秘密。

发布先代码/接线但无新Offer，再受控配置、回调/出口实测、商品白名单与小流量；每家独立验收。回滚下架新Offer、停新流量，保留旧版本/密钥/回调/Timer；在途清账前不撤回支持新检查点的制品。共享写入、实际交易、部署仍须独立授权。

## 9. 安全、日志与性能（FR-20、NFR-01～NFR-07）

HTTP使用现有OkHttp设施，显式关闭retryOnConnectionFailure和followRedirects等透明重放；host/port/path取冻结白名单合同，QNM9001需出口核实。请求/响应限长、完整调用预算和有限并发；不为本项目新增不限线程池或远端调用时持有数据库连接。

通过发布期SupplierEndpointPolicy不能省略运行期DNS/出口验证；域名不是可任意私网跳转的授权。共用MDC装饰/上下文恢复规则，独立Timer执行有自己的traceId/executionId，不伪造历史来源。

实际物理外呼记录一个request和一个response/failure，callId、attempt、目标动作及elapsedMs为数值；认证失败、业务拒绝与系统失败区别级别，传播链只一个边界记堆栈。提交后才打业务成功，日志丢失不推断未发送。

不要记录userkey/md5Key/MerchantKey、私钥、含密钥规范串、完整回执或充值目标；现有保护策略不扩大。结构化字段必须检查实际encoder JSON，不以message里key=value替代；ELK关闭的完整根配置也需回归。

容量验证按SRS NFR-04建议目标：隔离1000未决原单/10并发触发，测短事务P95、任务积压、锁争用/连接池、回调与查询竞态及bounded body。供应商响应延迟单列，容量/超时值C-04未确认不承诺生产SLA。

## 10. 自动化、实机验收与源码证据（NFR-08）

### 10.1 测试矩阵

Test覆盖纯状态/金额/预算/目标；ContractTest通过公开Adapter/回调HTTP验证各家签名、认证和ACK；IntegrationTest用真实MySQL/Liquibase/Spring/锁/事务；ArchitectureTest覆盖所有权与管理权限。供应商向量独立于本实现自签自验，外呼传输可用MockWebServer验证真实编码，不冒充供应商沙箱。

必须区分的输入：QNM100/100.00/"100"与0.001；三家签名大小写/值拼接差异；含+&=的表单；仁跃请求/回执/回调不同字段集合；错误商户/双号/目标；创建超时与真正失败；SZ_CARD retCode3与fail；同事件相反业务金额；合法MOBILE与被拒绝的EXTERNAL_ACCOUNT；旧代permit与新代查询。

真实MySQL须验证：两个工作者一次CREATE、QUERY与callback并发一次动账、认领提交后宕机、早回调/晚受理、Inbox唯一冲突重试、资金/Outbox写入失败原子回滚、可信成功留证后阻止失败释放、退役版本与轮换、手机号频控恢复与原单Timer竞争及完整建库/重启。开发期历史业务数据保留不作为本轮条件，见§10.4。不能以mock调用次数替代实际账本状态。

### 10.2 现有测试扩展与新增合同

优先扩展ConnectorPublicationIntegrationTest、ConnectorCredentialSetIntegrationTest、FundReservationIntegrationTest、SupplyChainLiquibaseIntegrationTest、RechargeFrequencyExecution/Timer/LifecycleIntegrationTest及现有Qnm/Huasheng公开接口测试；新增三家协议ContractTest和SingleStepRecovery/CallbackIntegrationTest检查新合同，不逐私有函数加测试。

Maven命令、前端命令、任务编号及验收关系见开发计划§4～§6。失败前证据、最后绿色批次与没执行的真实联调分别记录docs/集成测试.md；2026-10-15基线完整门禁失败，收口后保留两项延期错误，见开发计划§7。2026-10-16 T01公共钩子完成RED/GREEN及供应链全量单测/架构、20项定向集成和30项合同回归，见开发计划§8；三家完整功能、真实供应商联调和上线验收仍未执行。

### 10.3 卡管协议源码定位

卡管Java前缀为`/Users/mac/codespace/cardshop/cardShop-admin/src/main/java/com/ruoyi/web/mc/`：

- api/service/renyue/RenyueCrypto.java：canonicalize、buildCreateReceiptPlaintext、buildQueryReceiptPlaintext、decryptReceipt；RenyueSupplierTradeServiceImpl.java：创建/查单；RenyueCallbackService.java：双号/目标和回执。
- api/utils/QnmZcApiUtil.java：Send/Query、generateSign及verifySignature；api/service/impl/QnmSupplierTradeServiceImpl.java：ResContent与sumPrice元。
- api/utils/SzCardUtils.java、MD5Sign.java、DESDecryptUtil.java及api/service/impl/SzCardSupplierTradeServiceImpl.java：JSON、值签名、DES和结果。

这些源码仅作为证据，不复用mc_order写入、MQ、退款、原始敏感日志或失败时仍返回成功的行为。协议正式冻结仍依SRS C-02/C-03，不把历史实现注释当供应商永久保证。

### 10.4 T00基线收口修订（2026-10-16）

用户要求暂缓`ChannelOrderNumberIntegrationTest`的到店首次创建与同键并发两项错误，其余按基线收口顺序处理。只延后这两项，不删除、禁用或降低断言；完整门禁仍须如实报告它们。当前不修改到店订单与资金业务代码。

用户进一步确认：中台当前全部为开发测试数据，本轮不要求保留历史订单或核实记录。撤掉本轮新增的历史Case数据保留夹具，不新增旧数据兼容、排空或恢复迁移工作；既有022时间语义回归保持。该确认不等于删库授权，不影响卡管生产数据，也不减免新方案上线后新单的幂等、资金与持久恢复要求。

1. **迁移基线**：逐项核对065号段、066异常命令审计与067结算方迁移。现有主清单69个changeSet、129张业务表，时间列由063时304列加065的3列及066的4列组成，共311列。本轮验空库完整建库、当前字段/约束、时间精度/时区及重复启动，不以历史业务数据保留作为验收条件。066四列时间注释缺少Asia/Shanghai，须先用完整时间列门禁复现；确认后只追加068修正注释，不改066校验和、不移动时间值、不修改类型及可空性。这是最小元数据修复，不是历史业务数据迁移方案。追加后changeSet为70，业务表和时间列数量不变；不运行共享库迁移。
2. **Outbox异步门禁**：`dispatch`中的计数发生在`publish`短事务之前，计数为1不代表数据库已经提交PUBLISHED。测试应有界等待真实数据库终态，并继续核恰一次派发、发布时间与租约释放；不增加固定sleep或放宽状态，不修改生产投递顺序。
3. **鉴权完整应用门禁**：订单编号装配新增了必需的RedissonClient；nonce仍使用MySQL。沿用标准项目HTTP门禁的真实隔离Redis夹具，显式提供测试客户端和发号env/scope，保持生产缺Redis失败关闭。启动失败、正常结束均关闭客户端/容器并清零测试主密钥，不伪造认证链或关闭发号配置来绕过装配。启动恢复后暴露的停用能力查单旧断言，按`2026-10-12-project-standard-product-capability-design.md`§1/3的已定合同修正：已开通再停用仅允许摘要查单，用合法正文到达订单查询并消费nonce；创建/资产仍拒绝且不写nonce，从未开通、应用停用、凭证吊销仍拒绝。不得为使旧测试通过而收回历史查单权。
4. **前端基线**：在实际前端目录完成全量Node、类型及构建；旧测试若引用已移除文件，应改为验证当前真实组件的公开保存行为，不恢复废弃生产模块。既有跨模块类型错误逐项登记，不静默关闭检查或以构建成功代替类型通过。
5. **合同与实现分开**：C-01已存在quantity=1和EXTERNAL_ACCOUNT基础，max大于1并不使交易突破单份限制；min大于1导致供给不适用，应与三家发布配置规范区分。C-05现有未知保留与华昇结算语义可复用，但普通成功金额及三家封闭模板仍属后续实现。逐家协议、商品、订单号及中台回调必须取得证据；2026-10-17用户已排除与cardshop共用账号，共享账号共存不再作为门禁，不回写为早期已验收。不得把本次基线修复当作供应商事实确认或实机授权。
