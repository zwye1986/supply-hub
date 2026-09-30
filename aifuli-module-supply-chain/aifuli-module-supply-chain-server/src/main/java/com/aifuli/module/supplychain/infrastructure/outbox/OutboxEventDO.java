package com.aifuli.module.supplychain.infrastructure.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_outbox_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private Long aggregateVersion;
    private String eventType;
    private String targetType;
    private String targetName;
    private Integer payloadVersion;
    private String payloadJson;
    private LocalDateTime availableAt;
    private Short status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long fencingToken;
    private String traceId;
    private String correlationId;
    private String causationId;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    /** 事件投递状态乐观锁版本号。 */
    @Version
    private Long version;
}
