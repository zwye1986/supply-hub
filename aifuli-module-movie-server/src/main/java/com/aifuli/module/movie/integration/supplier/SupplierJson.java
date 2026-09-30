package com.aifuli.module.movie.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 严格读取供应商响应，关键字段缺失不能默认为零价、当前时间或空供给。 */
public final class SupplierJson {
    /** 参考协议的时间均为北京时间。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private SupplierJson() {
    }

    /** 读取可空文本字段；允许供应商将 ID 作为整数发送。 */
    public static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new MovieSupplierException("供应商记录不是对象");
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() && !value.isNumber()) {
            throw new MovieSupplierException("字段不是文本或数值：" + field);
        }
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    /** 关键业务字段必须存在。 */
    public static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new MovieSupplierException("供应商响应缺少字段：" + field);
        }
        return value;
    }

    /** 只接受真正的数组，缺失或对象不等于空列表。 */
    public static List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new MovieSupplierException("供应商列表结构不是数组");
        }
        List<JsonNode> result = new ArrayList<>(node.size());
        node.forEach(result::add);
        return result;
    }

    /** 可空整数，用于非价格属性。 */
    public static Integer integer(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value).intValueExact();
    }

    /** 可空十进制，不经 double 中转。 */
    public static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    /** 价格必须非负且能精确表示为人民币分，不自动四舍五入供应商数据。 */
    public static BigDecimal money(JsonNode node, String field) {
        BigDecimal value = new BigDecimal(required(node, field)).setScale(2, RoundingMode.UNNECESSARY);
        if (value.signum() < 0) {
            throw new MovieSupplierException("供应商返回负金额：" + field);
        }
        return value;
    }

    /** 可空公映日期，兼容参考接口携带的时间后缀。 */
    public static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    }

    /** 解析北京时间字符串，包括秒的小数部分。 */
    public static Instant instant(JsonNode node, String field) {
        return LocalDateTime.parse(required(node, field).replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZONE).toInstant();
    }
}
