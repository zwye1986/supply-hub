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
     * 「每人」维度自052起按实际履约目标手机号（fulfillment_account_value）统计，不再回退 buyer_user_id。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}、
     * {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#4-手机号限购与内部备注}。</p>
     */
    Long sumEffectivePurchaseQuantity(
            @Param("orderType") String orderType,
            @Param("projectId") Long projectId,
            @Param("fulfillmentAccountValue") String fulfillmentAccountValue,
            @Param("platformSkuId") Long platformSkuId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("failedStatus") short failedStatus,
            @Param("cancelledStatus") short cancelledStatus,
            @Param("fullyRefundedStatus") short fullyRefundedStatus);

    /**
     * 按履约目标手机号聚合仍占用额度的标准商品数量，不得改用买家 {@code buyer_user_id}。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    Long sumEffectivePhoneBindPurchaseQuantity(
            @Param("orderType") String orderType,
            @Param("projectId") Long projectId,
            @Param("fulfillmentAccountValue") String fulfillmentAccountValue,
            @Param("platformSkuId") Long platformSkuId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("failedStatus") short failedStatus,
            @Param("cancelledStatus") short cancelledStatus,
            @Param("fullyRefundedStatus") short fullyRefundedStatus);

    /**
     * 统计供应商+SKU 当日仍占用供货配额的数量。仅失败、取消释放；成功后退款仍已消耗供货。
     * 调用方须在写订单的同一事务中先锁平台 SKU，避免并发路由越过配额。
     * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
     */
    Long sumReservedSupplierSkuQuantity(
            @Param("orderType") String orderType,
            @Param("supplierId") Long supplierId,
            @Param("platformSkuId") Long platformSkuId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("failedStatus") short failedStatus,
            @Param("cancelledStatus") short cancelledStatus);

    /** 按供应链采购单号查询总订单，用于 DETAIL 入口只读加载。 */
    default SupplyChainOrderDO selectByOrderNo(String orderNo) {
        return selectOne(new LambdaQueryWrapper<SupplyChainOrderDO>()
                .eq(SupplyChainOrderDO::getOrderNo, orderNo));
    }

    /**
     * 离线身份迁移专用：回填总订单格式 2 无用户指纹/业务摘要与真实履约目标账户类型、
     * 规范化 MOBILE 手机快照。仅由 StandardOrderIdentityMigrationService 在全批验证通过后的
     * 供应链事务内调用；不触碰订单状态、资金、频率冻结等任何其他列。
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#51-离线应用迁移与实际手机号覆盖。
     */
    default int rebuildIdentitySnapshot(Long id, byte[] requestFingerprint, byte[] buyerOrderDigest,
                                         String fulfillmentAccountType, String fulfillmentAccountValue,
                                         LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<SupplyChainOrderDO>()
                .set(SupplyChainOrderDO::getRequestFingerprint, requestFingerprint)
                .set(SupplyChainOrderDO::getBuyerOrderDigest, buyerOrderDigest)
                .set(SupplyChainOrderDO::getFulfillmentAccountType, fulfillmentAccountType)
                .set(SupplyChainOrderDO::getFulfillmentAccountValue, fulfillmentAccountValue)
                .set(SupplyChainOrderDO::getUpdatedAt, updatedAt)
                .eq(SupplyChainOrderDO::getId, id));
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
