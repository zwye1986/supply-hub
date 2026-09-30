package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RawStatusObservationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 供应商原始状态观察 Mapper。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Mapper
public interface RawStatusObservationMapper extends BaseMapperX<RawStatusObservationDO> {
    /**
     * 原子写入观察，仅observation_key相同的竞争保持既有行；其他唯一键/非空错误必须使完整结果事务回滚。
     * 不使用RR快照预查询、不对不存在的键加gap锁、不依据affectedRows判断首次插入。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    int insertIdempotent(@Param("observation") RawStatusObservationDO observation);
}
