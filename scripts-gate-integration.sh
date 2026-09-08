#!/bin/bash
# 供应链集成测试全量门禁：每个测试类一条命令、一个独占空库（真实本地 MySQL）
set -uo pipefail
MYSQL=/opt/homebrew/opt/mysql-client@8.4/bin/mysql
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro
LOG_DIR=aifuli-module-supply-chain/aifuli-module-supply-chain-server/target/gate-integration-logs
mkdir -p "$LOG_DIR" || exit 1

# 清理 macOS 同步冲突副本（ArchUnit 性能劣化根因）
find aifuli-module-supply-chain/aifuli-module-supply-chain-server/target -type f \
  \( -name "* [0-9]*.class" -o -name "* [0-9]*.xml" \) -delete 2>/dev/null

TESTS=(
  "FulfillmentAcceptanceIntegrationTest supply-chain.test"
  "InstoreEntryCredentialIntegrationTest supply-chain.test"
  "InstoreMemberEntryFlowIntegrationTest supply-chain.test"
  "InstoreRefundFlowIntegrationTest supply-chain.test"
  "MasterDataLifecycleIntegrationTest supply-chain.test"
  "ConnectorPublicationIntegrationTest supply-chain.test"
  "FundReservationIntegrationTest supply-chain.test"
  "SupplierRoutingApplicationServiceIntegrationTest supply-chain.test"
  "SupplyChainLiquibaseIntegrationTest supply-chain.test"
  "TimerOutboxInboxIntegrationTest supply-chain.test"
  "InstoreProductionInvariantsMigrationIntegrationTest supply-chain.migration-test"
  "InstoreMemberEntryRouteMigrationIntegrationTest supply-chain.instore-member-entry-test"
  "BuyerProjectIntegrationRouteMigrationIntegrationTest supply-chain.project-route-test"
  "InstoreProjectCashierRouteMigrationIntegrationTest supply-chain.instore-project-route-test"
  "ProjectAuthNonceStoreIntegrationTest supply-chain.test"
  "ProjectAppAuthFlowIntegrationTest supply-chain.test"
  "SupplierAdminProfileIntegrationTest supply-chain.test"
)

idx=1
total=${#TESTS[@]}
FAILED=()
for entry in "${TESTS[@]}"; do
  cls=${entry%% *}
  prefix=${entry##* }
  db="sc_it_gate_$(printf '%02d' "$idx")"
  log_file="$LOG_DIR/$(printf '%02d' "$idx")-$cls.log"
  db_log_file="$LOG_DIR/$(printf '%02d' "$idx")-$cls-database.log"
  echo "=== [$idx/$total] $cls @ $db ==="
  echo "log: $log_file"
  "$MYSQL" -h 127.0.0.1 -u root \
    -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" \
    >"$db_log_file" 2>&1
  db_status=$?
  if (( db_status != 0 )); then
    echo "DATABASE FAILED (exit=$db_status)"
    tail -20 "$db_log_file"
    FAILED+=("$cls(database)")
    idx=$((idx+1))
    continue
  fi

  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
    -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test="$cls" -Dfailsafe.failIfNoSpecifiedTests=true \
    "-D${prefix}.jdbc-url=jdbc:mysql://127.0.0.1:3306/${db}?useSSL=false&allowPublicKeyRetrieval=true" \
    verify >"$log_file" 2>&1
  mvn_status=$?
  if ! grep -E "Tests run:|BUILD (SUCCESS|FAILURE)" "$log_file" | tail -4; then
    echo "未找到 Maven 测试摘要，输出日志末尾："
    tail -30 "$log_file"
  fi
  if (( mvn_status != 0 )); then
    echo "TEST FAILED (exit=$mvn_status)"
    FAILED+=("$cls")
  fi
  idx=$((idx+1))
done

if (( ${#FAILED[@]} > 0 )); then
  echo "=== GATE FAILED (${#FAILED[@]}/$total) ==="
  printf ' - %s\n' "${FAILED[@]}"
  exit 1
fi

echo "=== GATE PASSED ($total/$total) ==="
