package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.tracer.core.filter.TraceFilter;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.testsupport.SupplierExternalSkuTestSupport;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实Controller/业务服务/MySQL/异常处理器/TraceFilter验证现有前端错误契约，不修改前端。
 * 不加载方法授权代理；授权本身由SupplyChainAdminMethodSecurityContractTest负责。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#35-外部商品编码占用预检与管理端错误契约2026-10-22。
 */
class SupplierExternalSkuErrorContractTest extends SupplierExternalSkuTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void currentFrontendReceivesBusinessMessageAndTraceId(String method) throws Exception {
        var f = fixture();
        service.createPublished(f.supplierCode(), List.of(line(f.first(), "1")), 1L);
        String path = "/supply-chain/suppliers/" + f.supplierCode() + "/offers";
        if ("PUT".equals(method)) {
            path += "/" + service.createPublished(f.supplierCode(), List.of(line(f.second(), "2")), 1L).getFirst();
        }
        var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var body = mapper.createObjectNode().put("platformSkuCode", f.second().getSkuCode())
                .put("externalSkuCode", "1").put("priceMode", "FIXED").put("supplyMode", "API_DIRECT")
                .put("unitPriceCents", 30000).put("maxQuantity", 100).put("minQuantity", 1);
        var payload = "POST".equals(method) ? mapper.createObjectNode().set("items", mapper.createArrayNode().add(body)) : body;
        var controller = new SupplierAdminController(null, null, null, service, null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler("supply-chain-server", null))
                .addFilters(new TraceFilter()).build();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var response = mvc.perform(request(HttpMethod.valueOf(method), path).header("trace-id", "external-code-contract")
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                    .andExpect(status().isOk()).andExpect(header().stringValues("trace-id", "external-code-contract"))
                    .andExpect(jsonPath("$.code").value(1_050_001_183))
                    .andExpect(jsonPath("$.msg").value("供应商商品编码「1」已被合作商品「" + f.first().getSkuCode()
                            + "」占用，下架商品仍占用编码，请核对后修改。"))
                    .andReturn().getResponse();
            assertThat(mapper.readTree(response.getContentAsByteArray()).get("data").isNull()).isTrue();
            assertThat(offerCount(f)).isEqualTo("POST".equals(method) ? 1 : 2);
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).containsEntry("traceId", "external-code-contract");
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
