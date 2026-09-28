package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSkuDailyQuotaDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSkuDailyQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 管理端维护供应商+平台 SKU 的自然日累计供货配额；无配置行表示无限额。
 * 不接受直接写入已用量，路由从订单事实计算占用，避免人工编辑漂移。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierDailyQuotaAdminService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplierMapper supplierMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierSkuDailyQuotaMapper quotaMapper;
    private final SupplyChainDatabaseClock databaseClock;

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public QuotaView get(String supplierCode, String skuCode) {
        log.info("查询供应商SKU自然日配额 supplierCode={} skuCode={}", supplierCode, skuCode);
        Subject subject = requireSubject(supplierCode, skuCode, false);
        SupplierSkuDailyQuotaDO quota = quotaMapper.selectBySupplierAndSku(
                subject.supplier().getId(), subject.sku().getId());
        return view(subject, quota);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public QuotaView put(String supplierCode, String skuCode, long dailyLimit, Long operatorId) {
        log.info("设置供应商SKU自然日配额 supplierCode={} skuCode={} dailyLimit={} operatorId={}",
                supplierCode, skuCode, dailyLimit, operatorId);
        if (dailyLimit <= 0 || operatorId == null) {
            throw new IllegalArgumentException("每日供货配额与操作人必须有效");
        }
        Subject subject = requireSubject(supplierCode, skuCode, true);
        SupplierSkuDailyQuotaDO quota = quotaMapper.selectBySupplierAndSku(
                subject.supplier().getId(), subject.sku().getId());
        LocalDateTime now = databaseClock.now();
        if (quota == null) {
            quota = SupplierSkuDailyQuotaDO.builder()
                    .supplierId(subject.supplier().getId()).platformSkuId(subject.sku().getId())
                    .dailyLimit(dailyLimit).version(0L).createdBy(operatorId).createdAt(now)
                    .updatedBy(operatorId).updatedAt(now).build();
            quotaMapper.insert(quota);
        } else {
            quota.setDailyLimit(dailyLimit);
            quota.setUpdatedBy(operatorId);
            quota.setUpdatedAt(now);
            if (quotaMapper.updateById(quota) != 1) {
                throw new IllegalStateException("供应商SKU自然日配额已被并发修改");
            }
        }
        return view(subject, quota);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public void delete(String supplierCode, String skuCode, Long operatorId) {
        log.info("删除供应商SKU自然日配额 supplierCode={} skuCode={} operatorId={}",
                supplierCode, skuCode, operatorId);
        if (operatorId == null) {
            throw new IllegalArgumentException("操作人不能为空");
        }
        Subject subject = requireSubject(supplierCode, skuCode, true);
        SupplierSkuDailyQuotaDO quota = quotaMapper.selectBySupplierAndSku(
                subject.supplier().getId(), subject.sku().getId());
        if (quota != null && quotaMapper.deleteById(quota.getId()) != 1) {
            throw new IllegalStateException("供应商SKU自然日配额删除失败");
        }
    }

    private Subject requireSubject(String supplierCode, String skuCode, boolean forWrite) {
        if (supplierCode == null || supplierCode.isBlank() || skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("供应商编码与平台SKU编码不能为空");
        }
        // 写路径在首个一致性读前锁 SKU；等待其它受理/配置事务后再建立新快照。
        PlatformSkuDO sku = forWrite ? skuMapper.selectByCodeForUpdate(skuCode)
                : skuMapper.selectByCode(skuCode);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null || sku == null) {
            throw new IllegalArgumentException("供应商或平台SKU不存在");
        }
        return new Subject(supplier, sku);
    }

    private static QuotaView view(Subject subject, SupplierSkuDailyQuotaDO quota) {
        return new QuotaView(subject.supplier().getSupplierCode(), subject.sku().getSkuCode(),
                quota == null ? null : quota.getDailyLimit(),
                quota == null ? null : quota.getUpdatedAt());
    }

    private record Subject(SupplierDO supplier, PlatformSkuDO sku) {
    }

    /**
     * @param supplierCode 供应商永久业务编码
     * @param skuCode 平台SKU永久业务编码
     * @param dailyLimit 供应商对该平台SKU的自然日累计供货数量上限；空表示无限额
     * @param updatedAt 最后修改时间，Asia/Shanghai
     */
    public record QuotaView(String supplierCode, String skuCode, Long dailyLimit,
                            LocalDateTime updatedAt) {
    }
}
