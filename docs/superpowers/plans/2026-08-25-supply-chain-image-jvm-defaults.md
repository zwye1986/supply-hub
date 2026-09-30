# Supply Chain Image JVM Defaults Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将供应链服务的 JVM 基线固化为镜像内可覆盖的 6 GiB 默认值，使新服务器无需重复声明 `JAVA_OPTS`。

**Architecture:** Dockerfile 使用 `ENV JAVA_OPTS` 提供完整默认参数串，现有 shell ENTRYPOINT 继续在容器启动时展开它；Docker/Compose 环境中的同名变量可以整体覆盖默认值。部署示例不再主动声明 `JAVA_OPTS`，从而真实验证镜像默认值，同时保留注释形式的完整覆盖示例。

**Tech Stack:** Dockerfile、Docker Buildx、Docker Compose、POSIX shell、Markdown

---

## 文件边界

- 修改 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`：声明镜像 JVM 默认值；保留现有 ENTRYPOINT 和工作区中尚未提交的 Nacos home 目录改动。
- 修改 `script/deploy/supply-chain-test/.env.example`：移除活动的 `JAVA_OPTS`，改为注释形式的可选完整覆盖示例。
- 修改 `docs/集成测试.md`：补充镜像默认值、环境覆盖和运行进程验证步骤。
- 不修改 Java 生产代码或测试代码；该配置变更采用先失败的配置契约断言和真实 Docker 镜像验证。

### Task 1: 固化镜像默认值并保留覆盖能力

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`
- Modify: `script/deploy/supply-chain-test/.env.example`

- [ ] **Step 1: 运行配置契约 RED 断言**

从仓库根目录执行：

```bash
SUPPLY_CHAIN_DOCKERFILE=aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile
SUPPLY_CHAIN_ENV_EXAMPLE=script/deploy/supply-chain-test/.env.example

grep -Fqx 'ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"' \
  "$SUPPLY_CHAIN_DOCKERFILE"

if grep -Eq '^JAVA_OPTS=' "$SUPPLY_CHAIN_ENV_EXAMPLE"; then
  echo 'FAIL: .env.example still overrides the image JAVA_OPTS default' >&2
  exit 1
fi
```

Expected: 第一条 `grep` 以状态码 1 失败；第二项检查也因 `.env.example` 当前仍有活动 `JAVA_OPTS` 而报告 FAIL。这证明断言能够发现缺失的镜像默认值和部署模板遮蔽问题。

- [ ] **Step 2: 在 Dockerfile 中添加最小默认值实现**

在精确 JAR `COPY` 之后、`USER` 之前加入：

```dockerfile
# 镜像提供可移植的 JVM 基线；部署环境可用 JAVA_OPTS 整串覆盖。见 docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#91-构建。
ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
```

保持以下 ENTRYPOINT 不变：

```dockerfile
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
```

不得改写或删除工作区现有的 `useradd --create-home` 修改。

- [ ] **Step 3: 让部署示例默认继承镜像参数**

将 `.env.example` 中活动的旧配置：

```dotenv
JAVA_OPTS=-Xms512m -Xmx1024m -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8
```

替换为：

```dotenv
# JVM 默认使用镜像内的 6 GiB 基线；特殊服务器如需调整，必须取消下一行注释并完整覆盖全部参数。
# JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8
```

- [ ] **Step 4: 运行配置契约 GREEN 断言和 Compose 静态解析**

```bash
SUPPLY_CHAIN_DOCKERFILE=aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile
SUPPLY_CHAIN_ENV_EXAMPLE=script/deploy/supply-chain-test/.env.example

grep -Fqx 'ENV JAVA_OPTS="-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"' \
  "$SUPPLY_CHAIN_DOCKERFILE"
if grep -Eq '^JAVA_OPTS=' "$SUPPLY_CHAIN_ENV_EXAMPLE"; then
  echo 'FAIL: .env.example overrides the image JAVA_OPTS default' >&2
  exit 1
fi

SUPPLY_CHAIN_ENV_FILE=.env.example docker compose \
  --env-file script/deploy/supply-chain-test/.env.example \
  -f script/deploy/supply-chain-test/docker-compose.yml config --quiet
```

Expected: 全部命令状态码为 0，Compose 无输出。

- [ ] **Step 5: 构建镜像并验证默认值与覆盖值**

```bash
SUPPLY_CHAIN_SERVER_DIR=aifuli-module-supply-chain/aifuli-module-supply-chain-server
SUPPLY_CHAIN_JVM_TEST_IMAGE=aifuli/supply-chain-server:jvm-defaults-test

docker buildx build --platform linux/amd64 --load \
  --tag "$SUPPLY_CHAIN_JVM_TEST_IMAGE" \
  --file "$SUPPLY_CHAIN_SERVER_DIR/Dockerfile" "$SUPPLY_CHAIN_SERVER_DIR"

docker image inspect "$SUPPLY_CHAIN_JVM_TEST_IMAGE" \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -Fqx 'JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8'

docker image inspect "$SUPPLY_CHAIN_JVM_TEST_IMAGE" \
  --format '{{json .Config.Entrypoint}}' \
  | grep -Fq 'exec java ${JAVA_OPTS} -jar /app/app.jar'

docker run --rm --platform linux/amd64 --entrypoint sh \
  "$SUPPLY_CHAIN_JVM_TEST_IMAGE" -c \
  'test "$JAVA_OPTS" = "-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"'

docker run --rm --platform linux/amd64 --entrypoint sh \
  -e 'JAVA_OPTS=-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8' \
  "$SUPPLY_CHAIN_JVM_TEST_IMAGE" -c \
  'test "$JAVA_OPTS" = "-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8"'
```

Expected: Buildx 成功；镜像元数据、默认容器环境和显式覆盖容器环境四项检查均返回 0。

- [ ] **Step 6: 选择性暂存并提交配置改动**

Dockerfile 已有不属于本任务的 `--create-home` 工作区修改，必须用交互式分块暂存隔离提交：

```bash
git add -p aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile
# 对既有 --create-home hunk 选择 n；对新增 JAVA_OPTS hunk 选择 y。
git add script/deploy/supply-chain-test/.env.example
git diff --cached --check
git diff --cached
git commit -m "build(supply-chain): set overridable JVM image defaults"
```

Expected: cached diff 只包含新增 `ENV JAVA_OPTS` 和 `.env.example` 的可选覆盖示例；提交不包含 `--create-home`、Java 源码、测试源码或 HTML 文件。

### Task 2: 更新联调与部署验证步骤

**Files:**
- Modify: `docs/集成测试.md`

- [ ] **Step 1: 运行文档契约 RED 断言**

```bash
rg -n '镜像默认使用.*-Xms6g.*-Xmx6g|显式覆盖 JAVA_OPTS' docs/集成测试.md
```

Expected: 状态码 1，当前联调文档尚未记录新的镜像默认值和覆盖验证。

- [ ] **Step 2: 补充 Compose 前置条件**

在“3.6 人工联调步骤 / pro143 Docker Compose 前置”中加入以下约束：

```markdown
- 镜像默认使用 `-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8`；新服务器的
  `.env` 不声明 `JAVA_OPTS` 时直接继承该基线。特殊机器只能通过完整 `JAVA_OPTS` 参数串整体覆盖，不能只提供
  单个 `-Xms` 或 `-Xmx` 并期待与镜像默认值合并。
```

- [ ] **Step 3: 补充本地镜像验证命令**

在现有 `docker image inspect` 之后加入：

```bash
docker image inspect "$SUPPLY_CHAIN_IMAGE" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -Fqx 'JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8'
docker run --rm --platform linux/amd64 --entrypoint sh "$SUPPLY_CHAIN_IMAGE" -c \
  'test "$JAVA_OPTS" = "-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"'
docker run --rm --platform linux/amd64 --entrypoint sh \
  -e 'JAVA_OPTS=-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8' \
  "$SUPPLY_CHAIN_IMAGE" -c \
  'test "$JAVA_OPTS" = "-Xms256m -Xmx256m -Duser.timezone=UTC -Dfile.encoding=UTF-8"'
```

- [ ] **Step 4: 补充远程生效验证命令**

在远程 `docker compose ... up -d` 后加入：

```bash
  docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" supply-chain-server-test \
    | grep -Fqx "JAVA_OPTS=-Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
  docker top supply-chain-server-test -eo pid,ppid,args \
    | grep -F "java -Xms6g -Xmx6g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar /app/app.jar"
```

该命令只验证无覆盖部署；如果目标服务器有受控覆盖值，必须把两个期望值同时替换为完整覆盖参数串并记录原因。

- [ ] **Step 5: 运行文档与完整配置验证**

```bash
rg -n '镜像默认使用.*-Xms6g.*-Xmx6g|完整.*JAVA_OPTS.*整体覆盖' docs/集成测试.md
git diff --check -- docs/集成测试.md

SUPPLY_CHAIN_ENV_FILE=.env.example docker compose \
  --env-file script/deploy/supply-chain-test/.env.example \
  -f script/deploy/supply-chain-test/docker-compose.yml config --quiet
```

Expected: `rg` 找到新增约束，diff 无空白错误，Compose 解析状态码为 0。

- [ ] **Step 6: 提交联调文档**

```bash
git add docs/集成测试.md
git diff --cached --check
git diff --cached
git commit -m "docs(supply-chain): verify image JVM defaults"
```

Expected: 提交只包含 `docs/集成测试.md` 的 JVM 默认值和覆盖验证步骤。

### Task 3: 完成前复核

**Files:**
- Verify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/Dockerfile`
- Verify: `script/deploy/supply-chain-test/.env.example`
- Verify: `docs/集成测试.md`

- [ ] **Step 1: 重新运行最终配置与镜像证据**

重新执行 Task 1 Step 4、Task 1 Step 5 和 Task 2 Step 5 的完整命令。不得使用之前的成功输出代替本步骤的最新证据。

- [ ] **Step 2: 检查提交和未提交用户改动边界**

```bash
git log -3 --oneline
git status --short
git show --stat --oneline HEAD~1..HEAD
```

Expected: 新增两个聚焦提交；既有 Dockerfile `--create-home` hunk、Java/测试源码和 HTML 文件仍保持原有工作区状态，没有被本任务误提交或覆盖。
