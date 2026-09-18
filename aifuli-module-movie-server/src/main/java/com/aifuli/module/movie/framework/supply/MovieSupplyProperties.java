package com.aifuli.module.movie.framework.supply;

import com.aifuli.module.movie.enums.MovieChannel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影供给采集配置；只保存密钥环境变量名称，不承载明文凭证。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#2-接入账号与协议
 */
@Getter
@Setter
@ConfigurationProperties("movie.supply")
public class MovieSupplyProperties {
    /** 同步账号列表，同一供应商允许多个账号。 */
    private List<Account> accounts = new ArrayList<>();
    /** 单接口最大页数，防止上游忽略分页参数导致无限请求。 */
    private int maxPages = 1000;

    /** 接入账号元信息；禁止添加会打印凭证的 toString。 */
    @Getter
    @Setter
    public static class Account {
        /** 稳定账号引用，修改会形成另一份排期空间。 */
        private String accountRef;
        /** 供应商渠道。 */
        private MovieChannel channel;
        /** 账号是否参与供给采集。 */
        private boolean enabled;
        /** 非敏感配置版本，用于排期来源追踪。 */
        private String configVersion;
        /** 供应商服务根地址，必须显式包含 http/https。 */
        private String baseUrl;
        /** 应用标识所在的环境变量名。 */
        private String appKeyEnv;
        /** 应用密钥所在的环境变量名。 */
        private String appSecretEnv;
        /** 网络连接超时。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 单请求超时。 */
        private Duration requestTimeout = Duration.ofSeconds(30);
        /** 同账号跨五类任务的最小请求起始间隔；实际额度由供应商确认。 */
        private Duration minRequestInterval = Duration.ofMillis(1100);
        /** JC 报价订单类型：8 猫淘直连、15 快捷购票、30 优选购票，不统一解释为分钟 SLA。 */
        private String deliverMinutes = "8,15,30";
        /** 项目/采购商绑定引用，供后续可信账号选择使用。 */
        private List<String> bindingRefs = new ArrayList<>();
    }
}
