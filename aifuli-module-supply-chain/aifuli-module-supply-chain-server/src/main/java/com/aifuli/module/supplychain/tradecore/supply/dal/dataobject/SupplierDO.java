package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String supplierCode;
    private String supplierName;
    private String shortName;
    private String supplierType;
    private String legalEntityName;
    private String creditCode;
    private Short status;
    private String deliveryRegionType;
    private Short aftersalesSupported;
    /** 发货时效封闭枚举 */
    private String shippingSlaCode;
    /** 发货时效自定义说明，仅 CUSTOM 使用 */
    private String shippingSlaCustom;
    /** 结算方式封闭枚举 */
    private String settlementMethod;
    private Integer settlementCycleDays;
    private LocalDateTime onboardedAt;
    private String cooperationNote;
    private String legalPersonName;
    /** 法人身份证号 */
    private String legalPersonIdNo;
    private byte[] legalPersonMobileCipher;
    private String contactAddress;
    private String bankName;
    private byte[] bankAccountCipher;
    private String bankAccountKeyVersion;
    private byte[] licenseFileCipher;
    private byte[] idFrontCipher;
    private byte[] idBackCipher;
    private byte[] qualificationFileCipher;
    private String documentKeyVersion;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
