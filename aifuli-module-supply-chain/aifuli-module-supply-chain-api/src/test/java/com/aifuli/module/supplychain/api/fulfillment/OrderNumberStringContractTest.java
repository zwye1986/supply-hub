package com.aifuli.module.supplychain.api.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 新24位编号与历史ULID均保持JSON字符串身份，禁止转数字损失精度。 */
class OrderNumberStringContractTest {
    @Test
    void newAndHistoricalNumbersRoundTripAsJsonStrings() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : List.of("202610131530274200000001", "SC_01M4SJJ9QN2TWZV16MHSRQZ96V")) {
            var receipt = new FulfillmentAcceptanceReceiptRespDTO();
            receipt.setOrderNo(value);
            String json = mapper.writeValueAsString(receipt);
            assertThat(json).contains("\"orderNo\":\"" + value + "\"");
            assertThat(
                            mapper.readValue(json, FulfillmentAcceptanceReceiptRespDTO.class)
                                    .getOrderNo())
                    .isEqualTo(value);
        }
    }
}
