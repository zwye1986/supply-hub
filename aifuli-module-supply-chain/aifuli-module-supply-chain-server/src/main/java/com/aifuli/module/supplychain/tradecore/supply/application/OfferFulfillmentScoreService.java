package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_ITEMS_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 合作商品履约能力评分的单一写入服务：稳定性/时效性挂在未退役 Offer 身份列。
 * 供应商批量评分与商品列表弹窗读写同一行事实，catalog 不得直接 UPDATE 评分列。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferFulfillmentScoreService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplyChainDatabaseClock databaseClock;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferMapper offerMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;

    /**
     * 供应商侧写入：省略 SKU 列表则覆盖该供应商全部未退役 Offer；非空列表只覆盖这些 SKU，整批校验。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int saveSupplierScores(String supplierCode, Integer stabilityScore, Integer timelinessScore,
                                  List<String> platformSkuCodes, Long operatorId) {
        log.info("保存供应商履约评分 supplierCode={} skuSubsetSize={} operatorId={}",
                supplierCode, platformSkuCodes == null ? null : platformSkuCodes.size(), operatorId);
        requireScorePair(stabilityScore, timelinessScore);
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        int updated;
        if (platformSkuCodes == null || platformSkuCodes.isEmpty()) {
            updated = writeScores(offerMapper.selectLiveBySupplierId(supplier.getId()),
                    stabilityScore, timelinessScore, operatorId);
        } else {
            List<SupplierOfferDO> targets = new ArrayList<>();
            for (String skuCode : platformSkuCodes) {
                PlatformSkuDO sku = requireSku(skuCode);
                SupplierOfferDO offer = offerMapper.selectLiveBySupplierAndSku(supplier.getId(), sku.getId());
                if (offer == null) {
                    throw exception(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING);
                }
                targets.add(offer);
            }
            updated = writeScores(targets, stabilityScore, timelinessScore, operatorId);
        }
        log.info("保存供应商履约评分完成 supplierCode={} updatedCount={} operatorId={}",
                supplierCode, updated, operatorId);
        return updated;
    }

    /**
     * 商品侧查询：SKU 存在但无未退役 Offer 时返回空 items，不 404。
     */
    public SkuFulfillmentScoreView getSkuScores(String skuCode) {
        log.info("查询SKU履约评分 skuCode={}", skuCode);
        PlatformSkuDO sku = requireSku(skuCode);
        PlatformSpuDO spu = sku.getSpuId() == null ? null : spuMapper.selectById(sku.getSpuId());
        List<SkuScoreItem> items = new ArrayList<>();
        for (SupplierOfferDO offer : offerMapper.selectLiveBySkuId(sku.getId())) {
            SupplierDO supplier = supplierMapper.selectById(offer.getSupplierId());
            if (supplier == null) {
                throw exception(SUPPLIER_NOT_FOUND);
            }
            items.add(new SkuScoreItem(
                    supplier.getSupplierCode(),
                    supplier.getSupplierName(),
                    offer.getOfferNo(),
                    offer.getStabilityScore(),
                    offer.getTimelinessScore()));
        }
        return new SkuFulfillmentScoreView(
                sku.getSkuCode(),
                sku.getSkuName(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                List.copyOf(items));
    }

    /**
     * 商品侧写入：只更新提交行；未出现的当前合作供应商保持原值。整单拒绝非合作供应商。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int saveSkuScores(String skuCode, List<SkuSupplierScoreLine> items, Long operatorId) {
        log.info("保存SKU履约评分 skuCode={} itemCount={} operatorId={}",
                skuCode, items == null ? 0 : items.size(), operatorId);
        if (items == null || items.isEmpty()) {
            throw exception(FULFILLMENT_SCORE_ITEMS_REQUIRED);
        }
        Set<String> seen = new HashSet<>();
        for (SkuSupplierScoreLine line : items) {
            if (line == null || line.supplierCode() == null || line.supplierCode().isBlank()) {
                throw exception(FULFILLMENT_SCORE_INVALID);
            }
            if (!seen.add(line.supplierCode())) {
                throw exception(FULFILLMENT_SCORE_INVALID);
            }
            requireScorePair(line.stabilityScore(), line.timelinessScore());
        }
        PlatformSkuDO sku = requireSku(skuCode);
        List<SupplierOfferDO> targets = new ArrayList<>();
        List<Integer> stabilities = new ArrayList<>();
        List<Integer> timelinesses = new ArrayList<>();
        for (SkuSupplierScoreLine line : items) {
            SupplierDO supplier = supplierMapper.selectByCodeForUpdate(line.supplierCode());
            if (supplier == null) {
                throw exception(SUPPLIER_NOT_FOUND);
            }
            SupplierOfferDO offer = offerMapper.selectLiveBySupplierAndSku(supplier.getId(), sku.getId());
            if (offer == null) {
                throw exception(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING);
            }
            targets.add(offer);
            stabilities.add(line.stabilityScore());
            timelinesses.add(line.timelinessScore());
        }
        int updated = 0;
        for (int i = 0; i < targets.size(); i++) {
            updated += writeScores(List.of(targets.get(i)), stabilities.get(i), timelinesses.get(i), operatorId);
        }
        log.info("保存SKU履约评分完成 skuCode={} updatedCount={} operatorId={}",
                skuCode, updated, operatorId);
        return updated;
    }

    private int writeScores(List<SupplierOfferDO> offers, Integer stabilityScore, Integer timelinessScore,
                            Long operatorId) {
        if (offers == null || offers.isEmpty()) {
            return 0;
        }
        LocalDateTime now = databaseClock.now();
        int updated = 0;
        for (SupplierOfferDO offer : offers) {
            SupplierOfferDO locked = offerMapper.selectByNoForUpdate(offer.getOfferNo());
            if (locked == null || !isLive(locked)) {
                throw exception(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING);
            }
            locked.setStabilityScore(stabilityScore);
            locked.setTimelinessScore(timelinessScore);
            locked.setUpdatedBy(operatorId);
            locked.setUpdatedAt(now);
            offerMapper.updateById(locked);
            updated++;
        }
        return updated;
    }

    private PlatformSkuDO requireSku(String skuCode) {
        if (skuCode == null || skuCode.isBlank()) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        if (sku == null) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        return sku;
    }

    private static void requireScorePair(Integer stabilityScore, Integer timelinessScore) {
        if (!isScore(stabilityScore) || !isScore(timelinessScore)) {
            throw exception(FULFILLMENT_SCORE_INVALID);
        }
    }

    private static boolean isScore(Integer value) {
        return OfferFulfillmentScore.isValid(value);
    }

    private static boolean isLive(SupplierOfferDO offer) {
        if (offer.getStatus() == null) {
            return false;
        }
        OfferStatus status = OfferStatus.fromCode(offer.getStatus());
        return status == OfferStatus.PUBLISHED || status == OfferStatus.SUSPENDED;
    }

    public record ScoreUpdateResult(
            @Schema(description = "本次更新的未退役 Offer 行数") int updatedCount) {
    }

    public record SkuSupplierScoreLine(String supplierCode, Integer stabilityScore, Integer timelinessScore) {
    }

    public record SkuFulfillmentScoreView(
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "平台SKU标准名称") String skuName,
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "当前合作供应商履约能力评分") List<SkuScoreItem> items) {
    }

    public record SkuScoreItem(
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName,
            @Schema(description = "供应商报价稳定业务编号") String offerNo,
            @Schema(description = OfferFulfillmentScore.STABILITY_COMMENT) Integer stabilityScore,
            @Schema(description = OfferFulfillmentScore.TIMELINESS_COMMENT) Integer timelinessScore) {
    }
}
