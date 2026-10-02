package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 复杂聚合与SQL分页，见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。 */
@Mapper
public interface RechargeFrequencyMonitorMapper {
    Statistics statistics(@Param("f") Filter filter);
    List<WaitingOrder> waiting(@Param("f") Filter filter);
    long waitingCount(@Param("f") Filter filter);
    List<Occupancy> occupancies(@Param("f") Filter filter);
    long occupancyCount(@Param("f") Filter filter);
    List<Backlog> backlogs(@Param("f") Filter filter);
    long backlogCount(@Param("f") Filter filter);
    /** 只列尚待核实的HOLDING+SEND_POSSIBLE迁移风险，不能混入等待或可信已结束项。 */
    List<String> riskOrders(@Param("queueId") Long queueId,@Param("holdingStatus") short holdingStatus,@Param("sendPossiblePhase") short sendPossiblePhase);
}
