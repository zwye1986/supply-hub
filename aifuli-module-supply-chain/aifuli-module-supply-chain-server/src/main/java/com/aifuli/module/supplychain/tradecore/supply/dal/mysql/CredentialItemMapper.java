package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 凭据项（AD-3，sc_credential_item）数据访问。 */
@Mapper
public interface CredentialItemMapper extends BaseMapperX<CredentialItemDO> {
    /** 取得集锁后读取最新项，禁止使用 RR 旧快照决定绑定内容。 */
    default List<CredentialItemDO> selectListBySetIdForUpdate(Long id) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CredentialItemDO>()
                .eq(CredentialItemDO::getCredentialSetId, id).orderByAsc(CredentialItemDO::getItemKey).last("FOR UPDATE"));
    }
    default List<CredentialItemDO> selectListBySetId(Long credentialSetId) {
        return selectList(CredentialItemDO::getCredentialSetId, credentialSetId);
    }

    default CredentialItemDO selectBySetAndKey(Long credentialSetId, String itemKey) {
        return selectOne(CredentialItemDO::getCredentialSetId, credentialSetId,
                CredentialItemDO::getItemKey, itemKey);
    }
}
