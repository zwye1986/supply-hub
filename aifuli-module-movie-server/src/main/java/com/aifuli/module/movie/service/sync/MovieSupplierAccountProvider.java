package com.aifuli.module.movie.service.sync;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.framework.supply.MovieSyncChannelPolicy;
import com.aifuli.module.supplychain.api.movie.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 电影出站账号入口：只消费显式注册的中台API，不直接访问sc_*表或旧Nacos账号配置。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#数据库账号与渠道选择落地2026-09-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSupplierAccountProvider {
    /** 每轮渠道选择策略。 */
    private final MovieSyncChannelPolicy channelPolicy;
    /** 专用中台配置接口。 */
    private final MovieSupplierConfigApi configApi;
    /** 独立服务令牌，不使用供应商凭证作为RPC认证。 */
    private final Environment environment;

    /** 所选渠道只调用一次中台接口；失败上抛到任务边界，不解释为空供给。 */
    public List<Account> accounts() {
        List<MovieSupplierChannel> channels = channelPolicy.snapshot();
        if (channels.isEmpty()) {
            return List.of();
        }
        String token = environment.getProperty("movie.supply.config-token", "");
        if (token.isBlank() || token.length() < 32 || token.length() > 512) {
            throw new IllegalStateException("请配置32至512字符的movie.supply.config-token");
        }
        long started = System.nanoTime();
        String outcome = "FAILED";
        log.debug("请求中台电影配置 channels={}", channels);
        try {
            CommonResult<List<MovieSupplierAccountDTO>> response = configApi.accounts(token, channels);
            if (response == null) {
                throw new IllegalStateException("中台电影配置响应为空");
            }
            response.checkError();
            List<MovieSupplierAccountDTO> source = response.getData();
            if (source == null || source.isEmpty()) {
                throw new IllegalStateException("所选渠道没有可用电影账号，请检查中台启用状态和当前发布版本");
            }
            Set<MovieSupplierChannel> returned = new HashSet<>();
            for (MovieSupplierAccountDTO account : source) {
                if (account == null || account.getChannel() == null || !channels.contains(account.getChannel())) {
                    throw new IllegalStateException("中台返回了未选择渠道的电影账号");
                }
                returned.add(account.getChannel());
            }
            if (!returned.containsAll(channels)) {
                throw new IllegalStateException("中台未返回全部所选渠道的电影账号");
            }
            List<Account> result = source.stream().map(MovieSupplierAccountProvider::convert).toList();
            outcome = "SUCCESS";
            log.debug("中台电影配置响应 channels={} accounts={}", channels, result.size());
            return result;
        } finally {
            log.debug("中台电影配置调用结束 channels={} result={} elapsedMs={}", channels, outcome,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 显式映射跨服务字段，保留既有accountRef和v1等版本引用，禁止Bean复制泄露扩展字段。 */
    private static Account convert(MovieSupplierAccountDTO source) {
        if (source.getConnectTimeoutMs() == null || source.getReadTimeoutMs() == null
                || source.getMinRequestIntervalMs() == null) {
            throw new IllegalStateException("中台电影账号缺少超时或限流参数");
        }
        Account target = new Account();
        target.setEnabled(true);
        target.setAccountRef(source.getAccountRef());
        target.setChannel(MovieChannel.valueOf(source.getChannel().name()));
        target.setConfigVersion(source.getConfigVersion());
        target.setBaseUrl(source.getBaseUrl());
        target.setAppId(source.getAppId());
        target.setAppSecret(source.getAppSecret());
        target.setConnectTimeout(Duration.ofMillis(source.getConnectTimeoutMs()));
        target.setRequestTimeout(Duration.ofMillis(source.getReadTimeoutMs()));
        target.setMinRequestInterval(Duration.ofMillis(source.getMinRequestIntervalMs()));
        target.setDeliverMinutes(source.getDeliverMinutes());
        return target;
    }
}
