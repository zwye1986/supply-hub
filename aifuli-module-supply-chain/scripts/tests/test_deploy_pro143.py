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


if __name__ == "__main__":
    unittest.main()
