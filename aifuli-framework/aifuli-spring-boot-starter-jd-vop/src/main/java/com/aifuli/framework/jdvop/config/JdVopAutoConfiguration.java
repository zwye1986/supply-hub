package com.aifuli.framework.jdvop.config;

import com.aifuli.framework.jdvop.core.address.DefaultJdVopAddressClient;
import com.aifuli.framework.jdvop.core.address.JdVopAddressClient;
import com.aifuli.framework.jdvop.core.auth.JdVopTokenManager;
import com.aifuli.framework.jdvop.core.client.DefaultJdVopClient;
import com.aifuli.framework.jdvop.core.client.JdVopClient;
import com.aifuli.framework.jdvop.core.order.DefaultJdVopOrderClient;
import com.aifuli.framework.jdvop.core.order.JdVopOrderClient;
import com.aifuli.framework.jdvop.core.product.DefaultJdVopProductClient;
import com.aifuli.framework.jdvop.core.product.JdVopProductClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;

/**
 * 京东 VOP 自动配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(JdVopProperties.class)
@ConditionalOnProperty(prefix = JdVopProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JdVopAutoConfiguration {

    /**
     * 创建京东开放平台专用 HTTP 客户端。
     */
    @Bean("jdVopHttpClient")
    @ConditionalOnMissingBean(name = "jdVopHttpClient")
    public HttpClient jdVopHttpClient(JdVopProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public JdVopTokenManager jdVopTokenManager(JdVopProperties properties) {
        // Token 管理器内部持有独立 Redis 连接，不会覆盖应用已有主 Redis Bean。
        return new JdVopTokenManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdVopClient jdVopClient(
            JdVopProperties properties,
            JdVopTokenManager tokenManager,
            @Qualifier("jdVopHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper) {
        return new DefaultJdVopClient(properties, tokenManager, httpClient, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdVopProductClient jdVopProductClient(JdVopClient jdVopClient) {
        return new DefaultJdVopProductClient(jdVopClient);
    }

    /** 创建京东订单类型化客户端。 */
    @Bean
    @ConditionalOnMissingBean
    public JdVopOrderClient jdVopOrderClient(JdVopProperties properties, JdVopClient jdVopClient) {
        return new DefaultJdVopOrderClient(properties, jdVopClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdVopAddressClient jdVopAddressClient(
            JdVopProperties properties,
            JdVopClient jdVopClient) {
        return new DefaultJdVopAddressClient(properties, jdVopClient);
    }

}
