package com.aifuli.module.supplychain.integration.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采购商版本化集成能力与执行策略 DO。
 *
 * <p>MEMBER_PROFILE/CASHIER 的 sourceSystem 与 externalTenantId 是显式发布的 SaaS 路由，
 * 禁止由 projectId 或 buyerUserId 推导。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
@TableName("sc_buyer_integration_capability")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerIntegrationCapabilityDO {

    /** 采购商集成能力配置内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商集成档案版本内部主键，逻辑引用sc_buyer_integration_profile_version.id。 */
    private Long profileVersionId;
    /** 能力编码：MEMBER_PROFILE、CASHIER、ORDER_EVENT、FULFILLMENT_EVENT、CATALOG_EVENT。 */
    private String capabilityCode;
    /** 传输模式：RPC内部服务调用，HTTP签名网络调用。 */
    private String transportMode;
    /** 受控的采购商Adapter编码。 */
    private String adapterCode;
    /** 采购商集成契约版本。 */
    private String contractVersion;
    /** SaaS外部路由来源系统。 */
    private String sourceSystem;
    /** SaaS外部租户路由标识。 */
    private String externalTenantId;
    /** HTTP模式HTTPS基础地址，RPC模式必须为NULL。 */
    private String httpBaseUrl;
    /** HTTP出站凭证集内部主键，逻辑引用sc_buyer_credential_set.id。 */
    private Long credentialSetId;
    /** 连接超时时间，毫秒。 */
    private Integer connectTimeoutMs;
    /** 读取超时时间，毫秒。 */
    private Integer readTimeoutMs;
    /** 单次调用端到端超时时间，毫秒。 */
    private Integer totalTimeoutMs;
    /** 允许的最大响应体字节数。 */
    private Integer maxResponseBytes;
    /** 同一Buyer同一能力的最大并发舱大小。 */
    private Integer bulkheadConcurrency;
    /** 同一Buyer同一能力的每秒请求上限。 */
    private Integer rateLimitPerSecond;
    /** 受控的熔断策略编码。 */
    private String circuitBreakerPolicy;
    /** 重试策略：NONE、QUERY_SAME_KEY、OUTBOX_RETRY。 */
    private String retryPolicyCode;
    /** 能力状态：10启用，20停用。 */
    private Short status;
    /** 创建人用户ID。 */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
