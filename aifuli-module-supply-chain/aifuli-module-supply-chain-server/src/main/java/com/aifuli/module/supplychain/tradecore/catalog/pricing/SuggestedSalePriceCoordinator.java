package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 根锁内先更新动态建议价，再由调用者发布下游版本。所有当前Offer版本（包括非最低价）登记时间边界，
 * 调度与状态共用事务；延迟取Timer避免Registry→Handler→定价服务构造环。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#62-报价生效到期与重启。
 */
@Service @RequiredArgsConstructor
@Import({SuggestedSalePriceResolver.class, SuggestedSalePriceStateService.class, SuggestedPriceBoundaryTimerHandler.class})
public class SuggestedSalePriceCoordinator {
    private final SuggestedSalePriceResolver resolver;
    private final SuggestedSalePriceStateService states;
    private final SupplierOfferMapper offers;
    private final SupplierOfferVersionMapper versions;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper json;
    private final ObjectProvider<PersistentTimer> timers;

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public void refresh(PlatformSkuDO sku, LocalDateTime now) {
        var result = resolver.resolve(sku, now);
        LocalDateTime next = null;
        if (SuggestedSalePriceResolver.isDynamic(sku)) {
            List<SupplierOfferDO> current = offers.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                    .eq(SupplierOfferDO::getPlatformSkuId, sku.getId())
                    .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                    .isNotNull(SupplierOfferDO::getCurrentVersionId));
            if (!current.isEmpty()) {
                Map<Long, Long> pointers = new HashMap<>();
                for (SupplierOfferDO offer : current) {
                    Long previous = pointers.putIfAbsent(offer.getCurrentVersionId(), offer.getId());
                    if (previous != null && !previous.equals(offer.getId())) {
                        throw new IllegalStateException("current offer version has multiple owners");
                    }
                }
                var loaded = versions.selectBatchIds(pointers.keySet());
                if (loaded.size() != pointers.size()
                        || !loaded.stream().map(SupplierOfferVersionDO::getId).collect(java.util.stream.Collectors.toSet())
                            .equals(pointers.keySet())) {
                    throw new IllegalStateException("current offer version is missing");
                }
                for (SupplierOfferVersionDO version : loaded) {
                    if (!Objects.equals(version.getOfferId(), pointers.get(version.getId()))) {
                        throw new IllegalStateException("current offer version ownership mismatch");
                    }
                    next = schedule(sku.getId(), version, Boundary.FROM, version.getValidFrom(), now, next);
                    next = schedule(sku.getId(), version, Boundary.TO, version.getValidTo(), now, next);
                }
            }
        }
        states.refresh(sku, result, next, now);
    }

    private enum Boundary { FROM, TO }

    private LocalDateTime schedule(Long skuId, SupplierOfferVersionDO version, Boundary boundary,
                                    LocalDateTime at, LocalDateTime now, LocalDateTime earliest) {
        if (at == null || !at.isAfter(now)) return earliest;
        var payload = json.createObjectNode().put("platformSkuId", skuId).put("offerVersionId", version.getId())
                .put("boundary", boundary.name());
        timers.getObject().scheduleOnce(SuggestedPriceBoundaryTimerHandler.TASK_TYPE,
                version.getId() + ":" + boundary, payload, clock.toInstant(at));
        return earliest == null || at.isBefore(earliest) ? at : earliest;
    }
}
