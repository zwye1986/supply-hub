package com.aifuli.module.supplychain.integration.saasmall;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SaaS 商城 H5 收银台地址配置。
 *
 * <p>环境域名由 Nacos 注入，仓库不保存测试或生产域名；缺少基址时仅在真实建单入口失败关闭，
 * 避免未使用收银台能力的进程启动被无关配置阻断。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.cashier")
public class BuyerCashierProperties {

    /** SaaS 商城 H5 绝对基址，例如 https://mall.example.com。 */
    private String h5BaseUrl;

    /** 商城收银台 H5 页面路径。 */
    private String h5Path = "/h5/tbsg-cashier.html";
}
