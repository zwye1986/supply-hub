package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductClassificationValidationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeValidationService;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplyMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_SCOPE_MODE_INVALID;

/**
 * 供货配置整表替换。供货模式落在子表，一行对应多种模式；三码（可售品类/商品类型/商品分类）互相独立，
 * 不校验父子链，唯一性以供应商内三码组合判重。允许最低起订量为预留字段，仅存储和回显。
 * 商品分类写入与 SPU 相同：封闭枚举名或类目 {@code category_code}，命中仍按字符串全等。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}、
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierSupplyScopeService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final SupplierMapper supplierMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierSupplyScopeModeMapper modeMapper;
    private final ProductTypeValidationService productTypeValidationService;
    private final ProductClassificationValidationService productClassificationValidationService;

    /**
     * 全量替换供应商供货配置。无 FK，必须先按现有 scope id 删除模式子表再删主表。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void replaceAll(String supplierCode, List<ScopeLine> lines, Long operatorId) {
        log.info("保存供货配置 supplierCode={} lineCount={} operatorId={}",
                supplierCode, lines == null ? 0 : lines.size(), operatorId);
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        Set<String> triples = new HashSet<>();
        List<ScopeLine> normalized = lines == null ? List.of() : lines;
        Map<String, String> normalizedProductTypes = new HashMap<>();
        Map<String, String> normalizedClassifications = new HashMap<>();
        int modeCount = 0;
        for (ScopeLine line : normalized) {
            String sellableCategory = ClosedEnums.parseRequired(SellableCategory.class, line.sellableCategory()).name();
            String productType = normalizedProductTypes.computeIfAbsent(line.productType(),
                    code -> productTypeValidationService.requireActiveForUpdate(code).getTypeCode());
            String productClassification = normalizedClassifications.computeIfAbsent(line.productClassification(),
                    code -> productClassificationValidationService.requireWritable(code, true, null));
            String key = sellableCategory + ":" + productType + ":" + productClassification;
            if (!triples.add(key)) {
                throw exception(SUPPLIER_SCOPE_CONFLICT);
            }
            List<String> modes = validatedModeCodes(line.supplyModes());
            modeCount += modes.size();
            if (line.minQuantity() == null || line.maxQuantity() == null
                    || line.minQuantity() < 1 || line.maxQuantity() < 1) {
                throw new IllegalArgumentException("Supply scope quantity is invalid");
            }
        }
        log.info("保存供货配置校验完成 supplierCode={} lineCount={} modeCount={} operatorId={}",
                supplierCode, normalized.size(), modeCount, operatorId);
        LocalDateTime now = databaseClock.now();
        List<Long> existingIds = scopeMapper.selectBySupplierId(supplier.getId()).stream()
                .map(SupplierSupplyScopeDO::getId)
                .toList();
        modeMapper.deleteByScopeIds(existingIds);
        scopeMapper.deleteBySupplierId(supplier.getId());
        try {
            for (ScopeLine line : normalized) {
                List<String> modes = validatedModeCodes(line.supplyModes());
                SupplierSupplyScopeDO scope = SupplierSupplyScopeDO.builder()
                        .supplierId(supplier.getId())
                        .sellableCategory(ClosedEnums.parseRequired(SellableCategory.class, line.sellableCategory()).name())
                        .productType(normalizedProductTypes.get(line.productType()))
                        .productClassification(normalizedClassifications.get(line.productClassification()))
                        .maxQuantity(line.maxQuantity())
                        .minQuantity(line.minQuantity())
                        .version(0L)
                        .createdBy(operatorId)
                        .createdAt(now)
                        .updatedBy(operatorId)
                        .updatedAt(now)
                        .build();
                scopeMapper.insert(scope);
                for (String mode : modes) {
                    modeMapper.insert(SupplierSupplyScopeModeDO.builder()
                            .supplyScopeId(scope.getId())
                            .supplyMode(mode)
                            .createdAt(now)
                            .build());
                }
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
    }

    public List<SupplierSupplyScopeDO> list(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return scopeMapper.selectBySupplierId(supplier.getId());
    }

    /**
     * 空集合或未知模式码一律 {@code SUPPLY_SCOPE_MODE_INVALID}。
     * {@link SupplyMode#fromCode(String)} 的 {@link IllegalArgumentException} 包装后上抛，不吞异常。
     */
    private List<String> validatedModeCodes(List<String> supplyModes) {
        if (supplyModes == null || supplyModes.isEmpty()) {
            throw exception(SUPPLY_SCOPE_MODE_INVALID);
        }
        List<String> codes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : supplyModes) {
            SupplyMode mode;
            try {
                mode = SupplyMode.fromCode(code);
            } catch (IllegalArgumentException ex) {
                ServiceException invalid = exception(SUPPLY_SCOPE_MODE_INVALID);
                invalid.initCause(ex);
                throw invalid;
            }
            if (seen.add(mode.name())) {
                codes.add(mode.name());
            }
        }
        return codes;
    }

    /**
     * 供货配置一行：三码互相独立、不校验父子链，供应商内三码组合唯一；供货模式为多选。
     * {@code minQuantity} 为允许最低起订量预留字段，只做正整数校验，不与 {@code maxQuantity} 比较，
     * 也不参与供货范围命中、合作商品默认值或下单校验。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
     */
    public record ScopeLine(String sellableCategory, String productType, String productClassification,
                            List<String> supplyModes, Long maxQuantity, Long minQuantity) {
    }
}
