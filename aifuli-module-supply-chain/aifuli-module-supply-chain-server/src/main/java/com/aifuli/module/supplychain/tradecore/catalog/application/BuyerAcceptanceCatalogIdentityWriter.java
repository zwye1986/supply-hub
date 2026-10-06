package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.LocalDateTime;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/**
 * 在调用者的供应链整批事务内确保BPD对应的唯一受理目录身份，不独立提交、不发布商业版本。
 * 新AUTO_SELECTION行只创建真实BPD关联、DRAFT/NULL current及UNKNOWN/NOT_EVALUATED投影。
 * 已有LST仅普通读校验买家/SKU及编码归属后复用，保留MANUAL商业价/生命周期，不反锁已有LST；
 * RETIRED为终态，不自动恢复或创建第二条。该约束保持选品/定价到已有LST之间没有反向写锁。
 * 只有封闭LST身份键的真实1062转换为专用竞争异常，立即穿出worker使整批回滚；不在catch补写。
 * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#32-创建协议}
 * §3.2「创建协议」、§3.4「批次事务边界、真实调用者与竞争重试」及§7.1「固定锁序及禁止项」。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BuyerAcceptanceCatalogIdentityWriter {
    public static final String NOT_EVALUATED = "NOT_EVALUATED";
    private static final Set<String> IDENTITY_KEYS = Set.of("uk_sc_listing_buyer_sku", "uk_sc_listing_buyer_code", "uk_sc_listing_buyer_product");
    private final BuyerListingMapper listings;
    private final BuyerListingAvailabilityMapper availability;
    private final BusinessNoGenerator numbers;

    /**
     * 使用同事务已新建或复活的真实BPD确保目录身份，MANDATORY禁止把身份拆成独立提交。
     * 已有目录只读复用且不可换绑；新增目录和初始投影任一失败均随整个批次回滚。
     * 返回值只供成功worker返回，不能在竞争异常后把已回滚BPD继续作为成功身份。
     * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#32-创建协议}
     * §3.2「创建协议」及§3.4「批次事务边界、真实调用者与竞争重试」。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String ensure(BuyerProductDO product, Long operatorId, LocalDateTime now) {
        log.debug("确保受理目录身份 buyerId={} skuId={} buyerProductId={}", product.getBuyerId(), product.getPlatformSkuId(), product.getId());
        BuyerListingDO existing = listings.selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
        BuyerListingDO byCode = listings.selectByBuyerAndSkuCode(product.getBuyerId(), product.getBuyerProductCode());
        if (byCode != null && (existing == null || !Objects.equals(byCode.getId(), existing.getId())
                || !Objects.equals(byCode.getPlatformSkuId(), product.getPlatformSkuId()))) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        if (existing != null) {
            BuyerAcceptanceCatalogResolver.requireRowOwnership(existing, product.getBuyerId(), product.getPlatformSkuId());
            if (BuyerListingManagementMode.AUTO_SELECTION.name().equals(existing.getManagementMode())
                    && (!Objects.equals(product.getId(), existing.getBuyerProductId())
                    || !Objects.equals(product.getBuyerProductCode(), existing.getBuyerSkuCode()))) {
                throw exception(BUYER_CATALOG_CODE_CONFLICT);
            }
            if (Objects.equals(existing.getStatus(), BuyerListingStatus.RETIRED.getCode())) {
                throw exception(BUYER_CATALOG_RETIRED);
            }
            return existing.getListingNo();
        }
        BuyerListingDO row = BuyerListingDO.builder().listingNo(numbers.next(BusinessNoPrefixes.LST))
                .buyerId(product.getBuyerId()).platformSkuId(product.getPlatformSkuId())
                .buyerSkuCode(product.getBuyerProductCode()).managementMode(BuyerListingManagementMode.AUTO_SELECTION.name())
                .buyerProductId(product.getId()).status(BuyerListingStatus.DRAFT.getCode()).version(0L)
                .createdBy(operatorId).updatedBy(operatorId).createdAt(now).updatedAt(now).build();
        try {
            listings.insert(row);
        } catch (DuplicateKeyException duplicate) {
            if (!isIdentityKeyViolation(duplicate)) { throw duplicate; }
            throw new BuyerAcceptanceCatalogIdentityRaceException(product.getBuyerId(), product.getPlatformSkuId(), product.getBuyerProductCode(), duplicate);
        }
        availability.insert(BuyerListingAvailabilityDO.builder().listingId(row.getId())
                .availability(ListingAvailability.UNKNOWN.getCode()).reasonCode(NOT_EVALUATED)
                .basisVersion(0L).version(0L).evaluatedAt(now).updatedAt(now).build());
        return row.getListingNo();
    }

    /**
     * 仅真实MySQL 1062且命中封闭LST身份键才允许整批竞争协议，业务号及其它SQL异常原样上抛。
     * 不把BPD重复业务错误或任意DuplicateKeyException扩大为可重试目录竞争。
     * 设计见 {@code docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#34-批次事务边界真实调用者与竞争重试}
     * §3.4「批次事务边界、真实调用者与竞争重试」。
     */
    static boolean isIdentityKeyViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && sql.getMessage() != null) {
                return IDENTITY_KEYS.stream().anyMatch(key -> sql.getMessage().contains("." + key + "'")
                        || sql.getMessage().contains("'" + key + "'"));
            }
        }
        return false;
    }
}
