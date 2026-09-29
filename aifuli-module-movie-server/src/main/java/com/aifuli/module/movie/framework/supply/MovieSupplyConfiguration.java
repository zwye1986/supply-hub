package com.aifuli.module.movie.framework.supply;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.aifuli.module.supplychain.api.movie.MovieSupplierConfigApi;

import java.time.Clock;

/** 独立电影进程的供给配置，不扩大组件或 RPC 扫描边界。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MovieSupplyProperties.class)
@EnableFeignClients(clients = MovieSupplierConfigApi.class)
public class MovieSupplyConfiguration {
    /** 使用 UTC 时钟判断过期，供应商本地时间由协议适配层显式转换。 */
    @Bean
    public Clock movieSupplyClock() {
        return Clock.systemUTC();
    }
}
