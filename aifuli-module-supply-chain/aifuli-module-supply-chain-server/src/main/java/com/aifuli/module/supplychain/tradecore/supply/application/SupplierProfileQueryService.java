package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.ip.core.Area;
import com.aifuli.framework.ip.core.utils.AreaUtils;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierContactDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDeliveryRegionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierFileRefDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierIntegrationModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementAccountDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierContactMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierDeliveryRegionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierFileRefMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierIntegrationModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementAccountMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 供应商管理端查询。列表支持多值筛选、聚合列与 {@code view=simple}。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#51-列表}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierProfileQueryService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SupplierMapper supplierMapper;
    private final SupplierContactMapper contactMapper;
    private final SupplierDeliveryRegionMapper regionMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierSupplyScopeModeMapper modeMapper;
    private final SupplierOfferMapper offerMapper;
    private final PlatformSkuMapper platformSkuMapper;
    private final SupplierSettlementAccountMapper settlementAccountMapper;
    private final SupplierFileRefMapper fileRefMapper;
    private final SupplierIntegrationModeMapper integrationModeMapper;
    private final EnvelopeCryptoPort envelopeCrypto;

    /**
     * 分页查询供应商。按更新时间倒序；归档行永不出现。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#51-列表}。
     */
    public PageResult<SupplierListItem> page(SupplierPageQuery query) {
        log.info("分页查询供应商 supplierName={} supplierCode={} statuses={} sellableCategories={} supplyModes={} "
                        + "updatedFrom={} updatedTo={} pageNo={} pageSize={}",
                query.supplierName(), query.supplierCode(), query.statuses(), query.sellableCategories(),
                query.supplyModes(), query.updatedFrom(), query.updatedTo(), query.pageNo(), query.pageSize());
        List<Short> statusCodes = toStatusCodes(query.statuses());
        LambdaQueryWrapper<SupplierDO> wrapper = new LambdaQueryWrapper<SupplierDO>()
                .like(hasText(query.supplierName()), SupplierDO::getSupplierName, query.supplierName())
                .eq(hasText(query.supplierCode()), SupplierDO::getSupplierCode, query.supplierCode())
                .in(!statusCodes.isEmpty(), SupplierDO::getStatus, statusCodes)
                .ge(query.updatedFrom() != null, SupplierDO::getUpdatedAt, query.updatedFrom())
                .le(query.updatedTo() != null, SupplierDO::getUpdatedAt, query.updatedTo())
                .ne(SupplierDO::getStatus, SupplierStatus.ARCHIVED.getCode())
                .orderByDesc(SupplierDO::getUpdatedAt);
        if (hasValues(query.sellableCategories()) || hasValues(query.supplyModes())) {
            Set<Long> supplierIds = matchingScopeSupplierIds(query.sellableCategories(), query.supplyModes());
            if (supplierIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(SupplierDO::getId, supplierIds);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize());
        PageResult<SupplierDO> page = supplierMapper.selectPage(pageParam, wrapper);
        List<SupplierListItem> items = new ArrayList<>();
        for (SupplierDO supplier : page.getList()) {
            items.add(toListItem(supplier));
        }
        return new PageResult<>(items, page.getTotal());
    }

    /**
     * 名称下拉：只回编码与名称，不强制分页。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#51-列表}。
     */
    public List<SimpleSupplierItem> listSimple(String supplierName, String supplierCode) {
        log.info("简易供应商列表 supplierName={} supplierCode={}", supplierName, supplierCode);
        List<SupplierDO> suppliers = supplierMapper.selectList(new LambdaQueryWrapper<SupplierDO>()
                .like(hasText(supplierName), SupplierDO::getSupplierName, supplierName)
                .eq(hasText(supplierCode), SupplierDO::getSupplierCode, supplierCode)
                .ne(SupplierDO::getStatus, SupplierStatus.ARCHIVED.getCode())
                .orderByDesc(SupplierDO::getUpdatedAt));
        List<SimpleSupplierItem> items = new ArrayList<>();
        for (SupplierDO supplier : suppliers) {
            items.add(new SimpleSupplierItem(supplier.getSupplierCode(), supplier.getSupplierName()));
        }
        return items;
    }

    /**
     * 供货配置列表：视图含类目名与 {@code supplyModes[]}，不暴露 DO。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#54-供货配置}。
     */
    public List<SupplyScopeView> listSupplyScopes(String supplierCode) {
        log.info("查询供货配置 supplierCode={}", supplierCode);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return toScopeViews(scopeMapper.selectBySupplierId(supplier.getId()));
    }

    public SupplierDetail get(String supplierCode) {
        log.info("查询供应商详情 supplierCode={}", supplierCode);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        List<SupplierContactDO> contacts = contactMapper.selectBySupplierId(supplier.getId());
        List<String> adcodes = regionMapper.selectBySupplierId(supplier.getId()).stream()
                .map(SupplierDeliveryRegionDO::getAdcode)
                .toList();
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        List<SupplierOfferDO> liveOffers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        long spuCount = countLiveSpus(liveOffers);
        SupplierSettlementAccountDO account = settlementAccountMapper.selectBySupplierId(supplier.getId());
        SupplierContactDO primary = contacts.isEmpty() ? null : contacts.get(0);
        String keyVersion = firstVersion(supplier);
        List<SupplierFileRefDO> fileRefs = fileRefMapper.selectBySupplierId(supplier.getId());
        List<String> licenseFileKeys = decryptFileKeys(supplier, fileRefs, SupplierFileRefDO.FILE_ROLE_LICENSE,
                supplier.getLicenseFileCipher(), "license");
        List<String> qualificationFileKeys = decryptFileKeys(supplier, fileRefs,
                SupplierFileRefDO.FILE_ROLE_QUALIFICATION, supplier.getQualificationFileCipher(), "qualification");
        List<String> integrationModes = integrationModeMapper.selectBySupplierId(supplier.getId()).stream()
                .map(SupplierIntegrationModeDO::getIntegrationMode)
                .toList();
        return new SupplierDetail(
                supplier.getId(),
                supplier.getSupplierCode(),
                supplier.getSupplierName(),
                SupplierStatus.fromCode(supplier.getStatus()).name(),
                supplier.getDeliveryRegionType(),
                adcodes,
                supplier.getAftersalesSupported() != null && supplier.getAftersalesSupported() == 1,
                supplier.getShippingSlaCode(),
                supplier.getShippingSlaCustom(),
                supplier.getSettlementMethod(),
                supplier.getSettlementCycleDays(),
                echoOnboardedAt(supplier),
                supplier.getCooperationNote(),
                supplier.getLegalEntityName(),
                supplier.getCreditCode(),
                supplier.getLegalPersonName(),
                supplier.getLegalPersonIdNo(),
                decrypt(supplier.getLegalPersonMobileCipher(), supplier.getId(), "legalMobile", keyVersion),
                supplier.getContactAddress(),
                supplier.getBankName(),
                decrypt(supplier.getBankAccountCipher(), supplier.getId(), "bankAccount", keyVersion),
                licenseFileKeys,
                qualificationFileKeys,
                decrypt(supplier.getIdFrontCipher(), supplier.getId(), "idFront", keyVersion),
                decrypt(supplier.getIdBackCipher(), supplier.getId(), "idBack", keyVersion),
                integrationModes,
                contacts.stream().map(c -> new SupplierProfileCommandService.ContactLine(
                        c.getContactName(), c.getContactMobile(), c.getContactEmail())).toList(),
                toScopeViews(scopes),
                spuCount,
                scopes.size(),
                primary == null ? null : primary.getContactName(),
                primary == null ? null : primary.getContactMobile(),
                account == null ? 0L : account.getBalance());
    }

    /**
     * 列表「合作商品 SKU 数」与合作商品页「共 N 个」均为未退役 Offer 行数。
     * 详情顶栏使用 {@link #countLiveSpus(List)} 的 SPU 去重口径。
     * 设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#156-详情--供货配置}。
     */
    static long countLiveSkus(List<SupplierOfferDO> liveOffers) {
        return liveOffers == null ? 0L : liveOffers.size();
    }

    /**
     * 详情顶栏“合作商品数（SPU）”按未退役 Offer 关联的 {@code spu_id} 去重统计。
     * 设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#156-详情--供货配置}。
     */
    private long countLiveSpus(List<SupplierOfferDO> liveOffers) {
        if (liveOffers == null || liveOffers.isEmpty()) {
            return 0L;
        }
        Set<Long> skuIds = new HashSet<>();
        for (SupplierOfferDO offer : liveOffers) {
            if (offer.getPlatformSkuId() != null) {
                skuIds.add(offer.getPlatformSkuId());
            }
        }
        if (skuIds.isEmpty()) {
            return 0L;
        }
        List<PlatformSkuDO> skus = platformSkuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .select(PlatformSkuDO::getSpuId)
                .in(PlatformSkuDO::getId, skuIds));
        return countDistinctSpus(skus);
    }

    static long countDistinctSpus(List<PlatformSkuDO> skus) {
        if (skus == null || skus.isEmpty()) {
            return 0L;
        }
        Set<Long> spuIds = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            if (sku.getSpuId() != null) {
                spuIds.add(sku.getSpuId());
            }
        }
        return spuIds.size();
    }

    /**
     * 将供应商保存的国标行政区划编码转换为带名称的详情项。未知编码仍保留，名称为空，
     * 避免历史数据异常导致整个详情接口失败。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    static List<DeliveryRegionView> toDeliveryRegionViews(List<String> adcodes) {
        if (adcodes == null || adcodes.isEmpty()) {
            return List.of();
        }
        List<DeliveryRegionView> views = new ArrayList<>(adcodes.size());
        for (String adcode : adcodes) {
            Area area = hasText(adcode) && adcode.length() == 6
                    && adcode.chars().allMatch(Character::isDigit)
                    ? AreaUtils.getArea(Integer.valueOf(adcode)) : null;
            // AreaUtils 同时包含短位数国家 ID；必须保持十进制字符串完全一致，避免 000001 被误认成中国。
            if (area != null && !adcode.equals(String.valueOf(area.getId()))) {
                area = null;
            }
            views.add(new DeliveryRegionView(adcode, area == null ? null : area.getName()));
        }
        return List.copyOf(views);
    }

    /**
     * 按可售品类和/或供货模式过滤供应商。模式过滤只读子表。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}。
     */
    private Set<Long> matchingScopeSupplierIds(List<String> sellableCategories, List<String> supplyModes) {
        LambdaQueryWrapper<SupplierSupplyScopeDO> wrapper = new LambdaQueryWrapper<SupplierSupplyScopeDO>()
                .in(hasValues(sellableCategories), SupplierSupplyScopeDO::getSellableCategory, sellableCategories);
        if (hasValues(supplyModes)) {
            List<SupplierSupplyScopeModeDO> modeRows = modeMapper.selectList(
                    new LambdaQueryWrapper<SupplierSupplyScopeModeDO>()
                            .in(SupplierSupplyScopeModeDO::getSupplyMode, supplyModes));
            if (modeRows.isEmpty()) {
                return Set.of();
            }
            Set<Long> scopeIds = new HashSet<>();
            for (SupplierSupplyScopeModeDO row : modeRows) {
                scopeIds.add(row.getSupplyScopeId());
            }
            wrapper.in(SupplierSupplyScopeDO::getId, scopeIds);
        }
        Set<Long> ids = new HashSet<>();
        for (SupplierSupplyScopeDO scope : scopeMapper.selectList(wrapper)) {
            ids.add(scope.getSupplierId());
        }
        return ids;
    }

    private SupplierListItem toListItem(SupplierDO supplier) {
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        List<SupplyScopeView> scopeViews = toScopeViews(scopes);
        long skuCount = offerMapper.selectCount(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        List<SupplierContactDO> contacts = contactMapper.selectBySupplierId(supplier.getId());
        SupplierContactDO primary = contacts.isEmpty() ? null : contacts.get(0);
        return new SupplierListItem(
                supplier.getId(),
                supplier.getSupplierCode(),
                supplier.getSupplierName(),
                uniqueCodes(scopeViews, SupplyScopeView::sellableCategory),
                uniqueCodes(scopeViews, SupplyScopeView::productType),
                uniqueCodes(scopeViews, SupplyScopeView::productClassification),
                uniqueSupplyModes(scopeViews),
                skuCount,
                SupplierStatus.fromCode(supplier.getStatus()).name(),
                echoOnboardedAt(supplier),
                supplier.getUpdatedAt(),
                primary == null ? null : primary.getContactName(),
                primary == null ? null : primary.getContactMobile());
    }

    private static List<String> uniqueCodes(List<SupplyScopeView> scopes,
                                             java.util.function.Function<SupplyScopeView, String> codeFn) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SupplyScopeView scope : scopes) {
            String code = codeFn.apply(scope);
            if (code != null && !code.isBlank()) {
                unique.add(code);
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> uniqueSupplyModes(List<SupplyScopeView> scopes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SupplyScopeView scope : scopes) {
            if (scope.supplyModes() == null) {
                continue;
            }
            for (String mode : scope.supplyModes()) {
                if (mode != null && !mode.isBlank()) {
                    unique.add(mode);
                }
            }
        }
        return List.copyOf(unique);
    }

    private List<SupplyScopeView> toScopeViews(List<SupplierSupplyScopeDO> scopes) {
        List<Long> scopeIds = scopes.stream().map(SupplierSupplyScopeDO::getId).toList();
        Map<Long, List<String>> modesByScopeId = new LinkedHashMap<>();
        for (SupplierSupplyScopeModeDO mode : modeMapper.selectByScopeIds(scopeIds)) {
            modesByScopeId.computeIfAbsent(mode.getSupplyScopeId(), ignored -> new ArrayList<>())
                    .add(mode.getSupplyMode());
        }
        List<SupplyScopeView> views = new ArrayList<>();
        for (SupplierSupplyScopeDO scope : scopes) {
            views.add(toScopeView(scope, modesByScopeId.getOrDefault(scope.getId(), List.of())));
        }
        return views;
    }

    private SupplyScopeView toScopeView(SupplierSupplyScopeDO scope, List<String> supplyModes) {
        return new SupplyScopeView(
                scope.getId(),
                scope.getSellableCategory(),
                scope.getProductType(),
                scope.getProductClassification(),
                supplyModes,
                scope.getMaxQuantity(),
                scope.getMinQuantity());
    }

    private static LocalDateTime echoOnboardedAt(SupplierDO supplier) {
        LocalDateTime onboardedAt = SupplierProfileCommandService.normalizeOnboardedAt(supplier.getOnboardedAt());
        return onboardedAt != null ? onboardedAt : supplier.getCreatedAt();
    }

    private static String firstVersion(SupplierDO supplier) {
        if (supplier.getBankAccountKeyVersion() != null) {
            return supplier.getBankAccountKeyVersion();
        }
        return supplier.getDocumentKeyVersion();
    }

    private String decrypt(byte[] cipher, Long supplierId, String field, String keyVersion) {
        if (cipher == null || cipher.length == 0 || keyVersion == null) {
            return null;
        }
        byte[] aad = ("supplier:" + supplierId + ":" + field).getBytes(StandardCharsets.UTF_8);
        return new String(envelopeCrypto.decrypt(cipher, keyVersion, aad), StandardCharsets.UTF_8);
    }

    /**
     * 证件优先读 file_ref；无行时回退旧单列密文。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    private List<String> decryptFileKeys(SupplierDO supplier, List<SupplierFileRefDO> fileRefs,
                                          String fileRole, byte[] legacyCipher, String legacyField) {
        List<String> keys = new ArrayList<>();
        for (SupplierFileRefDO ref : fileRefs) {
            if (!fileRole.equals(ref.getFileRole())) {
                continue;
            }
            byte[] aad = ("supplier:" + supplier.getId() + ":" + fileRole + ":" + ref.getSortNo())
                    .getBytes(StandardCharsets.UTF_8);
            keys.add(new String(envelopeCrypto.decrypt(ref.getFileCipher(), ref.getKeyVersion(), aad),
                    StandardCharsets.UTF_8));
        }
        if (!keys.isEmpty()) {
            return keys;
        }
        String legacy = decrypt(legacyCipher, supplier.getId(), legacyField, firstVersion(supplier));
        return legacy == null ? List.of() : List.of(legacy);
    }

    static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    static String maskAccount(String account) {
        if (account == null || account.length() < 4) {
            return account == null ? null : "****";
        }
        return "****" + account.substring(account.length() - 4);
    }

    private static List<Short> toStatusCodes(List<SupplierStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<Short> codes = new ArrayList<>();
        for (SupplierStatus status : statuses) {
            if (status != null) {
                codes.add(status.getCode());
            }
        }
        return codes;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    public record SupplierPageQuery(
            String supplierName, String supplierCode,
            List<SupplierStatus> statuses, List<String> sellableCategories, List<String> supplyModes,
            LocalDateTime updatedFrom, LocalDateTime updatedTo,
            Integer pageNo, Integer pageSize) {
    }

    @Schema(description = "供应商简易下拉项")
    public record SimpleSupplierItem(
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName) {
    }

    /**
     * 供应商列表行。聚合列来自该供应商全部供货配置去重；{@code skuCount} 为未退役 Offer 数。
     */
    public record SupplierListItem(
            @Schema(description = "供应商内部主键") Long id,
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName,
            @Schema(description = "可售品类去重") List<String> sellableCategories,
            @Schema(description = "商品类型去重") List<String> productTypes,
            @Schema(description = "商品分类去重") List<String> productClassifications,
            @Schema(description = "供货模式去重") List<String> supplyModes,
            @Schema(description = "未退役合作商品数") long skuCount,
            @Schema(description = "合作状态：ACTIVE / SUSPENDED / BLACKLIST") String status,
            @Schema(description = "准入时间，Asia/Shanghai") LocalDateTime onboardedAt,
            @Schema(description = "最后修改时间，Asia/Shanghai") LocalDateTime updatedAt,
            String primaryContactName, String primaryContactMobile) {

        @JsonProperty("name")
        public String name() {
            return supplierName;
        }
    }

    @Schema(description = "供货配置行")
    public record SupplyScopeView(
            @Schema(description = "供货配置内部主键") Long id,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "商品类型封闭枚举名") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "供货模式枚举名列表") List<String> supplyModes,
            @Schema(description = "单次最大数量") Long maxQuantity,
            @Schema(description = "单次最小数量") Long minQuantity) {
    }

    @Schema(description = "供应商配送区域")
    public record DeliveryRegionView(
            @Schema(description = "国标行政区划代码") String adcode,
            @Schema(description = "行政区划名称；历史未知编码为空") String name) {
    }

    /**
     * 供应商详情。{@code spuCount} 为未退役 Offer 关联的平台 SPU 去重数。
     * 管理端详情按源分支契约回显明文；证件优先 file_ref，空则回退旧单列。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。
     */
    @Schema(description = "供应商详情")
    public record SupplierDetail(
            @Schema(description = "供应商内部主键") Long id,
            @Schema(description = "供应商永久业务编码") String supplierCode,
            @Schema(description = "供应商标准名称") String supplierName,
            @Schema(description = "合作状态：ACTIVE正常、SUSPENDED暂停供货、BLACKLIST黑名单、ARCHIVED归档") String status,
            @Schema(description = "配送区域：NATIONWIDE全国，DESIGNATED指定区域") String deliveryRegionType,
            @Schema(description = "国标行政区划代码") List<String> deliveryRegionAdcodes,
            @Schema(description = "是否支持售后：1是，0否") boolean aftersalesSupported,
            @Schema(description = "发货时效：SAME_DAY、NEXT_DAY、HOURS_48、HOURS_72、CUSTOM") String shippingSlaCode,
            @Schema(description = "发货时效自定义说明，仅 CUSTOM 使用") String shippingSlaCustom,
            @Schema(description = "结算方式：DAILY、WEEKLY、MONTHLY、QUARTERLY、PREPAID") String settlementMethod,
            @Schema(description = "结算周期天数") Integer settlementCycleDays,
            @JsonProperty("onboardAt") @Schema(description = "接入时间，UTC") LocalDateTime onboardedAt,
            @Schema(description = "合作说明") String cooperationNote,
            @Schema(description = "供应商签约法律主体名称") String legalEntityName,
            @Schema(description = "统一社会信用代码") String creditCode,
            @Schema(description = "法人姓名") String legalPersonName,
            @Schema(description = "法人身份证号") String legalPersonIdNo,
            @Schema(description = "法人手机号") String legalPersonMobile,
            @Schema(description = "联系地址") String contactAddress,
            @Schema(description = "开户行") String bankName,
            @Schema(description = "开户账号") String bankAccount,
            @Schema(description = "营业执照文件引用列表，最多5个") List<String> licenseFileKeys,
            @Schema(description = "资质文件引用列表，最多5个") List<String> qualificationFileKeys,
            @Schema(description = "法人身份证正面文件引用") String idFrontFileKey,
            @Schema(description = "法人身份证反面文件引用") String idBackFileKey,
            @Schema(description = "对接方式：API、H5、MANUAL") List<String> integrationModes,
            @Schema(description = "供应商运营联系人") List<SupplierProfileCommandService.ContactLine> contacts,
            @Schema(description = "供应商可供货范围与供货规则") List<SupplyScopeView> scopes,
            @Schema(description = "未退役 Offer 关联的平台 SPU 去重数") long spuCount,
            @Schema(description = "供货配置数量") int scopeCount,
            @Schema(description = "主联系人姓名") String primaryContactName,
            @Schema(description = "主联系人手机号") String primaryContactMobile,
            @Schema(description = "供应商预存可用额度，单位为分，只读") long settlementBalanceCents) {

        @JsonProperty("accountBalanceCents")
        @Schema(description = "供应商当前账本余额，单位为 CNY 分，只读", accessMode = Schema.AccessMode.READ_ONLY)
        public long accountBalanceCents() {
            return settlementBalanceCents;
        }

        @JsonProperty("deliveryRegions")
        @Schema(description = "配送区域编码及对应的行政区划名称", accessMode = Schema.AccessMode.READ_ONLY)
        public List<DeliveryRegionView> deliveryRegions() {
            return toDeliveryRegionViews(deliveryRegionAdcodes);
        }

        @JsonProperty("name")
        @Schema(description = "供应商标准名称（supplierName 的兼容别名）", accessMode = Schema.AccessMode.READ_ONLY)
        public String name() {
            return supplierName;
        }

        public String licenseFileKey() {
            return licenseFileKeys == null || licenseFileKeys.isEmpty() ? null : licenseFileKeys.getFirst();
        }

        public String qualificationFileKey() {
            return qualificationFileKeys == null || qualificationFileKeys.isEmpty()
                    ? null : qualificationFileKeys.getFirst();
        }
    }
}
