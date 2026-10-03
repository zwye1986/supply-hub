package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采购项目 Mapper。
 *
 * <p>项目编码全局唯一且永久稳定，查询使用 MyBatis Plus 方法引用，避免字符串列名漂移。
 * 设计见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#42-buyerproject}。</p>
 */
@Mapper
public interface BuyerProjectMapper extends BaseMapperX<BuyerProjectDO> {

    /** 按不可变主键当前读锁定准入记录；设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。 */
    default BuyerProjectDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(BuyerProjectDO::getId, id);
    }

    default BuyerProjectDO selectByProjectCode(String projectCode) {
        return selectOne(BuyerProjectDO::getProjectCode, projectCode);
    }

    default BuyerProjectDO selectByProjectCodeForUpdate(String projectCode) {
        return selectOneForUpdate(BuyerProjectDO::getProjectCode, projectCode);
    }
}
