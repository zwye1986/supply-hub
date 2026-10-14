"""部署配置摘要只输出非敏感白名单，不读取真实环境或调用远程服务。

设计：docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md
      §17.1 部署脚本配置输出安全门禁。
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class DeployConfigOutputTest(unittest.TestCase):
    def test_config_summary_prints_only_exact_safe_keys(self):
        script = Path(__file__).resolve().parents[1] / "deploy-pro143.sh"
        text = script.read_text()
        start = text.index('echo "[remote] 当前配置:"')
        end = text.index('\necho "[remote] 清理远程镜像包"', start)
        safe_lines = [
            "SUPPLY_CHAIN_IMAGE=example/mock:review",
            "AIFULI_SUPPLY_CHAIN_INSTORE_RTMART_INBOUND_TIMESTAMP_TOLERANCE_SECONDS=180",
            "SPRING_CLOUD_NACOS_CONFIG_NAMESPACE=mock-test",
            "SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=mock-test",
        ]
        # 全部值均为合成测试材料，覆盖密钥、同前缀新增敏感字段及无关配置。
        excluded_lines = [
            "AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY=synthetic-not-a-secret",
            "SUPPLY_CHAIN_IMAGE_PASSWORD=synthetic-image-password",
            "OTHER_NACOS_PRIVATE_NAMESPACE_TOKEN=synthetic-namespace-token",
            "UNRELATED_CONFIG=synthetic-unrelated",
        ]
        with tempfile.TemporaryDirectory(prefix="deploy-config-test-", dir=os.environ.get("TMPDIR")) as directory:
            Path(directory, ".env").write_text("\n".join(safe_lines + excluded_lines) + "\n")
            run = subprocess.run(
                ["bash", "-c", "set -euo pipefail\n" + text[start:end]],
                cwd=directory, text=True, capture_output=True,
            )
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.splitlines(), ["[remote] 当前配置:"] + safe_lines)

    def test_order_domain_is_checked_before_build_or_remote_mutation(self):
        """执行实际部署入口，用一次性文件替代 SSH，不连接服务器或执行构建。

        设计：docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#91-pro143-部署前编号域门禁。
        缺失配置不能等到发布新镜像后才由应用拒绝；合法域才允许进入构建。
        """
        script = Path(__file__).resolve().parents[1] / "deploy-pro143.sh"
        env_key = "AIFULI_SUPPLY_CHAIN_ORDER_NO_ENV"
        scope_key = "AIFULI_SUPPLY_CHAIN_ORDER_NO_SCOPE"
        cases = [
            ("missing", "", False, env_key),
            ("empty-env", f"{env_key}=\n{scope_key}=supply-chain-test\n", False, env_key),
            ("missing-scope", f"{env_key}=test\n", False, scope_key),
            ("invalid-scope", f"{env_key}=test\n{scope_key}=bad:scope\n", False, scope_key),
            ("duplicate", f"{env_key}=test\n{env_key}=prod\n{scope_key}=db\n", False, env_key),
            ("too-long", f"{env_key}=test\n{scope_key}={'a' * 65}\n", False, scope_key),
            ("valid", f"{env_key}=test\n{scope_key}=supply-chain-test\n", True, None),
            ("quoted-boundary", f"{env_key}='test' # fixture\n{scope_key}=\"{'a' * 64}\"\n", True, None),
        ]
        for name, domain, valid, error_key in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix="deploy-domain-test-") as directory:
                root = Path(directory)
                bin_dir = root / "bin"
                bin_dir.mkdir()
                # 其它配置值必须既不执行也不进入诊断输出；全部为合成材料。
                original = domain + "PRIVATE_FIXTURE_KEY=synthetic-not-a-secret\n"
                Path(root, ".env").write_text(original)
                stubs = {
                    "ssh": '#!/usr/bin/env bash\nexec bash -s -- "$TEST_REMOTE_DIR"\n',
                    "mvn": '#!/usr/bin/env bash\necho build-reached\nexit 83\n',
                    "docker": '#!/usr/bin/env bash\necho unexpected-docker >&2\nexit 99\n',
                    "scp": '#!/usr/bin/env bash\necho unexpected-scp >&2\nexit 99\n',
                }
                for command, body in stubs.items():
                    path = bin_dir / command
                    path.write_text(body)
                    path.chmod(0o700)
                run = subprocess.run(
                    ["bash", str(script)], text=True, capture_output=True,
                    env={**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                         "TEST_REMOTE_DIR": str(root)}, timeout=15,
                )
                output = run.stdout + run.stderr
                self.assertEqual(run.returncode, 83 if valid else 1, output)
                self.assertEqual("build-reached" in output, valid, output)
                if error_key:
                    self.assertIn(error_key, output)
                self.assertNotIn("synthetic-not-a-secret", output)
                self.assertNotIn("unexpected-", output)
                self.assertEqual(Path(root, ".env").read_text(), original)


if __name__ == "__main__":
    unittest.main()
