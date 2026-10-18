package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.MerchantCallLeaseDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.MerchantQuotaDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.MerchantCallLeaseMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.MerchantQuotaMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform;
import com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;

/**
 * 商户当前统一额度、成功准入与并发释放的唯一所有者；不依赖border、不触碰资金。
 * 准入必须加入原单事务，配置与释放独立短事务；同额度根行锁串行化跨实例竞争。
 * 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#6-准入算法与事务。
 */
@Service @RequiredArgsConstructor
public class MerchantQuotaService {
    private static final String TX = "supplyChainTransactionManager";
    private static final int CLEANUP_BATCH = 256;
    private final MerchantQuotaMapper quotas;
    private final MerchantCallLeaseMapper leases;
    private final SupplyChainDatabaseClock clock;

    public record Configure(MerchantQuotaPlatform platform,
                            /** 非秘密供应商商户账号，原值精确匹配 */ String merchantAccount,
                            /** 每秒成功准入上限 */ int rateLimitPerSecond,
                            /** 同步调用并发占用上限 */ int concurrencyLimit,
                            Long expectedVersion, Long operatorId,
                            /** 配置变更原因 */ String reason) { }
    public record Configuration(long id, long version, int rateLimitPerSecond, int concurrencyLimit) { }

    /** 只代表本地资源竞争失败；抛出使原单认领完整回滚，不伪造供应商事实。 */
    public static final class CapacityUnavailable extends RuntimeException {
        private final LocalDateTime retryAt;
        public CapacityUnavailable(LocalDateTime retryAt) { super("商户额度暂不可用"); this.retryAt = retryAt; }
        public LocalDateTime retryAt() { return retryAt; }
    }
    public static final class MissingConfiguration extends RuntimeException {
        public MissingConfiguration() { super("商户共享额度未配置"); }
    }

    /** null版本只允许创建；调额不重置窗口/租约，不改变任何原单冻结内容。 */
    @Transactional(transactionManager = TX, propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Configuration configure(Configure command) {
        if (command == null) throw new IllegalArgumentException("缺少商户额度配置");
        identity(command.platform(), command.merchantAccount());
        if (command.rateLimitPerSecond() <= 0 || command.concurrencyLimit() <= 0 || command.operatorId() == null
                || command.reason() == null || command.reason().isBlank() || command.reason().length() > 255
                || command.expectedVersion() != null && command.expectedVersion() < 0) throw new IllegalArgumentException("商户额度配置无效");
        var row = quotas.lock(command.platform().name(), command.merchantAccount());
        var now = clock.now();
        if (row == null) {
            if (command.expectedVersion() != null) throw new IllegalStateException("商户额度版本冲突");
            row = MerchantQuotaDO.builder().platformCode(command.platform().name()).merchantAccount(command.merchantAccount())
                    .rateLimitPerSecond(command.rateLimitPerSecond()).concurrencyLimit(command.concurrencyLimit()).version(0L)
                    .createdBy(command.operatorId()).createdAt(now).updatedBy(command.operatorId()).updatedAt(now).changeReason(command.reason()).build();
            quotas.insert(row); // 并发首次创建由唯一键拒绝，不以旧版本覆盖新配置。
        } else {
            if (!row.getVersion().equals(command.expectedVersion())) throw new IllegalStateException("商户额度版本冲突");
            long version = row.getVersion();
            if (quotas.update(null, new LambdaUpdateWrapper<MerchantQuotaDO>().eq(MerchantQuotaDO::getId, row.getId())
                    .eq(MerchantQuotaDO::getVersion, version).set(MerchantQuotaDO::getVersion, version + 1)
                    .set(MerchantQuotaDO::getRateLimitPerSecond, command.rateLimitPerSecond()).set(MerchantQuotaDO::getConcurrencyLimit, command.concurrencyLimit())
                    .set(MerchantQuotaDO::getUpdatedBy, command.operatorId()).set(MerchantQuotaDO::getUpdatedAt, now).set(MerchantQuotaDO::getChangeReason, command.reason())) != 1) {
                throw new IllegalStateException("商户额度版本冲突");
            }
            row.setVersion(version + 1); row.setRateLimitPerSecond(command.rateLimitPerSecond()); row.setConcurrencyLimit(command.concurrencyLimit());
        }
        return new Configuration(row.getId(), row.getVersion(), row.getRateLimitPerSecond(), row.getConcurrencyLimit());
    }

    /**
     * 调用者先持原单聚合锁；额度锁内读取DB时间并保守统计成功认领，不凭HTTP实际发送作计数退还。
     * 隔离级别READ_COMMITTED由原单认领保证；投影使用当前读防止旧RR快照漏算并发。
     */
    @Transactional(transactionManager = TX, propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void admit(MerchantQuotaPlatform platform, String account, String operation, long generation, LocalDateTime leaseUntil) {
        identity(platform, account);
        if (operation == null || operation.isBlank() || operation.length() > 64 || generation <= 0 || leaseUntil == null) throw new IllegalArgumentException("调用额度身份无效");
        var quota = quotas.lock(platform.name(), account);
        if (quota == null) throw new MissingConfiguration();
        var now = clock.now();
        if (!leaseUntil.isAfter(now)) throw new IllegalStateException("调用资格已过期");
        var same = leases.selectOne(new LambdaQueryWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getOperationNo, operation)
                .eq(MerchantCallLeaseDO::getExecutionGeneration, generation).last("FOR UPDATE"));
        if (same != null) throw new IllegalStateException("调用代次已取得额度，禁止重放发送资格");
        // 根锁串行化写；当前读列表只含最近1秒或活动租约，不扫描全部历史数据。
        var recent = leases.selectList(new LambdaQueryWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getQuotaId, quota.getId())
                .gt(MerchantCallLeaseDO::getAdmittedAt, now.minusSeconds(1)).le(MerchantCallLeaseDO::getAdmittedAt, now)
                .orderByAsc(MerchantCallLeaseDO::getAdmittedAt).last("FOR UPDATE"));
        var active = leases.selectList(new LambdaQueryWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getQuotaId, quota.getId())
                .isNull(MerchantCallLeaseDO::getReleasedAt).gt(MerchantCallLeaseDO::getLeaseUntil, now)
                .orderByAsc(MerchantCallLeaseDO::getLeaseUntil).last("FOR UPDATE"));
        var retry = MerchantQuotaPolicy.retryAt(now, quota.getRateLimitPerSecond(), quota.getConcurrencyLimit(), recent.size(), active.size(),
                recent.isEmpty() ? null : recent.getFirst().getAdmittedAt(), active.isEmpty() ? null : active.getFirst().getLeaseUntil());
        if (retry != null) throw new CapacityUnavailable(retry);
        leases.insert(MerchantCallLeaseDO.builder().quotaId(quota.getId()).operationNo(operation).executionGeneration(generation)
                .admittedAt(now).leaseUntil(leaseUntil).build());
        var expired = leases.selectList(new LambdaQueryWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getQuotaId, quota.getId())
                .le(MerchantCallLeaseDO::getAdmittedAt, now.minusSeconds(1))
                .and(w -> w.isNotNull(MerchantCallLeaseDO::getReleasedAt).or().le(MerchantCallLeaseDO::getLeaseUntil, now))
                .orderByAsc(MerchantCallLeaseDO::getId).last("LIMIT " + CLEANUP_BATCH));
        if (!expired.isEmpty()) leases.delete(new LambdaQueryWrapper<MerchantCallLeaseDO>()
                .in(MerchantCallLeaseDO::getId, expired.stream().map(MerchantCallLeaseDO::getId).toList()));
    }

    /** 只释放原代次并发；已过期/清理/重复结束均幂等，不向上回调原单或增加速率额度。 */
    @Transactional(transactionManager = TX, propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void release(String operation, long generation) {
        var row = leases.selectOne(new LambdaQueryWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getOperationNo, operation)
                .eq(MerchantCallLeaseDO::getExecutionGeneration, generation));
        if (row == null) return;
        var quota = quotas.selectOneForUpdate(MerchantQuotaDO::getId, row.getQuotaId());
        if (quota == null) throw new IllegalStateException("商户额度身份丢失");
        leases.update(null, new LambdaUpdateWrapper<MerchantCallLeaseDO>().eq(MerchantCallLeaseDO::getId, row.getId())
                .isNull(MerchantCallLeaseDO::getReleasedAt).set(MerchantCallLeaseDO::getReleasedAt, clock.now()));
    }
    private static void identity(MerchantQuotaPlatform platform, String account) {
        if (platform == null || account == null || account.isBlank() || account.length() > 128) throw new IllegalArgumentException("商户额度身份无效");
    }
}
