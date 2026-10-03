package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_connector_version")
@Data
@Builder
public class SupplierConnectorVersionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectorId;
    private Integer versionNo;
    private String baseUrl;
    private String merchantAccount;
    /** 凭证槽主键，逻辑引用sc_credential_slot.id */
    private Long credentialSlotId;
    /** 凭据集主键，逻辑引用sc_credential_set.id */
    private Long credentialSetId;
    /** 连接器冻结的凭据项身份与版本快照，不含秘密 */
    private String credentialItemSnapshot;
    private String protocolVersion;
    private String callbackPath;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private Integer rateLimit;
    private Integer concurrencyLimit;
    private Integer adapterConfigSchemaVersion;
    private String adapterConfigJson;
    /** 连接器版本非秘密可调参数，如expireTime秒、outMobile；密钥类禁止入此列 */
    private String extraConfig;
    private Short validationStatus;
    private LocalDateTime validatedAt;
    private LocalDateTime validationExpiresAt;
    private LocalDateTime publishedAt;
    private byte[] configDigest;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
