# supply-hub 独立仓库与商城四服务裁剪设计

> 日期：2026-09-07
>
> 状态：已确认，待实施。工作分支 `dev-0812-sc-new-merge`。
>
> 前置：
> - `docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md`（测试栈已是 gateway/system/infra/member 四服务）
> - 现网 `aifuli-test` systemd 与商城进程仍以 `aifuli-mall-pro` 为发版源

把当前供应链后端从 `aifuli-mall-pro` **快照迁入** GitLab
`http://47.117.129.235/jierongjinfu/supply-hub`（不带 mall-pro 提交历史），并在新仓源码中移除 pay / erp / mall / statistics。
mall 指整个 `aifuli-module-mall`（含 `mall-server`、product、promotion、trade、statistics）。

## 1. 已确认决策

| 编号 | 决策 |
| --- | --- |
| d1 | **双仓并存。** `aifuli-mall-pro` 继续持有全量商城与现网 58080 发版脚本。本分支的删除提交**不得**推送到 `origin`（mall-pro）。 |
| d2 | 目标仓 `supply-hub` 目前只有 GitLab 初始 README（`main` = `d90ad79`）。裁剪通过后，用**无父提交的孤儿快照**覆盖推到 `supply-hub` 的 `main`（允许 force，仅覆盖空 README）。新仓看不到 mall-pro 的 commit SHA。 |
| d3 | **不**对 mall-pro 做 `git filter-repo` 或改写已推送历史。新仓瘦身靠 orphan，不靠改写旧仓。 |
| d4 | 从源码删除 `aifuli-module-pay`、`aifuli-module-erp`、`aifuli-module-mall` 整树。不单独保留 `trade-api`。 |
| d5 | member **不**回退到 Git init `3350311e`。按包去掉吾盛 / 开放商城 / `trade-api`；**保留**芋道会员内核，以及供应链已用的 `MemberProfileResolveApi`、`MemberSsoTokenApi`（含 `createTokenAfterSsoLogin`）。 |
| d6 | 迁入独立仓后，**收回** 09-04「gateway 公共 yaml 保留 mall/pay/erp/statistics 路由」：那是 mall-pro 与现网 systemd 共用源码时的约束。本仓不再构建 58080，应删除这四类路由与 Knife4j 分组。 |
| d7 | 本轮不删 bpm / crm / im / mp / report / `aifuli-server` 空壳；gateway 里这些未部署服务的路由可暂留。 |
| d8 | **不改** `supplychainhubweb`。本轮**不**对 pro143 再发版（Compose 已是四服务）。 |
| d9 | 本地 remote：`origin` 继续指向 mall-pro；新增 `supply-hub` 指向新仓。本轮不把 origin 改名。 |
| d10 | 已有 member 库中的平台/吾盛相关表本轮不 DROP，闲置即可。 |

## 2. 仓库与历史

```text
aifuli-mall-pro (origin)                supply-hub (新 remote)
  完整提交历史（含商城）                  仅 1 个根提交 = 裁剪后工作树
  继续发 58080 systemd                   只发 supplyhub 四服务 + 58190 供应链
  不接收本分支删除提交                     main ← force push orphan 快照
```

本地仍在 `dev-0812-sc-new-merge` 上做裁剪与编译（这条分支保留 mall-pro 历史，方便对照）。推送时**另建孤儿分支**，不要 `git push supply-hub dev-0812-sc-new-merge:main`。

命令形态（裁剪提交已在工作分支落地之后）：

```bash
git remote add supply-hub http://47.117.129.235/jierongjinfu/supply-hub.git
git checkout --orphan supply-hub-snapshot
git add -A
git commit -m "供应链独立仓初始快照：去掉 pay/erp/mall/statistics"
git push -u supply-hub HEAD:main --force
git checkout dev-0812-sc-new-merge
git branch -D supply-hub-snapshot
```

`--force` 只覆盖目标仓空 README。`checkout --orphan` 不要在 mall-pro 的 `main` / `dev-0812-sc-new` 上执行后误推 origin。

验收：克隆 `supply-hub` 后 `git rev-list --count HEAD` 为 1，且 `git log --format=%P -1` 父提交为空。

## 3. 模块边界

### 3.1 删除

| 路径 | 说明 |
| --- | --- |
| `aifuli-module-pay/` | api + server |
| `aifuli-module-erp/` | api + server |
| `aifuli-module-mall/` | mall-server、product、promotion、trade（含 api）、statistics |

根 `pom.xml` 去掉对应 `<module>`。`aifuli-server/pom.xml` 去掉已注释的 pay/product/promotion/trade/statistics/erp 依赖块。`script/deploy/aifuli-test`、`script/deploy/aifuli-prod` 中面向这四个进程的 systemd / 上传说明，在**本仓**删除或改写为「见 mall-pro」，避免有人用 supply-hub 去发商城。`script/deploy/supplyhub-test/` 已是四服务，保持。

### 3.2 保留并构建

`aifuli-dependencies`、`aifuli-framework`、`aifuli-gateway`、`aifuli-module-system`、`aifuli-module-infra`、`aifuli-module-member`、`aifuli-module-supply-chain`、`aifuli-module-movie-api`、`aifuli-module-movie-server`。

电影目录 `sql/mysql/movie/mall/` 是电影对商城库的扩展 DDL，不是 mall 模块，保留。

### 3.3 member 裁剪（d5）

**删除（商城对接层）：**

- `controller/app/open/`（含吾盛支付回调 VO）
- `service/platform/`（含 wusheng、OpenMall*、MallPlatform*）
- `dal/**/platform/`、`mapper/platform/`
- `framework/platform/wusheng/`
- `enums/platform/`
- `member-api` 与 `member-server` 的 `api/platform/`（`PlatformPointApi` 及 DTO）
- `member-server/pom.xml` 的 `aifuli-module-trade-api`
- `RpcConfiguration` 中的 `TradePlatformOrderApi`、`TradePlatformPaymentApi`

**必须保留：**

- 用户 / 地址 / 积分 / 签到 / 等级 / 分组 / 标签 / 登录（芋道内核）
- `MemberProfileResolveApi` 及实现、契约测试（到店购资料解析）
- `MemberSsoTokenApi` 及实现、契约测试；`MemberAuthService.createTokenAfterSsoLogin`
- `application-test.yaml` / `application-prod.yaml`（环境配置，不是商城业务）

**不做：** 把整个 `aifuli-module-member` checkout 回 `3350311e`（会丢掉上述两套 RPC，供应链编不过）。

`aifuli-framework` 的 `OpenMallSignUtils`、`OpenMallApiErrorCodeConstants` 本轮可留（删 OpenMall 后成为死代码），不顺手改 common。

### 3.4 供应链对 mall 的残留契约

`ApiConstants.BUYER_SERVICE_NAME` 默认仍是 `mall-server`，对应 `BuyerEventRpcApi`（一期 SaaS 事件投递）。本轮不删该 Feign。supply-hub 无 mall 实例时调用失败，由配置覆盖或保持空跑。不把默认值改成假服务名，以免掩盖「调用方仍假设有采购商商城」这一事实。

## 4. 网关

从 `aifuli-gateway/src/main/resources/application.yaml` 删除：

- 路由：`pay-*`、`product-*`、`promotion-*`、`trade-*`、`statistics-*`、`erp-*`
- Knife4j：`pay-server`、`product-server`、`promotion-server`、`trade-server`、`statistics-server`、`erp-server`

保留 system / infra / member / supply-chain（含 `AIFULI_GATEWAY_SUPPLY_CHAIN_URI`）。bpm / crm / mp / im / ai 等未点名路由本轮不动。

09-04 文档同步一句：独立仓不再与现网共用 yaml，故删除上述路由。

## 5. 测试与发版门禁

裁剪后必须：

1. `mvn -pl aifuli-gateway,aifuli-module-system/aifuli-module-system-server,aifuli-module-infra/aifuli-module-infra-server,aifuli-module-member/aifuli-module-member-server,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am package`（JDK 21）通过。
2. 根 POM 不再声明 pay / erp / mall。
3. 仓库内无 `aifuli-module-trade-api` 模块；member 不再依赖它。
4. gateway yaml 不再出现 `pay-server` / `mall-server` / `erp-server` / `statistics-server`。
5. `SupplyhubDeployContractTest` 仍断言四服务。
6. 供应链 `*Test` 与既有架构规则保持绿灯（至少跑 `SupplyChainModuleArchitectureTest` 与 member 的 profile/sso 单测）。

本轮不跑 pro143 部署。`docs/集成测试.md` 补一条：supply-hub 仓发版源、mall-pro 仍负责 58080。

## 6. 明确不做

1. 不对 mall-pro 做 `git filter-repo`、不改写其已推送历史。新仓用 orphan 快照，不推送 mall-pro 完整历史。
2. 不把删除提交 merge / push 到 `aifuli-mall-pro`。
3. 不改前端、本轮不部署 pro143。
4. 不把 member 整模块回退到 Git init；只拆商城对接层。
5. 不删 bpm/crm/im/mp/report；不 DROP member 平台表；不改 `supplychainhubweb`。

## 7. 验收

- 本地 `git remote -v`：`origin` = mall-pro，`supply-hub` = 新仓。
- `git ls-remote supply-hub refs/heads/main` 指向孤儿快照；该提交无父提交，`rev-list --count` 为 1。
- mall-pro 的 `origin/dev-0812-sc-new` 尖端不因本任务前移（本分支不 `git push origin`）。
- 克隆 `supply-hub` 后按 §5 能编过四服务 + 供应链。
