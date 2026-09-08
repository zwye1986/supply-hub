package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryAccessService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryAccessService.DetailEntrySnapshot;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.IssuedCredential;
import com.aifuli.module.supplychain.border.instore.application.InstoreHomeEntryProperties;
import com.aifuli.module.supplychain.border.instore.domain.InstoreChannelCode;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.DetailUrlCommand;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.EntryUrlResult;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.HomeUrlCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ENTRY_URL_UNAVAILABLE;

/**
 * 采购商到店购 HOME/DETAIL 入口门面：事务外编排只读校验、当前会员路由解析、
 * 一次性凭证签发与旅通最终 URL 外呼。
 *
 * <p>本类自身禁止开启数据库事务：{@code issueHome/issueDetail} 与 {@code revoke}
 * 各自是独立本地事务，URL 外呼位于两段事务之间。URL 调用失败统一视为结果未知：
 * 先以签发版本撤销凭证，再返回 046 并通过 {@code initCause} 保留原始异常；
 * 调用方重试会签发新 token。校验失败发生在签发前，不创建凭证、不外呼。
 * 详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(InstoreHomeEntryProperties.class)
public class InstoreBuyerEntryFacade {

    private final BuyerProjectAccessResolver projectAccessResolver;
    private final InstoreEntryAccessService entryAccessService;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final InstoreEntryCredentialService credentialService;
    private final RtmartEntryUrlPort entryUrlPort;
    private final InstoreHomeEntryProperties homeEntryProperties;

    /**
     * 获取到店购首页最终入口 URL。
     *
     * <p>固定顺序：项目校验 → 读取 HOME 配置 companyCode 并校验启用绑定 → 当前 MEMBER_PROFILE 路由 →
     * HOME 凭证签发 → 旅通 URL 外呼。经纬度只透传给旅通请求，不持久化。
     * companyCode 不接受调用方传入。</p>
     */
    public InstoreEntryUrlRespDTO getHomeEntry(HomeEntryCommand command) {
        Objects.requireNonNull(command, "command");
        String channelCode = InstoreChannelCode.RT_MART.name();
        String companyCode = homeEntryProperties.getCompanyCode();
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][buyerEntry] getHomeEntry 入口 projectId={}, buyerId={}, channelCode={}",
                command.projectId(), command.buyerId(), channelCode);

        projectAccessResolver.requireActive(command.projectId(), command.buyerId());
        entryAccessService.requireEnabledCompany(channelCode, companyCode);
        BuyerIntegrationRoute route = routeResolver.resolveCurrent(
                command.projectId(), BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        IssuedCredential issued = credentialService.issueHome(
                command.buyerId(), command.projectId(), command.buyerUserId(),
                channelCode, route, companyCode);

        HomeUrlCommand urlCommand = new HomeUrlCommand(
                command.projectId(), channelCode, companyCode,
                command.longitude(), command.latitude(), issued.token());
        EntryUrlResult result = callEntryUrlPort(() -> entryUrlPort.getHomeUrl(urlCommand),
                command.projectId(), channelCode, issued, startMs);
        log.info("[INSTORE][buyerEntry] getHomeEntry 完成 projectId={}, buyerId={}, channelCode={}, "
                        + "credentialNo={}, purpose={}, cost={}ms",
                command.projectId(), command.buyerId(), channelCode,
                issued.credentialNo(), issued.purpose(), System.currentTimeMillis() - startMs);
        return toResponse(result);
    }

    /**
     * 获取到店购订单详情最终入口 URL。
     *
     * <p>固定顺序：项目校验 → 订单快照读取（companyCode/tradeId 只取订单侧固化值）→
     * 当前 MEMBER_PROFILE 路由 → 绑定订单的 DETAIL 凭证签发 → 旅通 URL 外呼。
     * 调用方只提交 orderNo，不能提交或覆盖 companyCode/tradeId。</p>
     */
    public InstoreEntryUrlRespDTO getOrderDetailEntry(DetailEntryCommand command) {
        Objects.requireNonNull(command, "command");
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][buyerEntry] getOrderDetailEntry 入口 projectId={}, buyerId={}, orderNo={}",
                command.projectId(), command.buyerId(), command.orderNo());

        projectAccessResolver.requireActive(command.projectId(), command.buyerId());
        DetailEntrySnapshot detail = entryAccessService.requireDetail(
                command.orderNo(), command.projectId(), command.buyerId(), command.buyerUserId());
        BuyerIntegrationRoute route = routeResolver.resolveCurrent(
                command.projectId(), BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        IssuedCredential issued = credentialService.issueDetail(
                command.buyerId(), command.projectId(), command.buyerUserId(),
                detail.channelCode(), route, detail.companyCode(), detail.instoreOrderId());

        DetailUrlCommand urlCommand = new DetailUrlCommand(
                command.projectId(), detail.channelCode(), detail.companyCode(),
                detail.tradeId(), issued.token());
        EntryUrlResult result = callEntryUrlPort(() -> entryUrlPort.getDetailUrl(urlCommand),
                command.projectId(), detail.channelCode(), issued, startMs);
        log.info("[INSTORE][buyerEntry] getOrderDetailEntry 完成 projectId={}, buyerId={}, orderNo={}, "
                        + "channelCode={}, credentialNo={}, purpose={}, cost={}ms",
                command.projectId(), command.buyerId(), command.orderNo(), detail.channelCode(),
                issued.credentialNo(), issued.purpose(), System.currentTimeMillis() - startMs);
        return toResponse(result);
    }

    /**
     * 执行旅通 URL 外呼；失败统一视为结果未知，先撤销刚签发的凭证再返回 046。
     *
     * <p>撤销使用签发返回的 credentialId + credentialVersion，保证只撤销本次外呼对应的
     * token；046 通过 {@code initCause} 保留原始异常供排障。日志只记录项目、渠道、
     * 凭证业务号、用途、耗时和错误分类，不记录 token 或最终 URL 参数。</p>
     */
    private EntryUrlResult callEntryUrlPort(EntryUrlCall call, Long projectId, String channelCode,
                                            IssuedCredential issued, long startMs) {
        try {
            log.info("[INSTORE][rtmart] entryUrl 请求 projectId={}, channelCode={}, credentialNo={}, purpose={}",
                    projectId, channelCode, issued.credentialNo(), issued.purpose());
            EntryUrlResult result = call.execute();
            log.info("[INSTORE][rtmart] entryUrl 响应 projectId={}, channelCode={}, credentialNo={}, "
                            + "purpose={}, cost={}ms",
                    projectId, channelCode, issued.credentialNo(), issued.purpose(),
                    System.currentTimeMillis() - startMs);
            return result;
        } catch (RuntimeException ex) {
            log.warn("[INSTORE][rtmart] entryUrl 异常 projectId={}, channelCode={}, credentialNo={}, "
                            + "purpose={}, errorClass={}, cost={}ms",
                    projectId, channelCode, issued.credentialNo(), issued.purpose(),
                    ex.getClass().getName(), System.currentTimeMillis() - startMs);
            credentialService.revoke(issued.credentialId(), issued.credentialVersion(),
                    InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);
            ServiceException unavailable = exception(INSTORE_ENTRY_URL_UNAVAILABLE);
            unavailable.initCause(ex);
            throw unavailable;
        }
    }

    private static InstoreEntryUrlRespDTO toResponse(EntryUrlResult result) {
        InstoreEntryUrlRespDTO response = new InstoreEntryUrlRespDTO();
        response.setEntryUrl(result.entryUrl());
        return response;
    }

    /** 旅通 URL 外呼的内部函数接口，仅用于统一 HOME/DETAIL 的失败处理。 */
    @FunctionalInterface
    private interface EntryUrlCall {
        EntryUrlResult execute();
    }

    /**
     * Facade 内部命令。projectId/buyerId/buyerUserId 来自可信认证上下文，
     * companyCode 来自 HOME 配置；Wire DTO 禁止声明这些字段。
     */
    public record HomeEntryCommand(Long projectId, Long buyerId, String buyerUserId,
                                   String longitude, String latitude) {
    }

    /**
     * Facade 内部命令。订单归属由可信上下文与订单快照共同校验，
     * 调用方只提交供应链采购单号。
     */
    public record DetailEntryCommand(Long projectId, Long buyerId, String buyerUserId, String orderNo) {
    }
}
