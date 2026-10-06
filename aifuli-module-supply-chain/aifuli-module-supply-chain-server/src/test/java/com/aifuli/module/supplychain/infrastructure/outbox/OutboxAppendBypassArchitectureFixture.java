package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.OutboxAppendIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * 仅供ArchUnit负控制导入，不装配、不执行：同包邻居也不得裸构造或写账本。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721。
 */
public final class OutboxAppendBypassArchitectureFixture {
    public OutboxEventRepository bareNew(OutboxEventMapper source, BusinessNoGenerator ids,
                                         ObjectMapper json, OutboxAppendIdentityMapper ledger, DataSource dataSource) {
        return new OutboxEventRepository(source, ids, json, ledger, dataSource);
    }

    public String appendLocal(String aggregateType) {
        return "禁止生产自调用";
    }

    public String selfCall() {
        return appendLocal("R3_02O_TECH_FIXTURE");
    }

    public int unauthorizedWrite(OutboxAppendIdentityMapper mapper, OutboxAppendIdentityDO row) {
        return mapper.insertSlotOnDuplicateKeyUpdate(row);
    }
}
