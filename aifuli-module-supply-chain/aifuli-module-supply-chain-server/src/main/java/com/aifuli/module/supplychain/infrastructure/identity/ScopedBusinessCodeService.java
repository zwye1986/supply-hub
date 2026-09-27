package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.BusinessCodeClaimDO;
import com.aifuli.module.supplychain.infrastructure.identity.dal.dataobject.BusinessCodeScopeDO;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.BusinessCodeClaimMapper;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.BusinessCodeExistingMapper;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.BusinessCodeScopeMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_CAPACITY_EXHAUSTED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_FORMAT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_OCCUPIED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_PARENT_MISMATCH;

/**
 * 在供应链独立库中按对象类型与直接父对象分配三位业务编号。
 * 同一作用域的行锁覆盖占号、业务创建和事务回滚；成功占用的号码永久保留。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发}。
 */
@Slf4j
@RequiredArgsConstructor
public class ScopedBusinessCodeService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final long ROOT_OWNER_ID = 0L;

    private final BusinessCodeScopeMapper scopeMapper;
    private final BusinessCodeClaimMapper claimMapper;
    private final BusinessCodeExistingMapper existingMapper;
    private final SupplyChainDatabaseClock databaseClock;

    public enum Kind {
        SPU("SPU", false), SKU("SKU", true), CAT_ROOT("CAT", false),
        CAT_CHILD("CAT", true), CAT_NODE(null, false), BRD("BRD", false),
        SPL("SPL", false), BYR("BYR", false), PRJ("PRJ", true);

        private final String prefix;
        private final boolean child;

        Kind(String prefix, boolean child) {
            this.prefix = prefix;
            this.child = child;
        }
    }

    public record Allocation(String code, int number) {
    }

    /** 根对象的可见编号；SPU 和分销商的数字段同时用作其子对象父段。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateRoot(Kind kind, String requested) {
        long startedAt = System.nanoTime();
        if (kind.child || kind.prefix == null) {
            throw new IllegalArgumentException("编码类型不是可见根对象: " + kind);
        }
        Integer preferred;
        try {
            preferred = requested == null || requested.isEmpty()
                    ? null : ScopedBusinessCodeFormat.parseRoot(kind.prefix, requested);
        } catch (IllegalArgumentException invalid) {
            throw exception(BUSINESS_CODE_FORMAT_INVALID, invalid.getMessage());
        }
        int number = claim(kind, ROOT_OWNER_ID, preferred, null, false);
        String code = ScopedBusinessCodeFormat.root(kind.prefix, number);
        log.debug("分配根对象编码 kind={} scope={} number={} manual={} elapsedMicros={}",
                kind, ROOT_OWNER_ID, number, preferred != null, (System.nanoTime() - startedAt) / 1_000);
        return new Allocation(code, number);
    }

    /** 子对象在已持久化父对象的作用域中分配编号，父段由可信父对象内部短序号给出。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateChild(Kind kind, long parentId, int parentNumber, String requested) {
        long startedAt = System.nanoTime();
        if (!kind.child || parentId <= 0) {
            throw new IllegalArgumentException("编码类型或父对象无效: " + kind);
        }
        Integer preferred;
        try {
            preferred = requested == null || requested.isEmpty()
                    ? null : ScopedBusinessCodeFormat.parseChild(kind.prefix, parentNumber, requested);
        } catch (IllegalArgumentException invalid) {
            throw exception(invalid.getMessage().startsWith("父段 ")
                    ? BUSINESS_CODE_PARENT_MISMATCH : BUSINESS_CODE_FORMAT_INVALID, invalid.getMessage());
        }
        int number = claim(kind, parentId, preferred, parentNumber, false);
        String code = ScopedBusinessCodeFormat.child(kind.prefix, parentNumber, number);
        log.debug("分配子对象编码 kind={} parentId={} parentNumber={} number={} manual={} elapsedMicros={}",
                kind, parentId, parentNumber, number, preferred != null,
                (System.nanoTime() - startedAt) / 1_000);
        return new Allocation(code, number);
    }

    /** 分类节点的独立全局短序号，不作为永久编码直接对外使用。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int allocateCategoryNodeNo() {
        return claim(Kind.CAT_NODE, ROOT_OWNER_ID, null, null, false);
    }

    /** 启动补号专用：存量对象的可见编码已存在，给它占同一个父段号码池。 */
    int claimLegacyParent(Kind kind, Integer preferred) {
        if (kind != Kind.SPU && kind != Kind.BYR) {
            throw new IllegalArgumentException("只有 SPU 和分销商可补父段: " + kind);
        }
        return claim(kind, ROOT_OWNER_ID, preferred, null, true);
    }

    /** 启动补号前锁住作用域，跨实例顺序读取并更新待补对象。调用方须在供应链事务中。 */
    void lockRootScope(Kind kind) {
        scopeMapper.ensureScope(kind.name(), ROOT_OWNER_ID);
        if (scopeMapper.selectForUpdate(kind.name(), ROOT_OWNER_ID) == null) {
            throw new IllegalStateException("无法锁定编号作用域: " + kind);
        }
    }

    /** 已有内部号的占号记录丢失时补齐，保持原内部号不变；作用域锁须先取得。 */
    void ensureExistingRootClaim(Kind kind, int number) {
        if (kind != Kind.SPU && kind != Kind.BYR && kind != Kind.CAT_NODE) {
            throw new IllegalArgumentException("不能补齐此类型的根占号: " + kind);
        }
        BusinessCodeScopeDO scope = scopeMapper.selectForUpdate(kind.name(), ROOT_OWNER_ID);
        if (scope == null) {
            throw new IllegalStateException("编号作用域不存在: " + kind);
        }
        Long count = claimMapper.selectCount(new LambdaQueryWrapper<BusinessCodeClaimDO>()
                .eq(BusinessCodeClaimDO::getScopeId, scope.getId())
                .eq(BusinessCodeClaimDO::getSerialNo, number));
        if (count == null || count == 0) {
            BusinessCodeClaimDO row = new BusinessCodeClaimDO();
            row.setScopeId(scope.getId());
            row.setSerialNo((short) number);
            row.setCreatedAt(databaseClock.now());
            claimMapper.insert(row);
        }
    }

    private int claim(Kind kind, long ownerId, Integer preferred, Integer parentNumber, boolean legacy) {
        scopeMapper.ensureScope(kind.name(), ownerId);
        BusinessCodeScopeDO scope = scopeMapper.selectForUpdate(kind.name(), ownerId);
        if (scope == null) {
            throw new IllegalStateException("编号作用域创建后不可读取: " + kind + "/" + ownerId);
        }
        boolean[] used = new boolean[ScopedBusinessCodeFormat.MAX_NO + 1];
        claimMapper.selectList(new LambdaQueryWrapper<BusinessCodeClaimDO>()
                        .eq(BusinessCodeClaimDO::getScopeId, scope.getId())
                        .select(BusinessCodeClaimDO::getSerialNo))
                .forEach(row -> used[row.getSerialNo()] = true);
        Set<String> existingCodes = kind == Kind.CAT_NODE || legacy ? Set.of()
                : existingMapper.selectCodes(kind.name()).stream()
                .map(code -> code.toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));
        int number;
        if (preferred != null) {
            String code = visibleCode(kind, parentNumber, preferred);
            if (used[preferred] || existingCodes.contains(code)) {
                if ((kind == Kind.SPU || kind == Kind.BYR) && used[preferred]
                        && !existingCodes.contains(code)) {
                    throw exception(BUSINESS_CODE_OCCUPIED, code + "；父段 " + preferred + " 已被旧父对象占用");
                }
                throw exception(BUSINESS_CODE_OCCUPIED, code + "；类型=" + kind + "，作用域=" + ownerId);
            }
            number = preferred;
        } else {
            number = ScopedBusinessCodeFormat.MIN_NO;
            while (number <= ScopedBusinessCodeFormat.MAX_NO
                    && (used[number] || existingCodes.contains(visibleCode(kind, parentNumber, number)))) {
                number++;
            }
            if (number > ScopedBusinessCodeFormat.MAX_NO) {
                throw exception(BUSINESS_CODE_CAPACITY_EXHAUSTED,
                        "类型=" + kind + "，作用域=" + ownerId + "，最大号码为999");
            }
        }
        BusinessCodeClaimDO row = new BusinessCodeClaimDO();
        row.setScopeId(scope.getId());
        row.setSerialNo((short) number);
        row.setCreatedAt(databaseClock.now());
        claimMapper.insert(row);
        return number;
    }

    private static String visibleCode(Kind kind, Integer parentNumber, int number) {
        return kind.child
                ? ScopedBusinessCodeFormat.child(kind.prefix, parentNumber, number)
                : ScopedBusinessCodeFormat.root(kind.prefix, number);
    }
}
