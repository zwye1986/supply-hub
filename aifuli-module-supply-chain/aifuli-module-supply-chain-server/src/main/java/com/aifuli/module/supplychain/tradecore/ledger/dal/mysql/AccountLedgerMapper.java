package com.aifuli.module.supplychain.tradecore.ledger.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountLedgerMapper extends BaseMapperX<AccountLedgerDO> {

    default AccountLedgerDO selectByEntryKey(Long accountId, String entryKey) {
        return selectOne(AccountLedgerDO::getAccountId, accountId,
                AccountLedgerDO::getEntryKey, entryKey);
    }
}
