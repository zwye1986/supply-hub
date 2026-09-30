package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierInvoiceDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商开票信息。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#7-表}。
 */
@Mapper
public interface SupplierInvoiceMapper extends BaseMapperX<SupplierInvoiceDO> {

    default SupplierInvoiceDO selectBySupplierId(Long supplierId) {
        return selectOne(SupplierInvoiceDO::getSupplierId, supplierId);
    }
}
