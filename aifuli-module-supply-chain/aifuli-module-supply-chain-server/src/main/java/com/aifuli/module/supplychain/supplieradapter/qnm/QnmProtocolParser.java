package com.aifuli.module.supplychain.supplieradapter.qnm;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 奇那梦协议通用解析工具：集中管理金额转换、卡密数组提取与日期解析，确保适配器与回调解析完全对齐。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#101-奇那梦qnmgameorder-v1-协议实现规约}。</p>
 */
@Slf4j
public final class QnmProtocolParser {

    private static final DateTimeFormatter STANDARD_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ISO_DATE_TIME;

    private QnmProtocolParser() {
    }

    /**
     * 判断 HTTP 状态码是否属于瞬时网络/网关/服务端错误（需映射为 RESULT_UNKNOWN）。
     *
     * @param statusCode HTTP 状态码
     * @return true 若为 5xx、408 请求超时或 429 限流
     */
    public static boolean isTransientError(Integer statusCode) {
        if (statusCode == null) {
            return false;
        }
        return statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    /**
     * 判断是否为订单不存在事实。
     *
     * @param statusCode HTTP 状态码
     * @param resCode    响应业务码
     * @param resMsg     响应描述
     * @return true 若订单在供应商侧不存在
     */
    public static boolean isOrderNotFound(Integer statusCode, String resCode, String resMsg) {
        if (statusCode != null && statusCode == 404) {
            return true;
        }
        if ("404".equals(resCode) || "-404".equals(resCode)) {
            return true;
        }
        if (resMsg != null) {
            String lower = resMsg.toLowerCase();
            return lower.contains("不存在") || lower.contains("未找到") || lower.contains("not found");
        }
        return false;
    }

    /**
     * 解析供应商报告结算金额（统一转换为“分”单位）。
     *
     * @param node JSON 节点
     * @return 报告金额（分），解析失败或缺失返回 null
     */
    public static Long parseSumPrice(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return new BigDecimal(node.asText().trim()).multiply(new BigDecimal("100")).longValue();
        }
        return parseSumPrice(node.asText(null));
    }

    /**
     * 解析金额字符串（支持带小数点的“元”转换为“分”或纯整数“分”）。
     *
     * @param text 金额文本
     * @return 报告金额（分），解析失败或缺失返回 null
     */
    public static Long parseSumPrice(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            if (trimmed.contains(".")) {
                BigDecimal bd = new BigDecimal(trimmed);
                return bd.multiply(new BigDecimal("100")).longValue();
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse sumPrice string: {}", text, e);
            return null;
        }
    }

    /**
     * 解析卡密数组 JSON 字符串。
     *
     * @param cardsJson    JSON 数组字符串
     * @param objectMapper 对象映射器
     * @return 解析出的资产事实列表
     */
    public static List<OperationAssetFact> parseCards(String cardsJson, ObjectMapper objectMapper) {
        if (cardsJson == null || cardsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(cardsJson);
            return parseCards(root, objectMapper);
        } catch (Exception e) {
            log.debug("Failed to parse cards JSON string, length={}", cardsJson.length(), e);
            return List.of();
        }
    }

    /**
     * 解析卡密数组 JSON 节点。
     *
     * @param cardsNode    JSON 节点（可能为 ArrayNode 或文本形式的 ArrayNode）
     * @param objectMapper 对象映射器
     * @return 解析出的资产事实列表
     */
    public static List<OperationAssetFact> parseCards(JsonNode cardsNode, ObjectMapper objectMapper) {
        if (cardsNode == null || cardsNode.isMissingNode() || cardsNode.isNull()) {
            return List.of();
        }
        if (cardsNode.isTextual()) {
            try {
                cardsNode = objectMapper.readTree(cardsNode.asText());
            } catch (Exception e) {
                log.debug("Failed to unwrap cards textual node, length={}", cardsNode.asText().length(), e);
                return List.of();
            }
        }
        if (!cardsNode.isArray()) {
            return List.of();
        }

        List<OperationAssetFact> facts = new ArrayList<>();
        int seq = 1;
        for (JsonNode item : cardsNode) {
            if (item.isTextual()) {
                // 处理纯字符串元素（如 ["PWD123", "PWD456"] 或 ["CARD1,PWD1"]）
                String text = item.asText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                String cardNo = null;
                String cardSecret = text;
                LocalDateTime validUntil = null;
                if (text.contains(",")) {
                    String[] parts = text.split(",");
                    cardNo = parts[0].trim();
                    cardSecret = parts.length > 1 ? parts[1].trim() : null;
                    if (parts.length > 2) {
                        validUntil = parseDateTime(parts[2].trim());
                    }
                } else if (text.contains("|")) {
                    String[] parts = text.split("\\|");
                    cardNo = parts[0].trim();
                    cardSecret = parts.length > 1 ? parts[1].trim() : null;
                    if (parts.length > 2) {
                        validUntil = parseDateTime(parts[2].trim());
                    }
                } else if (text.contains(":") && !text.startsWith("http")) {
                    String[] parts = text.split(":");
                    cardNo = parts[0].trim();
                    cardSecret = parts.length > 1 ? parts[1].trim() : null;
                    if (parts.length > 2) {
                        validUntil = parseDateTime(parts[2].trim());
                    }
                }
                facts.add(OperationAssetFact.builder()
                        .sequenceNo(seq++)
                        .cardNo(cardNo)
                        .cardSecret(cardSecret)
                        .validUntil(validUntil)
                        .build());
            } else if (item.isObject()) {
                String cardNo = extractText(item, "cardNo", "card_no", "cardno", "card", "cardNumber", "card_number", "no");
                String cardSecret = extractText(item, "cardPwd", "card_pwd", "cardpwd", "cardSecret", "card_secret", "password", "pwd", "secret");
                String externalAssetId = extractText(item, "cardId", "card_id", "cardid", "assetId", "asset_id", "id");
                String startStr = extractText(item, "validFrom", "valid_from", "startTime", "start_time", "beginTime", "begin_time");
                String expireStr = extractText(item, "expireTime", "expire_time", "expireDate", "validUntil", "validTo", "endTime", "end_time");
                LocalDateTime validFrom = parseDateTime(startStr);
                LocalDateTime validUntil = parseDateTime(expireStr);

                if ((cardNo != null && !cardNo.isBlank()) || (cardSecret != null && !cardSecret.isBlank()) || (externalAssetId != null && !externalAssetId.isBlank())) {
                    facts.add(OperationAssetFact.builder()
                            .sequenceNo(seq++)
                            .externalAssetId(externalAssetId)
                            .cardNo(cardNo)
                            .cardSecret(cardSecret)
                            .validFrom(validFrom)
                            .validUntil(validUntil)
                            .build());
                }
            }
        }
        return List.copyOf(facts);
    }

    /**
     * 提取响应结果业务码，兼容 ResCode、resCode、res_code、code。
     */
    public static String extractResCode(JsonNode root) {
        if (root == null) {
            return "";
        }
        String code = extractText(root, "ResCode", "resCode", "res_code", "code");
        return code != null ? code : "";
    }

    /**
     * 提取响应结果描述，兼容 ResMsg、resMsg、res_msg、msg、message。
     */
    public static String extractResMsg(JsonNode root) {
        if (root == null) {
            return "";
        }
        String msg = extractText(root, "ResMsg", "resMsg", "res_msg", "msg", "message");
        return msg != null ? msg : "";
    }

    /**
     * 提取供应商订单号，支持多种大小写与下划线别名（orderSn、order_sn、ordersn、order_no、orderno）。
     */
    public static String extractOrderSn(JsonNode root) {
        if (root == null) {
            return null;
        }
        return extractText(root, "orderSn", "order_sn", "ordersn", "ordersN", "order_no", "orderno");
    }

    /**
     * 提取 Map 表单中的供应商订单号，支持多种别名及大小写容错。
     */
    public static String extractOrderSn(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        for (String key : new String[]{"orderSn", "order_sn", "ordersn", "ordersN", "order_no", "orderno"}) {
            String val = params.get(key);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String k = entry.getKey();
            if (k != null && (k.equalsIgnoreCase("orderSn") || k.equalsIgnoreCase("order_sn")
                    || k.equalsIgnoreCase("ordersn") || k.equalsIgnoreCase("order_no") || k.equalsIgnoreCase("orderno"))) {
                String val = entry.getValue();
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    /**
     * 提取主动查单中的订单状态编码，兼容 orderStatus、order_status 与 status（含 PascalCase）。
     *
     * @param root JSON 根节点
     * @return 状态码整数值，不存在或格式异常返回 null
     */
    public static Integer extractOrderStatus(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String key : new String[]{"orderStatus", "order_status", "status", "OrderStatus", "Order_Status", "Status"}) {
            JsonNode node = root.get(key);
            if (node != null && !node.isNull()) {
                if (node.isNumber()) {
                    return node.asInt();
                }
                String text = node.asText().trim();
                if (!text.isEmpty()) {
                    try {
                        return Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse orderStatus text: {}", text, e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析时间文本（支持 yyyy-MM-dd HH:mm:ss 与 ISO 8601 格式）。
     */
    public static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            if (trimmed.contains("T")) {
                return LocalDateTime.parse(trimmed, ISO_DATETIME);
            }
            return LocalDateTime.parse(trimmed, STANDARD_DATETIME);
        } catch (DateTimeParseException e) {
            log.debug("Failed to parse date time: {}", text);
            return null;
        }
    }

    private static String extractText(JsonNode node, String... candidateKeys) {
        for (String key : candidateKeys) {
            JsonNode val = node.get(key);
            if (val != null && !val.isNull() && !val.asText().isBlank()) {
                return val.asText().trim();
            }
        }
        return null;
    }
}
