package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryRespDTO;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelUserBindingResolver;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelUserBindingResolver.BindingSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 采购商 O2O H5 入口门面：校验 Buyer、BuyerUser 与场景接入配置后读取或创建渠道用户绑定，
 * 再以匿名 channelUserId 返回 H5 入口。
 *
 * <p>盒马与淘宝闪购的区别仅体现在 H5 URL 配置（由 sceneCode 选择），对标原淘宝闪购
 * {@code mockElemeH5BaseUrl}/PPE/PROD 的配置化思路。内部 buyerId/buyerUserId 不得直接暴露给渠道。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#3.6-O2O-应用编排}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerO2OEntryFacade {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String SCENE_HEMA = "HEMA";

    private final BuyerResolver buyerResolver;
    private final O2OChannelUserBindingResolver bindingResolver;

    /** 默认 H5 入口 URL（淘宝闪购），可通过配置覆盖。 */
    @Value("${aifuli.supply-chain.o2o.entry.h5-url-default:https://tb.ele.me/wow/alsc/mod/69958d4a14beac9d0c5bbbc7/}")
    private String defaultH5Url;
    /** 盒马场景 H5 入口 URL，由 sceneCode=HEMA 选择。 */
    @Value("${aifuli.supply-chain.o2o.entry.h5-url-hema:https://tb.ele.me/wow/alsc/mod/hema/}")
    private String hemaH5Url;

    /**
     * 获取 O2O H5 入口：解析采购商 → 读取或创建渠道用户绑定 → 按 sceneCode 选择 H5 URL。
     *
     * <p>响应只返回匿名 channelUserId 与 H5 URL，不回传内部 buyerId。本期 token 留空，
     * 后续可改为调渠道 SSO 端点签发 JWT。</p>
     */
    public O2OEntryRespDTO getH5Entry(O2OEntryReqDTO request) {
        log.info("[O2O][facade] getH5Entry 入口 sourceSystem={}, externalTenantId={}, buyerUserId={}, channelCode={}, sceneCode={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getBuyerUserId(),
                request.getChannelCode(), request.getSceneCode());
        LocalDateTime now = databaseClock.now();
        BuyerResolver.BuyerSnapshot buyer = buyerResolver.resolve(
                request.getSourceSystem(), request.getExternalTenantId(), now);
        BindingSnapshot binding = bindingResolver.resolveOrCreateForEntry(
                buyer.id(), request.getBuyerUserId(), request.getChannelCode());

        O2OEntryRespDTO response = new O2OEntryRespDTO();
        response.setChannelUserId(binding.channelUserId());
        response.setH5Url(selectH5Url(request.getSceneCode()) + "?channelUserId=" + binding.channelUserId());
        response.setToken(null);
        log.info("[O2O][facade] getH5Entry 完成 buyerId={}, channelUserId={}, sceneCode={}",
                buyer.id(), binding.channelUserId(), request.getSceneCode());
        return response;
    }

    private String selectH5Url(String sceneCode) {
        if (sceneCode != null && SCENE_HEMA.equalsIgnoreCase(sceneCode)) {
            return hemaH5Url;
        }
        return defaultH5Url;
    }
}
