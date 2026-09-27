package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerTenantBindingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerTenantBindingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BuyerCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BuyerMapper buyerMapper;
    private final BuyerTenantBindingMapper bindingMapper;
    private final ScopedBusinessCodeService scopedBusinessCodeService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createBuyer(CreateBuyerCommand command) {
        if (command.buyerCode() != null && !command.buyerCode().isEmpty()) {
            throw com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(
                    com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_MANUAL_NOT_ALLOWED,
                    "分销商");
        }
        ScopedBusinessCodeService.Allocation allocation = scopedBusinessCodeService.allocateRoot(
                ScopedBusinessCodeService.Kind.BYR, null);
        LocalDateTime now = databaseClock.now();
        LocalDateTime validFrom = command.validFrom() == null ? now : command.validFrom();
        BuyerDO buyer = BuyerDO.builder()
                .buyerCode(allocation.code())
                .codeScopeNo((short) allocation.number())
                .buyerName(command.buyerName())
                .shortName(command.shortName())
                .buyerType("SAAS_MALL")
                .status(BuyerStatus.ACTIVE.getCode())
                .contactName(command.contactName())
                .contactMobile(command.contactMobile())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        buyerMapper.insert(buyer);

        BuyerTenantBindingDO binding = BuyerTenantBindingDO.builder()
                .buyerId(buyer.getId())
                .sourceSystem(command.sourceSystem())
                .externalTenantId(command.externalTenantId())
                .bindingVersion(1)
                .validFrom(validFrom)
                .currentFlag((short) 1)
                .status((short) 10)
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        bindingMapper.insert(binding);
        return buyer.getBuyerCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String buyerCode, BuyerStatus target, Long operatorId) {
        BuyerDO buyer = requireBuyerForUpdate(buyerCode);
        BuyerStatus current = BuyerStatus.fromCode(buyer.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Buyer status transition is not allowed: " + current + " -> " + target);
        }
        buyer.setStatus(target.getCode());
        buyer.setUpdatedBy(operatorId);
        buyer.setUpdatedAt(databaseClock.now());
        if (buyerMapper.updateById(buyer) != 1) {
            throw new IllegalStateException("Buyer status update lost optimistic lock");
        }
    }

    private BuyerDO requireBuyerForUpdate(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(buyerCode);
        if (buyer == null) {
            throw new IllegalArgumentException("Buyer does not exist: " + buyerCode);
        }
        return buyer;
    }

    public record CreateBuyerCommand(String buyerCode, String buyerName, String shortName,
                                     String contactName, String contactMobile,
                                     String sourceSystem, String externalTenantId,
                                     LocalDateTime validFrom, Long operatorId) {
    }
}
