package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;

/**
 * 管理端事务外编排：原领域整批提交后才逐条独立刷新；目录故障不伪装成已提交保存失败。
 * 显式重算则返回真实同步结果，故障原样传播由 HTTP 边界负责日志。所有调用均经独立 bean 代理。
 * 设计见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#3-入口与提交后的真实结果}
 * 及 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#7-管理可见性与显式重算}。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BuyerCatalogManagementService {
    private final BuyerProductCommandService selection;
    private final GenericBatchPricingService pricing;
    private final BuyerSelectionCatalogLifecycle lifecycle;
    private final BuyerMapper buyers;
    private final BuyerProductMapper products;
    private final BuyerListingMapper listings;

    /** 返回编码只取已成功提交的 worker，不由请求 SKU 或未提交尝试拼装。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.NEVER)
    public List<String> addProducts(String buyerCode, List<BuyerProductCommandService.ProductLine> lines, Long operatorId) {
        List<String> committedCodes = selection.addProducts(buyerCode, lines, operatorId);
        refreshCommitted(committedCodes, operatorId);
        return committedCodes;
    }

    /** 保留原单事务整批定价与项目继承重建，提交返回后才逐条刷新，不拆价格事务。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.NEVER)
    public boolean saveBuyerPricing(String buyerCode, List<String> productCodes, ProjectOverrides overrides, Long operatorId) {
        pricing.saveBuyer(buyerCode, productCodes, overrides, operatorId);
        refreshCommitted(productCodes, operatorId);
        return true;
    }

    /** 路径买家归属先校验；既有暂停/退役/手工目录由生命周期返回，不再次进入补身份协议。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.NEVER)
    public BuyerSelectionCatalogLifecycle.RefreshResult recalculate(String buyerCode, String productCode, Long operatorId) {
        var buyer = buyers.selectByBuyerCode(buyerCode);
        if (buyer == null) throw exception(BUYER_PROFILE_NOT_FOUND);
        var product = products.selectByCode(productCode);
        if (product == null || !Objects.equals(product.getBuyerId(), buyer.getId())) {
            throw exception(BUYER_PRODUCT_NOT_FOUND);
        }
        return lifecycle.refreshListing(locateOrEnsure(productCode, product, operatorId), operatorId);
    }

    /**
     * 保存已经提交，本层是该条降级的唯一终结边界；系统故障一次 ERROR 保留因果，业务拒绝单行 WARN。
     * 不续期、不补 AVAILABLE、不重发非幂等保存；每条失败仍继续其余已提交 BPD。
     * 日志遵循 {@code docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#23-异常日志分级与防重复打印规范职责单一原则}。
     */
    private void refreshCommitted(List<String> codes, Long operatorId) {
        for (String code : new LinkedHashSet<>(codes)) {
            String listingNo = null;
            try {
                listingNo = locateOrEnsure(code, products.selectByCode(code), operatorId);
                lifecycle.refreshListing(listingNo, operatorId);
            } catch (ServiceException rejected) {
                log.warn("[catalogRefresh][商品已保存但目录刷新被拒绝] buyerProductCode={}, listingNo={}, errorCode={}",
                        code, listingNo, rejected.getCode());
            } catch (RuntimeException failure) {
                log.error("[catalogRefresh][商品已保存但目录刷新失败] buyerProductCode={}, listingNo={}",
                        code, listingNo, failure);
            }
        }
    }

    private String locateOrEnsure(String code, BuyerProductDO product, Long operatorId) {
        if (product != null) {
            var listing = listings.selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
            if (listing != null) {
                if (!Objects.equals(listing.getBuyerId(), product.getBuyerId())
                        || !Objects.equals(listing.getPlatformSkuId(), product.getPlatformSkuId())
                        || BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode())
                        && (!Objects.equals(listing.getBuyerProductId(), product.getId())
                        || !Objects.equals(listing.getBuyerSkuCode(), product.getBuyerProductCode()))) {
                    throw new IllegalStateException("Acceptance catalog immutable ownership is invalid");
                }
                return listing.getListingNo();
            }
        }
        return selection.ensureIdentity(code, operatorId);
    }
}
