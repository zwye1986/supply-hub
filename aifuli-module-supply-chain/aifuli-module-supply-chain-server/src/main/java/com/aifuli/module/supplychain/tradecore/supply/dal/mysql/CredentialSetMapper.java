package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import org.apache.ibatis.annotations.Mapper;

/** 凭据集（AD-3，sc_credential_set）数据访问。 */
@Mapper
public interface CredentialSetMapper extends BaseMapperX<CredentialSetDO> {
    /** 固定当前读：集是绑定和追加的共同锁入口。 */
    default CredentialSetDO selectByIdForUpdate(Long id) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CredentialSetDO>()
                .eq(CredentialSetDO::getId, id).last("FOR UPDATE"));
    }
    default CredentialSetDO selectBySupplierAndCode(Long supplierId, String setCode) {
        return selectOne(CredentialSetDO::getSupplierId, supplierId, CredentialSetDO::getSetCode, setCode);
    }
}
