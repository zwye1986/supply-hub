package com.aifuli.module.supplychain.integration.system;

import com.aifuli.framework.common.biz.system.dict.DictDataCommonApi;
import com.aifuli.framework.common.biz.system.dict.dto.DictDataRespDTO;
import com.aifuli.framework.common.enums.CommonStatusEnum;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxGroupValidator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * system 动态税率字典防腐层。失败时必须关闭写入口，已有定价读取不调用本适配器。
 * 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxRateDictionaryAdapter {
    public static final String DICT_TYPE = "supply_chain_tax_rate";
    private final DictDataCommonApi dictDataApi;

    public TaxMetadata requireEnabled(Boolean taxIncluded, String taxRateCode) {
        PricingTaxGroupValidator.requireComplete(taxIncluded, taxRateCode);
        if (!taxIncluded && !PricingTaxRateCodes.NON_TAXABLE.equals(taxRateCode)) {
            throw new IllegalArgumentException("tax excluded pricing must use NON_TAXABLE");
        }
        final List<DictDataRespDTO> entries;
        long started = System.nanoTime();
        try {
            entries = dictDataApi.getDictDataList(DICT_TYPE).getCheckedData();
        } catch (RuntimeException ex) {
            log.warn("税率字典RPC失败 dictType={} elapsedMs={}", DICT_TYPE,
                    (System.nanoTime() - started) / 1_000_000, ex);
            throw new IllegalStateException("tax rate dictionary is unavailable", ex);
        }
        List<DictDataRespDTO> enabled = entries == null ? List.of() : entries.stream()
                .filter(item -> CommonStatusEnum.ENABLE.getStatus().equals(item.getStatus()))
                .toList();
        DictDataRespDTO selected = enabled.stream()
                .filter(item -> taxRateCode.equals(item.getValue()))
                .findFirst().orElse(null);
        if (selected == null) {
            throw new IllegalArgumentException("tax rate code is not enabled: " + taxRateCode);
        }
        log.info("税率字典校验成功 dictType={} taxRateCode={} label={} enabledCount={} elapsedMs={}",
                DICT_TYPE, taxRateCode, selected.getLabel(), enabled.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new TaxMetadata(taxIncluded, selected.getValue(), selected.getLabel());
    }
}
