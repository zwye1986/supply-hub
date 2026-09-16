package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.pricing.StandardPricingQuoteApi;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteRespDTO;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 标准商品项目报价 RPC 入口。凭证有效期 15 分钟，绑定项目、SKU、数量、用户和采购商订单号；
 * 已消费订单号禁止重新签发。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
@Slf4j
@Primary
@Validated
@RestController
@RequiredArgsConstructor
public class StandardPricingQuoteApiImpl implements StandardPricingQuoteApi {
    private final StandardPricingQuoteService quoteService;

    @Override
    public CommonResult<StandardPricingQuoteRespDTO> quote(StandardPricingQuoteReqDTO request) {
        log.info("标准商品项目报价RPC入口 sourceSystem={} externalTenantId={} projectCode={} buyerOrderNo={} skuCode={} quantity={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getProjectCode(),
                request.getBuyerOrderNo(), request.getPlatformSkuCode(), request.getQuantity());
        return success(quoteService.quote(request));
    }
}
