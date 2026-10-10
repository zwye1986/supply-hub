package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerListingMapper extends BaseMapperX<BuyerListingDO> {

    /** 排序规则专属标量比较，Lambda不能表达有界字符前缀；见桥接设计§3.1。 */
    boolean isReservedBuyerProductCode(@org.apache.ibatis.annotations.Param("normalizedCode") String normalizedCode,
                                      @org.apache.ibatis.annotations.Param("reservedPrefix") String reservedPrefix);

    default BuyerListingDO selectByBuyerAndSku(Long buyerId, Long skuId) {
        return selectOne(new LambdaQueryWrapper<BuyerListingDO>().eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getPlatformSkuId, skuId));
    }

    default BuyerListingDO selectByBuyerAndSkuForUpdate(Long buyerId, Long skuId) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>().eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getPlatformSkuId, skuId));
    }

    default BuyerListingDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(BuyerListingDO::getId, id);
    }

    default BuyerListingDO selectByListingNo(String listingNo) {
        return selectOne(BuyerListingDO::getListingNo, listingNo);
    }

    default BuyerListingDO selectByListingNoForUpdate(String listingNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getListingNo, listingNo));
    }

    default BuyerListingDO selectByBuyerAndSkuCode(Long buyerId, String buyerSkuCode) {
        return selectOne(BuyerListingDO::getBuyerId, buyerId,
                BuyerListingDO::getBuyerSkuCode, buyerSkuCode);
    }

    /**
     * 悲观锁锁定采购商上架记录，防止并发重开与受理冲突。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    default BuyerListingDO selectByBuyerAndSkuCodeForUpdate(Long buyerId, String buyerSkuCode) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getBuyerSkuCode, buyerSkuCode));
    }

    /** 按买家商品身份定位受理目录；管理保存后目录定位使用。 */
    default BuyerListingDO selectByBuyerProductId(Long buyerProductId) {
        return selectOne(BuyerListingDO::getBuyerProductId, buyerProductId);
    }

    /** 扫描遍历上界：AUTO且非退役的最大主键；无候选返回null。见设计 §6.2「公平推进与重启」。 */
    default Long selectMaxAutoSweepableId() {
        List<BuyerListingDO> rows = selectList(new LambdaQueryWrapper<BuyerListingDO>()
                .select(BuyerListingDO::getId)
                .eq(BuyerListingDO::getManagementMode, "AUTO_SELECTION")
                .ne(BuyerListingDO::getStatus, BuyerListingStatus.RETIRED.getCode())
                .orderByDesc(BuyerListingDO::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.getFirst().getId();
    }

    /** 扫描候选有限ID集：AUTO且非退役、主键升序、(afterId, upperId]；只读ID不锁行。见设计 §6.1。 */
    default List<Long> selectAutoSweepableIds(Long afterId, Long upperId, int limit) {
        return selectList(new LambdaQueryWrapper<BuyerListingDO>()
                .select(BuyerListingDO::getId)
                .eq(BuyerListingDO::getManagementMode, "AUTO_SELECTION")
                .ne(BuyerListingDO::getStatus, BuyerListingStatus.RETIRED.getCode())
                .gt(afterId != null, BuyerListingDO::getId, afterId)
                .le(BuyerListingDO::getId, upperId)
                .orderByAsc(BuyerListingDO::getId)
                .last("LIMIT " + limit))
                .stream().map(BuyerListingDO::getId).toList();
    }
}
