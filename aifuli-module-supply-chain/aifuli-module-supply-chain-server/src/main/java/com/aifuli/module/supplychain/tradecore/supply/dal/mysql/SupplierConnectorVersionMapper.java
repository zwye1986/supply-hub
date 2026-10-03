package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierConnectorVersionMapper extends BaseMapperX<SupplierConnectorVersionDO> {

    /** 当前读版本引用，避免添加项事务已建立旧 RR 快照而漏掉已提交绑定。 */
    default java.util.List<SupplierConnectorVersionDO> selectByCredentialSetForUpdate(Long setId) {
        return selectList(new LambdaQueryWrapper<SupplierConnectorVersionDO>()
                .eq(SupplierConnectorVersionDO::getCredentialSetId, setId).last("FOR UPDATE"));
    }
    default SupplierConnectorVersionDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(SupplierConnectorVersionDO::getId, id);
    }

    default SupplierConnectorVersionDO selectLatest(Long connectorId) {
        return selectOne(new LambdaQueryWrapper<SupplierConnectorVersionDO>()
                .eq(SupplierConnectorVersionDO::getConnectorId, connectorId)
                .orderByDesc(SupplierConnectorVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }
}
