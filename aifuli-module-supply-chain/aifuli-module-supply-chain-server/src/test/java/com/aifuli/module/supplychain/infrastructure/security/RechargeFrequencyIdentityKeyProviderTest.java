package com.aifuli.module.supplychain.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class RechargeFrequencyIdentityKeyProviderTest {
    @Test
    void validEnvironmentKeyIsSnapshottedAndDefensivelyCopied() throws Exception {
        var current = new AtomicReference<>(Base64.getEncoder().encodeToString(new byte[32]));
        var provider = new ConfiguredRechargeFrequencyIdentityKeyProvider("env:TEST_KEY", name -> current.get());
        byte[] original = provider.identityKey();
        current.set(Base64.getEncoder().encodeToString(new byte[31]));
        byte[] mutable = provider.identityKey();
        mutable[0] = 77;
        assertThat(provider.identityKey()).isEqualTo(original).hasSize(32);
    }

    @Test
    void missingOrMalformedKeyPreventsSupplyChainStartupButOtherProcessIsUnaffected() {
        for (String reference : new String[]{"", "file:/private/key", "env:AIFULI_FREQUENCY_TEST_ENV_DOES_NOT_EXIST"}) {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RechargeFrequencyIdentityConfiguration.class))
                    .withPropertyValues("aifuli.supply-chain.enabled=true", "aifuli.supply-chain.recharge-frequency.identity-key-ref=" + reference)
                    .run(context -> assertThat(context).hasFailed());
        }
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RechargeFrequencyIdentityConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RechargeFrequencyIdentityKeyProvider.class));
        for (String invalid : new String[]{"private-secret-%%%", Base64.getEncoder().encodeToString(new byte[31]), ""}) {
            assertThatThrownBy(() -> ConfiguredRechargeFrequencyIdentityKeyProvider.resolve("env:TEST_KEY", name -> invalid))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(invalid.isEmpty() ? "sentinel" : invalid);
        }
        assertThatThrownBy(() -> ConfiguredRechargeFrequencyIdentityKeyProvider.resolve("env:TEST_KEY", name -> null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfiguredRechargeFrequencyIdentityKeyProvider.resolve("file:/private/key", name -> "unused"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitKeyPortOverridesDefaultWithoutEnvironmentRequirement() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RechargeFrequencyIdentityConfiguration.class))
                .withPropertyValues("aifuli.supply-chain.enabled=true")
                .withBean(RechargeFrequencyIdentityKeyProvider.class, () -> () -> new byte[32])
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RechargeFrequencyIdentityKeyProvider.class));
    }
}
