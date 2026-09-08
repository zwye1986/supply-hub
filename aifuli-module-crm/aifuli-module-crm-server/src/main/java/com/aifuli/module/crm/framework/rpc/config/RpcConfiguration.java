package com.aifuli.module.crm.framework.rpc.config;

import com.aifuli.module.bpm.api.task.BpmProcessInstanceApi;
import com.aifuli.module.system.api.dept.DeptApi;
import com.aifuli.module.system.api.dept.PostApi;
import com.aifuli.module.system.api.logger.OperateLogApi;
import com.aifuli.module.system.api.user.AdminUserApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "crmRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {AdminUserApi.class, DeptApi.class, PostApi.class,
        OperateLogApi.class,
        BpmProcessInstanceApi.class})
public class RpcConfiguration {
}
