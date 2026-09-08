package com.aifuli.module.supplychain.border.order.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface SupplyChainOrderMapper extends BaseMapperX<SupplyChainOrderDO> {

    /** 按供应链采购单号查询总订单，用于 DETAIL 入口只读加载。 */
    default SupplyChainOrderDO selectByOrderNo(String orderNo) {
        return selectOne(new LambdaQueryWrapper<SupplyChainOrderDO>()
                .eq(SupplyChainOrderDO::getOrderNo, orderNo));
    }

    default SupplyChainOrderDO selectByBuyerOrder(Long buyerId, String buyerOrderNo) {
        return selectOne(SupplyChainOrderDO::getBuyerId, buyerId,
                SupplyChainOrderDO::getBuyerOrderNo, buyerOrderNo);
    }

    default int attachDetail(Long id, Long detailId, long detailVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<SupplyChainOrderDO>()
                .set(SupplyChainOrderDO::getDetailId, detailId)
                .set(SupplyChainOrderDO::getDetailVersion, detailVersion)
                .set(SupplyChainOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(SupplyChainOrderDO::getId, id)
                .isNull(SupplyChainOrderDO::getDetailId));
    }

    default int advanceDetailVersion(Long id, long expectedVersion, long nextVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<SupplyChainOrderDO>()
                .set(SupplyChainOrderDO::getDetailVersion, nextVersion)
                .set(SupplyChainOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(SupplyChainOrderDO::getId, id)
                .eq(SupplyChainOrderDO::getDetailVersion, expectedVersion));
    }

    /**
     * CAS 投影售后状态并推进 detailVersion；退款 Case 每次状态变更后由 INSTORE Lifecycle 调用。
     * 售后状态码见 {@code sc_supply_chain_order.aftersale_status} DDL CHECK：
     * 0 无售后、10 处理中、20 部分退款、30 全部退款、90 人工处理。
     */
    default int advanceAftersaleStatus(Long id, long expectedVersion, long nextVersion,
                                       short aftersaleStatus, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<SupplyChainOrderDO>()
                .set(SupplyChainOrderDO::getAftersaleStatus, aftersaleStatus)
                .set(SupplyChainOrderDO::getDetailVersion, nextVersion)
                .set(SupplyChainOrderDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(SupplyChainOrderDO::getId, id)
                .eq(SupplyChainOrderDO::getDetailVersion, expectedVersion));
    }
}
