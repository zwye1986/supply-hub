package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreBuyerEntryApi;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.OrderDetailEntryReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.DetailEntryCommand;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.HomeEntryCommand;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.BuyerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 采购商到店购入口 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>身份三元组只从 {@link ProjectCallContextAccessor} 读取，不从请求体、普通 Header
 * 或租户上下文推断；本类不注入 Resolver、Service 或 Mapper。日志不得打印 buyerUserId、
 * token 或最终入口 URL。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreBuyerEntryApiImpl implements InstoreBuyerEntryApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreBuyerEntryFacade facade;

    /**
     * 获取到店购首页入口：读取可信买家上下文后委托 Facade，DTO 只承载页面位置。
     */
    @Override
    public CommonResult<InstoreEntryUrlRespDTO> getHomeEntry(HomeEntryReqDTO request) {
        BuyerContext context = contextAccessor.requireBuyerContext();
        log.info("[INSTORE][rpc] getHomeEntry 入口 projectId={}, buyerId={}",
                context.projectId(), context.buyerId());
        InstoreEntryUrlRespDTO response = facade.getHomeEntry(new HomeEntryCommand(
                context.projectId(), context.buyerId(), context.buyerUserId(),
                request.getLongitude(), request.getLatitude()));
        log.info("[INSTORE][rpc] getHomeEntry 出口 projectId={}, buyerId={}",
                context.projectId(), context.buyerId());
        return success(response);
    }

    /**
     * 获取到店购订单详情入口：读取可信买家上下文后委托 Facade，DTO 只承载供应链采购单号。
     */
    @Override
    public CommonResult<InstoreEntryUrlRespDTO> getOrderDetailEntry(OrderDetailEntryReqDTO request) {
        BuyerContext context = contextAccessor.requireBuyerContext();
        log.info("[INSTORE][rpc] getOrderDetailEntry 入口 projectId={}, buyerId={}, orderNo={}",
                context.projectId(), context.buyerId(), request.getOrderNo());
        InstoreEntryUrlRespDTO response = facade.getOrderDetailEntry(new DetailEntryCommand(
                context.projectId(), context.buyerId(), context.buyerUserId(),
                request.getOrderNo()));
        log.info("[INSTORE][rpc] getOrderDetailEntry 出口 projectId={}, buyerId={}, orderNo={}",
                context.projectId(), context.buyerId(), request.getOrderNo());
        return success(response);
    }
}
