package com.aifuli.module.supplychain.infrastructure.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject.ProjectAuthNonceDO;
import com.aifuli.module.supplychain.infrastructure.auth.dal.mysql.ProjectAuthNonceMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nonce 防重放单测：锁定数据库上海本地时间写入、失败关闭因果链、安全保留窗口与有限批次清扫。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
@ExtendWith(MockitoExtension.class)
class NonceStoreTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T12:34:56.789Z");
    private static final LocalDateTime FIXED_DATABASE_TIME =
            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneId.of("Asia/Shanghai"));
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private ProjectAuthNonceMapper nonceMapper;

    private ProjectAppAuthProperties properties;
    private NonceStore store;
    private Logger storeLogger;
    private Level originalLoggerLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ProjectAuthNonceDO.class);
    }

    @BeforeEach
    void setUp() {
        properties = new ProjectAppAuthProperties();
        store = new NonceStore(nonceMapper, properties, FIXED_CLOCK);
        storeLogger = (Logger) LoggerFactory.getLogger(NonceStore.class);
        originalLoggerLevel = storeLogger.getLevel();
        storeLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        storeLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        storeLogger.detachAppender(logAppender);
        logAppender.stop();
        storeLogger.setLevel(originalLoggerLevel);
    }

    @Test
    void ensureUniqueUsesOneFixedDatabaseTimeForCreationAndExpiry() {
        store.ensureUnique("app-1", "nonce-1");

        ArgumentCaptor<ProjectAuthNonceDO> captor = ArgumentCaptor.forClass(ProjectAuthNonceDO.class);
        verify(nonceMapper).insert(captor.capture());
        ProjectAuthNonceDO row = captor.getValue();
        assertThat(row.getAppId()).isEqualTo("app-1");
        assertThat(row.getNonce()).isEqualTo("nonce-1");
        assertThat(row.getCreatedAt()).isEqualTo(FIXED_DATABASE_TIME);
        assertThat(row.getExpireAt()).isEqualTo(FIXED_DATABASE_TIME.plusHours(1));
    }

    @Test
    void ensureUniqueRejectsReplayAndPreservesDuplicateCause() {
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate");
        when(nonceMapper.insert(any(ProjectAuthNonceDO.class))).thenThrow(duplicate);

        assertThatThrownBy(() -> store.ensureUnique("app-1", "nonce-1"))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> {
                    ServiceException serviceException = (ServiceException) exception;
                    assertThat(serviceException.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
                    assertThat(serviceException.getCause()).isSameAs(duplicate);
                });
    }

    @Test
    void ensureUniqueFailsClosedAndPreservesStoreFailureCause() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(nonceMapper.insert(any(ProjectAuthNonceDO.class))).thenThrow(failure);

        assertThatThrownBy(() -> store.ensureUnique("app-1", "nonce-1"))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> {
                    ServiceException serviceException = (ServiceException) exception;
                    assertThat(serviceException.getCode()).isEqualTo(BUYER_AUTH_INVALID.getCode());
                    assertThat(serviceException.getCause()).isSameAs(failure);
                });
    }

    @Test
    void ensureUniqueRateLimitsStoreFailureErrorsWithoutRenderingSensitiveDatabaseMessages() {
        String sensitiveNonce = "nonce-must-not-appear";
        SQLException sqlCause = new SQLException("sql leaked " + sensitiveNonce, "08001", 1040);
        IllegalStateException rootCause = new IllegalStateException("root leaked " + sensitiveNonce);
        sqlCause.initCause(rootCause);
        rootCause.initCause(sqlCause);
        DataAccessResourceFailureException firstFailure =
                new DataAccessResourceFailureException("first leaked " + sensitiveNonce, sqlCause);
        DataAccessResourceFailureException secondFailure =
                new DataAccessResourceFailureException("second leaked " + sensitiveNonce,
                        new IllegalArgumentException("nested leaked " + sensitiveNonce));
        when(nonceMapper.insert(any(ProjectAuthNonceDO.class))).thenThrow(firstFailure, secondFailure);

        Throwable firstThrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> store.ensureUnique("app-1", sensitiveNonce));
        Throwable secondThrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> store.ensureUnique("app-1", sensitiveNonce));

        assertThat(firstThrown).isInstanceOf(ServiceException.class);
        assertThat(firstThrown.getCause()).isSameAs(firstFailure);
        assertThat(secondThrown).isInstanceOf(ServiceException.class);
        assertThat(secondThrown.getCause()).isSameAs(secondFailure);
        List<ILoggingEvent> storeFailureEvents = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("STORE_UNAVAILABLE"))
                .toList();
        assertThat(storeFailureEvents).hasSize(2);

        ILoggingEvent firstEvent = storeFailureEvents.get(0);
        assertThat(firstEvent.getLevel()).isEqualTo(Level.ERROR);
        assertThat(firstEvent.getFormattedMessage())
                .isEqualTo("[project-auth] nonce 写入失败 category=STORE_UNAVAILABLE appId=app-1 "
                        + "errorClass=org.springframework.dao.DataAccessResourceFailureException "
                        + "rootCauseClass=java.lang.IllegalStateException sqlState=08001 vendorCode=1040");
        assertThat(firstEvent.getThrowableProxy()).isNotNull();
        assertThat(firstEvent.getThrowableProxy().getClassName())
                .endsWith("NonceStore$SafeStoreDiagnosticException");
        assertThat(firstEvent.getThrowableProxy().getMessage()).isEqualTo(firstEvent.getFormattedMessage());
        assertThat(firstEvent.getThrowableProxy().getCause()).isNull();
        assertThat(firstEvent.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();

        ILoggingEvent secondEvent = storeFailureEvents.get(1);
        assertThat(secondEvent.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(secondEvent.getThrowableProxy()).isNull();
        assertThat(secondEvent.getFormattedMessage())
                .isEqualTo("[project-auth] nonce 写入失败 category=STORE_UNAVAILABLE appId=app-1 "
                        + "errorClass=org.springframework.dao.DataAccessResourceFailureException "
                        + "rootCauseClass=java.lang.IllegalArgumentException");
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(render(event))
                        .doesNotContain(sensitiveNonce,
                                "first leaked", "sql leaked", "root leaked",
                                "second leaked", "nested leaked"));
    }

    @Test
    void cleanupExpiredUsesFixedDatabaseCutoffAndBatchLimitUntilShortBatch() {
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class), anyInt()))
                .thenReturn(NonceStore.CLEANUP_BATCH_SIZE, NonceStore.CLEANUP_BATCH_SIZE, 3);

        store.cleanupExpired();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(nonceMapper, times(3)).deleteExpiredBefore(cutoffCaptor.capture(), limitCaptor.capture());
        assertThat(cutoffCaptor.getAllValues())
                .containsExactly(FIXED_DATABASE_TIME, FIXED_DATABASE_TIME, FIXED_DATABASE_TIME);
        assertThat(limitCaptor.getAllValues()).containsOnly(1000);
    }

    @Test
    void cleanupExpiredSkipsWhenDisabled() {
        properties.setEnabled(false);
        properties.setNonceRetentionHours(0);
        store = new NonceStore(nonceMapper, properties, FIXED_CLOCK);

        store.cleanupExpired();

        verify(nonceMapper, never()).deleteExpiredBefore(any(LocalDateTime.class), anyInt());
    }

    @Test
    void cleanupExpiredHandlesStoreFailureAtScheduledBoundary() {
        String sensitiveNonce = "cleanup-nonce-must-not-appear";
        SQLException sqlCause = new SQLException("cleanup sql leaked " + sensitiveNonce, "08006", 1042);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "cleanup leaked " + sensitiveNonce, sqlCause);
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class), anyInt())).thenThrow(failure);

        assertThatCode(store::cleanupExpired).doesNotThrowAnyException();
        verify(nonceMapper).deleteExpiredBefore(FIXED_DATABASE_TIME, NonceStore.CLEANUP_BATCH_SIZE);
        ILoggingEvent cleanupFailureEvent = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("CLEANUP_STORE_UNAVAILABLE"))
                .findFirst()
                .orElseThrow();
        assertThat(cleanupFailureEvent.getLevel()).isEqualTo(Level.WARN);
        assertThat(cleanupFailureEvent.getThrowableProxy()).isNotNull();
        assertThat(cleanupFailureEvent.getThrowableProxy().getCause()).isNull();
        assertThat(render(cleanupFailureEvent))
                .contains("errorClass=org.springframework.dao.DataAccessResourceFailureException",
                        "rootCauseClass=java.sql.SQLException", "sqlState=08006", "vendorCode=1042")
                .doesNotContain(sensitiveNonce, "cleanup leaked", "cleanup sql leaked");
    }

    @Test
    void cleanupExpiredStopsAfterMaximumRoundsEvenWhenEveryBatchIsFull() {
        when(nonceMapper.deleteExpiredBefore(any(LocalDateTime.class), anyInt()))
                .thenReturn(NonceStore.CLEANUP_BATCH_SIZE);

        store.cleanupExpired();

        verify(nonceMapper, times(NonceStore.MAX_CLEANUP_ROUNDS))
                .deleteExpiredBefore(FIXED_DATABASE_TIME, NonceStore.CLEANUP_BATCH_SIZE);
    }

    @Test
    void enabledRejectsZeroRetention() {
        properties.setNonceRetentionHours(0);

        assertThatThrownBy(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonce-retention-hours", "timestamp-skew-minutes");
    }

    @Test
    void enabledRejectsRetentionEqualToTwiceTimestampSkew() {
        properties.setNonceRetentionHours(1);
        properties.setTimestampSkewMinutes(30);

        assertThatThrownBy(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("严格大于");
    }

    @Test
    void enabledAcceptsRetentionStrictlyGreaterThanTwiceTimestampSkew() {
        properties.setNonceRetentionHours(1);
        properties.setTimestampSkewMinutes(29);

        assertThatCode(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    void disabledSkipsRetentionWindowValidation() {
        properties.setEnabled(false);
        properties.setNonceRetentionHours(0);
        properties.setTimestampSkewMinutes(Long.MAX_VALUE);

        assertThatCode(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledFailsClosedWhenDurationConversionWouldOverflow() {
        properties.setNonceRetentionHours(Long.MAX_VALUE);

        assertThatThrownBy(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超出可支持范围");
    }

    @Test
    void enabledFailsClosedAtConstructionWhenExpiryExceedsLocalDateTimeRange() {
        properties.setNonceRetentionHours(10_000_000_000_000L);

        assertThatThrownBy(() -> new NonceStore(nonceMapper, properties, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超出可支持范围")
                .hasCauseInstanceOf(DateTimeException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonceDoHasNoSyntheticIdAndLambdaDeleteUsesMappedColumnAndLimit() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(ProjectAuthNonceDO.class);
        assertThat(tableInfo.getTableName()).isEqualTo("sc_project_auth_nonce");
        assertThat(tableInfo.getKeyProperty()).isNull();
        assertThat(tableInfo.getFieldList())
                .extracting(field -> field.getProperty())
                .containsExactlyInAnyOrder("appId", "nonce", "createdAt", "expireAt");

        ArgumentCaptor<Wrapper<ProjectAuthNonceDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        ProjectAuthNonceMapper realDefaultMethodMapper = mock(ProjectAuthNonceMapper.class, CALLS_REAL_METHODS);
        doReturn(0).when(realDefaultMethodMapper).delete(any(Wrapper.class));

        realDefaultMethodMapper.deleteExpiredBefore(FIXED_DATABASE_TIME, 1000);

        verify(realDefaultMethodMapper).delete(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("expire_at", "<", "LIMIT 1000");
    }

    @Test
    void authAutoConfigurationRemainsBootAutoConfigurationAndEnablesScheduling() {
        assertThat(ProjectAppAuthConfiguration.class)
                .hasAnnotation(AutoConfiguration.class)
                .hasAnnotation(EnableScheduling.class);
    }

    private static String render(ILoggingEvent event) {
        String throwable = event.getThrowableProxy() == null
                ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
        return event.getFormattedMessage() + System.lineSeparator() + throwable;
    }
}
