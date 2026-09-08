# supply-hub 独立仓裁剪 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `dev-0812-sc-new-merge` 上删掉 pay/erp/mall/statistics 源码、拆掉 member 的商城对接层，再用无历史孤儿快照推到 `http://47.117.129.235/jierongjinfu/supply-hub`。

**Architecture:** 本地工作分支保留 mall-pro 历史便于对照；契约测试先红后绿锁住「无四模块、无 trade-api、网关无四服务路由、profile/sso RPC 仍在」。推送只用 `git checkout --orphan`，不 `git push origin`，不对 mall-pro 做 filter-repo。

**Tech Stack:** JDK 21、Maven、JUnit 5、GitLab `supply-hub`。

**Spec:** `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`

---

## File map

| 路径 | 职责 |
| --- | --- |
| `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md` | 已确认设计 |
| `docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md` | 收回「gateway 保留四服务路由」 |
| `SupplyHubRepoSplitContractTest.java` | 仓级门禁：目录/POM/网关/member |
| `aifuli-module-member/**` | 删 OpenMall/吾盛/`trade-api`，留 profile/sso |
| `aifuli-module-pay/`、`erp/`、`mall/` | 整树删除 |
| `pom.xml`、`aifuli-server/pom.xml` | 去掉模块与注释依赖 |
| `aifuli-gateway/.../application.yaml` | 删除四服务路由与 Knife4j |
| `script/deploy/aifuli-test`、`aifuli-prod` | 去掉四进程发版入口 |
| `docs/集成测试.md` | 记录双仓发版源 |

工作目录：`/Users/mac/.codex/worktrees/a79d/aifuli-mall-pro`。所有 `mvn` 前执行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

指定 `-Dtest=` 时加 `-Dsurefire.failIfNoSpecifiedTests=false`。

---

### Task 1: 提交设计文档

**Files:**
- Create: `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`（若尚未入库）
- Modify: `docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md`

- [ ] **Step 1: 确认工作区只含设计文件**

```bash
git status -sb
```

Expected: 当前分支 `dev-0812-sc-new-merge`；未跟踪/已改的是上述 spec，不含 pay/mall 业务代码。

- [ ] **Step 2: 提交**

```bash
git add docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md \
  docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md \
  docs/superpowers/plans/2026-09-07-supply-hub-repo-split.md
git commit -m "$(cat <<'EOF'
文档：supply-hub 独立仓裁剪设计与实施计划

EOF
)"
```

Expected: 新提交在 `dev-0812-sc-new-merge`，**不要** `git push origin`。

---

### Task 2: 先写失败的仓级契约测试

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyHubRepoSplitContractTest.java`

复用同包 `SupplyhubDeployContractTest` 的仓库根查找方式，并支持目录。

- [ ] **Step 1: 写入测试（当前必红）**

```java
package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * supply-hub 裁剪门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md#5-测试与发版门禁}。
 */
class SupplyHubRepoSplitContractTest {

    @Test
    void payErpMallModuleDirectoriesAreGone() {
        assertThat(repoDir("aifuli-module-pay")).doesNotExist();
        assertThat(repoDir("aifuli-module-erp")).doesNotExist();
        assertThat(repoDir("aifuli-module-mall")).doesNotExist();
    }

    @Test
    void rootPomDoesNotDeclarePayErpMall() throws IOException {
        String pom = Files.readString(repoFile("pom.xml"));
        assertThat(pom).doesNotContain("<module>aifuli-module-pay</module>");
        assertThat(pom).doesNotContain("<module>aifuli-module-erp</module>");
        assertThat(pom).doesNotContain("<module>aifuli-module-mall</module>");
        assertThat(pom).contains("<module>aifuli-module-member</module>");
        assertThat(pom).contains("<module>aifuli-module-supply-chain</module>");
    }

    @Test
    void gatewayYamlOmitsMallPayErpStatistics() throws IOException {
        String yaml = Files.readString(repoFile("aifuli-gateway/src/main/resources/application.yaml"));
        assertThat(yaml).doesNotContain("uri: grayLb://pay-server");
        assertThat(yaml).doesNotContain("uri: grayLb://mall-server");
        assertThat(yaml).doesNotContain("uri: grayLb://erp-server");
        assertThat(yaml).doesNotContain("uri: grayLb://statistics-server");
        assertThat(yaml).doesNotContain("service-name: pay-server");
        assertThat(yaml).doesNotContain("service-name: mall-server");
        assertThat(yaml).doesNotContain("service-name: erp-server");
        assertThat(yaml).doesNotContain("service-name: statistics-server");
        assertThat(yaml).contains("id: supply-chain-admin-api");
        assertThat(yaml).contains("${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}");
    }

    @Test
    void memberDoesNotDependOnTradeApi() throws IOException {
        String pom = Files.readString(repoFile(
                "aifuli-module-member/aifuli-module-member-server/pom.xml"));
        String rpc = Files.readString(repoFile(
                "aifuli-module-member/aifuli-module-member-server/src/main/java/"
                        + "com/aifuli/module/member/framework/rpc/config/RpcConfiguration.java"));
        assertThat(pom).doesNotContain("aifuli-module-trade-api");
        assertThat(rpc).doesNotContain("com.aifuli.module.trade");
        assertThat(rpc).doesNotContain("TradePlatform");
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-server/src/main/java/"
                        + "com/aifuli/module/member/controller/app/open/OpenMallMemberController.java"))
                .doesNotExist();
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-api/src/main/java/"
                        + "com/aifuli/module/member/api/profile/MemberProfileResolveApi.java"))
                .exists();
        assertThat(repoFile(
                "aifuli-module-member/aifuli-module-member-api/src/main/java/"
                        + "com/aifuli/module/member/api/sso/MemberSsoTokenApi.java"))
                .exists();
    }

    private static Path repoFile(String relative) {
        Path found = resolve(relative);
        return found;
    }

    private static Path repoDir(String relative) {
        return resolve(relative);
    }

    private static Path resolve(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return Path.of("").toAbsolutePath().resolve(relative);
    }
}
```

`repoFile` 给「不应存在」的路径时：找不到则返回相对路径（`exists()` 为 false），不要抛 `AssertionError`。

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dtest=SupplyHubRepoSplitContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL。`payErpMallModuleDirectoriesAreGone`、`rootPomDoesNotDeclarePayErpMall`、`gatewayYamlOmitsMallPayErpStatistics`、`memberDoesNotDependOnTradeApi` 均失败。

- [ ] **Step 3: 提交红灯测试**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyHubRepoSplitContractTest.java
git commit -m "$(cat <<'EOF'
测试：增加 supply-hub 裁剪仓级契约（当前应失败）

EOF
)"
```

---

### Task 3: 拆掉 member 商城对接层

**Files:**
- Modify: `aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/framework/rpc/config/RpcConfiguration.java`
- Modify: `aifuli-module-member/aifuli-module-member-server/pom.xml`（删 `aifuli-module-trade-api` 依赖块，第 43–47 行）
- Delete: 下列路径整树（`git rm -r`）

```text
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/controller/app/open/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/service/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/dataobject/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/mysql/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/redis/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/redis/RedisKeyConstants.java
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/framework/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/enums/platform/
aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/api/platform/
aifuli-module-member/aifuli-module-member-server/src/main/resources/mapper/platform/
aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/platform/
```

**禁止删除：** `api/profile/`、`api/sso/`、`MemberAuthService.createTokenAfterSsoLogin`、`application-test.yaml`、`application-prod.yaml`。

- [ ] **Step 1: 删除目录**

```bash
git rm -r \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/controller/app/open \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/service/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/dataobject/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/mysql/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/redis/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/framework/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/enums/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/api/platform \
  aifuli-module-member/aifuli-module-member-server/src/main/resources/mapper/platform \
  aifuli-module-member/aifuli-module-member-api/src/main/java/com/aifuli/module/member/api/platform
git rm aifuli-module-member/aifuli-module-member-server/src/main/java/com/aifuli/module/member/dal/redis/RedisKeyConstants.java
```

- [ ] **Step 2: 恢复 RpcConfiguration 为仅 system Feign**

把 `RpcConfiguration.java` 写成：

```java
package com.aifuli.module.member.framework.rpc.config;

import com.aifuli.module.system.api.logger.LoginLogApi;
import com.aifuli.module.system.api.sms.SmsCodeApi;
import com.aifuli.module.system.api.social.SocialClientApi;
import com.aifuli.module.system.api.social.SocialUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "memberRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {SmsCodeApi.class, LoginLogApi.class, SocialUserApi.class, SocialClientApi.class})
public class RpcConfiguration {
}
```

- [ ] **Step 3: 从 member-server POM 删除 trade-api**

删除：

```xml
        <dependency>
            <groupId>com.aifuli</groupId>
            <artifactId>aifuli-module-trade-api</artifactId>
            <version>${revision}</version>
        </dependency>
```

- [ ] **Step 4: 确认无残留 import 后编译 member**

```bash
rg -n "com\\.aifuli\\.module\\.trade|OpenMall|Wusheng|MallPlatform|PlatformPoint|PlatformNonce" \
  aifuli-module-member --glob '!**/target/**'
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-module-member/aifuli-module-member-server,aifuli-module-member/aifuli-module-member-api -am test \
  -Dtest=MemberProfileResolveApiImplTest,MemberSsoTokenApiImplTest,MemberProfileResolveApiContractTest,MemberSsoTokenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `rg` 无业务命中（最多只剩注释）；四条测试 PASS。`SupplyHubRepoSplitContractTest.memberDoesNotDependOnTradeApi` 此时应变绿，其余三条仍红。

- [ ] **Step 5: 提交**

```bash
git add aifuli-module-member
git commit -m "$(cat <<'EOF'
member 去掉开放商城与吾盛对接，不再依赖 trade-api

EOF
)"
```

---

### Task 4: 删除 pay / erp / mall 模块并改根 POM

**Files:**
- Delete: `aifuli-module-pay/`、`aifuli-module-erp/`、`aifuli-module-mall/`（含 trade-api，不再提升为根模块）
- Modify: `pom.xml` 第 21、24、26 行
- Modify: `aifuli-server/pom.xml` 已注释的 pay/product/promotion/trade/statistics/erp 依赖块

- [ ] **Step 1: 删除模块目录**

```bash
git rm -r aifuli-module-pay aifuli-module-erp aifuli-module-mall
```

- [ ] **Step 2: 根 POM modules 改为**

```xml
    <modules>
        <module>aifuli-dependencies</module>
        <module>aifuli-gateway</module>
        <module>aifuli-framework</module>
        <!-- Server 主项目 -->
        <module>aifuli-server</module>
        <!--  各种 module 拓展 -->
        <module>aifuli-module-system</module>
        <module>aifuli-module-infra</module>
        <module>aifuli-module-member</module>
        <module>aifuli-module-bpm</module>
        <module>aifuli-module-report</module>
        <module>aifuli-module-mp</module>
        <module>aifuli-module-crm</module>
        <module>aifuli-module-im</module>
        <module>aifuli-module-supply-chain</module>
        <!-- 电影专业子系统：契约与独立应用直接位于仓库根目录 -->
        <module>aifuli-module-movie-api</module>
        <module>aifuli-module-movie-server</module>
    </modules>
```

保留 bpm/report/mp/crm/im。不要删 `sql/mysql/movie/mall/`。

- [ ] **Step 3: 清理 aifuli-server 注释依赖**

删除 `aifuli-server/pom.xml` 中整段「支付服务」「商城相关模块」「ERP 相关模块」注释掉的 `<dependency>`（`aifuli-module-pay-server`、`product-server`、`promotion-server`、`trade-server`、`statistics-server`、`erp-server`）。保留 system/infra 与已注释的 member/report/bpm/mp/crm/im。

- [ ] **Step 4: 跑契约测试看目录/POM 断言**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dtest=SupplyHubRepoSplitContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `payErpMallModuleDirectoriesAreGone` 与 `rootPomDoesNotDeclarePayErpMall` PASS；`gatewayYamlOmitsMallPayErpStatistics` 仍 FAIL。

- [ ] **Step 5: 提交**

```bash
git add pom.xml aifuli-server/pom.xml
git commit -m "$(cat <<'EOF'
从源码移除 pay、erp、mall（含 statistics）模块

EOF
)"
```

`git rm` 的删除已在暂存区，与 POM 一起提交。

---

### Task 5: 网关去掉四服务路由

**Files:**
- Modify: `aifuli-gateway/src/main/resources/application.yaml`

- [ ] **Step 1: 删除 routes 中这些 `id` 整段**

- `pay-admin-api`、`pay-app-api`
- `product-admin-api`、`product-app-api`
- `promotion-admin-api`、`promotion-app-api`
- `trade-admin-api`、`trade-app-api`
- `statistics-admin-api`
- `erp-admin-api`

保留 `system-*`、`infra-*`、`member-*`、`supply-chain-*`、`bpm-*`、`mp-*`、`crm-*`、`ai-*`、`iot-*`、`mes-*`、`wms-*`、`im-*`、`report-*`。

- [ ] **Step 2: 删除 knife4j.gateway.routes 中**

```yaml
      - name: pay-server
        service-name: pay-server
        url: /admin-api/pay/v3/api-docs
      - name: product-server
        service-name: mall-server
        url: /admin-api/product/v3/api-docs
      - name: promotion-server
        service-name: mall-server
        url: /admin-api/promotion/v3/api-docs
      - name: trade-server
        service-name: mall-server
        url: /admin-api/trade/v3/api-docs
      - name: statistics-server
        service-name: statistics-server
        url: /admin-api/statistics/v3/api-docs
      - name: erp-server
        service-name: erp-server
        url: /admin-api/erp/v3/api-docs
```

保留 `system-server`、`infra-server`、`member-server`、`supply-chain-server`。

- [ ] **Step 3: 契约测试应变绿（网关项）**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dtest=SupplyHubRepoSplitContractTest,SupplyhubDeployContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `SupplyHubRepoSplitContractTest` 4 个方法全 PASS；`SupplyhubDeployContractTest` 仍 PASS（供应链路由未改）。

- [ ] **Step 4: 提交**

```bash
git add aifuli-gateway/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
网关删除 mall/pay/erp/statistics 路由与 Knife4j 分组

EOF
)"
```

---

### Task 6: 裁掉本仓的商城四进程发版脚本

**Files:**
- Modify: `script/deploy/aifuli-test/install-systemd.sh`
- Modify: `script/deploy/aifuli-test/templates/deploy.sh`
- Modify: `script/deploy/aifuli-test/templates/startup.sh`
- Modify: `script/deploy/aifuli-test/README.md`、`script/deploy/aifuli-test/测试环境部署与版本发布手册.md`：写明 58080 八服务发版源是 mall-pro；本仓脚本只留 gateway/system/infra/member
- Delete: `script/deploy/aifuli-test/systemd/aifuli-test-pay.service`、`aifuli-test-erp.service`、`aifuli-test-mall.service`、`aifuli-test-statistics.service`
- Delete: `script/deploy/aifuli-prod/apps/pay/`、`apps/mall/`、`apps/statistics/`

- [ ] **Step 1: install-systemd.sh 改为**

```bash
SERVICES=(gateway system infra member)

for application_name in gateway-server system-server infra-server member-server; do
  install -d -o aifuli -g aifuli -m 750 "/data/aifuli-test/logs/${application_name}"
done
```

- [ ] **Step 2: templates/deploy.sh 的 case 只留**

```bash
case "${SERVICE_KEY}" in
  gateway) JAR_NAME="aifuli-gateway.jar" ;;
  system) JAR_NAME="aifuli-module-system-server.jar" ;;
  infra) JAR_NAME="aifuli-module-infra-server.jar" ;;
  member) JAR_NAME="aifuli-module-member-server.jar" ;;
  *)
    echo "未知服务目录: ${SERVICE_KEY}"
    exit 1
    ;;
esac
```

- [ ] **Step 2b: templates/startup.sh 的 APPLICATION_NAME case 只留**

```bash
case "${SERVICE_KEY}" in
  gateway) APPLICATION_NAME="gateway-server" ;;
  system) APPLICATION_NAME="system-server" ;;
  infra) APPLICATION_NAME="infra-server" ;;
  member) APPLICATION_NAME="member-server" ;;
  *)
    echo "未知服务目录: ${SERVICE_KEY}"
    exit 1
    ;;
esac
```

- [ ] **Step 3: 删除四服务 unit 与 prod 目录**

```bash
git rm script/deploy/aifuli-test/systemd/aifuli-test-pay.service \
  script/deploy/aifuli-test/systemd/aifuli-test-erp.service \
  script/deploy/aifuli-test/systemd/aifuli-test-mall.service \
  script/deploy/aifuli-test/systemd/aifuli-test-statistics.service
git rm -r script/deploy/aifuli-prod/apps/pay \
  script/deploy/aifuli-prod/apps/mall \
  script/deploy/aifuli-prod/apps/statistics
```

README / 手册开头加一句：现网 `58080` 的 mall/pay/erp/statistics 只从 `aifuli-mall-pro` 发版。

- [ ] **Step 4: 提交**

```bash
git add script/deploy
git commit -m "$(cat <<'EOF'
本仓发版脚本去掉 pay/erp/mall/statistics 进程入口

EOF
)"
```

---

### Task 7: 联调文档

**Files:**
- Modify: `docs/集成测试.md`（在 §7.19 之后追加 §7.20，不要改写 7.19 已发生的 pro143 记录）

- [ ] **Step 1: 追加**

```markdown
### 7.20 后端迁入 supply-hub 独立仓（2026-09-07，文档）

设计：`docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`。

| 项 | 约定 |
|---|---|
| 新仓 | `http://47.117.129.235/jierongjinfu/supply-hub`，`main` 为裁剪后孤儿快照（无 mall-pro 历史） |
| mall-pro | 继续全量；现网 systemd `58080` 与商城四进程仍从此仓发版 |
| 本仓源码 | 无 `aifuli-module-pay` / `erp` / `mall`；member 无 OpenMall/吾盛/`trade-api` |
| 网关 | supply-hub 的 `application.yaml` 已删除 mall/pay/erp/statistics 路由 |
| 本轮 | 不改 `supplychainhubweb`，不对 pro143 再发版 |
```

- [ ] **Step 2: 提交**

```bash
git add "docs/集成测试.md"
git commit -m "$(cat <<'EOF'
联调文档记录 supply-hub 与 mall-pro 双仓发版边界

EOF
)"
```

---

### Task 8: 编译与供应链回归

- [ ] **Step 1: 四服务 + 供应链 package**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-gateway,aifuli-module-system/aifuli-module-system-server,aifuli-module-infra/aifuli-module-infra-server,aifuli-module-member/aifuli-module-member-server,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am package -DskipTests
```

Expected: BUILD SUCCESS。若失败，先修 POM 残留引用，不要跳过删模块。

- [ ] **Step 2: 契约 + 架构 + member RPC 单测**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server,aifuli-module-member/aifuli-module-member-server,aifuli-module-member/aifuli-module-member-api -am test \
  -Dtest=SupplyHubRepoSplitContractTest,SupplyhubDeployContractTest,SupplyChainModuleArchitectureTest,MemberProfileResolveApiImplTest,MemberSsoTokenApiImplTest,MemberProfileResolveApiContractTest,MemberSsoTokenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 全部 PASS。

- [ ] **Step 3: 根 reactor 能解析**

```bash
mvn -q -N help:evaluate -Dexpression=project.modules -q
```

Expected: 输出不含 `aifuli-module-pay` / `erp` / `mall`。

不要本轮跑全量 failsafe，也不要 `git push origin`。

---

### Task 9: 孤儿快照推到 supply-hub

**只在 Task 8 绿灯之后。** 工作树必须干净。

- [ ] **Step 1: 加 remote（若尚无）**

```bash
git remote add supply-hub http://47.117.129.235/jierongjinfu/supply-hub.git
git remote -v
```

Expected: `origin` 仍是 `aifuli-mall-pro`；`supply-hub` 指向新仓。

- [ ] **Step 2: 孤儿提交并 force 推 main**

```bash
git checkout --orphan supply-hub-snapshot
git add -A
git status
git commit -m "$(cat <<'EOF'
供应链独立仓初始快照：去掉 pay/erp/mall/statistics

EOF
)"
git rev-list --count HEAD
git log --format=%P -1
git push -u supply-hub HEAD:main --force
```

Expected: `rev-list --count` 为 `1`；`%P` 为空；远程 `main` 更新。`--force` 只覆盖 GitLab 空 README。

- [ ] **Step 3: 回到工作分支，删临时 orphan 分支**

```bash
git checkout dev-0812-sc-new-merge
git branch -D supply-hub-snapshot
git status -sb
```

Expected: 仍在 `dev-0812-sc-new-merge`，ahead origin 若干（设计+裁剪提交）；**没有** push 到 `origin`。

- [ ] **Step 4: 远程验收**

```bash
git ls-remote supply-hub refs/heads/main
git fetch supply-hub main
git -C "$(git rev-parse --show-toplevel)" rev-list --count supply-hub/main
```

Expected: count 为 1。clone 新仓后 `aifuli-module-mall` 不存在，且 `mvn -pl aifuli-gateway,aifuli-module-system/aifuli-module-system-server -am package -DskipTests` 在 JDK 21 下可启动 reactor。

---

## Spec 覆盖

| 决策 | Task |
| --- | --- |
| d1 不推 mall-pro | Task 1/9 禁止 `git push origin` |
| d2/d3 孤儿快照、不 filter-repo | Task 9 |
| d4 删三模块、不留 trade-api | Task 3–4 |
| d5 member 拆对接、留 profile/sso | Task 3 |
| d6 网关删路由 | Task 5 |
| d7 不删 bpm 等 | Task 4 根 POM |
| d8 不改前端、不发 pro143 | 全计划无此步骤 |
| d9 origin 不改名 | Task 9 |
| d10 不 DROP 表 | 无 DDL |
| §5 门禁 | Task 2 + 8 |
| 联调记录 | Task 7 |
| 发版脚本 | Task 6 |
