package com.aifuli.module.supplychain.routing.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 发布配置、供给版本和动态资格事实合并后的候选快照。 */
public record RoutingCandidate(int candidatePriority,
                               Long supplierId,
                               String supplierCode,
                               String supplierName,
                               Long offerId,
                               Long offerVersionId,
                               Long connectorVersionId,
                               Long inventorySourceId,
                               Long supplierSettlementUnitPrice,
                               String currency,
                               boolean eligible,
                               String availabilityClass,
                               List<String> rejectionReasons,
                               String inventoryFactStatus,
                               String balanceFactStatus,
                               String healthFactStatus,
                               LocalDateTime latestFactAt) {

    public RoutingCandidate {
        if (candidatePriority < 0 || supplierId == null || supplierCode == null
                || supplierCode.isBlank() || supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("Routing candidate identity is invalid");
        }
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        if (eligible && (!rejectionReasons.isEmpty() || offerId == null || offerVersionId == null
                || connectorVersionId == null || inventorySourceId == null
                || supplierSettlementUnitPrice == null || !"CNY".equals(currency))) {
            throw new IllegalArgumentException("Eligible routing candidate lacks immutable execution snapshot");
        }
        if (!eligible && rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("Ineligible routing candidate requires a stable rejection reason");
        }
    }
}
