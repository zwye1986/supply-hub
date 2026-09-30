package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.framework.common.exception.ServiceException;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_CAPACITY_EXHAUSTED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_FORMAT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_LEGACY_PARENT_OCCUPIED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_OCCUPIED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_PARENT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_PERMANENT_EXISTS;

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
        SPU("SPU", false, "SPU"), SKU("SKU", true, "SKU"),
        CAT_ROOT("CAT", false, "根分类"), CAT_CHILD("CAT", true, "子分类"),
        CAT_NODE(null, false, "分类节点"), BRD("BRD", false, "品牌"),
        SPL("SPL", false, "供应商"), BYR("BYR", false, "分销商"),
        PRJ("PRJ", true, "项目");

        private final String prefix;
        private final boolean child;
        private final String label;

        Kind(String prefix, boolean child, String label) {
            this.prefix = prefix;
            this.child = child;
            this.label = label;
        }

        public String prefix() {
            return prefix;
        }

        public String label() {
            return label;
        }
    }

    public record Allocation(String code, int number) {
    }

    /** 根对象的可见编号；SPU 和分销商的数字段同时用作其子对象父段。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateRoot(Kind kind, String requested) {
        return allocateRoot(kind, requested, null);
    }

    /** 操作人来自创建命令；无人工操作人的兼容或系统调用以 SYSTEM 记录。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateRoot(Kind kind, String requested, Long operatorId) {
        long startedAt = System.nanoTime();
        log.debug("业务编码分配入口 kind={} scope={} manual={} requested={} operatorId={}",
                kind, ROOT_OWNER_ID, requested != null && !requested.isEmpty(), requested, actor(operatorId));
        try {
            if (kind.child || kind.prefix == null) {
                throw new IllegalArgumentException("编码类型不是可见根对象: " + kind);
            }
            Integer preferred;
            try {
                preferred = requested == null || requested.isEmpty()
                        ? null : ScopedBusinessCodeFormat.parseRoot(kind.prefix, requested);
            } catch (IllegalArgumentException invalid) {
                ServiceException businessError = exception(BUSINESS_CODE_FORMAT_INVALID, invalid.getMessage());
                businessError.initCause(invalid);
                throw businessError;
            }
            int number = claim(kind, ROOT_OWNER_ID, preferred, null, false);
            String code = ScopedBusinessCodeFormat.root(kind.prefix, number);
            log.debug("业务编码分配完成 kind={} scope={} code={} number={} manual={} operatorId={} elapsedMicros={}",
                    kind, ROOT_OWNER_ID, code, number, preferred != null, actor(operatorId),
                    (System.nanoTime() - startedAt) / 1_000);
            return new Allocation(code, number);
        } catch (RuntimeException failure) {
            logFailure(kind, ROOT_OWNER_ID, operatorId, startedAt, failure);
            throw failure;
        }
    }

    /** 子对象在已持久化父对象的作用域中分配编号，父段由可信父对象内部短序号给出。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateChild(Kind kind, long parentId, int parentNumber, String requested) {
        return allocateChild(kind, parentId, parentNumber, requested, null);
    }

    /** 子对象编号入口含真实父 ID、父短号和操作人，失败时保持原异常供 HTTP 边界映射。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Allocation allocateChild(Kind kind, long parentId, int parentNumber, String requested,
                                    Long operatorId) {
        long startedAt = System.nanoTime();
        log.debug("业务编码分配入口 kind={} scope={} parentNumber={} manual={} requested={} operatorId={}",
                kind, parentId, parentNumber, requested != null && !requested.isEmpty(), requested, actor(operatorId));
        try {
            if (!kind.child || parentId <= 0) {
                throw new IllegalArgumentException("编码类型或父对象无效: " + kind);
            }
            Integer preferred;
            try {
                preferred = requested == null || requested.isEmpty()
                        ? null : ScopedBusinessCodeFormat.parseChild(kind.prefix, parentNumber, requested);
            } catch (ScopedBusinessCodeFormat.ParentMismatchException mismatch) {
                ServiceException businessError = exception(BUSINESS_CODE_PARENT_MISMATCH, mismatch.getMessage());
                businessError.initCause(mismatch);
                throw businessError;
            } catch (IllegalArgumentException invalid) {
                ServiceException businessError = exception(BUSINESS_CODE_FORMAT_INVALID, invalid.getMessage());
                businessError.initCause(invalid);
                throw businessError;
            }
            int number = claim(kind, parentId, preferred, parentNumber, false);
            String code = ScopedBusinessCodeFormat.child(kind.prefix, parentNumber, number);
            log.debug("业务编码分配完成 kind={} scope={} parentNumber={} code={} number={} manual={} "
                            + "operatorId={} elapsedMicros={}",
                    kind, parentId, parentNumber, code, number, preferred != null, actor(operatorId),
                    (System.nanoTime() - startedAt) / 1_000);
            return new Allocation(code, number);
        } catch (RuntimeException failure) {
            logFailure(kind, parentId, operatorId, startedAt, failure);
            throw failure;
        }
    }

    /** 分类节点的独立全局短序号，不作为永久编码直接对外使用。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int allocateCategoryNodeNo() {
        return allocateCategoryNodeNo(null);
    }

    /** 分类节点全局内部号先于可见编码占用，保持既定锁顺序。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public int allocateCategoryNodeNo(Long operatorId) {
        long startedAt = System.nanoTime();
        log.debug("业务编码分配入口 kind={} scope={} manual=false operatorId={}",
                Kind.CAT_NODE, ROOT_OWNER_ID, actor(operatorId));
        try {
            int number = claim(Kind.CAT_NODE, ROOT_OWNER_ID, null, null, false);
            log.debug("业务编码分配完成 kind={} scope={} number={} manual=false operatorId={} elapsedMicros={}",
                    Kind.CAT_NODE, ROOT_OWNER_ID, number, actor(operatorId),
                    (System.nanoTime() - startedAt) / 1_000);
            return number;
        } catch (RuntimeException failure) {
            logFailure(Kind.CAT_NODE, ROOT_OWNER_ID, operatorId, startedAt, failure);
            throw failure;
        }
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
        // 作用域锁内仅以可用号码组成完整候选编码，使用业务表唯一索引做有界匹配。
        // 手填最多一个候选；自动分配最多 999 个，旧表总行数不会决定本次内存用量。
        List<String> candidates = new ArrayList<>();
        if (!legacy && kind != Kind.CAT_NODE) {
            if (preferred != null) {
                candidates.add(visibleCode(kind, parentNumber, preferred));
            } else {
                for (int candidate = ScopedBusinessCodeFormat.MIN_NO;
                     candidate <= ScopedBusinessCodeFormat.MAX_NO; candidate++) {
                    if (!used[candidate]) {
                        candidates.add(visibleCode(kind, parentNumber, candidate));
                    }
                }
            }
        }
        Set<String> existingCodes = candidates.isEmpty() ? Set.of()
                : existingMapper.selectExistingCodes(kind.name(), candidates).stream()
                .map(code -> code.toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));
        int number;
        if (preferred != null) {
            String code = visibleCode(kind, parentNumber, preferred);
            if (existingCodes.contains(code)) {
                throw exception(BUSINESS_CODE_PERMANENT_EXISTS, code);
            }
            if (used[preferred]) {
                if (kind == Kind.SPU || kind == Kind.BYR) {
                    String parentCode = existingMapper.selectParentCodeByNumber(kind.name(), preferred);
                    if (parentCode != null && !parentCode.equalsIgnoreCase(code)) {
                        throw exception(BUSINESS_CODE_LEGACY_PARENT_OCCUPIED,
                                "父段 " + ScopedBusinessCodeFormat.segment(preferred)
                                        + " 已被旧" + kind.label() + "占用；对象编码=" + parentCode);
                    }
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

    private static Object actor(Long operatorId) {
        return operatorId == null ? "SYSTEM" : operatorId;
    }

    private static void logFailure(Kind kind, long scope, Long operatorId, long startedAt,
                                   RuntimeException failure) {
        Integer errorCode = failure instanceof ServiceException service ? service.getCode() : null;
        log.debug("业务编码分配失败 kind={} scope={} operatorId={} errorCode={} errorType={} elapsedMicros={}",
                kind, scope, actor(operatorId), errorCode, failure.getClass().getSimpleName(),
                (System.nanoTime() - startedAt) / 1_000);
    }
}
