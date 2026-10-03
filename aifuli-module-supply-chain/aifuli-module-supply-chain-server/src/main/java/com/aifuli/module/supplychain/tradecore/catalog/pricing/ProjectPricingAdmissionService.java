package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/**
 * 项目首次价格冻结与最终受理的当前选品准入，所有状态/归属决定仅使用锁后当前值。
 * 锁序为已锁报价（存在时）→ProjectListing→BuyerProduct，持锁到调用事务提交；不锁项目或定价。
 * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPricingAdmissionService {
    private final ProjectListingMapper listingMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;

    /** 无报价首冻只能定位不可变身份，随后按统一顺序当前锁定；此时调用者已经锁定项目。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Admission requireForFirstFreeze(ProjectQuoteKeyContext context) {
        BuyerProductDO identity = productMapper.selectByBuyerAndSku(context.buyerId(), context.platformSkuId());
        ProjectListingDO listingIdentity = identity == null ? null
                : listingMapper.selectByProjectAndBuyerProduct(context.projectId(), identity.getId());
        if (listingIdentity == null) {
            throw new ProductNotSaleableException("project SKU is not in catalog");
        }
        Admission admission = requireLocked(context, listingIdentity.getId());
        var sku = skuMapper.selectById(context.platformSkuId());
        if (sku == null) {
            throw new ProductNotSaleableException("platform SKU is not currently saleable");
        }
        return new Admission(admission.listingId(), sku.getSkuCode());
    }

    /** 已有报价/最终受理必须锁报价冻结的真实项目选品，不能任取其它选品或比较分销Listing主键。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void requireCurrentProjectAdmission(ProjectQuoteKeyContext context, StandardPricingQuoteService.FrozenQuote quote) {
        requireLocked(context, quote.listingId());
    }

    private Admission requireLocked(ProjectQuoteKeyContext context, Long listingId) {
        log.debug("项目选品当前准入 projectId={}, buyerId={}, platformSkuId={}, listingId={}",
                context.projectId(), context.buyerId(), context.platformSkuId(), listingId);
        ProjectListingDO listing = listingMapper.selectByIdForUpdate(listingId);
        if (listing == null || !Objects.equals(context.projectId(), listing.getProjectId())) {
            throw new IllegalStateException("frozen project listing reference is missing or mismatched");
        }
        BuyerProductDO product = productMapper.selectByIdForUpdate(listing.getBuyerProductId());
        if (product == null || !Objects.equals(context.buyerId(), product.getBuyerId())
                || !Objects.equals(context.platformSkuId(), product.getPlatformSkuId())) {
            throw new IllegalStateException("frozen buyer product reference is missing or mismatched");
        }
        if (listing.getStatus() != ProjectListingStatus.AVAILABLE.getCode()
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw new ProductNotSaleableException("project listing or buyer product is no longer saleable");
        }
        return new Admission(listing.getId(), null);
    }

    /** 已核验的真实项目选品身份与平台SKU编码；不携带可变状态或价格。 */
    public record Admission(Long listingId, String platformSkuCode) { }
}
