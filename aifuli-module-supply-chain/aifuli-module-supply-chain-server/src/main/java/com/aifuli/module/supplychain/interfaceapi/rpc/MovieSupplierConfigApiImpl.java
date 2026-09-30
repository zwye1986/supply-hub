package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.apilog.core.annotation.ApiAccessLog;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.movie.*;
import com.aifuli.module.supplychain.tradecore.supply.application.MovieSupplierConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.ArrayList;
import static com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;

/** 仅供电影进程获取签名配置，方法级服务鉴权不可被普通登录或permitAll替代。 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MovieSupplierConfigApiImpl implements MovieSupplierConfigApi {
    /** 应用层负责数据所有权、已发布状态和凭证解析。 */
    private final MovieSupplierConfigService configService;

    /** 禁止记录整个请求/响应，HTTP缓存不得保留含密钥的配置。 */
    @Override
    @ApiAccessLog(enable = false)
    @PreAuthorize("@movieSupplierRpcAccess.allowed(#p0)")
    public CommonResult<List<MovieSupplierAccountDTO>> accounts(String token, List<MovieSupplierChannel> channels) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            attributes.getResponse().setHeader("Cache-Control", "no-store");
        }
        try {
            return CommonResult.success(configService.accounts(channels));
        } catch (RuntimeException failure) {
            // 显式返回失败，不让配置JSON、SQL或第三方异常消息被全局错误日志/响应直接输出。
            List<String> causes = new ArrayList<>();
            Throwable current = failure;
            while (current != null && causes.size() < 10) {
                String location = current.getStackTrace().length == 0 ? "unknown" : current.getStackTrace()[0].toString();
                causes.add(current.getClass().getSimpleName() + "@" + location);
                current = current.getCause();
            }
            log.debug("电影配置读取失败 channels={} 安全因果链={}", channels, causes);
            return CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "电影配置读取失败，请检查当前发布版本、JSON字段及凭证槽；中台debug日志包含安全因果链");
        }
    }
}
