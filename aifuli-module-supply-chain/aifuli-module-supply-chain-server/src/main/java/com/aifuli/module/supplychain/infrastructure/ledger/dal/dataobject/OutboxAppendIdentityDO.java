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
 * Outbox追加身份账本数据对象。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行。
 */
@TableName("sc_outbox_append_identity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxAppendIdentityDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String aggregateType;
    private String aggregateId;
    private Long aggregateVersion;
    private String eventType;
    private Short bindingStatus;
    private byte[] reservationNonce;
    private String targetType;
    private String targetName;
    private Integer payloadVersion;
    private String comparisonProtocol;
    private String payloadJson;
    private String eventId;
    private LocalDateTime createdAt;
    private LocalDateTime boundAt;
}
