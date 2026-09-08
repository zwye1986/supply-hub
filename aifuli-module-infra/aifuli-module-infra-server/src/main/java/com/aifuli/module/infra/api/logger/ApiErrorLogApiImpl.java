package com.aifuli.module.infra.api.logger;

import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.infra.service.logger.ApiErrorLogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import static com.aifuli.framework.common.pojo.CommonResult.success;

@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class ApiErrorLogApiImpl implements ApiErrorLogCommonApi {

    @Resource
    private ApiErrorLogService apiErrorLogService;

    @Override
    public CommonResult<Boolean> createApiErrorLog(ApiErrorLogCreateReqDTO createDTO) {
        apiErrorLogService.createApiErrorLog(createDTO);
        return success(true);
    }

}
