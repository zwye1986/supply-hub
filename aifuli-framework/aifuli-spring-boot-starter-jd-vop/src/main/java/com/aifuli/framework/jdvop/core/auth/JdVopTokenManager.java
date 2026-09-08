package com.aifuli.framework.jdvop.core.auth;

import com.aifuli.framework.jdvop.config.JdVopProperties;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * 京东 VOP Token 只读管理器。
 *
 * <p>该管理器使用独立 Redis 连接读取旧京东项目维护的 accessToken，
 * 不提供 Token 获取、刷新、写入或删除能力。</p>
 */
public class JdVopTokenManager implements AutoCloseable {

    /** 京东 Token 专用 Redis 连接工厂，不注册为 Spring 通用 Redis Bean。 */
    private final LettuceConnectionFactory tokenRedisConnectionFactory;

    /** 仅用于读取京东 accessToken 的字符串 Redis 客户端。 */
    private final StringRedisTemplate tokenRedisTemplate;

    /** 旧京东项目维护的 accessToken Redis Key。 */
    private final String accessTokenKey;

    public JdVopTokenManager(JdVopProperties properties) {
        JdVopProperties.TokenRedis tokenRedis = properties.getTokenRedis();
        validateTokenRedisProperties(tokenRedis);

        // 使用独立连接参数，避免复用或覆盖当前应用的主 Redis 数据源。
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                tokenRedis.getHost(), tokenRedis.getPort());
        redisConfiguration.setDatabase(tokenRedis.getDatabase());
        if (StringUtils.hasText(tokenRedis.getPassword())) {
            redisConfiguration.setPassword(RedisPassword.of(tokenRedis.getPassword()));
        }

        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(tokenRedis.getCommandTimeout())
                .build();
        this.tokenRedisConnectionFactory = new LettuceConnectionFactory(
                redisConfiguration, clientConfiguration);
        this.tokenRedisConnectionFactory.afterPropertiesSet();

        this.tokenRedisTemplate = new StringRedisTemplate(this.tokenRedisConnectionFactory);
        this.tokenRedisTemplate.afterPropertiesSet();
        this.accessTokenKey = tokenRedis.getKey();
    }

    /**
     * 从京东专用 Redis 实时读取 accessToken。
     *
     * @return 当前有效的京东 accessToken
     */
    public String getAccessToken() {
        try {
            // 此处是本组件对京东 Token Redis 执行的唯一数据命令，只允许 GET。
//            String accessToken = clearRedisQuotes(tokenRedisTemplate.opsForValue().get(accessTokenKey));
            String accessToken = tokenRedisTemplate.opsForValue().get(accessTokenKey);
            if (!StringUtils.hasText(accessToken)) {
                throw new JdVopException("京东 Token Redis 中不存在有效的 accessToken，Key：" + accessTokenKey);
            }
            return accessToken;
        } catch (DataAccessException exception) {
            throw new JdVopException("读取京东 Token 专用 Redis 失败", exception);
        }
    }

    /**
     * 关闭 starter 自己创建的京东 Token Redis 连接。
     */
    @Override
    public void close() {
        tokenRedisConnectionFactory.destroy();
    }

    /**
     * 校验京东 Token 专用 Redis 的必要配置。
     */
    private static void validateTokenRedisProperties(JdVopProperties.TokenRedis tokenRedis) {
        if (!StringUtils.hasText(tokenRedis.getHost())) {
            throw new IllegalArgumentException("京东 Token Redis host 不能为空");
        }
        if (tokenRedis.getPort() <= 0 || tokenRedis.getPort() > 65535) {
            throw new IllegalArgumentException("京东 Token Redis port 必须在 1 到 65535 之间");
        }
        if (tokenRedis.getDatabase() < 0) {
            throw new IllegalArgumentException("京东 Token Redis database 不能小于 0");
        }
        if (!StringUtils.hasText(tokenRedis.getKey())) {
            throw new IllegalArgumentException("京东 accessToken Redis Key 不能为空");
        }
        if (tokenRedis.getCommandTimeout() == null
                || tokenRedis.getCommandTimeout().isZero()
                || tokenRedis.getCommandTimeout().isNegative()) {
            throw new IllegalArgumentException("京东 Token Redis commandTimeout 必须大于 0");
        }
    }

    /**
     * 兼容旧项目使用 JSON 字符串序列化后可能产生的首尾双引号。
     */
    private static String clearRedisQuotes(String value) {
        if (!StringUtils.hasText(value) || value.length() < 2) {
            return value;
        }
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

}
