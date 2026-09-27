package com.aifuli.module.supplychain.interfaceapi;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_OCCUPIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量创建的同类冲突仍逐请求返回业务错误，但只按错误码汇总告警。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
 */
class BusinessCodeConflictExceptionAdviceTest {

    @Test
    void repeatedScopeConflictsReturnEachErrorButSampleWarnings() {
        var annotation = BusinessCodeConflictExceptionAdvice.class.getAnnotation(RestControllerAdvice.class);
        assertThat(annotation.basePackages()).contains("com.aifuli.module.supplychain.interfaceapi");
        Logger logger = (Logger) LoggerFactory.getLogger(BusinessCodeConflictExceptionAdvice.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var advice = new BusinessCodeConflictExceptionAdvice(null);
            for (int attempt = 0; attempt < 205; attempt++) {
                ServiceException error = exception(BUSINESS_CODE_OCCUPIED, "BRD_100");
                assertThat(advice.handle(error).getCode()).isEqualTo(BUSINESS_CODE_OCCUPIED.getCode());
            }
            assertThat(appender.list).hasSize(3);
            assertThat(appender.list.get(2).getFormattedMessage()).contains("count=200");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mvcRoutesSupplyChainConflictsToSampledAdviceBeforeGlobalHandler() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(BusinessCodeConflictExceptionAdvice.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new ConflictController())
                    .setControllerAdvice(new BusinessCodeConflictExceptionAdvice(null),
                            new GlobalExceptionHandler("supply-chain-server", null))
                    .build();
            mvc.perform(get("/supply-chain/test/business-code-conflict").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(BUSINESS_CODE_OCCUPIED.getCode()));
            assertThat(appender.list).hasSize(1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @RestController
    static class ConflictController {
        @GetMapping("/supply-chain/test/business-code-conflict")
        public void conflict() {
            throw exception(BUSINESS_CODE_OCCUPIED, "BRD_100");
        }
    }
}
