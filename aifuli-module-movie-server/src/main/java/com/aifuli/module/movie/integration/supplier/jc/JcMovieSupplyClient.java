package com.aifuli.module.movie.integration.supplier.jc;

import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.*;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.aifuli.module.movie.integration.supplier.SupplierJson.*;

/**
 * JC 供给协议，以供应商文档定义 filmId 专资编码及影院原始 ID，旧代码仅补充线协议参考。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#3-主数据映射
 */
@Component
@RequiredArgsConstructor
public class JcMovieSupplyClient implements MovieSupplyClient {
    /** JC 公共接口约定电影服务类型为 0。 */
    private static final String MOVIE_SERVICE_TYPE = "0";
    /** 旧 DTO 明确：仅特惠不可用，不影响快票。 */
    private static final int SPECIAL_DISABLED = 1;
    /** JC API §4.4 明确每页固定 1000 条。 */
    private static final int CINEMA_PAGE_SIZE = 1000;
    /** 签名传输客户端。 */
    private final MovieSupplierHttpClient http;
    /** 分页防护配置。 */
    private final MovieSupplyProperties properties;

    @Override
    public MovieChannel channel() {
        return MovieChannel.JC;
    }

    /** cityCode 是渠道城市 ID，adCode 才是行政区划码。 */
    @Override
    public List<City> cities(Account account) {
        return array(http.request(account, "/getAllCity", Map.of("type", MOVIE_SERVICE_TYPE))).stream()
                .map(row -> new City(required(row, "cityCode"), required(row, "adCode"), required(row, "cityName"),
                        text(row, "firstLetter"), integer(row, "ishot"))).toList();
    }

    /** JC API §4.1/4.2 明确 filmId 是专资编码；未取得 ID 的待映记录交主数据层逐条报告。 */
    @Override
    public List<Film> films(Account account, boolean presell) {
        return array(http.request(account, presell ? "/getFutureMovies" : "/getMovies", Map.of())).stream()
                .map(row -> {
                    String id = text(row, "filmId");
                    return new Film(id, id, required(row, "filmName"), text(row, "pic"),
                            integer(row, "duration"), date(row, "publishDate"), text(row, "director"), text(row, "actor"),
                            text(row, "intro"), text(row, "filmTypes"), decimal(row, "score"), presell);
                }).toList();
    }

    /** 依据固定页容量识别短页；兼容文档 data.data 和历史 data 数组，禁止泛化解包。 */
    @Override
    public List<Cinema> cinemas(Account account, String supplierCityId) {
        List<Cinema> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int page = 1; page <= properties.getMaxPages(); page++) {
            JsonNode data = http.request(account, "/getCinemas",
                    Map.of("cityId", supplierCityId, "page", Integer.toString(page)));
            if (data.isObject()) {
                JsonNode success = data.get("success");
                if (success != null && !((success.isBoolean() && success.booleanValue())
                        || (success.isTextual() && "true".equals(success.textValue())))) {
                    throw new MovieSupplierException("JC 影院内层响应不是成功结果");
                }
                data = data.get("data");
            }
            List<JsonNode> rows = array(data);
            if (rows.size() > CINEMA_PAGE_SIZE) {
                throw new MovieSupplierException("JC 影院返回数量超过固定页容量");
            }
            for (JsonNode row : rows) {
                String id = required(row, "cinemaId");
                if (!ids.add(id)) {
                    throw new MovieSupplierException("JC 影院分页重复，不能确认全量结果");
                }
                String code = text(row, "standardId");
                result.add(new Cinema(id, code, required(row, "cityId"), required(row, "cinemaName"),
                        text(row, "areaName"), required(row, "address"), decimal(row, "longitude"),
                        decimal(row, "latitude"), text(row, "tel")));
            }
            if (rows.size() < CINEMA_PAGE_SIZE) {
                return result;
            }
        }
        throw new MovieSupplierException("JC 影院分页超过配置上限");
    }

    /** 展开影片→日期→场次；私有影院 ID 用于请求，standardId 用于返回归属校验。 */
    @Override
    public List<Schedule> schedules(Account account, String supplierCinemaId, String cinemaCode) {
        List<Schedule> result = new ArrayList<>();
        for (JsonNode film : array(http.request(account, "/getShows",
                Map.of("cinemaId", supplierCinemaId, "deliverMinutes", account.getDeliverMinutes())))) {
            String responseCinema = text(film, "standardId");
            if (responseCinema != null && !cinemaCode.equals(responseCinema)) {
                throw new MovieSupplierException("JC 返回了其它影院的排期");
            }
            for (JsonNode date : array(film.get("dates"))) {
                for (JsonNode row : array(date.get("list"))) {
                    Integer disableFlag = integer(row, "disableFlag");
                    String filmId = required(film, "filmId");
                    // JC API §4.7 明确此 filmId 即专资编码，不套用 JY 内部影片 ID 的语义。
                    ObjectNode quickPrices = JsonNodeFactory.instance.objectNode();
                    for (String field : List.of("settlementQuickPrice", "minSettlementQuickPrice", "quickPrice", "minQuickPrice")) {
                        if (row.has(field)) {
                            quickPrices.set(field, row.get(field));
                        }
                    }
                    result.add(new Schedule(required(row, "showId"), filmId, filmId,
                            text(row, "hallId"), required(row, "hallName"), text(row, "version"),
                            instant(row, "startTime"), instant(row, "endTime"), instant(row, "stopPurchase"),
                            money(row, "showPrice"), Integer.valueOf(SPECIAL_DISABLED).equals(disableFlag)
                                    || text(row, "minSettlementPrice") == null ? null : money(row, "minSettlementPrice"),
                            disableFlag, row.get("areas"), row.get("priceOption"), quickPrices));
                }
            }
        }
        return result;
    }
}
