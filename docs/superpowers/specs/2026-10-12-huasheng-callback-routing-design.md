# 华昇测试环境公网回调路由设计

## 1. 范围与已授权目标

用户要求核查已新增的 Nacos 公网回调基础地址，并补齐 Nginx → 网关 → 供应链服务。仅修改 supplyhub 测试环境及对应仓库配置；不修改凭据、订单、资金、其他供应商或生产环境。实际验收记录维护 `docs/集成测试.md`。

当前链路：公网 `https://supplyhubtest.qihaofu.cn` → pro197 Nginx → pro143 网关 `172.19.64.105:59080` → 同机独立供应链 `127.0.0.1:58190`。供应链回调控制器已经注册，拒绝无签名空体 POST 时返回 HTTP 400 `Invalid callback`。

## 2. 回调地址与生效核实

供应链 Nacos namespace `aifuli-supplyhub-test`、group `test`、Data ID `supply-chain-server-test.yaml` 中的 `aifuli.supply-chain.fulfillment.public-callback-base-url` 应为 `https://supplyhubtest.qihaofu.cn`。检查当前容器启动时间、实际加载快照与制品中的配置绑定前缀；地址与 `/callbacks/suppliers/huasheng/{connectorVersionId}` 拼接。当前发布连接器版本 ID 4，完整回调地址为 `https://supplyhubtest.qihaofu.cn/callbacks/suppliers/huasheng/4`。

配置快照证据确认地址已加载，不替代实际订单冻结 notifyUrl 的业务验收。不要把 cardShop 的 notify-url 复制到中台。

## 3. 精确路由与透传

Nginx HTTPS vhost 新增 `location ^~ /callbacks/suppliers/huasheng/`，转发到现有 supplyhub 网关 `172.19.64.105:59080`，不改 URI、请求正文或 Appid/X-Sign/trace-id 头；维持 Host、X-Real-IP、X-Forwarded-For、X-Forwarded-Proto，关闭错误响应拦截，使供应链 400/404/409/503/500 保持原样。回调请求体上限 1MiB，与现有解析上限一致。

网关新增路由 `supply-chain-huasheng-callback`，谓词 `Path=/callbacks/suppliers/huasheng/{connectorVersionId}` 且 `Method=POST`，URI 复用 `${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}`。不添加 RewritePath、StripPrefix 或登录鉴权前置，不改变控制器真实验签、防重及资产资金闭环逻辑，不开放整个 `/callbacks/**`。

仓库默认路由与 Nginx 模板同步更新。当前网关运行制品尚不含该路由，优先在其现有 Nacos Data ID `gateway-server-test.yaml` 动态追加持久配置。Spring 的 routes 列表采用覆盖语义：若原 Nacos 没有 routes，需完整保留实际运行制品的原路由列表后再追加，禁止只写新路由而丢失 system/infra/member/supply-chain 等原路由。禁止使用仅驻留内存的 actuator 路由作为交付。发布前读回核对原配置无并发变动；动态刷新未生效时才评估仅重启测试网关。

## 4. 发布、验证与回滚

修改前保存受保护的 Nacos 原文和远端 Nginx vhost；备份可能含敏感配置，不进 Git。Nacos 修改前后核对除 routes 外原配置语义保持一致，追加后原路由 id/目标/谓词/过滤器保持一致。

Nginx 仅向现有站点插入该 location，保存时间戳备份，执行整个根配置 `nginx -t`；失败恢复原 vhost，通过后平滑 reload。不替换其他站点，不重启 Nginx。

先验证网关 POST 已从业务码 404 转为 HTTP 400 `Invalid callback`，再验证公网相同无 Appid、无签名空体请求得到相同拒绝；该诊断请求不能生成有效供应商回调或推进订单。核对 trace-id 透传，并回归管理端供应链/系统/基础设施/会员原路由及健康检查。现有回调敏感日志保护测试适用，复用已有测试，不新增与配置实现重复的测试。

已签名回调及实际订单闭环在另行授权的测试交易中验证，不以无签名拒绝冒充业务成功。必要时恢复 Nacos 原文及 Nginx 备份后 reload，记录回滚结果。
