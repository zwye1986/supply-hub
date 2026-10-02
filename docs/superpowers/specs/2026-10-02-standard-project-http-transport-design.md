# 标准项目HTTP接入、原始字节透传与敏感日志保护

日期：2026-10-02。状态：Task1—4基础上的最终接入细化，待核心通过两级审查后实施。

上位`2026-09-30-project-signed-standard-order-http-design.md` §2.1、§3、§5、§9。本文落实标准三个HTTPS POST契约，不新增对外报价/回调/资产管理API。真实测试环境、菜单、登录页面和实际供应商联调仍暂缓；本地真实应用/数据库及本地供应商模拟服务自动化继续完成。

## 1. Controller与严格请求解析

三个生产映射位于`interfaceapi.open.project`，精确使用ProjectAuthOperations注册路径，POST、consumes=application/json。Controller先取得对应`requireStandardProductContext`，调用专用严格解析器生成封闭DTO，再委派项目Facade；不能直接读DAL、调用供应商、读取普通project/user header或租户上下文。接口不接受业务查询参数，非空query string报安全参数错误，不将其计入业务命令。

使用`@RequestBody byte[]`读取已经认证的原始正文，专用局部JSON reader解析树并显式核验字段，避免全局Jackson的数值/字符串强制转换、未知字段忽略或XSS String deserializer改变已签名业务值。解析器保持标准DTO/Bean约束语义，不修改全局ObjectMapper。选择DTO专属deserializer时也必须证明同样严格与字节语义，不能只加`ignoreUnknown=false`误以为覆盖全局宽松配置。

- body必须是一个JSON对象；重复键（含target）、尾随第二对象/非法字符、unknown字段拒绝，不接受数组/null/空正文。
- create准确字段集合buyerOrderNo/buyerSkuCode/quantity/target/可选previousReceiptVersion。前三身份字符串非空、最大长度按主设计；target平铺且值仅字符串，最终Schema准确字段由共享目标校验决定。已删除的buyerUserId、limitMobile、listingVersion、pricingQuoteToken、paidAt、paidAmountCents及调用方身份字段均unknown。
- quantity必须JSON整数且精确等于1，字符串、1.0/指数小数形式、null/0/负值/溢出/大于1均拒绝。previousReceiptVersion必须可容纳Long的正整数，同样不做字符串或浮点coercion。
- query/assets body只接受buyerOrderNo字符串；不接受requestNo/orderNo/scope/user等替代字段。

参数错误使用稳定CommonResult和安全固定文案，不回显JSON、字段值或parser异常message；cause保留用于安全堆栈。目标规范化/加密/业务拒绝沿共享受理，DTO不添加手机号或价格参数。资产响应只能经成功独立审计后写出。

HMAC仍绑定收到的body原字节，格式变化需重新签名。缓存正文Wrapper供Controller完整重复读取，签名过滤器不允许默认登入身份补上下文。验证从Gateway到真实MVC链的目标值确为已签名输入，不能被通用日志或XSS读取/改写。

## 2. 供应链独立进程安全与缓存

server application.yaml permit-all只增加注册的三个精确路径，原INSTORE四组保持。不放行整个open-api或orders/**；受保护orders前缀未知路径由认证链拒绝，没有业务Controller授权。独立供应链进程的Spring Security与HMAC分工不变，没有商城聚合或其它模块表访问。

标准orders根及子路径在HMAC入口读正文前设置Cache-Control:no-store，成功/参数/业务/鉴权/系统错误都保留；这比只在资产Controller成功后设置更早，避免认证拒绝漏header。共享Web日志保护也应具备前置no-store，不能依赖日志功能开启才安全。Gateway标准异常同样no-store。普通路径缓存/安全合同不改变。

## 3. Gateway精确路由与不消费正文

实际`aifuli-gateway/src/main/resources/application.yaml`新增唯一route id，URI沿现有`${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}`，Path列三精确完整路径且Method=POST，无RewritePath。既有admin route存在重复id，不在本需求顺手重构，验证新route实际生效及原路径兼容。

AccessLogFilter对orders根/子路径在任何bodyToMono、codec、CachedBodyOutputMessage、response decorator或buffer操作之前进入专门分支；直接`chain.filter(exchange)`透传原请求/响应流，只在完成/异常时采集method、注册操作路径类别、route、状态、耗时等安全元数据。不能仅不写日志对象却仍把请求转String重编码，不能仍buffer完整响应卡密。实际DataBuffer流和Content-Length/Content-Type/签名头对下游保持原值。

标准日志对象不缓存request/response正文、query、签名或任意头原值；保留安全固定占位。日志URL只记录注册精确路径，未知子路径记录orders/[protected]，不能把夹带账号的路径/URI/query写入。高频成功可debug或沿采样，失败类别/数量受控，不逐次完整堆栈。普通路由沿原访问日志行为，原开放商城保护保留。

Gateway GlobalExceptionHandler对标准前缀使用稳定错误文案和安全类型/脱敏cause/suppressed/栈位置；不记录request.getURI原串或ex.reason/message，也不把reason回显响应。响应已提交时保持原因传播，不能重新发送成功/部分资产。网关日志自身失败不覆盖业务结果，安全记录失败数量；不引入Servlet Web starter到Gateway仅为日志工具，不改进程形态。

## 4. 共享Web保护与影响范围

ApiAccessLogPathUtils新增标准orders的精确路径段分类（支持正确context path剥离）；orders根和子路径均保护，相似前缀不是标准接口。增加明确“需安全异常处理”的判断，仅包含原限频管理和新标准前缀，不借此扩大其他模块行为。该工具类位于aifuli-common（包`com.aifuli.framework.common.util.http`）：Servlet栈（starter-web访问日志/异常处理）与Reactive栈（gateway访问日志/异常处理）共享同一份路径分类，不得引入Servlet/Reactive类型依赖。common不能依赖供应链模块，标准orders根路径常量与`ProjectAuthOperations`登记路径存在双侧同步义务，由`ProjectAuthOperationsContractTest`以契约断言锁定一致；GlobalExceptionHandler以统一的敏感路径分类（标准订单/限频/普通）收敛参数错误、HTTP错误与业务拒绝三套策略骨架。

ApiAccessLogFilter在读取参数/ServletUtils.getBody前识别新路径，正文及query均不读作日志；即使Controller显式responseEnable=true也强制省略标准响应，不能把明文卡券写平台访问日志。标准日志URL安全固定类别，CommonResult参数/系统错误msg安全。敏感例外路径的日志API故障复用受控计数/采样及安全堆栈，返回已确定业务结果；这不同于业务资产审计，后者必须失败关闭。

ApiAccessLogInterceptor正文/参数省略；GlobalExceptionHandler对标准参数绑定/JSON/校验/ServiceException/SQL/解密/系统故障的外层响应与平台错误日志均不包含原始body/目标/卡密/完整凭证/异常message。错误日志URL固定标准保护标识、requestParams={}，保留异常类别/因果/堆栈代码位置，持续错误日志API故障防风暴。原限频管理安全处理不退化，普通路径原异常语义保持。

重要日志与注释对应本文件章节，禁止在高频处理中吞掉业务异常；平台日志失败兜底保留安全cause/suppressed与数量。安全采样不能拿原异常当Throwable直接交给Logger导致message泄漏。

## 5. 自动化HTTP闭环与完成判定

以实际SupplyChainServerApplication、真实MySQL/Liquibase/Mapper/事务、真实HMAC/app凭证/nonce与生产Controller执行；本地HTTP供应商模拟服务只能模拟外部供应商，不能用测试Controller/Mock Facade模拟本阶段三个业务接口。报价通过真实内部报价入口/服务先冻结，生产受理和Handler产生真实订单/资产。

至少覆盖：

- 虚拟卡券内部报价→签名下单成功→新nonce同参重放stable requestId/orderNo、钱/quote/Outbox不重复→签名查单真实PROCESSING→未交付资产[]+hint→真实履约结果生产加密资产→资产查询对应封闭明文与独立审计、SUCCEEDED摘要/assetAvailable。
- 实际MOBILE直充内部报价→READY覆盖与同手机号SPU队列→签名下单/查单→本地模拟供应商可信终态→查单成功且assetAvailable=false→资产接口不支持提示。未READY不受理、不绕过。非手机号直充与卡券跳过每人限购仅内部备注，HTTP不泄漏。
- 同buyer两project同buyerOrderNo各下单/查询/资产、另一项目无同号返回统一不存在，合法同号不串；鉴权ACTIVE/SUSPENDED只有query、cap关闭/凭证吊销/轮换/nonce重放/body改动，普通头身份spoof无效。
- 严格字段/数量/目标/CAS/duplicateJSON/尾随/媒体类型/非法编码/查询参数矩阵，拒绝无受理副作用。异参业务号冲突、普通拒绝重放、同CAS并发只一次，已有accepted在Listing变动仍重放。
- 资产各种等待/manual/拒绝/终态/类型不支持/缺资产/解密/解析/审计故障均正确CommonResult、无部分明文、no-store；真实审计在异常回滚后存在。
- 请求/响应/各级普通日志与平台访问/错误DTO、日志API连续故障均无敏感正文；被拒的未知路径/query也不回显目标，普通路径和原INSTORE/限频回归。

Gateway本身独立编译并使用隔离classpath/本地实际进程探针验证AccessLogFilter的生产流处理、分块字节与响应不缓冲、异常安全和路由。不能把Gateway生产依赖加入供应链容器或用自写proxy代替真实Gateway。本地整链如使用单独Gateway进程，使用实际配置路由（Nacos测试开关可关闭），目标本地SC实际端口。probe/运行脚本可存script/test并纳入源码，原报告和源hash保存；不以静态grep配置代替真实传输验证。

网关探针`script/test/gateway-standard-orders-probe.sh`落实上述要求：不覆盖路由，使用application.yaml实际`supply-chain-standard-orders`路由（URI经`AIFULI_GATEWAY_SUPPLY_CHAIN_URI`指向本地挡板上游），验证chunked透传、响应不缓冲、访问日志脱敏、未注册兄弟路径404与上游故障异常安全（脱敏CommonResult且不泄漏正文）；报告与源hash（git HEAD + 网关jar sha256）默认保存于`script/test/gateway-standard-orders-probe-report.md`。目标为本地SC实际端口的完整整链联调在`docs/集成测试.md`人工联调章节跟踪，探针阶段以挡板上游隔离验证网关传输行为。

信封加密实现`AesGcmEnvelopeCrypto`移除`@Primary`：生产它是`EnvelopeCryptoPort`唯一实现，注解冗余，且会压制/冲突集成测试配置注册的替身实现（如`ProjectStandardQueryIntegrationSupport`的mock）；移除不影响生产装配。

最后串行运行完整供应链Test、独立API Contract、profile真实Integration/Contract和Arch全部门禁；共享Web/Gateway变更须有真实隔离行为探针与编译验证，SC之外单测依用户范围跳过，不跳SC。逐类新鲜XML计数与去重结果诚实记录，不能用早期通过的报告冒充最终HEAD。前端配套文案的实际构建验证单独记录，不将既有全仓类型错误隐藏为全绿。

三接口生产代码、数据库增量、请求/响应契约、资金/履约/资产审计链及上述自动化均通过，且独立规格、质量和最终全范围审查完成，才标记标准HTTP代码闭环完成。真实部署/页面/供应商验收继续低优先级待办未勾选，不以代码闭环宣称已上线。
