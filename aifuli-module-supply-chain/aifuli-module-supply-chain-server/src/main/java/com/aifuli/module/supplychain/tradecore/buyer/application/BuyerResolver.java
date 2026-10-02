package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerTenantBindingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerTenantBindingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_DISABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_NOT_FOUND;

/** 把已认证来源系统中的外部租户身份解析为中台采购商。 */
@Service
@RequiredArgsConstructor
public class BuyerResolver {

    private final BuyerTenantBindingMapper bindingMapper;
    private final BuyerMapper buyerMapper;

    public BuyerSnapshot resolve(String sourceSystem, String externalTenantId, LocalDateTime occurredAt) {
        BuyerTenantBindingDO binding = bindingMapper.selectCurrent(sourceSystem, externalTenantId, occurredAt);
        if (binding == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        BuyerDO buyer = buyerMapper.selectById(binding.getBuyerId());
        if (buyer == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        if (buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw exception(BUYER_DISABLED);
        }
        return new BuyerSnapshot(buyer.getId(), buyer.getBuyerCode(), buyer.getBuyerName(),
                buyer.getShortName(), binding.getBindingVersion());
    }

    /**
     * 按采购商主键校验并返回启用快照，供标准商品等由项目可信上下文驱动的流程使用。
     */
    public BuyerSnapshot requireActiveById(Long buyerId) {
        if (buyerId == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        BuyerDO buyer = buyerMapper.selectById(buyerId);
        if (buyer == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        if (buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw exception(BUYER_DISABLED);
        }
        return new BuyerSnapshot(buyer.getId(), buyer.getBuyerCode(), buyer.getBuyerName(),
                buyer.getShortName(), null);
    }

    public record BuyerSnapshot(Long id, String buyerCode, String buyerName,
                                String shortName, Integer bindingVersion) {
    }
}
