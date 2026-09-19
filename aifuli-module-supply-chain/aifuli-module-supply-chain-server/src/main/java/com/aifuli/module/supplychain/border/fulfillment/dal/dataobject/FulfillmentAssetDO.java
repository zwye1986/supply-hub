package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 履约交付资产持久化对象，对应数据库表 {@code sc_fulfillment_asset}。
 *
 * <p>核心职责：保存供应商实际交付的逐券、卡密、链接或账户绑定权益。
 * 遵循设计规范：敏感资产经 KMS 信封加密保存于 {@code encrypted_payload}，对外输出始终返回脱敏值 {@code masked_value}，
 * 通过 {@code fingerprint} 保证去重且不可逆推明文。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#73-supplierorderoperation-与-fulfillmentasset}。</p>
 */
@TableName("sc_fulfillment_asset")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentAssetDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 履约交付资产稳定业务编号，前缀 FA_ */
    private String assetNo;

    /** 履约请求内部主键 */
    private Long fulfillmentRequestId;

    /** 产生资产的B端履约尝试主键 */
    private Long bOrderId;

    /** 产生资产的履约操作主键 */
    private Long operationId;

    /** 交付资产供应商内部主键 */
    private Long supplierId;

    /** 资产所属采购商内部主键 */
    private Long buyerId;

    /** 采购商范围内的C端用户唯一标识 */
    private String buyerUserId;

    /** BOrder范围内资产顺序号，从1递增 */
    private Integer sequenceNo;

    /** 本资产代表的已交付数量 */
    private Long unitQuantity;

    /** 资产类型：VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益 */
    private String assetType;

    /** 供应商侧独立资产标识 */
    private String externalAssetId;

    /** 供应商侧资产商品标识 */
    private String externalProductId;

    /** 卡号卡密、券码、链接或权益信息信封加密密文 */
    private byte[] encryptedPayload;

    /** 后台和授权查询使用的脱敏资产值 */
    private String maskedValue;

    /** 资产明文HMAC指纹，用于去重且不可反推明文 */
    private byte[] fingerprint;

    /** 资产密文加密密钥版本 */
    private String keyVersion;

    /** 资产有效期开始时间，UTC */
    private LocalDateTime validFrom;

    /** 资产有效期结束时间，UTC */
    private LocalDateTime validUntil;

    /** 资产生命周期：10可交付，20已交付，30已作废，40已解绑，50已过期，90隔离不可交付 */
    private Short lifecycleStatus;

    /** 资产生命周期乐观锁版本号 */
    @Version
    private Long version;

    /** 资产创建时间，UTC */
    private LocalDateTime createdAt;

    /** 资产最后更新时间，UTC */
    private LocalDateTime updatedAt;
}
