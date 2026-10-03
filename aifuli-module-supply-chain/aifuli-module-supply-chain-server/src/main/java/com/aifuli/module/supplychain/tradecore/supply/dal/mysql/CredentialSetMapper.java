package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import org.apache.ibatis.annotations.Mapper;

/** 凭据集（AD-3，sc_credential_set）数据访问。 */
@Mapper
public interface CredentialSetMapper extends BaseMapperX<CredentialSetDO> {
    default CredentialSetDO selectBySupplierAndCode(Long supplierId, String setCode) {
        return selectOne(CredentialSetDO::getSupplierId, supplierId, CredentialSetDO::getSetCode, setCode);
    }
}
