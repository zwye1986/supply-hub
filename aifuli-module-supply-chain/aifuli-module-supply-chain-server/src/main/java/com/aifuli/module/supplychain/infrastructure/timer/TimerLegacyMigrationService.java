package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.ledger.LedgerJsonEncoding;
import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 强制旧 Timer 迁移与启动切换检查服务。
 * 依据设计规范：
 * docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#82-强制旧-timer-迁移与启动切换检查实现r3-04
 * 职责与不变量：
 * 1. 扫描 sc_timer_task 中尚未绑定账本的存量任务，回填至 sc_timer_schedule_identity；
 * 2. 严格复用 R3-01 冻结的 LedgerJsonEncoding 资源预算与规范化校验，超限或损坏 fail-closed 阻断；
 * 3. 生成不可变迁移 nonce，直接建立 binding_status = 20 (BOUND) 绑定，不提交占位 RESERVED；
 * 4. 源表 sc_timer_task 所有字段保持逐字逐值不变（零 UPDATE、零 DELETE）；
 * 5. 覆盖所有状态旧任务（PENDING/CLAIMED/EXECUTING/RETRY/COMPLETED/DEAD/CANCELLED）；
 * 6. 执行全量双向映射与载荷一致性核验，发现漏账本或映射破坏阻断应用启动。
 */
@Slf4j
@Service
public class TimerLegacyMigrationService {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    public TimerLegacyMigrationService(
            @Qualifier("supplyChainDataSource") DataSource dataSource,
            @Qualifier("supplyChainTransactionManager") PlatformTransactionManager txManager
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txManager = txManager;
    }

    /**
     * 执行旧 Timer 任务回填。
     *
     * @return 成功回填建立 BOUND 账本槽的数量
     */
    public int backfillLegacyTasks() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Integer result = tx.execute(status -> {
            // 扫描源表中尚未在账本中建立 BOUND 绑定的任务
            List<Map<String, Object>> unmapped = jdbc.queryForList(
                    "SELECT t.id, t.task_no, t.task_type, t.idempotency_key, t.payload_version, "
                            + "CAST(t.payload_json AS CHAR) AS payload_str "
                            + "FROM sc_timer_task t "
                            + "LEFT JOIN sc_timer_schedule_identity s "
                            + "ON t.task_type = s.task_type AND t.idempotency_key = s.idempotency_key "
                            + "WHERE s.id IS NULL "
                            + "ORDER BY t.id ASC");

            if (unmapped.isEmpty()) {
                log.info("旧 Timer 账本回填检查：无待回填记录");
                return 0;
            }

            log.info("旧 Timer 账本回填启动：待回填记录数={}", unmapped.size());
            int inserted = 0;
            for (Map<String, Object> task : unmapped) {
                Long taskId = ((Number) task.get("id")).longValue();
                String taskNo = (String) task.get("task_no");
                String taskType = (String) task.get("task_type");
                String idempotencyKey = (String) task.get("idempotency_key");
                String payloadStr = (String) task.get("payload_str");

                // 1. R3-01 资源预算与规范化比对守卫
                try {
                    LedgerJsonEncoding.canonicalBytes(payloadStr);
                } catch (Exception e) {
                    log.error("旧 Timer 载荷超出资源预算或损坏，阻断切换: taskId={}, taskNo={}, taskType={}, idempotencyKey={}",
                            taskId, taskNo, taskType, idempotencyKey, e);
                    throw new SystemIntegrityException("存量Timer任务载荷超出资源预算或损坏: taskNo=" + taskNo, e);
                }

                // 2. 生成 16 字节确定性不可变 reservation_nonce
                byte[] nonce = generateMigrationNonce(taskId);

                // 3. 直接建立 BOUND 槽（binding_status = 20）
                jdbc.update(
                        "INSERT INTO sc_timer_schedule_identity "
                                + "(task_type, idempotency_key, binding_status, reservation_nonce, payload_version, "
                                + "comparison_protocol, payload_json, task_no, task_id, created_at, bound_at) "
                                + "VALUES (?, ?, 20, ?, 1, 'LEGACY_JSON_V1', ?, ?, ?, NOW(3), NOW(3))",
                        taskType, idempotencyKey, nonce, payloadStr, taskNo, taskId
                );
                inserted++;
            }

            log.info("旧 Timer 账本回填完成：成功写入 BOUND 槽数={}", inserted);
            return inserted;
        });
        return result != null ? result : 0;
    }

    /**
     * 启动切换一致性核验守卫（Switch Guard）。
     * 全量核验源表与账本双向映射，有源必有账本且 BOUND，字段与载荷严格等价。
     */
    public void verifyMigrationAndSwitchIntegrity() {
        // 1. 检查是否存在源任务缺少对应的 BOUND 账本
        Integer missingLedgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task t "
                        + "LEFT JOIN sc_timer_schedule_identity s "
                        + "ON t.task_type = s.task_type AND t.idempotency_key = s.idempotency_key "
                        + "WHERE s.id IS NULL OR s.binding_status != 20", Integer.class);
        if (missingLedgerCount != null && missingLedgerCount > 0) {
            log.error("Timer 启动切换核验失败：存在 {} 条未映射或未 BOUND 的源任务", missingLedgerCount);
            throw new SystemIntegrityException("Timer启动切换核验失败：存在 " + missingLedgerCount + " 条未映射或未BOUND的源任务");
        }

        // 2. 检查账本中 task_no / task_id 是否与源表完全一致
        Integer mismatchedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task t "
                        + "INNER JOIN sc_timer_schedule_identity s "
                        + "ON t.task_type = s.task_type AND t.idempotency_key = s.idempotency_key "
                        + "WHERE s.task_no != t.task_no OR s.task_id != t.id", Integer.class);
        if (mismatchedCount != null && mismatchedCount > 0) {
            log.error("Timer 启动切换核验失败：存在 {} 条身份映射不一致记录", mismatchedCount);
            throw new SystemIntegrityException("Timer启动切换核验失败：存在 " + mismatchedCount + " 条身份映射不一致记录");
        }

        // 3. 检查载荷规范等价性与资源预算
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.task_no, CAST(t.payload_json AS CHAR) AS src_payload, "
                        + "CAST(s.payload_json AS CHAR) AS ledger_payload "
                        + "FROM sc_timer_task t "
                        + "INNER JOIN sc_timer_schedule_identity s "
                        + "ON t.task_type = s.task_type AND t.idempotency_key = s.idempotency_key");

        for (Map<String, Object> row : rows) {
            String taskNo = (String) row.get("task_no");
            String srcPayload = (String) row.get("src_payload");
            String ledgerPayload = (String) row.get("ledger_payload");

            byte[] srcBytes;
            byte[] ledgerBytes;
            try {
                srcBytes = LedgerJsonEncoding.canonicalBytes(srcPayload);
                ledgerBytes = LedgerJsonEncoding.canonicalBytes(ledgerPayload);
            } catch (Exception e) {
                log.error("Timer 启动切换核验失败：载荷超出资源预算或损坏: taskNo={}", taskNo, e);
                throw new SystemIntegrityException("Timer启动切换核验失败：载荷超出资源预算或损坏: taskNo=" + taskNo, e);
            }

            if (!Arrays.equals(srcBytes, ledgerBytes)) {
                log.error("Timer 启动切换核验失败：源任务与账本载荷不一致: taskNo={}", taskNo);
                throw new SystemIntegrityException("Timer启动切换核验失败：源任务与账本载荷不一致: taskNo=" + taskNo);
            }
        }

        // 4. 检查是否存在残留的 RESERVED 孤儿槽
        Integer orphanReservedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE binding_status = 10", Integer.class);
        if (orphanReservedCount != null && orphanReservedCount > 0) {
            log.error("Timer 启动切换核验失败：账本中存在 {} 个未绑定的 RESERVED 孤儿槽", orphanReservedCount);
            throw new SystemIntegrityException("Timer启动切换核验失败：账本中存在 " + orphanReservedCount + " 个未完成绑定的RESERVED孤儿槽");
        }

        log.info("Timer 启动切换一致性核验成功：全量源任务与账本双向核验通过，共核验记录数={}", rows.size());
    }

    private byte[] generateMigrationNonce(Long taskId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("MIGRATION:TIMER:" + taskId).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
