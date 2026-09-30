package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商渠道绑定 Mapper。
 *
 * <p>遵循规范：优先 LambdaQueryWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#5-主数据与标准商品池}。</p>
 */
@Mapper
public interface SupplierChannelBindingMapper extends BaseMapperX<SupplierChannelBindingDO> {

    /** 按渠道与外部商户解析当前启用的绑定；O2O 受理与 INSTORE 入口校验共用，受理时冻结该版本。 */
    default SupplierChannelBindingDO selectCurrentEnabled(String channelCode, String externalMerchantId) {
        return selectOne(new LambdaQueryWrapper<SupplierChannelBindingDO>()
                .eq(SupplierChannelBindingDO::getChannelCode, channelCode)
                .eq(SupplierChannelBindingDO::getExternalMerchantId, externalMerchantId)
                .eq(SupplierChannelBindingDO::getCurrentFlag, (byte) 1)
                .eq(SupplierChannelBindingDO::getStatus, (short) 10));
    }
}
