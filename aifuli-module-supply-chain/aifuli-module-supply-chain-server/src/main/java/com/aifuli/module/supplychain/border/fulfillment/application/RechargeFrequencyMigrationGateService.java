package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMigrationGateMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyMigrationGateState;
import com.aifuli.module.supplychain.infrastructure.security.RechargeFrequencyIdentityKeyProvider;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 持久化发布覆盖门禁：每次MOBILE入队/marker按当前专用密钥指纹读取，不缓存默认允许。
 * 已READY迁移重跑先独立提交CLOSED，失败不因迁移事务回滚恢复旧READY；仅完整预检事务发布READY。 设计见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyMigrationGateService {
    private static final long GATE_ID = 1L;
    private final RechargeFrequencyMigrationGateMapper gates;
    private final RechargeFrequencyIdentityKeyProvider keyProvider;
    private final SupplyChainDatabaseClock clock;

    /**
     * 入队/发送资格前置校验：缺行、关闭或合法但错误密钥均拒绝。 可信结果与合法撤销不调用本方法，因此发布保护不会阻断已发生外部事实的收敛。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    public void requireReady() {
        var gate = gates.selectById(GATE_ID);
        if (gate == null
                || !RechargeFrequencyMigrationGateState.READY.name().equals(gate.getState())
                || gate.getVerifiedAt() == null
                || gate.getVerifiedArtifact() == null
                || gate.getIdentityKeyFingerprint() == null
                || !MessageDigest.isEqual(
                        gate.getIdentityKeyFingerprint(), currentKeyFingerprint())) {
            throw new IllegalStateException("直充限频迁移发布门禁未就绪");
        }
    }

    /**
     * 已验证部署证据后独立关闭门禁，保留冻结密钥指纹，禁止换密钥重建身份。 返回兼容性，由外层迁移事务在关闭已提交后拒绝不兼容密钥；不得在本事务内抛出导致恢复旧READY。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public boolean closeBeforePreflight() {
        log.info("[直充限频迁移] 关闭全局覆盖门禁");
        var gate = gates.selectById(GATE_ID);
        if (gate == null) {
            throw new IllegalStateException("直充限频迁移发布门禁缺失");
        }
        boolean compatible =
                gate.getIdentityKeyFingerprint() == null
                        || MessageDigest.isEqual(
                                gate.getIdentityKeyFingerprint(), currentKeyFingerprint());
        gate.setState(RechargeFrequencyMigrationGateState.CLOSED.name());
        if (gates.updateById(gate) != 1) {
            throw new IllegalStateException("直充限频迁移关闭门禁版本冲突");
        }
        return compatible;
    }

    /**
     * 已有可信指纹绑定时可直接复用冻结项；首次绑定必须核验原target与队列HMAC。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    public boolean hasVerifiedIdentityBinding() {
        var gate = gates.selectById(GATE_ID);
        return gate != null && gate.getIdentityKeyFingerprint() != null;
    }

    /**
     * 仅完整候选迁移事务发布READY；组合blocked保持，局部风险的成功或失败核实不直接发布READY。 发布制品版本必须已通过离线证据核对，不记录密钥或指纹到日志。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    @Transactional(
            transactionManager = "supplyChainTransactionManager",
            propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public void publishReady(String verifiedArtifact) {
        log.info("[直充限频迁移] 提交完整候选覆盖发布门禁");
        var gate = gates.selectById(GATE_ID);
        if (gate == null
                || !RechargeFrequencyMigrationGateState.CLOSED.name().equals(gate.getState())) {
            throw new IllegalStateException("直充限频迁移发布门禁状态变化");
        }
        gate.setState(RechargeFrequencyMigrationGateState.READY.name());
        gate.setVerifiedArtifact(verifiedArtifact);
        gate.setIdentityKeyFingerprint(currentKeyFingerprint());
        gate.setVerifiedAt(clock.now());
        if (gates.updateById(gate) != 1) {
            throw new IllegalStateException("直充限频迁移发布门禁版本冲突");
        }
    }

    /**
     * 只返回新建SHA256摘要，不泄漏原密钥；本地临时key在finally清零。 设计见
     * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
     */
    public byte[] currentKeyFingerprint() {
        byte[] key = keyProvider.identityKey();
        if (key == null || key.length != 32) {
            throw new IllegalStateException("直充限频迁移身份密钥不可用");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(key);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("直充限频迁移指纹算法不可用", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
