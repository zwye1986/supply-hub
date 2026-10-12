# 项目标准订单测试环境公网入口补齐

> **后续v2草案（2026-10-12，未实施）：** [平台SKU下单设计](2026-10-12-platform-sku-order-contract-design.md) §7／§9 拟配套三个精确v2入口。本文已生效的v1路径及透传边界保持；新增模板、pro197 Nginx和pro143 Gateway／供应链接线必须一起审查。此前v1发布授权不扩为本轮v2部署授权，当前未修改远端入口或配置。

## 1. 范围与已授权目标

用户在核心链路就绪分析后明确要求先解决“公网项目订单入口未转发”。本次只修改supplyhub测试站点的Nginx转发和对应仓库模板；不改变网关路由、鉴权契约、应用凭据、项目能力、Outbox/Timer开关、订单或资金，不处理其它主链缺口。

实际链路为`https://supplyhubtest.qihaofu.cn` → pro197 Nginx → pro143网关`172.19.64.105:59080` → 独立供应链`127.0.0.1:58190`。当前公网三个入口返回站点兜底文本；相同无签名请求直达网关返回JSON业务码1050001001“采购商调用身份无效”。pro197实际vhost没有open-api转发，网关已注册三个精确POST路径，修复点只在Nginx。

## 2. 精确路径与透传边界

HTTPS vhost新增一个首尾锚定的正则location：

```nginx
location ~ ^/open-api/supply-chain/project/v1/orders(/query|/assets/query)?$ {
    client_max_body_size 1m;
    proxy_pass http://172.19.64.105:59080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_intercept_errors off;
}
```

它只匹配`/open-api/supply-chain/project/v1/orders`、`/orders/query`、`/orders/assets/query`三个完整路径，不匹配末尾斜杠、兄弟路径或更深层级。不开放整个`/open-api/**`。相比三个重复location，共用一组相同代理指令；POST准入沿现有网关`Method=POST`，不在Nginx另建一套方法/鉴权规则，其它方法仍由网关拒绝。

`proxy_pass`不携带URI部分，禁止rewrite、追加admin-api前缀或重编码请求正文。HMAC请求头与`trace-id`沿Nginx默认请求头透传，不将认证信息置空，也不新增认证信息到日志；使用原有Host/代理来源头。请求体上限1MiB与`ProjectAppAuthProperties.HARD_MAX_REQUEST_BODY_BYTES`一致，不改其它location的上限。关闭错误拦截保持上游错误语义；不更改网关历史HTTP200包装业务拒绝的行为。

## 3. 发布与回滚

先更新本设计与测试栈设计§9，再修改仓库模板。远端当前vhost须与受检基线逐字一致，否则停止并重核，不能覆盖并发修改；应用前保存原vhost时间戳备份。只向该站点插入上述location，维持文件权限/属主，不操作其它站点、证书或Nacos。

执行完整根配置`/usr/local/nginx/sbin/nginx -t -c /usr/local/nginx/conf/nginx.conf`，通过后用同一根配置平滑reload，不重启Nginx或Java进程。语法失败恢复原文件，不reload；生效后验收失败需恢复备份、再次检查并reload。回滚备份路径与实际结果写入`docs/集成测试.md`。

## 4. 验证与完成边界

修改前后三个路径运行相同无认证`POST {}`探针：前者为HTTP200站点文本，后者须为JSON业务码1050001001，与直达网关一致，证明进入供应链鉴权边界。核响应`trace-id`与传入值一致；无签名请求不创建订单，不进行供应商交易。

负例覆盖三个路径的GET、兄弟路径、末尾斜杠和额外层级：GET应为网关未匹配拒绝，未注册路径保持原站点兜底，不进入新增转发。探测超过1MiB的无认证正文须在Nginx拒绝，不能传到业务服务。回归原华昇回调无签名400、管理端无登录拒绝、API文档路由、根路径兜底以及网关/供应链健康状态；不输出业务数据或秘密。

该变更为外层代理配置，复用真实公网/网关探针和完整根配置校验，不新增与location文本绑定的单元测试，不重跑无关Java模块测试。有效项目HMAC、商品准入、华昇履约和资金验收未执行时保持未验；本项完成仅解除公网入口阻断，不表示全链已跑通。
