package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierConnectorMapper extends BaseMapperX<SupplierConnectorDO> {

    default SupplierConnectorDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(SupplierConnectorDO::getId, id);
    }

    default SupplierConnectorDO selectByNo(String connectorNo) {
        return selectOne(SupplierConnectorDO::getConnectorNo, connectorNo);
    }

    default SupplierConnectorDO selectByNoForUpdate(String connectorNo) {
        return selectOneForUpdate(SupplierConnectorDO::getConnectorNo, connectorNo);
    }

    /**
     * 判断同一供应商下是否已存在「其它」已发布连接器（{@code current_version_id} 非空的其它行）。
     *
     * <p>用于连接器发布的 D1 供应商级唯一守卫：先于 DB 唯一键
     * {@code uk_sc_connector_supplier_published} 报错，返回清晰业务异常。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#55-d1-published_flag-唯一键变更集-056。</p>
     *
     * @param supplierId         供应商主键
     * @param excludeConnectorId 待发布连接器自身主键，需从「其它行」判定中排除
     * @return 存在其它已发布连接器返回 {@code true}
     */
    default boolean existsOtherPublishedBySupplier(Long supplierId, Long excludeConnectorId) {
        return selectCount(new LambdaQueryWrapper<SupplierConnectorDO>()
                .eq(SupplierConnectorDO::getSupplierId, supplierId)
                .isNotNull(SupplierConnectorDO::getCurrentVersionId)
                .ne(SupplierConnectorDO::getId, excludeConnectorId)) > 0;
    }

    /**
     * 查该供应商唯一已发布连接器（{@code current_version_id} 非空，即 {@code published_flag=1} 的那一行）。
     *
     * <p>B1 上架即发布步骤 2「C3 连接器自动引用」用：DB 唯一键 {@code uk_sc_connector_supplier_published}
     * 与 D1 守卫保证至多一行；{@code LIMIT 1} 仅防脏数据下 {@code selectOne} 抛 TooManyResults。
     * 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心。</p>
     *
     * @param supplierId 供应商主键
     * @return 唯一已发布连接器，无则 {@code null}
     */
    default SupplierConnectorDO selectPublishedBySupplier(Long supplierId) {
        return selectOne(new LambdaQueryWrapper<SupplierConnectorDO>()
                .eq(SupplierConnectorDO::getSupplierId, supplierId)
                .isNotNull(SupplierConnectorDO::getCurrentVersionId)
                .orderByAsc(SupplierConnectorDO::getId)
                .last("LIMIT 1"));
    }
}
