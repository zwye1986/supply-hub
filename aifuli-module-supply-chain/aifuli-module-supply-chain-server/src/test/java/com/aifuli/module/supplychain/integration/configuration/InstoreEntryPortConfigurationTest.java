package com.aifuli.module.supplychain.integration.configuration;

import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryAdapterConfiguration;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 到店购出站端口默认配置测试，保证未接真实 Adapter 时失败关闭且可按端口独立替换。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#44-Integration-Port-层}。</p>
 */
class InstoreEntryPortConfigurationTest {

    private static final String ADAPTER_MISSING_MESSAGE = "Rtmart entry URL adapter is not configured";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_CODE = "COMPANY";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "tenant-a";
    private static final String TRANSPORT_MODE = "RPC";
    private static final String CONTRACT_VERSION = "v1";
    private static final String AUTO_CONFIGURATION_CLASS_NAME =
            InstoreEntryPortConfiguration.class.getName();
    private static final String OTHER_AUTO_CONFIGURATIONS = ImportCandidates
            .load(AutoConfiguration.class, InstoreEntryPortConfiguration.class.getClassLoader())
            .getCandidates()
            .stream()
            .filter(candidate -> !AUTO_CONFIGURATION_CLASS_NAME.equals(candidate))
            .collect(Collectors.joining(","));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.autoconfigure.exclude=" + OTHER_AUTO_CONFIGURATIONS)
            .withUserConfiguration(AutoConfigurationRegistration.class);

    @Test
    void fallbackIsDiscoveredThroughBootAutoConfigurationImports() {
        java.util.List<String> candidates = ImportCandidates.load(
                AutoConfiguration.class, InstoreEntryPortConfiguration.class.getClassLoader()).getCandidates();
        assertThat(candidates).contains(AUTO_CONFIGURATION_CLASS_NAME, RtmartEntryAdapterConfiguration.class.getName());
        assertThat(candidates.indexOf(RtmartEntryAdapterConfiguration.class.getName()))
                .isLessThan(candidates.indexOf(AUTO_CONFIGURATION_CLASS_NAME));
        assertThat(InstoreEntryPortConfiguration.class).hasAnnotation(AutoConfiguration.class);

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BuyerMemberProfilePort.class);
            assertThat(context).hasSingleBean(RtmartEntryUrlPort.class);
        });
    }

    @Test
    void defaultsFailClosedWithoutInventingMemberOrEntryUrl() {
        contextRunner.run(context -> {
            BuyerMemberProfilePort memberPort = context.getBean(BuyerMemberProfilePort.class);
            BuyerMemberProfilePort.MemberProfileResult member = memberPort.resolve(
                    new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), "001234"));
            assertThat(member.status()).isEqualTo(BuyerMemberProfilePort.MemberResolutionStatus.UNAVAILABLE);
            assertThat(member.mobile()).isNull();
            assertThat(member.userName()).isNull();

            RtmartEntryUrlPort urlPort = context.getBean(RtmartEntryUrlPort.class);
            assertThatThrownBy(() -> urlPort.getHomeUrl(homeCommand()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(ADAPTER_MISSING_MESSAGE);
            assertThatThrownBy(() -> urlPort.getDetailUrl(detailCommand()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(ADAPTER_MISSING_MESSAGE);
        });
    }

    @Test
    void customMemberPortIndependentlyReplacesOnlyMemberDefault() {
        contextRunner.withUserConfiguration(CustomMemberPortConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(BuyerMemberProfilePort.class))
                    .containsOnlyKeys("customBuyerMemberProfilePort");
            assertThat(context.getBean(BuyerMemberProfilePort.class).resolve(
                    new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), "001234")).status())
                    .isEqualTo(BuyerMemberProfilePort.MemberResolutionStatus.FOUND);
            assertThat(context.getBeansOfType(RtmartEntryUrlPort.class))
                    .containsOnlyKeys("rtmartEntryUrlPort");
            assertThatThrownBy(() -> context.getBean(RtmartEntryUrlPort.class).getHomeUrl(homeCommand()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(ADAPTER_MISSING_MESSAGE);
        });
    }

    @Test
    void customUrlPortIndependentlyReplacesOnlyUrlDefault() {
        contextRunner.withUserConfiguration(CustomUrlPortConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(RtmartEntryUrlPort.class))
                    .containsOnlyKeys("customRtmartEntryUrlPort");
            assertThat(context.getBean(RtmartEntryUrlPort.class).getHomeUrl(homeCommand()).entryUrl())
                    .isEqualTo("https://custom.example/home");
            assertThat(context.getBean(RtmartEntryUrlPort.class).getDetailUrl(detailCommand()).entryUrl())
                    .isEqualTo("https://custom.example/detail");
            assertThat(context.getBeansOfType(BuyerMemberProfilePort.class))
                    .containsOnlyKeys("buyerMemberProfilePort");
            assertThat(context.getBean(BuyerMemberProfilePort.class).resolve(
                    new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), "001234")).status())
                    .isEqualTo(BuyerMemberProfilePort.MemberResolutionStatus.UNAVAILABLE);
        });
    }

    private static BuyerIntegrationRoute memberRoute() {
        return new BuyerIntegrationRoute(
                101L, 202L, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                SOURCE_SYSTEM, EXTERNAL_TENANT_ID, TRANSPORT_MODE,
                "SAAS_MEMBER_RPC", CONTRACT_VERSION, null);
    }

    private static RtmartEntryUrlPort.HomeUrlCommand homeCommand() {
        return new RtmartEntryUrlPort.HomeUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, "121.4737", "31.2304", "token-home");
    }

    private static RtmartEntryUrlPort.DetailUrlCommand detailCommand() {
        return new RtmartEntryUrlPort.DetailUrlCommand(
                101L, CHANNEL_CODE, COMPANY_CODE, "TRADE001", "token-detail");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfigurationRegistration {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMemberPortConfiguration {

        @Bean
        BuyerMemberProfilePort customBuyerMemberProfilePort() {
            return command -> new BuyerMemberProfilePort.MemberProfileResult(
                    BuyerMemberProfilePort.MemberResolutionStatus.FOUND, "13800138000", "会员甲");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomUrlPortConfiguration {

        @Bean
        RtmartEntryUrlPort customRtmartEntryUrlPort() {
            return new RtmartEntryUrlPort() {
                @Override
                public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
                    return new EntryUrlResult("https://custom.example/home");
                }

                @Override
                public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
                    return new EntryUrlResult("https://custom.example/detail");
                }
            };
        }
    }
}
