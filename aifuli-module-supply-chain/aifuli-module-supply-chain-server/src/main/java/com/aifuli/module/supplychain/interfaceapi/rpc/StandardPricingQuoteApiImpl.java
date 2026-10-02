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
 * 标准商品项目报价 RPC 入口。按「项目+项目订单号」永久键签发或重放首次冻结报价：
 * 不绑定用户与支付时间，无 15 分钟有效期；同键重复询价返回同一冻结结果，已消费报价同样重放。
 * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#1-内部契约与归属}。
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
