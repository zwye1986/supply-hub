# supplyhub 测试环境（Docker Compose）

设计：`docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md`

- 后端：pro143 `/data/aifuli-test/supplyhub/`，host 网络，4 服务（gateway/system/infra/member）端口 59080/59081/59082/59087（现网 58xxx +1000；不得用 68xxx，超过 TCP 65535）。2026-09-07 移除 pay/erp/mall/statistics，supplyhub 栈不需要这些能力。
- 入口：pro197 Nginx `supplyhubtest.qihaofu.cn` → `172.19.64.105:59080`。
- 供应链：现有 `supply-chain-server-test:58190`，本目录 Compose **不含**该服务。

## 发布

在仓库根目录（JDK 21）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
# 全量发布四服务
bash script/deploy/supplyhub-test/deploy-pro143.sh
# 或仅发布单个服务（如 system）
bash script/deploy/supplyhub-test/deploy-pro143.sh system
```

首次会把 `.env.example` 拷到服务器；脚本用本机 `application-test.yaml` 的 Nacos 地址/账号写入服务器 `.env`（0600），**不会**把 `.env` 提交 Git。脚本默认 SSH 连接别名 `pro143`（可通过 `SUPPLYHUB_DOCKER_HOST` 覆盖）。

不要对供应链目录执行 `docker compose down`。

## Nginx（pro197）

```bash
bash script/deploy/supplyhub-test/install-nginx-pro197.sh
```
