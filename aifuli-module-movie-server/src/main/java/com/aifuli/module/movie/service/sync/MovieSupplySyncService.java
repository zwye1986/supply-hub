package com.aifuli.module.movie.service.sync;

import com.aifuli.module.movie.dal.dataobject.mysql.*;
import com.aifuli.module.movie.dal.mongo.CinemaScheduleRepository;
import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.MovieSupplyClient;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import static com.aifuli.module.movie.enums.MovieDataStatus.ENABLED;

/**
 * 供给采集编排；每条主数据独立事务，每个影院独立快照，故障不扩大删除范围。
 * 对外入口仅是内部服务和条件调度 Bean，不暴露绕过安全链的 HTTP 接口。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSupplySyncService {
    /** JC 文档 §4.7 支持的订单类型，不能接受任意分钟数字。 */
    private static final Set<String> JC_DELIVERY_OPTIONS = Set.of("8", "15", "30");
    /** 账号及分页配置。 */
    private final MovieSupplyProperties properties;
    /** 显式的两渠道供给端口，按渠道枚举选择。 */
    private final List<MovieSupplyClient> clients;
    /** MySQL 主数据事务服务。 */
    private final MovieMasterDataService masterData;
    /** 排期领域转换与 Mongo 持久化各自独立。 */
    private final MovieScheduleConverter documents;
    private final CinemaScheduleRepository schedules;
    /** 跨实例同步互斥锁。 */
    private final MovieSyncLock syncLock;
    /** 统一过期时钟。 */
    private final Clock movieSupplyClock;

    /** 启动即校验启用账号元信息，凭证只在外呼时读取，日志不输出配置对象。 */
    @PostConstruct
    public void validateConfiguration() {
        if (properties.getMaxPages() < 1 || properties.getMaxPages() > 10000) {
            throw new IllegalArgumentException("movie.supply.max-pages 必须介于 1 和 10000");
        }
        Set<String> refs = new HashSet<>();
        for (Account account : properties.getAccounts()) {
            if (account.getAccountRef() == null || !account.getAccountRef().matches("[A-Za-z0-9_-]{1,64}")
                    || !refs.add(account.getAccountRef())) {
                throw new IllegalArgumentException("电影账号引用缺失、重复或包含不允许字符");
            }
            if (!account.isEnabled()) {
                continue;
            }
            if (account.getChannel() == null || account.getConfigVersion() == null || account.getConfigVersion().isBlank()
                    || account.getBaseUrl() == null || account.getBaseUrl().isBlank()
                    || account.getAppKeyEnv() == null || account.getAppSecretEnv() == null
                    || account.getConnectTimeout() == null || account.getConnectTimeout().isNegative() || account.getConnectTimeout().isZero()
                    || account.getRequestTimeout() == null || account.getRequestTimeout().isNegative() || account.getRequestTimeout().isZero()
                    || account.getMinRequestInterval() == null || account.getMinRequestInterval().isNegative()
                    || account.getMinRequestInterval().compareTo(java.time.Duration.ofMinutes(1)) > 0
                    || !validDeliveryOptions(account)) {
                throw new IllegalArgumentException("电影启用账号配置不完整或请求间隔不在 0 到 1 分钟范围：" + account.getAccountRef());
            }
            client(account);
        }
    }

    /** 仅 JC 使用 deliverMinutes，要求档位有效且不重复，JY 不受该私有参数约束。 */
    private static boolean validDeliveryOptions(Account account) {
        if (account.getChannel() != MovieChannel.JC) {
            return true;
        }
        if (account.getDeliverMinutes() == null) {
            return false;
        }
        List<String> options = List.of(account.getDeliverMinutes().split(",", -1));
        return JC_DELIVERY_OPTIONS.containsAll(options) && new HashSet<>(options).size() == options.size();
    }

    /**
     * 手工全量初始化入口，不注册日常调度；阶段失败或锁忙即中止，完成前置数据后再手工重跑。
     * 各阶段复用独立入口及其锁，避免初始化路径与周期任务形成两套实现。
     */
    public void synchronizeMasterData() {
        log.info("电影主数据手工初始化开始");
        if (enabledAccounts().isEmpty()) {
            throw new IllegalStateException("电影主数据初始化需要至少一个启用的供应商账号");
        }
        synchronizeCities();
        synchronizeSoonFilms();
        synchronizeHotFilms();
        synchronizeCinemas();
        log.info("电影主数据手工初始化完成");
    }

    /** 只同步城市及映射；先拉全部 JC 城市，随后处理 JY 的唯一同名匹配。 */
    public void synchronizeCities() {
        List<Account> accounts = enabledAccounts();
        log.info("电影城市同步开始 accounts={}", accounts.size());
        if (accounts.isEmpty()) {
            return;
        }
        syncLock.execute(MovieSyncScope.CITY, guard -> {
            SyncSummary summary = new SyncSummary("city");
            for (Account account : accounts) {
                guard.run();
                try {
                    for (City city : client(account).cities(account)) {
                        guard.run();
                        summary.run(account, "city:" + city.supplierId(), () -> masterData.syncCity(account.getChannel(), city));
                    }
                } catch (RuntimeException exception) {
                    summary.failure(account, "cities", exception);
                }
            }
            summary.finish();
        });
    }

    /** 只拉热映影片接口，可独立于待映任务高频执行。 */
    public void synchronizeHotFilms() {
        synchronizeFilms(false);
    }

    /** 只拉待映影片接口，不顺带拉热映、城市或影院。 */
    public void synchronizeSoonFilms() {
        synchronizeFilms(true);
    }

    /** 两类影片分别统计结果，但共享 FILM 锁保护标准影片及 Mapping。 */
    private void synchronizeFilms(boolean presell) {
        List<Account> accounts = enabledAccounts();
        String stage = presell ? "soon-film" : "hot-film";
        log.info("电影影片同步开始 stage={}, accounts={}", stage, accounts.size());
        if (accounts.isEmpty()) {
            return;
        }
        syncLock.execute(MovieSyncScope.FILM, guard -> {
            SyncSummary summary = new SyncSummary(stage);
            for (Account account : accounts) {
                guard.run();
                try {
                    for (Film film : client(account).films(account, presell)) {
                        guard.run();
                        summary.run(account, "film:" + film.supplierId(), () -> masterData.syncFilm(account.getChannel(), film));
                    }
                } catch (RuntimeException exception) {
                    summary.failure(account, stage, exception);
                }
            }
            summary.finish();
        });
    }

    /** 只按已映射城市同步影院，前置城市不存在时报告缺失，不隐式触发城市同步。 */
    public void synchronizeCinemas() {
        List<Account> accounts = enabledAccounts();
        log.info("电影影院同步开始 accounts={}", accounts.size());
        if (accounts.isEmpty()) {
            return;
        }
        syncLock.execute(MovieSyncScope.CINEMA, guard -> {
            SyncSummary summary = new SyncSummary("cinema");
            for (Account account : accounts) {
                guard.run();
                List<MovieCityMappingDO> cities = masterData.cities(account.getChannel());
                if (cities.isEmpty()) {
                    summary.failure(account, "cities", new MissingMovieMappingException("影院同步前请先初始化渠道城市映射"));
                }
                for (MovieCityMappingDO city : cities) {
                    guard.run();
                    try {
                        List<Cinema> cinemas = client(account).cinemas(account, city.getSupplierCityId());
                        for (Cinema cinema : cinemas) {
                            if (!city.getSupplierCityId().equals(cinema.supplierCityId())) {
                                throw new IllegalStateException("供应商影院返回了其它城市的数据");
                            }
                            guard.run();
                            summary.run(account, "cinema:" + cinema.supplierId(), () -> masterData.syncCinema(account.getChannel(), cinema));
                        }
                    } catch (RuntimeException exception) {
                        summary.failure(account, "cinemas:" + city.getSupplierCityId(), exception);
                    }
                }
            }
            summary.finish();
        });
    }

    /** 排期全轮同步，逐账号、逐影院处理，永不跨账号清理。 */
    public void synchronizeSchedules() {
        List<Account> accounts = enabledAccounts();
        log.info("电影排期同步开始 accounts={}", accounts.size());
        if (accounts.isEmpty()) {
            return;
        }
        syncLock.execute(MovieSyncScope.SCHEDULE, guard -> {
            schedules.verifyStorage();
            SyncSummary summary = new SyncSummary("schedule");
            for (Account account : accounts) {
                long afterId = 0;
                while (true) {
                    guard.run();
                    List<MovieCinemaMappingDO> page = masterData.cinemas(account.getChannel(), afterId);
                    if (page.isEmpty()) {
                        if (afterId == 0) {
                            summary.failure(account, "cinemas", new MissingMovieMappingException("排期同步前请先初始化渠道影院映射"));
                        }
                        break;
                    }
                    for (MovieCinemaMappingDO mapping : page) {
                        guard.run();
                        summary.run(account, "schedule:" + mapping.getSupplierCinemaId(), () -> syncCinemaSchedule(account, mapping, guard));
                        afterId = mapping.getId();
                    }
                }
            }
            summary.finish();
        });
    }

    /** 完整解析及映射成功后才触发 MongoDB 替换；同影院影片缓存避免每个场次重复查 MySQL。 */
    private void syncCinemaSchedule(Account account, MovieCinemaMappingDO mapping, Runnable guard) {
        CinemaScheduleRepository.Refresh refresh = schedules.beginRefresh(account, mapping.getSupplierCinemaId(), guard);
        MovieCinemaDO cinema = masterData.cinema(mapping);
        MovieCityDO city = masterData.city(cinema.getStandardCityId());
        if (!Integer.valueOf(ENABLED).equals(mapping.getStatus()) || !Integer.valueOf(ENABLED).equals(cinema.getStatus())
                || !Integer.valueOf(ENABLED).equals(city.getStatus())) {
            guard.run();
            schedules.replace(account, mapping.getSupplierCinemaId(), List.of(), refresh, guard);
            return;
        }
        List<Schedule> source = client(account).schedules(account, mapping.getSupplierCinemaId(), cinema.getCinemaCode());
        List<Document> snapshot = new ArrayList<>();
        Map<String, MovieFilmDO> films = new HashMap<>();
        Set<String> showIds = new HashSet<>();
        for (Schedule schedule : source) {
            if (!showIds.add(schedule.showId())) {
                throw new IllegalStateException("供应商排期响应存在重复 showId");
            }
            if (!schedule.stopSellTime().isAfter(movieSupplyClock.instant())) {
                continue;
            }
            MovieFilmDO film = films.computeIfAbsent(schedule.supplierFilmId(), id -> {
                guard.run();
                return masterData.resolveFilm(account.getChannel(), id, schedule.filmCode());
            });
            if (schedule.filmCode() != null && !schedule.filmCode().equals(film.getFilmCode())) {
                throw new IllegalStateException("同一供应商影片在排期内对应多个专资编码");
            }
            if (!Integer.valueOf(ENABLED).equals(film.getStatus())) {
                continue;
            }
            snapshot.add(documents.create(account, mapping.getSupplierCinemaId(), schedule, city, cinema, film));
        }
        guard.run();
        schedules.replace(account, mapping.getSupplierCinemaId(), snapshot, refresh, guard);
    }

    /** 城市源先 JC 后 JY，账号内部顺序稳定；仅处理明确启用的账号。 */
    private List<Account> enabledAccounts() {
        return properties.getAccounts().stream().filter(Account::isEnabled)
                .sorted(Comparator.comparingInt((Account account) -> account.getChannel() == MovieChannel.JC ? 0 : 1)
                        .thenComparing(Account::getAccountRef)).toList();
    }

    /** 每个渠道必须恰好注册一个适配器，不做隐式降级或换路。 */
    private MovieSupplyClient client(Account account) {
        List<MovieSupplyClient> matches = clients.stream().filter(client -> client.channel() == account.getChannel()).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("电影渠道适配器注册异常：" + account.getChannel());
        }
        return matches.getFirst();
    }

    /**
     * 每轮只输出一次汇总，最多保留十条非敏感错误上下文和首个原因，防止全国影院失败日志风暴。
     * 捕获异常用于继续其它独立数据项，本轮结束仍明确抛出失败，不吞异常或报假成功。
     */
    private static final class SyncSummary {
        /** 失败示例与因果链数量上限。 */
        private static final int MAX_SAMPLES = 10;
        /** 当前同步阶段。 */
        private final String stage;
        /** 单轮耗时用于容量评估，不能以 fixedDelay 代替全国刷新时长。 */
        private final long started = System.nanoTime();
        /** 聚合计数，不保存全国失败报文。 */
        private int success;
        private int failed;
        private int unmapped;
        /** 有界错误示例和第一个异常原因。 */
        private final List<String> samples = new ArrayList<>();
        private RuntimeException firstFailure;

        private SyncSummary(String stage) {
            this.stage = stage;
        }

        /** 执行互相独立的数据项，失败留待本轮结尾统一抛出。 */
        void run(Account account, String scope, Runnable action) {
            try {
                action.run();
                success++;
            } catch (RuntimeException exception) {
                failure(account, scope, exception);
            }
        }

        /** 保存安全上下文，不把 HTTP 签名、响应全文或异常 message 写入高频日志。 */
        void failure(Account account, String scope, RuntimeException exception) {
            if (exception instanceof MovieSyncAbortedException || Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            failed++;
            if (exception instanceof MissingMovieMappingException) {
                unmapped++;
            }
            if (firstFailure == null) {
                firstFailure = exception;
            }
            if (samples.size() < MAX_SAMPLES) {
                String safeScope = scope.replaceAll("[\\r\\n\\t]", "_");
                samples.add(account.getAccountRef() + "/" + safeScope.substring(0, Math.min(safeScope.length(), 128))
                        + "/" + exception.getClass().getSimpleName());
                if (firstFailure != exception) {
                    firstFailure.addSuppressed(exception);
                }
            }
        }

        /** 每轮汇总一次并将失败返回调度器，便于任务监控识别部分失败。 */
        void finish() {
            log.info("电影同步结束 stage={}, success={}, failed={}, unmapped={}, elapsedMs={}, samples={}",
                    stage, success, failed, unmapped, (System.nanoTime() - started) / 1_000_000, samples);
            if (failed > 0) {
                throw new IllegalStateException("电影同步部分失败 stage=" + stage + ", failed=" + failed + ", samples=" + samples, firstFailure);
            }
        }
    }
}
