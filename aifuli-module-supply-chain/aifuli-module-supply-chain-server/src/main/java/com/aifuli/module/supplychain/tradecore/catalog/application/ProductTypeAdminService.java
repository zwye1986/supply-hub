package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_NOT_FOUND;

/**
 * 商品类型管理应用服务。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#44-商品类型管理prd-87producttypeadmincontroller}。
 *
 * <p>不变量：类型编码与名称在未归档范围内唯一；停用只阻止新增或启用引用该类型的 SPU，历史 SPU 保持可读；
 * 仍被未归档 SPU 引用的类型禁止删除。状态端点只接受启用/停用，归档必须走删除路径以触发引用强校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTypeAdminService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final ProductTypeMapper typeMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuMapper skuMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;

    public PageResult<ProductTypeView> page(String keyword, Short status, Integer pageNo, Integer pageSize) {
        PageParam param = new PageParam();
        param.setPageNo(pageNo == null ? 1 : pageNo);
        param.setPageSize(pageSize == null ? 20 : pageSize);
        LambdaQueryWrapper<ProductTypeDO> wrapper = new LambdaQueryWrapper<ProductTypeDO>()
                .ne(ProductTypeDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(status != null, ProductTypeDO::getStatus, status)
                .and(hasText(keyword), w -> w.like(ProductTypeDO::getTypeCode, keyword)
                        .or().like(ProductTypeDO::getTypeName, keyword))
                .orderByAsc(ProductTypeDO::getSort)
                .orderByAsc(ProductTypeDO::getId);
        PageResult<ProductTypeDO> page = typeMapper.selectPage(param, wrapper);
        Map<String, TypeCounts> counts = typeCounts(page.getList().stream()
                .map(ProductTypeDO::getTypeCode).toList());
        List<ProductTypeView> views = page.getList().stream()
                .map(type -> toView(type, counts.getOrDefault(type.getTypeCode(), TypeCounts.ZERO)))
                .toList();
        return new PageResult<>(views, page.getTotal());
    }

    public ProductTypeView detail(String typeCode) {
        ProductTypeDO type = requireLive(typeCode, false);
        TypeCounts counts = typeCounts(List.of(type.getTypeCode()))
                .getOrDefault(type.getTypeCode(), TypeCounts.ZERO);
        return toView(type, counts);
    }

    private record TypeCounts(long spuCount, long skuCount) {
        private static final TypeCounts ZERO = new TypeCounts(0L, 0L);
    }

    /**
     * 批量统计各商品类型的未归档 SPU 与关联未归档 SKU 数量。
     * 调用数据库 GROUP BY 聚合查询，避免在内存中加载整页类型关联的全部 SPU/SKU 对象。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#28-商品管理列表端模型增强与类型优化030-changeset}。
     */
    private Map<String, TypeCounts> typeCounts(List<String> typeCodes) {
        if (typeCodes == null || typeCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, TypeCounts> result = new HashMap<>();
        for (String typeCode : typeCodes) {
            result.put(typeCode, TypeCounts.ZERO);
        }
        List<com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeCountRow> rows =
                spuMapper.countProductAndSkuByTypeCodes(typeCodes);
        if (rows != null) {
            for (var row : rows) {
                if (row.productType() != null) {
                    result.put(row.productType(), new TypeCounts(row.spuCount(), row.skuCount()));
                }
            }
        }
        return result;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateCommand command) {
        String code = hasText(command.typeCode())
                ? command.typeCode().trim().toUpperCase(Locale.ROOT)
                : businessNoGenerator.next(BusinessNoPrefixes.PT);
        LocalDateTime now = databaseClock.now();
        Short status = command.status() == null ? CatalogEntityStatus.ACTIVE.getCode() : command.status();
        if (status != CatalogEntityStatus.ACTIVE.getCode() && status != CatalogEntityStatus.INACTIVE.getCode()) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        String description = hasText(command.description()) ? command.description().trim()
                : (hasText(command.remark()) ? command.remark().trim() : null);
        ProductTypeDO type = ProductTypeDO.builder()
                .typeCode(code)
                .typeName(command.typeName().trim())
                .iconUrl(command.iconUrl() != null ? command.iconUrl().trim() : null)
                .sort(command.sort() == null ? 0 : command.sort())
                .status(status)
                .description(description)
                .remark(command.remark())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        try {
            typeMapper.insert(type);
        } catch (DuplicateKeyException duplicate) {
            throw exception(PRODUCT_TYPE_DUPLICATE);
        }
        log.info("创建商品类型 typeCode={} operatorId={}", code, command.operatorId());
        return code;
    }

    /**
     * 更新商品类型基础信息及可选状态。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#44-商品类型管理prd-87producttypeadmincontroller}。
     *
     * <p>当 {@code command.status()} 非空时联动校验并更新启用/停用状态；归档状态必须调用 {@link #delete(String, Long)}。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(String typeCode, UpdateCommand command) {
        ProductTypeDO type = requireLive(typeCode, true);
        type.setTypeName(command.typeName().trim());
        type.setIconUrl(command.iconUrl() != null ? command.iconUrl().trim() : null);
        type.setSort(command.sort() == null ? type.getSort() : command.sort());
        if (command.status() != null) {
            if (command.status() != CatalogEntityStatus.ACTIVE.getCode()
                    && command.status() != CatalogEntityStatus.INACTIVE.getCode()) {
                throw exception(PRODUCT_STATUS_INVALID);
            }
            type.setStatus(command.status());
        }
        if (command.description() != null) {
            type.setDescription(command.description().trim());
        }
        type.setRemark(command.remark());
        touch(type, command.operatorId());
        try {
            requireUpdated(typeMapper.updateById(type));
        } catch (DuplicateKeyException duplicate) {
            throw exception(PRODUCT_TYPE_DUPLICATE);
        }
        log.info("更新商品类型 typeCode={} status={} operatorId={}", typeCode, type.getStatus(), command.operatorId());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String typeCode, CatalogEntityStatus target, Long operatorId) {
        if (target != CatalogEntityStatus.ACTIVE && target != CatalogEntityStatus.INACTIVE) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        ProductTypeDO type = requireLive(typeCode, true);
        if (type.getStatus() == target.getCode()) {
            return;
        }
        type.setStatus(target.getCode());
        touch(type, operatorId);
        requireUpdated(typeMapper.updateById(type));
        log.info("变更商品类型状态 typeCode={} targetStatus={} operatorId={}", typeCode, target, operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void delete(String typeCode, Long operatorId) {
        ProductTypeDO type = requireLive(typeCode, true);
        Long count = spuMapper.selectCount(new LambdaQueryWrapper<PlatformSpuDO>()
                .eq(PlatformSpuDO::getProductType, type.getTypeCode())
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        if (count != null && count > 0) {
            throw exception(PRODUCT_TYPE_HAS_PRODUCTS);
        }
        type.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        touch(type, operatorId);
        requireUpdated(typeMapper.updateById(type));
        log.info("删除商品类型 typeCode={} operatorId={}", typeCode, operatorId);
    }

    private ProductTypeDO requireLive(String typeCode, boolean forUpdate) {
        ProductTypeDO type = forUpdate ? typeMapper.selectByCodeForUpdate(typeCode) : typeMapper.selectByCode(typeCode);
        if (type == null || type.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PRODUCT_TYPE_NOT_FOUND);
        }
        return type;
    }

    private ProductTypeView toView(ProductTypeDO type, TypeCounts counts) {
        return new ProductTypeView(
                type.getTypeCode(),
                type.getTypeName(),
                type.getIconUrl(),
                type.getSort(),
                type.getStatus(),
                type.getDescription(),
                type.getRemark(),
                counts.spuCount(),
                counts.spuCount(),
                counts.skuCount(),
                type.getCreatedAt(),
                type.getUpdatedAt());
    }

    private void touch(ProductTypeDO type, Long operatorId) {
        type.setUpdatedBy(operatorId);
        type.setUpdatedAt(databaseClock.now());
    }

    private static void requireUpdated(int count) {
        if (count != 1) {
            throw exception(PRODUCT_CONCURRENT_UPDATE);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record CreateCommand(String typeCode, String typeName, String iconUrl, Integer sort,
                                Short status, String description, String remark, Long operatorId) {
    }

    public record UpdateCommand(String typeName, String iconUrl, Integer sort,
                                Short status, String description, String remark, Long operatorId) {
    }

    /** 商品类型管理端投影；字段语义与 {@code sc_product_type} DDL COMMENT 一致。 */
    public record ProductTypeView(
            @Schema(description = "商品类型业务编码") String typeCode,
            @Schema(description = "商品类型名称") String typeName,
            @Schema(description = "商品类型图标地址") String iconUrl,
            @Schema(description = "展示排序值，越小越靠前") Integer sort,
            @Schema(description = "管理状态：10启用，20禁用，90已归档；字典 supply_chain_product_type_status") Short status,
            @Schema(description = "商品类型详细描述") String description,
            @Schema(description = "类型内部备注与说明") String remark,
            @Schema(description = "引用该类型的未归档SPU数量（兼容旧接口，等同于spuCount，新接入方请使用spuCount）") long productCount,
            @Schema(description = "引用该类型的未归档SPU数量") long spuCount,
            @Schema(description = "引用该类型的未归档SKU数量") long skuCount,
            @Schema(description = "类型创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "类型最后更新时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }
}
