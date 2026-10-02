package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 项目标准 HTTP 请求专用封闭解析器。
 *
 * <p>直接对原始正文 byte[] 执行严格词法与语法分析，启用重复键检测，禁止未知字段、禁止尾随数据，
 * 严格限制整数值与纯字符串平铺映射，避免全局 Jackson 的强制类型转换、未知字段忽略或 XSS 清洗副作用。
 * 解析失败抛出固定的安全参数错误，不回显非法字段值或敏感信息。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。</p>
 */
@Slf4j
@Component
public class ProjectStandardHttpRequestParser {

    /** 通用字符串字段最大长度，与 buyerOrderNo 上限一致（上位设计 §3）。 */
    private static final int MAX_STRING_LENGTH = 128;

    /** buyerSkuCode 最大长度，见 docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md §3。 */
    private static final int MAX_SKU_CODE_LENGTH = 64;

    /** target 属性值最大长度：HTTP 层统一兜底上限，精确字段与格式由共享目标 Schema 校验决定。 */
    private static final int MAX_TARGET_VALUE_LENGTH = 128;

    /** 标准订单固定数量：本契约仅支持单件下单（上位设计 §3）。 */
    private static final int STANDARD_ORDER_QUANTITY = 1;

    private final JsonFactory jsonFactory = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * 严格解析标准下单请求正文。
     *
     * @param rawBody 原始正文字节数组
     * @return 封闭的 ProjectStandardOrderCreateReqDTO
     */
    public ProjectStandardOrderCreateReqDTO parseCreate(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw invalidParameter("正文为空");
        }
        try (JsonParser parser = jsonFactory.createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalidParameter("根节点必须为JSON对象");
            }

            String buyerOrderNo = null;
            String buyerSkuCode = null;
            Integer quantity = null;
            Map<String, String> target = null;
            Long previousReceiptVersion = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                parser.nextToken();

                switch (fieldName) {
                    case "buyerOrderNo" -> {
                        if (parser.currentToken() != JsonToken.VALUE_STRING) {
                            throw invalidParameter("buyerOrderNo 必须为字符串");
                        }
                        buyerOrderNo = parser.getText();
                        validateTextField(buyerOrderNo, MAX_STRING_LENGTH);
                    }
                    case "buyerSkuCode" -> {
                        if (parser.currentToken() != JsonToken.VALUE_STRING) {
                            throw invalidParameter("buyerSkuCode 必须为字符串");
                        }
                        buyerSkuCode = parser.getText();
                        // buyerSkuCode 上限 64 比通用字符串字段更严格（上位设计 §3）。
                        validateTextField(buyerSkuCode, MAX_SKU_CODE_LENGTH);
                    }
                    case "quantity" -> {
                        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                            throw invalidParameter("quantity 必须为严格整数");
                        }
                        quantity = parser.getIntValue();
                        if (quantity != STANDARD_ORDER_QUANTITY) {
                            throw invalidParameter("quantity 必须精确等于 1");
                        }
                    }
                    case "target" -> {
                        if (parser.currentToken() != JsonToken.START_OBJECT) {
                            throw invalidParameter("target 必须为对象");
                        }
                        target = new LinkedHashMap<>();
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String targetKey = parser.currentName();
                            parser.nextToken();
                            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                                throw invalidParameter("target 属性值必须全为字符串");
                            }
                            String targetValue = parser.getText();
                            validateTargetKeyValue(targetKey, targetValue);
                            target.put(targetKey, targetValue);
                        }
                        if (target.isEmpty()) {
                            throw invalidParameter("target 不能为空对象");
                        }
                    }
                    case "previousReceiptVersion" -> {
                        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                            throw invalidParameter("previousReceiptVersion 必须为正整数");
                        }
                        previousReceiptVersion = parser.getLongValue();
                        if (previousReceiptVersion <= 0) {
                            throw invalidParameter("previousReceiptVersion 必须为正整数");
                        }
                    }
                    default -> throw invalidParameter("包含未知或已废弃字段: " + fieldName);
                }
            }

            if (parser.nextToken() != null) {
                throw invalidParameter("存在尾随非法内容");
            }

            if (buyerOrderNo == null || buyerSkuCode == null || quantity == null || target == null) {
                throw invalidParameter("缺少必要请求字段");
            }

            ProjectStandardOrderCreateReqDTO dto = new ProjectStandardOrderCreateReqDTO();
            dto.setBuyerOrderNo(buyerOrderNo);
            dto.setBuyerSkuCode(buyerSkuCode);
            dto.setQuantity(quantity);
            dto.setTarget(target);
            dto.setPreviousReceiptVersion(previousReceiptVersion);
            return dto;
        } catch (ServiceException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            log.debug("[项目标准解析] 请求正文解析失败", ex);
            throw invalidParameter("JSON格式错误");
        }
    }

    /**
     * 严格解析标准查单/资产查询请求正文，二者共用同一封闭契约：仅接受 buyerOrderNo 单字段。
     *
     * @param rawBody 原始正文字节数组
     * @return 采购商订单号
     * @see docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析
     */
    public String parseBuyerOrderNo(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw invalidParameter("正文为空");
        }
        try (JsonParser parser = jsonFactory.createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalidParameter("根节点必须为JSON对象");
            }

            String buyerOrderNo = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                parser.nextToken();

                if ("buyerOrderNo".equals(fieldName)) {
                    if (parser.currentToken() != JsonToken.VALUE_STRING) {
                        throw invalidParameter("buyerOrderNo 必须为字符串");
                    }
                    buyerOrderNo = parser.getText();
                    validateTextField(buyerOrderNo, MAX_STRING_LENGTH);
                } else {
                    throw invalidParameter("包含未知字段: " + fieldName);
                }
            }

            if (parser.nextToken() != null) {
                throw invalidParameter("存在尾随非法内容");
            }

            if (buyerOrderNo == null) {
                throw invalidParameter("缺少 buyerOrderNo 字段");
            }

            return buyerOrderNo;
        } catch (ServiceException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            log.debug("[项目标准解析] 查单正文解析失败", ex);
            throw invalidParameter("JSON格式错误");
        }
    }

    /** 严格文本校验骨架：非空白、长度不超上限、不含 ISO 控制字符；各字段上限差异见常量注释。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。 */
    private static void validateTextField(String val, int maxLength) {
        if (val == null || val.isBlank() || val.length() > maxLength
                || val.chars().anyMatch(Character::isISOControl)) {
            throw invalidParameter("字段格式不正确");
        }
    }

    private static void validateTargetKeyValue(String key, String val) {
        if (key == null || key.isBlank() || key.length() > MAX_STRING_LENGTH
                || key.chars().anyMatch(Character::isISOControl)) {
            throw invalidParameter("target key 格式不正确");
        }
        if (val == null || val.length() > MAX_TARGET_VALUE_LENGTH
                || val.chars().anyMatch(Character::isISOControl)) {
            throw invalidParameter("target value 格式不正确");
        }
    }

    private static ServiceException invalidParameter(String reason) {
        log.debug("[项目标准解析安全拦截] 原因: {}", reason);
        return exception(GlobalErrorCodeConstants.BAD_REQUEST);
    }
}
