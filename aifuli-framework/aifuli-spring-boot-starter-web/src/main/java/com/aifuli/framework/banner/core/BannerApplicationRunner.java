package com.aifuli.framework.banner.core;

import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.concurrent.TimeUnit;

/**
 * 项目启动成功后，提供文档相关的地址
 *
 * @author 芋道源码
 */
@Slf4j
public class BannerApplicationRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        ThreadUtil.execute(() -> {
            ThreadUtil.sleep(1, TimeUnit.SECONDS); // 延迟 1 秒，保证输出到结尾
            log.info("\n----------------------------------------------------------\n\t" +
                            "项目启动成功！\n\t" +
                            "接口文档: \t{} \n\t" +
                            "开发文档: \t{} \n\t" +
                            "视频教程: \t{} \n" +
                            "----------------------------------------------------------",
                    "https://cloud.iocoder.cn/api-doc/",
                    "https://cloud.iocoder.cn",
                    "https://t.zsxq.com/02Yf6M7Qn");

            // 商城系统
            System.out.println("[商城系统 aifuli-module-mall 教程][参考 https://cloud.iocoder.cn/mall/build/ 开启]");
            // ERP 系统
            System.out.println("[ERP 系统 aifuli-module-erp - 教程][参考 https://cloud.iocoder.cn/erp/build/ 开启]");
            // 支付平台
            System.out.println("[支付系统 aifuli-module-pay - 教程][参考 https://cloud.iocoder.cn/pay/build/ 开启]");
        });
    }

}
