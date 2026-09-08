package com.aifuli.module.supplychain.infrastructure.inbox;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface InboxEventMapper extends BaseMapperX<InboxEventDO> {

    /** 与 {@link InboxService} 中 STATUS_PROCESSING 一致。 */
    short STATUS_PROCESSING = 10;
    /** 与 {@link InboxService} 中 STATUS_SUCCESS 一致。 */
    short STATUS_SUCCESS = 20;

    default InboxEventDO selectForUpdate(String consumerName, String eventId) {
        return selectOneForUpdate(InboxEventDO::getConsumerName, consumerName,
                InboxEventDO::getEventId, eventId);
    }

    default int markSucceeded(Long id, long expectedVersion, LocalDateTime processedAt,
                              LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InboxEventDO>()
                .set(InboxEventDO::getStatus, STATUS_SUCCESS)
                .set(InboxEventDO::getProcessedAt, processedAt)
                .set(InboxEventDO::getLastErrorCode, null)
                .setSql("version = version + 1")
                .set(InboxEventDO::getUpdatedAt, updatedAt)
                .eq(InboxEventDO::getId, id)
                .eq(InboxEventDO::getVersion, expectedVersion)
                .eq(InboxEventDO::getStatus, STATUS_PROCESSING));
    }

    default int finishProcessing(Long id, long expectedVersion, short newStatus, String errorCode,
                                 LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InboxEventDO>()
                .set(InboxEventDO::getStatus, newStatus)
                .set(InboxEventDO::getLastErrorCode, errorCode)
                .setSql("version = version + 1")
                .set(InboxEventDO::getUpdatedAt, updatedAt)
                .eq(InboxEventDO::getId, id)
                .eq(InboxEventDO::getVersion, expectedVersion)
                .eq(InboxEventDO::getStatus, STATUS_PROCESSING));
    }
}
