package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAuthNonceMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * MySQL nonce 防重放存储：依靠复合主键唯一约束拦截重放，数据库不可用时失败关闭。
 *
 * <p>保留窗口必须严格覆盖最坏重放有效期；构造阶段校验
 * {@code retention > 2 * timestampSkew}，避免清扫后旧 nonce 在时间窗内重新插入。清扫使用
 * 单次固定数据库本地时间截止点，单批与轮数均有上限。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
@Slf4j
@Component
public class NonceStore {

    /** 单批删除上限，避免清扫形成长事务。 */
    static final int CLEANUP_BATCH_SIZE = 1000;

    /** 单周期清扫轮数上限，避免持续满批时无限循环。 */
    static final int MAX_CLEANUP_ROUNDS = 100;

    /** 写入存储异常 ERROR 日志的最小间隔，避免认证高频路径产生日志风暴。 */
    private static final Duration STORE_FAILURE_ERROR_INTERVAL = Duration.ofMinutes(1);

    private final ProjectAuthNonceMapper nonceMapper;
    private final ProjectAppAuthProperties properties;
    private final Clock clock;
    private final Supplier<LocalDateTime> currentTime;
    private final Duration nonceRetention;
    private final AtomicReference<Instant> nextStoreFailureErrorAt = new AtomicReference<>(Instant.MIN);

    /** 生产入口使用数据库时钟；独立 UTC 时钟仅用于故障日志限频，包内入口供边界测试。 */
    @Autowired
    public NonceStore(ProjectAuthNonceMapper nonceMapper, ProjectAppAuthProperties properties,
                      SupplyChainDatabaseClock databaseClock) {
        this(nonceMapper, properties, Clock.systemUTC(), databaseClock::now);
    }

    NonceStore(ProjectAuthNonceMapper nonceMapper, ProjectAppAuthProperties properties, Clock clock) {
        this(nonceMapper, properties, clock,
                () -> LocalDateTime.ofInstant(clock.instant(), SupplyChainDatabaseClock.DATABASE_ZONE));
    }

    private NonceStore(ProjectAuthNonceMapper nonceMapper, ProjectAppAuthProperties properties,
                       Clock clock, Supplier<LocalDateTime> currentTime) {
        this.nonceMapper = Objects.requireNonNull(nonceMapper, "nonceMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.currentTime = Objects.requireNonNull(currentTime, "currentTime");
        this.nonceRetention = validateAndResolveRetention(properties, currentTime);
    }

    /**
     * 插入 nonce 并保证唯一；验签通过后调用，任何数据库异常都统一失败关闭。
     *
     * <p>一次调用只读取一个数据库本地时间，使 {@code created_at} 与 {@code expire_at} 精确共享同一
     * 基准。异常对外统一为 {@code BUYER_AUTH_INVALID}，同时保留原始数据库异常因果。</p>
     *
     * @param appId 项目应用稳定标识
     * @param nonce 调用方随机串；禁止写入日志
     */
    public void ensureUnique(String appId, String nonce) {
        log.debug("[project-auth] nonce 写入 category=INSERT appId={}", appId);
        LocalDateTime now = currentTime.get();
        ProjectAuthNonceDO row = new ProjectAuthNonceDO();
        row.setAppId(appId);
        row.setNonce(nonce);
        row.setCreatedAt(now);
        row.setExpireAt(now.plus(nonceRetention));
        try {
            nonceMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            log.debug("[project-auth] nonce 拒绝 category=REPLAY appId={}", appId);
            throw authInvalid(ex);
        } catch (DataAccessException ex) {
            logStoreFailure(appId, ex);
            throw authInvalid(ex);
        }
    }

    /**
     * 低频、有限批次清扫过期 nonce；数据库失败在定时任务边界告警后等待下周期。
     *
     * <p>所有批次复用一次读取的数据库本地时间截止点，确保本轮删除集合稳定。捕获数据库异常是定时任务
     * 的有意边界处理；WARN 只携带安全结构化元数据与复制的调用栈，不挂原异常且不在当前周期重试。</p>
     */
    @Scheduled(fixedDelayString = "${aifuli.supply-chain.auth.nonce-cleanup-interval:PT1H}")
    public void cleanupExpired() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = currentTime.get();
        try {
            for (int round = 0; round < MAX_CLEANUP_ROUNDS; round++) {
                int deleted = nonceMapper.deleteExpiredBefore(cutoff, CLEANUP_BATCH_SIZE);
                if (deleted < CLEANUP_BATCH_SIZE) {
                    return;
                }
            }
            log.debug("[project-auth] nonce 清扫到达轮数上限 category=CLEANUP_LIMIT");
        } catch (DataAccessException ex) {
            logCleanupFailure(ex);
        }
    }

    /** enabled 模式下校验安全窗口和本地时间 expiry 可表示性，避免把错误配置推迟到首个请求。 */
    private static Duration validateAndResolveRetention(ProjectAppAuthProperties properties,
                                                        Supplier<LocalDateTime> currentTime) {
        if (!properties.isEnabled()) {
            return Duration.ZERO;
        }
        try {
            Duration retention = Duration.ofHours(properties.getNonceRetentionHours());
            Duration maximumReplayWindow = Duration.ofMinutes(properties.getTimestampSkewMinutes())
                    .multipliedBy(2);
            if (properties.getTimestampSkewMinutes() < 0
                    || retention.isZero()
                    || retention.isNegative()
                    || retention.compareTo(maximumReplayWindow) <= 0) {
                throw invalidRetentionWindow();
            }
            currentTime.get().plus(retention);
            return retention;
        } catch (DateTimeException | ArithmeticException ex) {
            throw new IllegalStateException(
                    "aifuli.supply-chain.auth nonce-retention-hours 或 timestamp-skew-minutes 超出可支持范围",
                    ex);
        }
    }

    private static IllegalStateException invalidRetentionWindow() {
        return new IllegalStateException("aifuli.supply-chain.auth.nonce-retention-hours 必须严格大于 "
                + "2 * aifuli.supply-chain.auth.timestamp-skew-minutes 对应时长");
    }

    /** 高频数据库故障每分钟最多一条安全诊断 ERROR，其余降为不附 Throwable 的 DEBUG。 */
    private void logStoreFailure(String appId, DataAccessException failure) {
        SafeFailureDetails details = safeFailureDetails(failure);
        Instant now = clock.instant();
        while (true) {
            Instant nextAllowed = nextStoreFailureErrorAt.get();
            if (now.isBefore(nextAllowed)) {
                log.debug("[project-auth] nonce 写入失败 category=STORE_UNAVAILABLE appId={} "
                                + "errorClass={} rootCauseClass={}",
                        appId, details.errorClass(), details.rootCauseClass());
                return;
            }
            Instant next = now.plus(STORE_FAILURE_ERROR_INTERVAL);
            if (nextStoreFailureErrorAt.compareAndSet(nextAllowed, next)) {
                String message = appendSqlMetadata(
                        "[project-auth] nonce 写入失败 category=STORE_UNAVAILABLE appId=" + appId
                                + " errorClass=" + details.errorClass()
                                + " rootCauseClass=" + details.rootCauseClass(),
                        details);
                log.error(message, safeDiagnosticThrowable(message, failure));
                return;
            }
        }
    }

    /** 清扫是低频定时边界，记录一条安全 WARN 后返回，绝不挂接可能泄密的原始数据库异常。 */
    private void logCleanupFailure(DataAccessException failure) {
        SafeFailureDetails details = safeFailureDetails(failure);
        String message = appendSqlMetadata(
                "[project-auth] nonce 清扫失败 category=CLEANUP_STORE_UNAVAILABLE"
                        + " errorClass=" + details.errorClass()
                        + " rootCauseClass=" + details.rootCauseClass(),
                details);
        log.warn(message, safeDiagnosticThrowable(message, failure));
    }

    /**
     * 仅提取类名与 JDBC 结构化元数据；按对象身份防 cause 环，且绝不读取异常 message。
     */
    private static SafeFailureDetails safeFailureDetails(DataAccessException failure) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = failure;
        Throwable rootCause = failure;
        SQLException sqlException = null;
        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            rootCause = current;
            if (sqlException == null && current instanceof SQLException candidate) {
                sqlException = candidate;
            }
            current = current.getCause();
        }
        return new SafeFailureDetails(
                failure.getClass().getName(),
                rootCause.getClass().getName(),
                sqlException == null ? null : sqlException.getSQLState(),
                sqlException == null ? null : sqlException.getErrorCode());
    }

    private static String appendSqlMetadata(String message, SafeFailureDetails details) {
        if (details.vendorCode() == null) {
            return message;
        }
        return message + " sqlState=" + details.sqlState() + " vendorCode=" + details.vendorCode();
    }

    /** 安全 Throwable 只复用原始调用栈，不保留原 message、cause 或 suppressed 异常。 */
    private static SafeStoreDiagnosticException safeDiagnosticThrowable(
            String message, DataAccessException failure) {
        return new SafeStoreDiagnosticException(message, failure.getStackTrace());
    }

    private record SafeFailureDetails(
            String errorClass, String rootCauseClass, String sqlState, Integer vendorCode) {
    }

    private static final class SafeStoreDiagnosticException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private SafeStoreDiagnosticException(String message, StackTraceElement[] originalStackTrace) {
            super(message, null, false, true);
            setStackTrace(originalStackTrace);
        }
    }

    private static ServiceException authInvalid(DataAccessException cause) {
        ServiceException authInvalid = exception(BUYER_AUTH_INVALID);
        authInvalid.initCause(cause);
        return authInvalid;
    }
}
