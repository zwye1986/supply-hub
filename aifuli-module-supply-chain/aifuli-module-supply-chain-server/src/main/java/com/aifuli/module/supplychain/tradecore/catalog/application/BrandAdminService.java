package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NAME_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;

/**
 * 商品品牌管理应用服务。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#43-商品品牌管理brandadmincontroller}。
 *
 * <p>不变量：品牌名称在未归档范围内唯一，由应用层预检与 {@code uk_sc_brand_name} 唯一索引双重保证；
 * 仍被未归档 SPU 引用的品牌禁止归档。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandAdminService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BrandMapper brandMapper;
    private final PlatformSpuMapper spuMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;

    public PageResult<BrandView> page(String keyword, Short status, Integer pageNo, Integer pageSize) {
        PageParam param = new PageParam();
        param.setPageNo(pageNo == null ? 1 : pageNo);
        param.setPageSize(pageSize == null ? 20 : pageSize);
        PageResult<BrandDO> page = brandMapper.selectPage(param, new LambdaQueryWrapper<BrandDO>()
                .ne(BrandDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(status != null, BrandDO::getStatus, status)
                .and(hasText(keyword), w -> w.like(BrandDO::getBrandCode, keyword)
                        .or().like(BrandDO::getBrandName, keyword))
                .orderByAsc(BrandDO::getSort).orderByAsc(BrandDO::getId));
        Map<Long, Long> productCounts = productCounts(page.getList().stream().map(BrandDO::getId).toList());
        List<BrandView> views = page.getList().stream()
                .map(brand -> toView(brand, productCounts.getOrDefault(brand.getId(), 0L)))
                .toList();
        return new PageResult<>(views, page.getTotal());
    }

    public BrandView detail(String brandCode) {
        BrandDO brand = requireLive(brandCode, false);
        return toView(brand, productCounts(List.of(brand.getId())).getOrDefault(brand.getId(), 0L));
    }

    /**
     * 批量统计各品牌的未归档 SPU 数量。整页一次查询后在内存聚合，
     * 避免逐行 {@code selectCount} 造成的 N+1。
     */
    private Map<Long, Long> productCounts(List<Long> brandIds) {
        if (brandIds.isEmpty()) {
            return Map.of();
        }
        return spuMapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                        .select(PlatformSpuDO::getBrandId)
                        .in(PlatformSpuDO::getBrandId, brandIds)
                        .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())).stream()
                .map(PlatformSpuDO::getBrandId).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(brandId -> brandId, Collectors.counting()));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateCommand command) {
        String code = hasText(command.brandCode()) ? command.brandCode().trim()
                : businessNoGenerator.next(BusinessNoPrefixes.BRD);
        requireNameAvailable(command.brandName().trim(), null);
        LocalDateTime now = databaseClock.now();
        // TODO(product-center): 品牌默认状态 DRAFT，商品类型默认状态 ACTIVE，两者不一致——待产品确认是否统一。
        //  设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#43-商品品牌管理brandadmincontroller
        Short status = command.status() == null ? CatalogEntityStatus.DRAFT.getCode() : command.status();
        if (status != CatalogEntityStatus.DRAFT.getCode()
                && status != CatalogEntityStatus.ACTIVE.getCode()
                && status != CatalogEntityStatus.INACTIVE.getCode()) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        BrandDO brand = BrandDO.builder().brandCode(code).brandName(command.brandName().trim())
                .shortName(hasText(command.shortName()) ? command.shortName().trim() : null)
                .englishName(hasText(command.englishName()) ? command.englishName().trim() : null)
                .logoUrl(command.logoUrl())
                .description(hasText(command.description()) ? command.description().trim() : null)
                .status(status)
                .sort(command.sort() == null ? 0 : command.sort()).version(0L)
                .createdBy(command.operatorId()).createdAt(now).updatedBy(command.operatorId()).updatedAt(now).build();
        try {
            brandMapper.insert(brand);
        } catch (DuplicateKeyException duplicate) {
            throw exception(BRAND_NAME_DUPLICATE);
        }
        log.info("创建商品品牌 brandCode={} operatorId={}", code, command.operatorId());
        return code;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(String brandCode, UpdateCommand command) {
        log.info("更新商品品牌 brandCode={} operatorId={}", brandCode, command.operatorId());
        BrandDO brand = requireLive(brandCode, true);
        requireNameAvailable(command.brandName().trim(), brand.getId());
        brand.setBrandName(command.brandName().trim());
        brand.setShortName(hasText(command.shortName()) ? command.shortName().trim() : null);
        brand.setEnglishName(hasText(command.englishName()) ? command.englishName().trim() : null);
        brand.setLogoUrl(command.logoUrl());
        brand.setDescription(hasText(command.description()) ? command.description().trim() : null);
        brand.setSort(command.sort() == null ? brand.getSort() : command.sort());
        touch(brand, command.operatorId());
        try {
            requireUpdated(brandMapper.updateById(brand));
        } catch (DuplicateKeyException duplicate) {
            throw exception(BRAND_NAME_DUPLICATE);
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String brandCode, CatalogEntityStatus target, Long operatorId) {
        log.info("变更商品品牌状态 brandCode={} targetStatus={} operatorId={}", brandCode, target, operatorId);
        BrandDO brand = requireLive(brandCode, true);
        transition(brand.getStatus(), target);
        if (target == CatalogEntityStatus.ARCHIVED) {
            validateNotReferenced(brand.getId());
        }
        brand.setStatus(target.getCode());
        touch(brand, operatorId);
        requireUpdated(brandMapper.updateById(brand));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void delete(String brandCode, Long operatorId) {
        log.info("删除商品品牌 brandCode={} operatorId={}", brandCode, operatorId);
        BrandDO brand = requireLive(brandCode, true);
        validateNotReferenced(brand.getId());
        brand.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        touch(brand, operatorId);
        requireUpdated(brandMapper.updateById(brand));
    }

    /**
     * 品牌名称防重的应用层预检。{@code uk_sc_brand_name} 唯一索引只作为并发写入的最终防线，
     * 正常路径必须先在应用层给出明确业务错误码。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#24-平台品牌增加名称唯一索引-sc_brand}。
     *
     * @param excludedId 更新场景下需要排除的自身主键；创建场景传 {@code null}
     */
    private void requireNameAvailable(String brandName, Long excludedId) {
        Long count = brandMapper.selectCount(new LambdaQueryWrapper<BrandDO>()
                .eq(BrandDO::getBrandName, brandName)
                .ne(BrandDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .ne(excludedId != null, BrandDO::getId, excludedId));
        if (count != null && count > 0) {
            throw exception(BRAND_NAME_DUPLICATE);
        }
    }

    private void validateNotReferenced(Long brandId) {
        Long count = spuMapper.selectCount(new LambdaQueryWrapper<PlatformSpuDO>()
                .eq(PlatformSpuDO::getBrandId, brandId)
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        if (count != null && count > 0) {
            throw exception(BRAND_HAS_PRODUCTS);
        }
    }

    private BrandDO requireLive(String code, boolean forUpdate) {
        BrandDO brand = forUpdate ? brandMapper.selectByCodeForUpdate(code) : brandMapper.selectByCode(code);
        if (brand == null || brand.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(BRAND_NOT_FOUND);
        }
        return brand;
    }

    private BrandView toView(BrandDO brand, long productCount) {
        return new BrandView(brand.getBrandCode(), brand.getBrandName(), brand.getShortName(),
                brand.getEnglishName(), brand.getLogoUrl(), brand.getDescription(),
                brand.getSort(), brand.getStatus(), productCount, productCount,
                brand.getCreatedAt(), brand.getUpdatedAt());
    }

    private void touch(BrandDO brand, Long operatorId) {
        brand.setUpdatedBy(operatorId);
        brand.setUpdatedAt(databaseClock.now());
    }

    private static void transition(short current, CatalogEntityStatus target) {
        if (!CatalogEntityStatus.fromCode(current).canTransitionTo(target)) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw exception(PRODUCT_CONCURRENT_UPDATE);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record CreateCommand(String brandCode, String brandName, String shortName, String englishName,
                                String logoUrl, String description, Integer sort, Short status, Long operatorId) {
    }

    public record UpdateCommand(String brandName, String shortName, String englishName, String logoUrl,
                                String description, Integer sort, Long operatorId) {
    }

    /** 商品品牌管理端投影；字段语义与 {@code sc_brand} DDL COMMENT 一致。 */
    public record BrandView(
            @Schema(description = "平台品牌永久业务编码") String brandCode,
            @Schema(description = "品牌中文或标准名称") String brandName,
            @Schema(description = "品牌简称") String shortName,
            @Schema(description = "品牌英文名称") String englishName,
            @Schema(description = "品牌Logo地址") String logoUrl,
            @Schema(description = "品牌介绍与详细说明") String description,
            @Schema(description = "品牌展示排序值") Integer sort,
            @Schema(description = "品牌管理状态：0草稿，10启用，20停用，90已归档") Short status,
            @Schema(description = "引用该品牌的未归档SPU数量（兼容旧接口，等同于spuCount，新接入方请使用spuCount）") long productCount,
            @Schema(description = "引用该品牌的未归档SPU数量") long spuCount,
            @Schema(description = "品牌创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "品牌最后更新时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }
}
