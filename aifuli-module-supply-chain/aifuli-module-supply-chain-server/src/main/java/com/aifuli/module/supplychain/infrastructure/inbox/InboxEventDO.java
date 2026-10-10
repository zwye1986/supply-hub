package com.aifuli.module.supplychain.infrastructure.inbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_inbox_event")
@Data
@Builder
public class InboxEventDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String consumerName;
    /** 被消费的Outbox事件或回调稳定业务编号，华昇使用SHA-256十六进制。 */
    private String eventId;
    /** 收到的事件载荷SHA-256族摘要，华昇使用HMAC-SHA256。 */
    private byte[] payloadDigest;
    private Short status;
    private String traceId;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private String lastErrorCode;
    private Long version;
    private LocalDateTime updatedAt;
}
