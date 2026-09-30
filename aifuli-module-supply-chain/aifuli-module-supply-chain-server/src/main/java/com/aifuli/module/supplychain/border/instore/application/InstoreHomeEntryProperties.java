package com.aifuli.module.supplychain.border.instore.application;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 到店购 HOME 入口企业组配置。
 *
 * <p>过渡方案：SaaS 请求体不传 {@code companyCode}，由本配置提供并经
 * {@link InstoreEntryAccessService#requireEnabledCompany} 校验启用绑定。
 * 正式态改为 {@code appId} 绑定，不在本期实现。空白或超长配置在启动期失败关闭，
 * 禁止带着无效商品组对外签发凭证。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}
 * 与 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#91-home-首页入口}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.instore.home")
public class InstoreHomeEntryProperties {

    /** 当前测试环境默认旅通商品组。 */
    public static final String DEFAULT_COMPANY_CODE = "L0000A";

    /** 与历史 HomeEntryReqDTO 字段长度上限一致。 */
    static final int MAX_COMPANY_CODE_LENGTH = 64;

    /**
     * HOME 入口使用的旅通企业编码（商品组）。
     *
     * <p>对应 {@code sc_supplier_channel_binding.external_merchant_id}。</p>
     */
    private String companyCode = DEFAULT_COMPANY_CODE;

    /**
     * 规范化并校验企业组配置；空白或超过 64 字符阻止应用启动。
     */
    @PostConstruct
    public void validate() {
        if (companyCode != null) {
            companyCode = companyCode.trim();
        }
        if (companyCode == null || companyCode.isEmpty()
                || companyCode.length() > MAX_COMPANY_CODE_LENGTH) {
            throw new IllegalStateException("HOME 入口 companyCode 配置无效");
        }
    }
}
