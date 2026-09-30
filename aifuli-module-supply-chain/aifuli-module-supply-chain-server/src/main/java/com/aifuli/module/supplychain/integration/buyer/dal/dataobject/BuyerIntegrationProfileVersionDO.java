package com.aifuli.module.supplychain.integration.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 采购商集成配置不可变发布版本 DO。 */
@TableName("sc_buyer_integration_profile_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerIntegrationProfileVersionDO {

    /** 采购商集成档案版本内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商集成档案内部主键，逻辑引用sc_buyer_integration_profile.id。 */
    private Long profileId;
    /** 同一档案下递增的配置版本号。 */
    private Integer versionNo;
    /** 版本状态：0草稿，5已验证，10已发布，20已被替代，90已退役。 */
    private Short status;
    /** 版本业务生效时间，Asia/Shanghai。 */
    private LocalDateTime effectiveAt;
    /** 执行配置验证的用户ID。 */
    private Long validatedBy;
    /** 配置验证通过时间，Asia/Shanghai。 */
    private LocalDateTime validatedAt;
    /** 发布配置版本的用户ID。 */
    private Long publishedBy;
    /** 配置版本发布时间，Asia/Shanghai。 */
    private LocalDateTime publishedAt;
    /** 本版本变更或回滚原因。 */
    private String changeReason;
    /** 版本全部能力配置的SHA-256摘要。 */
    private byte[] contentDigest;
    /** 当前已发布版本标记：1当前，NULL历史或草稿。 */
    private Byte currentFlag;
    /** 草稿修改和发布状态乐观锁版本号。 */
    @Version
    private Long version;
    /** 创建人用户ID。 */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID。 */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
