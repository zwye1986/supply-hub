package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        Map<String, Long> productCounts = productCounts(page.getList().stream()
                .map(ProductTypeDO::getTypeCode).toList());
        List<ProductTypeView> views = page.getList().stream()
                .map(type -> toView(type, productCounts.getOrDefault(type.getTypeCode(), 0L)))
                .toList();
        return new PageResult<>(views, page.getTotal());
    }

    public ProductTypeView detail(String typeCode) {
        ProductTypeDO type = requireLive(typeCode, false);
        return toView(type, productCounts(List.of(type.getTypeCode())).getOrDefault(type.getTypeCode(), 0L));
    }

    /**
     * 批量统计各商品类型的未归档 SPU 数量。整页一次查询后在内存聚合，
     * 避免逐行 {@code selectCount} 造成的 N+1。
     */
    private Map<String, Long> productCounts(List<String> typeCodes) {
        if (typeCodes.isEmpty()) {
            return Map.of();
        }
        return spuMapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                        .select(PlatformSpuDO::getProductType)
                        .in(PlatformSpuDO::getProductType, typeCodes)
                        .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())).stream()
                .map(PlatformSpuDO::getProductType).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(typeCode -> typeCode, Collectors.counting()));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateCommand command) {
        String code = hasText(command.typeCode())
                ? command.typeCode().trim().toUpperCase(Locale.ROOT)
                : businessNoGenerator.next(BusinessNoPrefixes.PT);
        LocalDateTime now = databaseClock.now();
        ProductTypeDO type = ProductTypeDO.builder()
                .typeCode(code)
                .typeName(command.typeName().trim())
                .sort(command.sort() == null ? 0 : command.sort())
                .status(CatalogEntityStatus.ACTIVE.getCode())
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

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(String typeCode, UpdateCommand command) {
        ProductTypeDO type = requireLive(typeCode, true);
        type.setTypeName(command.typeName().trim());
        type.setSort(command.sort() == null ? type.getSort() : command.sort());
        type.setRemark(command.remark());
        touch(type, command.operatorId());
        try {
            requireUpdated(typeMapper.updateById(type));
        } catch (DuplicateKeyException duplicate) {
            throw exception(PRODUCT_TYPE_DUPLICATE);
        }
        log.info("更新商品类型 typeCode={} operatorId={}", typeCode, command.operatorId());
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

    private ProductTypeView toView(ProductTypeDO type, long productCount) {
        return new ProductTypeView(type.getTypeCode(), type.getTypeName(), type.getSort(), type.getStatus(),
                type.getRemark(), productCount, type.getCreatedAt(), type.getUpdatedAt());
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

    public record CreateCommand(String typeCode, String typeName, Integer sort, String remark, Long operatorId) {
    }

    public record UpdateCommand(String typeName, Integer sort, String remark, Long operatorId) {
    }

    public record ProductTypeView(String typeCode, String typeName, Integer sort, Short status, String remark,
                                  long productCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
