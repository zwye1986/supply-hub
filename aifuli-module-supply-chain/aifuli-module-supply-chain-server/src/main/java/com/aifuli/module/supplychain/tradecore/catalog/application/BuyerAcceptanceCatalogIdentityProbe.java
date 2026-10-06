package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import java.util.Objects;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_CODE_CONFLICT;

/**
 * 受理目录唯一竞争的独立短事务探测器，只裁决是否允许原参数整批重跑，不执行补写。
 * 前置条件是失败worker已经穿出真实Spring事务代理并完全回滚，不能在失败事务内调用。
 * 以当前读交叉核验买家/SKU、买家/编码及获胜AUTO行的真实BPD归属；MANUAL不被接管。
 * 合法winner只允许后续新worker重跑；绝不返回原回滚BPD的ID/编码作为成功结果或继续写外键。
 * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
 * §3.4「批次事务边界、真实调用者与竞争重试」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerAcceptanceCatalogIdentityProbe {
    private final BuyerListingMapper listings;
    private final BuyerProductMapper products;
    /**
     * worker真实代理完全回滚后，经本bean代理开启REQUIRES_NEW短事务核验双唯一身份。
     * 无获胜行保留原竞争原因上抛，身份矛盾按稳定冲突拒绝；正常返回仅表示可以整批重跑。
     * attemptedCode只是失败尝试的诊断定位值，不代表已提交BPD；本方法不继续写入或返回该身份。
     * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
     * §3.4「批次事务边界、真实调用者与竞争重试」。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void requireWholeBatchRetry(BuyerAcceptanceCatalogIdentityRaceException race) {
        log.debug("重探测受理目录身份竞争 buyerId={} platformSkuId={} attemptedCode={}",
                race.buyerId(), race.platformSkuId(), race.attemptedCode());
        var winner = listings.selectByBuyerAndSkuForUpdate(race.buyerId(), race.platformSkuId());
        var byCode = listings.selectByBuyerAndSkuCodeForUpdate(race.buyerId(), race.attemptedCode());
        if (byCode != null && (winner == null || !Objects.equals(winner.getId(), byCode.getId())
                || !Objects.equals(race.platformSkuId(), byCode.getPlatformSkuId()))) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        if (winner == null) { throw race; }
        BuyerAcceptanceCatalogResolver.requireRowOwnership(winner, race.buyerId(), race.platformSkuId());
        if (BuyerListingManagementMode.AUTO_SELECTION.name().equals(winner.getManagementMode())) {
            var product = products.selectByIdForUpdate(winner.getBuyerProductId());
            if (product == null || !Objects.equals(winner.getBuyerId(), product.getBuyerId())
                    || !Objects.equals(winner.getPlatformSkuId(), product.getPlatformSkuId())
                    || !Objects.equals(winner.getBuyerSkuCode(), product.getBuyerProductCode())) {
                throw exception(BUYER_CATALOG_CODE_CONFLICT);
            }
        }
        var attempted = products.selectByCodeForUpdate(race.attemptedCode());
        if (attempted != null && (!Objects.equals(race.buyerId(), attempted.getBuyerId())
                || !Objects.equals(race.platformSkuId(), attempted.getPlatformSkuId()))) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        // 不返回已回滚BPD，也不在本短事务继续写；合法获胜行仅允许原参数整批重跑。
    }
}
