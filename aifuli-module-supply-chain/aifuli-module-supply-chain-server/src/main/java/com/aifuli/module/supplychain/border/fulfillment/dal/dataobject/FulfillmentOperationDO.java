package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fulfillment_operation")
@Data
@Builder
public class FulfillmentOperationDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operationNo;
    private Long bOrderId;
    private Long targetAssetId;
    private String operationKind;
    private String adapterStepCode;
    private Integer sequenceNo;
    private String idempotencyKey;
    private byte[] configDigest;
    private String externalOperationNo;
    private Short status;
    private String resultClass;
    private Integer attemptCount;
    private LocalDateTime nextRecoverAt;
    private Integer checkpointSchemaVersion;
    private byte[] checkpointCiphertext;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
}
