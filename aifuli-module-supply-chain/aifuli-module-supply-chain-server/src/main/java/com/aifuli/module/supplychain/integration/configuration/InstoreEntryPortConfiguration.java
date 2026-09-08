package com.aifuli.module.supplychain.integration.configuration;

import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 到店购会员资料与旅通入口 URL 出站端口的失败关闭默认配置。
 *
 * <p>真实 Adapter 尚未接入时，会员查询明确返回不可用，URL 获取明确抛错；默认实现不得伪造
 * 会员数据或入口地址。该类通过 Spring Boot 自动配置导入，在用户 Bean 注册后再分别判断缺失类型，
 * 避免组件扫描顺序导致默认 Bean 抢先注册；两个端口可以被后续 Adapter 独立替换。
 * 设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#44-Integration-Port-层}。</p>
 */
@AutoConfiguration
public class InstoreEntryPortConfiguration {

    private static final String RTMART_ENTRY_ADAPTER_MISSING =
            "Rtmart entry URL adapter is not configured";

    /**
     * 注册不伪造会员资料的默认端口；真实会员 Adapter 存在时本 Bean 不创建。
     *
     * @return 始终返回 UNAVAILABLE 的失败关闭端口
     */
    @Bean
    @ConditionalOnMissingBean(BuyerMemberProfilePort.class)
    public BuyerMemberProfilePort buyerMemberProfilePort() {
        return command -> new BuyerMemberProfilePort.MemberProfileResult(
                BuyerMemberProfilePort.MemberResolutionStatus.UNAVAILABLE, null, null);
    }

    /**
     * 注册拒绝生成假 URL 的默认端口；HOME/DETAIL 共用稳定错误消息便于调用方统一诊断。
     *
     * @return 未配置真实 Adapter 时抛错的失败关闭端口
     */
    @Bean
    @ConditionalOnMissingBean(RtmartEntryUrlPort.class)
    public RtmartEntryUrlPort rtmartEntryUrlPort() {
        return new RtmartEntryUrlPort() {
            @Override
            public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
                throw adapterMissing();
            }

            @Override
            public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
                throw adapterMissing();
            }
        };
    }

    private static IllegalStateException adapterMissing() {
        return new IllegalStateException(RTMART_ENTRY_ADAPTER_MISSING);
    }
}
