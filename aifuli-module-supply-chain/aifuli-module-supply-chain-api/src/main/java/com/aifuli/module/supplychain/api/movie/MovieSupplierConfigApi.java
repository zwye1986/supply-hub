package com.aifuli.module.supplychain.api.movie;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Bean;
import feign.Logger;
import feign.codec.ErrorDecoder;

import java.util.List;

/** 电影专属配置RPC；调用方必须提供独立服务令牌，禁止普通管理端调用。 */
@FeignClient(name = ApiConstants.NAME, contextId = "movieSupplierConfig", primary = false,
        configuration = MovieSupplierConfigApi.PrivateClientConfiguration.class)
public interface MovieSupplierConfigApi {
    /** 精确鉴权路径，不开放整个RPC前缀。 */
    String PATH = ApiConstants.PREFIX + "/movie-supplier-config/accounts";
    /** 独立服务凭据头，不使用供应商密钥。 */
    String TOKEN_HEADER = "X-Movie-Config-Token";

    /** 一次取得所选渠道的完整快照，失败不能解释为空供给。 */
    @GetMapping(PATH)
    CommonResult<List<MovieSupplierAccountDTO>> accounts(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestParam("channels") List<MovieSupplierChannel> channels);

    /** 仅装配到该Feign子容器，保证全局FULL日志设置也不会打印密钥或服务令牌。 */
    class PrivateClientConfiguration {
        /** 不记录任何线协议内容，调用耗时和渠道由电影应用层单独记录。 */
        @Bean
        public Logger movieConfigPrivateLogger() {
            return new Logger() {
                @Override
                protected void log(String configKey, String format, Object... args) {
                    // 凭证RPC刻意禁用报文日志，不能把args委托给其它日志器。
                }
            };
        }

        /** 非成功响应不将服务报文带进Feign异常，避免全局异常页中的敏感信息扩散。 */
        @Bean
        public ErrorDecoder movieConfigErrorDecoder() {
            return (methodKey, response) -> new IllegalStateException(
                    "中台电影配置RPC返回HTTP " + response.status());
        }
    }
}
