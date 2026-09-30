package com.aifuli.channel.taobaoflash;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 淘宝闪购 O2O 渠道协议服务启动类。
 *
 * <p>独立部署、独立 DB，不访问供应链中台表；通过 Feign 调用中台 O2OChannelCommandApi。
 * 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.7。</p>
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.aifuli.channel.taobaoflash.mapper")
@EnableFeignClients(basePackages = {"com.aifuli.channel.taobaoflash", "com.aifuli.module.supplychain.api"})
public class TaobaoFlashChannelApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled", "false");
        SpringApplication.run(TaobaoFlashChannelApplication.class, args);
    }
}
