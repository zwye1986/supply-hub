package com.aifuli.module.supplychain.border.order.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 供应链总订单 DAL 映射器。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}
 * 与 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
 */
@Mapper
public interface SupplyChainOrderMapper extends BaseMapperX<SupplyChainOrderDO> {

    /**
     * 聚合限购窗口内仍占用额度的标准商品数量。聚合 SQL 放在 XML，避免在 Java 中加载全部历史订单。
     * 失败、取消和全额退款状态通过参数化枚举码排除，禁止在 SQL 中散落业务魔法值。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    Long sumEffectivePurchaseQuantity(
            @Param("orderType") String orderType,
            @Param("projectId") Long projectId,
            @Param("buyerUserId") String buyerUserId,
            @Param("platformSkuId") Long platformSkuId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("failedStatus") short failedStatus,
            @Param("cancelledStatus") short cancelledStatus,
            @Param("fullyRefundedStatus") short fullyRefundedStatus);

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
