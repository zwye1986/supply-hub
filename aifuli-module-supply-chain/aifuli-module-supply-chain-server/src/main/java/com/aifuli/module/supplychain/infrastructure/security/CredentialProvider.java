package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;

/** 供应商秘密材料解析端口；调用方不得记录返回值。 */
public interface CredentialProvider {
    boolean supports(String providerType);

    boolean isResolvable(CredentialSlotDO slot);

    char[] resolve(CredentialSlotDO slot);
}
