package com.aifuli.module.supplychain.integration.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.dal.dataobject.BuyerIntegrationCapabilityDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Objects;

/**
 * 采购项目版本化集成能力 Mapper。
 *
 * <p>按档案版本与受控能力枚举精确定位路由，避免字符串列名和能力魔法值。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
@Mapper
public interface BuyerIntegrationCapabilityMapper extends BaseMapperX<BuyerIntegrationCapabilityDO> {

    default BuyerIntegrationCapabilityDO selectByProfileVersionAndCode(
            Long profileVersionId, BuyerIntegrationCapabilityCode capability) {
        Objects.requireNonNull(capability, "capability");
        return selectOne(new LambdaQueryWrapper<BuyerIntegrationCapabilityDO>()
                .eq(BuyerIntegrationCapabilityDO::getProfileVersionId, profileVersionId)
                .eq(BuyerIntegrationCapabilityDO::getCapabilityCode, capability.name()));
    }
}
