package com.aifuli.module.supplychain.api.movie;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Nacos渠道选择契约，空值、未知值和重复值不能悄悄启用其它渠道。 */
class MovieSupplierChannelTest {
    @Test
    void acceptsExplicitChannelsAndEmptySelection() {
        assertThat(MovieSupplierChannel.parseSelection("JC, LX"))
                .containsExactly(MovieSupplierChannel.JC, MovieSupplierChannel.LX);
        assertThat(MovieSupplierChannel.parseSelection("")).isEmpty();
        assertThat(MovieSupplierChannel.parseSelection("  ")).isEmpty();
        assertThat(MovieSupplierChannel.parseSelection(null)).isEmpty();
    }

    @Test
    void rejectsTyposTrailingSeparatorsAndDuplicates() {
        for (String invalid : new String[]{"jc", "JC,", "JC,UNKNOWN", "JC,JC", ","}) {
            assertThatThrownBy(() -> MovieSupplierChannel.parseSelection(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
