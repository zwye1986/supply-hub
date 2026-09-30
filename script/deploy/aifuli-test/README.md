# 爱福利测试环境部署脚本

现网 `58080` 的 mall/pay/erp/statistics（及完整八服务 systemd）只从 `aifuli-mall-pro` 发版；本仓脚本只安装/发布 gateway/system/infra/member。

本目录用于将 4 个 Spring Boot Jar 以 systemd 方式部署到测试服务器。

## 一次性安装

将整个 `script/deploy/aifuli-test` 目录上传到测试服务器任意临时目录后，以 root 执行：

```bash
cd /上传后的/aifuli-test
sh install-systemd.sh
```

该脚本会：

- 安装 4 个 systemd 单元到 `/etc/systemd/system/`
- 将 `startup.sh`、`deploy.sh` 分发到每个 `/data/aifuli-test/apps/<服务>/` 目录
- 刷新 systemd 配置

## 发布 Jar

将本地构建的 Jar 上传到服务器任意临时目录，然后以 root 执行：

```bash
sh /data/aifuli-test/apps/infra/deploy.sh /上传目录/aifuli-module-infra-server.jar 20260716_001
```

第二个参数是发布版本号，只允许字母、数字、点、下划线和连字符；省略时自动使用当前时间。

发布脚本会写入 `releases/<版本号>/`、原子切换 `current` 软链接并重启服务。若重启失败且存在旧版本，会自动回滚到旧版本。

## 日常运维

```bash
sh /data/aifuli-test/apps/member/startup.sh start
sh /data/aifuli-test/apps/member/startup.sh stop
sh /data/aifuli-test/apps/member/startup.sh restart
sh /data/aifuli-test/apps/member/startup.sh status
sh /data/aifuli-test/apps/member/startup.sh logs
```

应用文件日志由 Nacos 配置写入 `/data/aifuli-test/logs/<服务名>/`；`logs` 子命令查看 systemd 控制台日志。

## 服务与 Jar 对照

| 目录 | systemd 服务 | Jar 名称 | 端口 |
| --- | --- | --- | --- |
| gateway | aifuli-test-gateway | aifuli-gateway.jar | 58080 |
| system | aifuli-test-system | aifuli-module-system-server.jar | 58081 |
| infra | aifuli-test-infra | aifuli-module-infra-server.jar | 58082 |
| member | aifuli-test-member | aifuli-module-member-server.jar | 58087 |

启动顺序：`system -> infra -> member -> gateway`。

mall / pay / erp / statistics 的 systemd 单元、Jar 名称与启动顺序见 `aifuli-mall-pro` 仓库同路径文档，不要用本仓脚本发布这四个进程。
