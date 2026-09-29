package com.aifuli.module.movie.integration.supplier.lx;

import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.MovieSupplierException;
import com.aifuli.module.movie.integration.supplier.MovieSupplierHttpClient;
import com.aifuli.module.movie.integration.supplier.MovieSupplyClient;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.module.movie.integration.supplier.SupplierJson.*;

/**
 * 影乐星只读供给适配；渠道原始 ID 与专资编码分离，全国影院每轮只采集一次。
 * 任何协议错误向上传递，由同步编排汇总，不能作为成功空排期清理旧快照。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#8-lx-影乐星渠道接入
 */
@Component
@RequiredArgsConstructor
public class LxMovieSupplyClient implements MovieSupplyClient {
    /** 影乐星影片查询状态，热映与待映分别同步。 */
    private static final String HOT_FILMS = "1";
    private static final String SOON_FILMS = "2";
    /** 含签名、账号限流、gzip 和脱敏调用日志的通道。 */
    private final MovieSupplierHttpClient http;
    /** 仅用于解包文档允许的分区 JSON 数组字符串。 */
    private final ObjectMapper objectMapper;

    /** 注册影乐星渠道适配器。 */
    @Override
    public MovieChannel channel() {
        return MovieChannel.LX;
    }

    /** 城市行政区编码与渠道城市 ID 独立保存，不使用区县编码冒充城市。 */
    @Override
    public List<City> cities(Account account) {
        return array(http.request(account, "/openapi/getCityList", Map.of())).stream()
                .map(row -> new City(required(row, "city_id"), required(row, "region_code"),
                        required(row, "region_name"), text(row, "pin_yin"), null)).toList();
    }

    /** 两类影片接口只改变 status_type，缺专资码交给既有主数据映射规则处理。 */
    @Override
    public List<Film> films(Account account, boolean presell) {
        return array(http.request(account, "/openapi/getFilmList",
                Map.of("status_type", presell ? SOON_FILMS : HOT_FILMS))).stream()
                .map(row -> new Film(required(row, "id"), text(row, "film_code"), required(row, "name"),
                        text(row, "pic"), integer(row, "duration"), date(row, "publish_date"),
                        text(row, "director"), text(row, "cast"), text(row, "intro"), text(row, "film_types"),
                        decimal(row, "grade"), presell)).toList();
    }

    /** 声明全国采集能力，编排层据此避免逐城市重复请求全国数据。 */
    @Override
    public boolean supportsAllCinemas() {
        return true;
    }

    /** 单城市调用保持端口语义；批量同步必须使用 allCinemas。 */
    @Override
    public List<Cinema> cinemas(Account account, String supplierCityId) {
        return allCinemas(account).stream().filter(cinema -> supplierCityId.equals(cinema.supplierCityId())).toList();
    }

    /** 先完整校验全国列表与重复 ID，再交给主数据服务逐影院独立事务处理。 */
    @Override
    public List<Cinema> allCinemas(Account account) {
        List<Cinema> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : array(http.request(account, "/openapi/getCinemaList", Map.of()))) {
            String id = required(row, "cinema_id");
            if (!ids.add(id)) {
                throw new MovieSupplierException("LX 全国影院列表存在重复影院 ID");
            }
            result.add(new Cinema(id, text(row, "standard_id"), required(row, "city_id"),
                    required(row, "cinema_name"), text(row, "county"), required(row, "address"),
                    decimal(row, "longitude"), decimal(row, "latitude"), null));
        }
        return result;
    }

    /**
     * 排期按影院专资码请求，同时校验渠道影院 ID；含税 price 才是结算成本。
     * LX 无散场字段，按正整数影片时长推算；停售由统一平台策略计算。
     * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#83-排期映射与统一动态停售规则
     */
    @Override
    public List<Schedule> schedules(Account account, String supplierCinemaId, String cinemaCode) {
        return array(http.request(account, "/openapi/getPlanList", Map.of("standard_id", cinemaCode))).stream()
                .map(row -> {
                    if (!cinemaCode.equals(required(row, "standard_id"))
                            || !supplierCinemaId.equals(required(row, "cinema_id"))) {
                        throw new MovieSupplierException("LX 返回了其它影院的排期");
                    }
                    Integer duration = integer(row, "duration");
                    if (duration == null || duration <= 0) {
                        throw new MovieSupplierException("LX 排期影片时长缺失或不是正整数");
                    }
                    Instant showTime = instant(row, "show_time");
                    return new Schedule(required(row, "show_id"), required(row, "film_id"), text(row, "film_code"),
                            null, required(row, "hall_name"), text(row, "show_version_type"),
                            showTime, showTime.plus(duration, ChronoUnit.MINUTES),
                            money(row, "original_price"), money(row, "price"), null, areas(row), null, null);
                }).toList();
    }

    /** 严格验证分区数组及必要价格，分区 ID 原样保留，未知字段不推断用途。 */
    private JsonNode areas(JsonNode row) {
        JsonNode value = row.get("schedule_area");
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        if (value.isTextual()) {
            try {
                value = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature()).readTree(value.asText());
            } catch (JsonProcessingException exception) {
                throw new MovieSupplierException("LX 分区价格不是有效 JSON 数组", exception);
            }
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode area : array(value)) {
            if (!ids.add(required(area, "area"))) {
                throw new MovieSupplierException("LX 分区价格包含重复分区 ID");
            }
            money(area, "original_price");
            money(area, "price");
            // 不含税参考价仅在上游提供时校验，不能取代含税结算成本。
            for (String field : List.of("nax_price", "nax_price_st")) {
                if (area.has(field)) {
                    money(area, field);
                }
            }
        }
        return value;
    }
}
