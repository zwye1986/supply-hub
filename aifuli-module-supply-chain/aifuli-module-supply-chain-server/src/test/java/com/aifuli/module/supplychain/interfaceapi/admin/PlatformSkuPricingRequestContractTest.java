package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品默认税务组的真实 HTTP 绑定门禁：明确不含税仍需编码，缺含税标志必须在入站拒绝。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
class PlatformSkuPricingRequestContractTest {
    private static final String PATH = "/supply-chain/products/skus/pricing";
    private static final String BODY = """
            {"platformSkuCodes":["SKU_1"],"distributionRule":{"mode":"DIRECT_MARKET_PRICE"},
             "salesRule":{"mode":"DIRECT_MARKET_PRICE"},
             "serviceFeeRule":{"percentage":100,"operator":"MULTIPLY","operand":0,"hideCopy":true},
             "taxIncluded":false,"taxRateCode":"NON_TAXABLE"}
            """;
    private final GenericBatchPricingService service = mock(GenericBatchPricingService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PlatformSkuAdminController(
                        mock(PlatformSkuQueryService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler("supply-chain-server", null))
                .build();
    }

    @Test
    void acceptsCompleteNonTaxableGroupWithoutLosingFalse() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        var captor = org.mockito.ArgumentCaptor.forClass(PricingConfig.class);
        verify(service).savePlatform(eq(java.util.List.of("SKU_1")), captor.capture(), any());
        assertThat(captor.getValue().taxMetadata().taxIncluded()).isFalse();
        assertThat(captor.getValue().taxMetadata().taxRateCode()).isEqualTo("NON_TAXABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"taxIncluded", "taxRateCode"})
    void incompleteGroupIsBadRequestForSaveAndPreview(String missingField) throws Exception {
        var body = mapper.readTree(BODY);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove(missingField);
        for (var request : java.util.List.of(put(PATH), post(PATH + "/preview"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(missingField)));
        }
        verifyNoInteractions(service);
    }

    @Test
    void schemaDocumentsBothRequiredFieldsAndNonTaxableCode() throws Exception {
        var type = PlatformSkuAdminController.PlatformSkuBatchPricingReqVO.class;
        assertThat(type.getDeclaredField("taxIncluded").getAnnotation(Schema.class).description())
                .contains("NON_TAXABLE", "不参与价格计算");
        assertThat(type.getDeclaredField("taxRateCode").getAnnotation(Schema.class).requiredMode())
                .isEqualTo(Schema.RequiredMode.REQUIRED);
    }

    /** 状态并发变化仍由后端权威拒绝，HTTP 边界输出业务码与单行 WARN，不产生 ERROR 堆栈。 */
    @Test
    void nonActivePricingReturnsBusinessErrorAtHttpBoundary() throws Exception {
        var errorCode = com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_PRICING_NOT_ALLOWED;
        var exception = com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception(errorCode, "SKU_1");
        doThrow(exception).when(service).previewPlatform(any(), any());
        doThrow(exception).when(service).savePlatform(any(), any(), any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (var request : java.util.List.of(put(PATH), post(PATH + "/preview"))) {
                mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(BODY))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(errorCode.getCode()))
                        .andExpect(jsonPath("$.msg").value(exception.getMessage()));
            }
            assertThat(logs.list).hasSize(2).allSatisfy(log -> {
                assertThat(log.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(log.getThrowableProxy()).isNull();
                assertThat(log.getFormattedMessage()).doesNotContain("\n");
            });
            assertThat(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.retryDirective(errorCode.getCode()))
                    .isEqualTo(com.aifuli.module.supplychain.error.RetryDirective.DO_NOT_RETRY);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
