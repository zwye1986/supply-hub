package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 履约交付资产持久化 Mapper。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#73-supplierorderoperation-与-fulfillmentasset}。</p>
 */
@Mapper
public interface FulfillmentAssetMapper extends BaseMapperX<FulfillmentAssetDO> {

    default List<FulfillmentAssetDO> selectListByFulfillmentRequestId(Long fulfillmentRequestId) {
        return selectList(FulfillmentAssetDO::getFulfillmentRequestId, fulfillmentRequestId);
    }

    default List<FulfillmentAssetDO> selectListByBOrderId(Long bOrderId) {
        return selectList(FulfillmentAssetDO::getBOrderId, bOrderId);
    }

    default FulfillmentAssetDO selectByAssetNo(String assetNo) {
        return selectOne(FulfillmentAssetDO::getAssetNo, assetNo);
    }

    default FulfillmentAssetDO selectByExternal(Long supplierId, String externalAssetId) {
        return selectOne(FulfillmentAssetDO::getSupplierId, supplierId,
                FulfillmentAssetDO::getExternalAssetId, externalAssetId);
    }

    default FulfillmentAssetDO selectByFingerprint(Long supplierId, byte[] fingerprint) {
        return selectOne(FulfillmentAssetDO::getSupplierId, supplierId,
                FulfillmentAssetDO::getFingerprint, fingerprint);
    }
}
