package com.aifuli.module.movie.framework.supply;

import com.aifuli.module.movie.enums.MovieChannel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影分页防护配置；账号由中台RPC提供，不再绑定Nacos账号列表。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#2-接入账号与协议
 */
@Getter
@Setter
@ConfigurationProperties("movie.supply")
public class MovieSupplyProperties {
    /** 单接口最大页数，防止上游忽略分页参数导致无限请求。 */
    private int maxPages = 1000;

    /** 单轮中台账号快照；不参与配置绑定，禁止添加会打印凭证的toString或持久化整个对象。 */
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
        /** 中台JSON中的应用标识，直接参与当前渠道签名。 */
        private String appId;
        /** 中台解析的签名密钥，只保留在本轮处理内存中。 */
        private String appSecret;
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
