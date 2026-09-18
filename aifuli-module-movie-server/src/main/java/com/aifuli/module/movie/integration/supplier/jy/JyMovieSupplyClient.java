package com.aifuli.module.movie.integration.supplier.jy;

import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.*;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * JY 主数据与基础排期适配，字段依据 qihaofu 的 MovieUtils 和电影 DTO。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#2-接入账号与协议
 */
@Component
@RequiredArgsConstructor
public class JyMovieSupplyClient implements MovieSupplyClient {
    /** JY API §1.4 给定的相对海报 CDN 与示例尺寸。 */
    private static final String POSTER_CDN = "https://gw.alicdn.com/tfscom/";
    private static final String POSTER_SIZE = "_80x80.jpg";
    /** 已封装签名与传输的客户端。 */
    private final MovieSupplierHttpClient http;
    /** 分页防护配置。 */
    private final MovieSupplyProperties properties;

    @Override
    public MovieChannel channel() {
        return MovieChannel.JY;
    }

    /** JY 不伪造行政区划码，由主数据层按照参考项目规则映射。 */
    @Override
    public List<City> cities(Account account) {
        return array(http.request(account, "/api/city/list", Map.of())).stream()
                .map(row -> new City(required(row, "city_id"), null, required(row, "city_name"),
                        text(row, "first_letter"), integer(row, "is_hot"))).toList();
    }

    /** 根据总条数完成分页；重复 ID 或提前空页视为不完整响应。 */
    @Override
    public List<Film> films(Account account, boolean presell) {
        List<Film> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        // 冻结第一页的总数；分页期间发生变化时重跑，不能把缩小后的 total 当成完整结果。
        Long expectedTotal = null;
        String path = presell ? "/api/film/soon/list" : "/api/film/hot/list";
        for (int page = 1; page <= properties.getMaxPages(); page++) {
            JsonNode data = http.request(account, path, Map.of("current", Integer.toString(page)));
            Integer current = integer(data, "current");
            if (current != null && current != page) {
                throw new MovieSupplierException("JY 影片返回页码与请求不一致");
            }
            long total = new java.math.BigDecimal(required(data, "total")).longValueExact();
            if (total < 0) {
                throw new MovieSupplierException("JY 影片总条数无效");
            }
            if (expectedTotal == null) {
                expectedTotal = total;
            } else if (expectedTotal != total) {
                throw new MovieSupplierException("JY 影片分页期间 total 发生变化");
            }
            List<JsonNode> rows = array(data.get("records"));
            for (JsonNode row : rows) {
                String id = required(row, "film_id");
                if (!ids.add(id)) {
                    throw new MovieSupplierException("JY 影片分页重复");
                }
                result.add(new Film(id, text(row, "film_code"), required(row, "film_name"), poster(text(row, "pic")),
                        integer(row, "duration"), date(row, "publish_date"), text(row, "director"), text(row, "cast"),
                        text(row, "intro"), text(row, "film_type"), decimal(row, "score"), presell));
            }
            if (result.size() > total) {
                throw new MovieSupplierException("JY 影片记录数超过 total");
            }
            if (result.size() == total) {
                return result;
            }
            if (rows.isEmpty()) {
                throw new MovieSupplierException("JY 影片分页在达到 total 前返回空页");
            }
        }
        throw new MovieSupplierException("JY 影片分页超过配置上限");
    }

    /** 按渠道 city_id 拉取完整影院数组。 */
    @Override
    public List<Cinema> cinemas(Account account, String supplierCityId) {
        return array(http.request(account, "/api/cinema/batch/list", Map.of("city_id", supplierCityId))).stream()
                .map(row -> new Cinema(required(row, "cinema_id"), text(row, "cinema_code"),
                        required(row, "city_id"), required(row, "cinema_name"), text(row, "region_name"),
                        required(row, "address"), decimal(row, "longitude"), decimal(row, "latitude"), text(row, "phone"))).toList();
    }

    /** 拉排期时必须使用 cinema_code，不能误用 JY 内部 cinema_id。 */
    @Override
    public List<Schedule> schedules(Account account, String supplierCinemaId, String cinemaCode) {
        return array(http.request(account, "/api/show/batch/list", Map.of("cinema_code", cinemaCode))).stream()
                .map(row -> {
                    if (!cinemaCode.equals(required(row, "cinema_code"))) {
                        throw new MovieSupplierException("JY 返回了其它影院的排期");
                    }
                    return new Schedule(required(row, "show_id"), required(row, "film_id"), text(row, "film_code"),
                            text(row, "hallCode"), required(row, "hall_name"), text(row, "version_type"),
                            instant(row, "show_time"), instant(row, "close_time"), instant(row, "stop_sell_time"),
                            money(row, "net_price"), money(row, "settle_price"), null, areaPrices(row), null, null);
                }).toList();
    }

    /** 文档的相对海报路径需补全，已包含协议的地址不重复拼接。 */
    private static String poster(String source) {
        if (source == null || source.startsWith("https://") || source.startsWith("http://")) {
            return source;
        }
        return POSTER_CDN + source + POSTER_SIZE;
    }

    /**
     * JY §2.6 分区价是分号/逗号/冒号协议，不是 JSON；完整验证后才交给领域层转 BSON。
     * 保留分区 ID 原文，未知字段仍保留为字符串；重复或缺项不能被解释为无分区价。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#75-用户提供-api-文档后的契约校正
     */
    private static JsonNode areaPrices(JsonNode row) {
        String value = text(row, "area_price");
        if (value == null) {
            return null;
        }
        ArrayNode areas = JsonNodeFactory.instance.arrayNode();
        Set<String> ids = new HashSet<>();
        for (String part : value.split(";", -1)) {
            ObjectNode area = JsonNodeFactory.instance.objectNode();
            for (String entry : part.split(",", -1)) {
                String[] pair = entry.split(":", 2);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || area.has(pair[0].trim())) {
                    throw new MovieSupplierException("JY 分区价字段格式错误或重复");
                }
                area.put(pair[0].trim(), pair[1].trim());
            }
            String id = required(area, "area");
            if (!id.matches("[0-9]+") || !ids.add(id)) {
                throw new MovieSupplierException("JY 分区 ID 无效或重复");
            }
            area.put("price", money(area, "price"));
            area.put("settle_price", money(area, "settle_price"));
            areas.add(area);
        }
        return areas;
    }
}
