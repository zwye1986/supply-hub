package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 完整事实摘要与稳定身份的无歧义编码门禁，设计见华昇闭环设计 §8.2。 */
class HuashengCallbackCanonicalTest {
    @Test
    void timestampAndEveryCardFieldStateParticipateInDigestInput() {
        byte[] original = fact(1, "n", "{\"type\":201,\"code\":\"C\",\"pass\":\"P\"}").canonicalFactBytes(200L);
        assertThat(fact(2, "n", "{\"type\":201,\"code\":\"C\",\"pass\":\"P\"}").canonicalFactBytes(200L)).isNotEqualTo(original);
        for (String field : new String[]{"id", "crc", "url", "urlPass", "validTime"}) {
            byte[] withNull = fact(1, "n", "{\"type\":201,\"code\":\"C\",\"pass\":\"P\",\"" + field + "\":null}").canonicalFactBytes(200L);
            assertThat(withNull).as(field).isNotEqualTo(original);
        }
    }

    @Test
    void canonicalFactIsJsonAndEscapesDelimiterLikeValues() throws Exception {
        var fact = fact(1, "quote\"&nonce=x", "{\"type\":201,\"code\":\"C&pass=x\",\"pass\":\"P\"}");
        var json = new ObjectMapper().readTree(fact.canonicalFactBytes(200L));
        assertThat(json.get("nonce").textValue()).isEqualTo("quote\"&nonce=x");
        assertThat(json.get("card").get("code").textValue()).isEqualTo("C&pass=x");
    }

    private HuashengCallbackFact fact(long timestamp, String nonce, String card) {
        var price = ((HuashengInternalAttempt.Parsed<HuashengPrice>) HuashengMoneyParser.parsePrice("10.00")).value();
        return new HuashengCallbackFact(timestamp, nonce, "remote", "bill", 1, price, HuashengOrderStatus.SUCCESS,
                Optional.of(HuashengType201AssetCodecTest.card(card)), "10.00", "0123456789abcdef");
    }
}
