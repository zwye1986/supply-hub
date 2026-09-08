package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface BuyerProductL2OverrideMapper extends BaseMapperX<BuyerProductL2OverrideDO> {
    default BuyerProductL2OverrideDO selectByBuyerProductId(Long buyerProductId) {
        return selectById(buyerProductId);
    }

    /**
     * 清空手工覆盖指针。MP 默认跳过 null 字段，必须用 Wrapper 显式 SET NULL。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    default int clearCurrentVersion(Long buyerProductId, Long expectedVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<BuyerProductL2OverrideDO>()
                .eq(BuyerProductL2OverrideDO::getBuyerProductId, buyerProductId)
                .eq(BuyerProductL2OverrideDO::getVersion, expectedVersion)
                .set(BuyerProductL2OverrideDO::getCurrentVersionId, null)
                .set(BuyerProductL2OverrideDO::getUpdatedAt, updatedAt)
                .set(BuyerProductL2OverrideDO::getVersion, expectedVersion + 1));
    }
}
