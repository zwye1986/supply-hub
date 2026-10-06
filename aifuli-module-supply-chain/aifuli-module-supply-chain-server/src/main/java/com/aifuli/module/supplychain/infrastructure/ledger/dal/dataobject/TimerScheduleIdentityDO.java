package com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Timer调度身份账本数据对象。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行。
 */
@TableName("sc_timer_schedule_identity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerScheduleIdentityDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskType;
    private String idempotencyKey;
    private Short bindingStatus;
    private byte[] reservationNonce;
    private Integer payloadVersion;
    private String comparisonProtocol;
    private String payloadJson;
    private String taskNo;
    private Long taskId;
    private LocalDateTime createdAt;
    private LocalDateTime boundAt;
}
