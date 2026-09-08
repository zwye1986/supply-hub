package com.aifuli.module.supplychain.infrastructure.time;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 供应链持久化业务时钟，以 MySQL 会话的毫秒级当前时间作为唯一真相源。
 *
 * <p>数据库、JDBC 会话和持久化 {@link LocalDateTime} 统一采用 {@code Asia/Shanghai}。
 * 读取失败时不得回退 JVM 时钟，否则多节点的 Timer、Outbox、有效期判断会重新产生时钟漂移。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#22-当前时间来源}。</p>
 */
public class SupplyChainDatabaseClock {

    public static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String CURRENT_TIME_SQL = "SELECT CURRENT_TIMESTAMP(3)";
    private static final String SESSION_OFFSET_SQL = """
            SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())
            """;
    private static final int EXPECTED_UTC_OFFSET_SECONDS = 8 * 60 * 60;

    private final JdbcTemplate jdbcTemplate;

    public SupplyChainDatabaseClock(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 读取当前数据库时间；一次业务操作应只调用一次并复用返回值。
     *
     * @return 毫秒精度的上海本地时间
     */
    public LocalDateTime now() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(CURRENT_TIME_SQL, LocalDateTime.class),
                "MySQL CURRENT_TIMESTAMP(3) returned null");
    }

    /** 将外部绝对时刻转换为供应链数据库的上海本地时间。 */
    public LocalDateTime fromInstant(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), DATABASE_ZONE);
    }

    /** 将供应链数据库的上海本地时间恢复为绝对时刻。 */
    public Instant toInstant(LocalDateTime databaseTime) {
        return Objects.requireNonNull(databaseTime, "databaseTime").atZone(DATABASE_ZONE).toInstant();
    }

    /** 启动期失败关闭：连接池初始化 SQL 必须把每个物理连接固定到 UTC+8。 */
    @PostConstruct
    void verifySessionTimeZone() {
        Integer offset = jdbcTemplate.queryForObject(SESSION_OFFSET_SQL, Integer.class);
        if (!Objects.equals(offset, EXPECTED_UTC_OFFSET_SECONDS)) {
            throw new IllegalStateException("供应链数据库会话时区必须为 Asia/Shanghai(+08:00)，实际偏移秒=" + offset);
        }
    }
}
