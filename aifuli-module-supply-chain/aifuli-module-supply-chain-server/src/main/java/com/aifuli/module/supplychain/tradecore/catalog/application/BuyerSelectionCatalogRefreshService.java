package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.BuyerProductPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 自动目录刷新事务外门面：禁止活动事务，调用独立短事务 worker，必要时在事务外委派已有定价单 BPD 重算后重刷一次。
 * 门面自身不写任何表、不自调用、不用挂起事务偷跑；技术失败继续上抛由管理编排映射。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(BuyerCatalogProperties.class)
public class BuyerSelectionCatalogRefreshService {

    /** 触发种类：管理保存必须带真实操作人；扫描／下单准备使用配置的系统操作人。 */
    public enum Trigger { ADMIN_SAVE, SWEEP, ORDER_PREPARE }

    private final BuyerSelectionCatalogRefreshTransactionalWorker worker;
    private final GenericBatchPricingService pricing;
    private final BuyerCatalogProperties properties;

    /**
     * 单LST刷新：ADMIN_SAVE要求真实操作人；扫描／下单准备忽略调用方身份并取配置系统操作人。
     * 首次返回需重算身份时，先结束LST短事务，委派已有定价单 BPD 重算并确认提交后再刷新一次，最多一次。
     * 设计见 §4.3「Task 3 实施接口与事务边界」，见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
     */
    public BuyerSelectionCatalogRefreshResult refresh(Long listingId, Trigger trigger, Long operatorId) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(trigger, "trigger");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "Catalog refresh facade must run without an active transaction");
        }
        if (trigger == Trigger.ADMIN_SAVE && (operatorId == null || operatorId <= 0)) {
            throw new IllegalArgumentException("ADMIN_SAVE catalog refresh requires a real operator");
        }
        Long effectiveOperator = trigger == Trigger.ADMIN_SAVE
                ? operatorId
                : (properties.hasSystemOperator() ? properties.getSystemOperatorId() : null);
        long startedNanos = System.nanoTime();
        BuyerSelectionCatalogRefreshTransactionalWorker.Outcome first =
                worker.refresh(listingId, effectiveOperator);
        BuyerSelectionCatalogRefreshResult result;
        if (first.pricingRecalculationBuyerProductId() == null
                || !(trigger == Trigger.ADMIN_SAVE || properties.hasSystemOperator())) {
            // 无重算需求，或扫描／下单准备未配置系统操作人（不委派重算写定价表，按首次真实评估返回）。
            result = first.result();
        } else {
            pricing.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(
                    first.pricingRecalculationBuyerProductId(), effectiveOperator));
            result = worker.refresh(listingId, effectiveOperator).result();
        }
        // worker事务已提交后才记录业务事实：发布推进INFO、无变化与待就绪DEBUG，技术失败继续上抛不打堆栈。
        if (result.refreshOutcome() == BuyerCatalogRefreshOutcome.UPDATED) {
            log.info("[refreshListing][目录刷新发布完成] listingId={}, outcome={}, publicationState={}, "
                            + "availability={}, elapsedMs={}",
                    listingId, result.refreshOutcome(), result.publicationState(),
                    result.availabilityStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        } else {
            log.debug("[refreshListing][目录刷新完成] listingId={}, outcome={}, publicationState={}, "
                            + "availability={}, reason={}, elapsedMs={}",
                    listingId, result.refreshOutcome(), result.publicationState(),
                    result.availabilityStatus(), result.reasonCode(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }
        return result;
    }
}
