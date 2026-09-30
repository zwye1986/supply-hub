package com.aifuli.module.supplychain.integration.system;

import com.aifuli.framework.common.biz.system.dict.DictDataCommonApi;
import com.aifuli.framework.common.biz.system.dict.dto.DictDataRespDTO;
import com.aifuli.framework.common.enums.CommonStatusEnum;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 税率字典 RPC 契约：失败关闭写入口，缺省含税标志不得默认为不含税。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
class TaxRateDictionaryAdapterContractTest {

    @Test
    void wrapsRpcFailureAsFailClosedIllegalState() {
        DictDataCommonApi dictDataApi = mock(DictDataCommonApi.class);
        when(dictDataApi.getDictDataList(TaxRateDictionaryAdapter.DICT_TYPE))
                .thenThrow(new RuntimeException("dictionary rpc down"));
        TaxRateDictionaryAdapter adapter = new TaxRateDictionaryAdapter(dictDataApi);

        assertThatThrownBy(() -> adapter.requireEnabled(true, "RATE_6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tax rate dictionary is unavailable");
    }

    @Test
    void omittedTaxIncludedDoesNotDefaultToExcluded() {
        TaxRateDictionaryAdapter adapter = new TaxRateDictionaryAdapter(mock(DictDataCommonApi.class));
        assertThatThrownBy(() -> adapter.requireEnabled(null, PricingTaxRateCodes.NON_TAXABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
    }

    @Test
    void returnsEnabledDictionaryLabel() {
        DictDataRespDTO enabled = new DictDataRespDTO();
        enabled.setValue("RATE_6");
        enabled.setLabel("6%");
        enabled.setStatus(CommonStatusEnum.ENABLE.getStatus());
        DictDataCommonApi dictDataApi = mock(DictDataCommonApi.class);
        when(dictDataApi.getDictDataList(TaxRateDictionaryAdapter.DICT_TYPE))
                .thenReturn(CommonResult.success(List.of(enabled)));
        TaxRateDictionaryAdapter adapter = new TaxRateDictionaryAdapter(dictDataApi);

        TaxMetadata tax = adapter.requireEnabled(true, "RATE_6");
        assertThat(tax.taxIncluded()).isTrue();
        assertThat(tax.taxRateCode()).isEqualTo("RATE_6");
        assertThat(tax.taxRateLabel()).isEqualTo("6%");
    }
}
