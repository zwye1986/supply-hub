package com.aifuli.module.movie.service.sync;

import com.aifuli.module.movie.dal.dataobject.mongo.CinemaScheduleDocument;
import com.aifuli.module.movie.enums.MovieChannel;
import com.aifuli.module.movie.dal.dataobject.mysql.MovieCinemaDO;
import com.aifuli.module.movie.dal.dataobject.mysql.MovieCityDO;
import com.aifuli.module.movie.dal.dataobject.mysql.MovieFilmDO;
import com.aifuli.module.movie.framework.supply.MovieSupplyProperties.Account;
import com.aifuli.module.movie.integration.supplier.SupplierMovieData.Schedule;
import com.aifuli.module.movie.integration.supplier.SupplierJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 排期领域转换：校验时间、聚合候选场次并按已知协议转换金额，不执行数据库操作。
 * 未知供应商字段保留类型，不根据字段子串推断价格，避免误改选项 ID 和档位。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#73-协议转换请求容量与诊断
 */
@Component
@RequiredArgsConstructor
public class MovieScheduleConverter {
    /** JC 特惠购票禁用标记，快票有效性由 priceOption 决定。 */
    private static final int JC_SPECIAL_DISABLED = 1;
    /** DTO 与总体设计明确列出的金额字段，未知字段不扩展猜测规则。 */
    private static final Set<String> JC_MONEY_FIELDS = Set.of("serviceFee", "salePrice", "minSalePrice",
            "minQuickPrice", "minSettlementPrice", "settlementPrice", "settlementQuickPrice",
            "minShowPrice", "quickPrice", "showPrice", "minSettlementQuickPrice", "settlePrice",
            "settlementPrice_8", "settlementPrice_15", "settlementPrice_30");
    /** JY 分区价由适配器按专有分隔格式展开；不把 JC 字段规则套在 JY 上。 */
    private static final Set<String> JY_MONEY_FIELDS = Set.of("price", "settle_price");
    /** 解析供应商嵌套 JSON 字符串的映射器。 */
    private final ObjectMapper objectMapper;

    /** 创建不含审计时间的业务快照，审计时间由仓库 upsert 原子维护。 */
    public Document create(Account account, String supplierCinemaId, Schedule source,
                           MovieCityDO city, MovieCinemaDO cinema, MovieFilmDO film) {
        if (source.closeTime().isBefore(source.showTime()) || source.stopSellTime().isAfter(source.showTime())) {
            throw new IllegalArgumentException("供应商排期的开场、散场或停售时间顺序异常");
        }
        String hall = Normalizer.normalize(source.hallName(), Normalizer.Form.NFKC)
                .replaceAll("[\\p{P}\\p{S}\\p{Z}\\s]", "");
        if (hall.isEmpty()) {
            throw new IllegalArgumentException("影厅名称归一后为空");
        }
        String groupSource = CinemaScheduleDocument.key(cinema.getCinemaCode(), film.getFilmCode(), hall,
                source.showTime().toString());
        String id = CinemaScheduleDocument.key(account.getChannel().code(), account.getAccountRef(),
                supplierCinemaId, source.showId());
        BigDecimal cost = account.getChannel() == MovieChannel.JC
                && Integer.valueOf(JC_SPECIAL_DISABLED).equals(source.disableFlag()) ? null : source.minSettlementPrice();
        Set<String> moneyFields = account.getChannel() == MovieChannel.JC ? JC_MONEY_FIELDS : JY_MONEY_FIELDS;
        return new Document("_id", id)
                .append("schemaVersion", CinemaScheduleDocument.SCHEMA_VERSION)
                .append("channel", account.getChannel().code())
                .append("connectorAccountRef", account.getAccountRef()).append("configVersion", account.getConfigVersion())
                .append("supplierShowId", source.showId()).append("supplierCinemaId", supplierCinemaId)
                .append("supplierFilmId", source.supplierFilmId()).append("supplierHallId", source.hallId())
                .append("showGroupId", md5(groupSource))
                .append("cityCode", city.getCityCode()).append("cityName", city.getCityName())
                .append("standardCinemaId", cinema.getId()).append("cinemaCode", cinema.getCinemaCode())
                .append("cinemaName", cinema.getCinemaName()).append("standardFilmId", film.getId())
                .append("filmCode", film.getFilmCode()).append("filmName", film.getFilmName()).append("posterUrl", film.getPosterUrl())
                .append("hallName", source.hallName()).append("versionType", source.versionType())
                .append("showDate", source.showTime().atZone(SupplierJson.ZONE).toLocalDate().toString())
                .append("showTime", Date.from(source.showTime())).append("closeTime", Date.from(source.closeTime()))
                .append("stopSellTime", Date.from(source.stopSellTime())).append("netPrice", amount(source.netPrice()))
                .append("minSettlementPrice", cost == null ? null : amount(cost)).append("disableFlag", source.disableFlag())
                .append("areas", structured(source.areas(), "areas", moneyFields))
                .append("priceOption", structured(source.priceOption(), "priceOption", moneyFields))
                .append("quickPrices", structured(source.quickPrices(), "quickPrices", moneyFields));
    }

    /** 仅解包协议约定的顶层 JSON；普通标量不是有效分区或选项，必须保留旧快照并报错。 */
    private Object structured(JsonNode value, String key, Set<String> moneyFields) {
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        if (value.isTextual()) {
            try {
                value = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature()).readTree(value.asText());
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("供应商分区或快票 JSON 无效", exception);
            }
        }
        if (value == null || (!value.isObject() && !value.isArray())) {
            throw new IllegalArgumentException("供应商分区或快票字段必须是对象或数组");
        }
        return nested(value, key, moneyFields);
    }

    /** 保留未知字段的原始类型，只转换白名单金额；不递归解包普通字符串。 */
    private Object nested(JsonNode value, String key, Set<String> moneyFields) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            Document result = new Document();
            value.fields().forEachRemaining(entry -> result.append(entry.getKey(), nested(entry.getValue(), entry.getKey(), moneyFields)));
            return result;
        }
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(element -> result.add(nested(element, key, moneyFields)));
            return result;
        }
        if (moneyFields.contains(key) || ("priceOption".equals(key) && value.isNumber())) {
            if (!value.isNumber() && !value.isTextual()) {
                throw new IllegalArgumentException("供应商金额字段类型错误");
            }
            return amount(new BigDecimal(value.asText()));
        }
        if (value.isFloatingPointNumber()) {
            return new Decimal128(value.decimalValue());
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue().longValueExact();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    /** 所有平台金额统一人民币元两位精度，负金额和超精度均中止快照替换。 */
    private static Decimal128 amount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("排期金额缺失或为负");
        }
        return new Decimal128(value.setScale(2, RoundingMode.UNNECESSARY));
    }

    /** 仅用于物理场次聚合，不承担凭证或安全签名职责。 */
    private static String md5(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 MD5 算法", exception);
        }
    }
}
