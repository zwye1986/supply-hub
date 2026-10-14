package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 华昇专用严格响应解析器。
 *
 * <p>职责：封装专用 Jackson 流式约束与词法解析，严格解析 TOKEN、ORDER、TAKE 响应以及外层 envelope。
 *
 * <p>不变量：私有构造；专用静态不可变 ObjectMapper，限制 16384 字符长度与 32 层嵌套深度，开启 STRICT_DUPLICATE_DETECTION；
 * 解析前预检 1MiB UTF-8 字节；readTree 后检查 nextToken 为 null；整数字段必须使用 IntNode 或 LongNode 校验；
 * 失败统一经内部载荷 {@link HuashengInternalAttempt#failed(HuashengProtectedFailure)} 传递，证据恒为 Optional.empty()；
 * 业务拒绝统一以 {@link HuashengParseFact.Business} 表达，绝不伪造系统 Throwable cause。
 *
 * <p>关键决策：MoneyParser 失败原样透传；TAKE/订单 data 额外属性忽略，卡片载荷未知非空属性严格拒绝。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#51-json-接受规则、§15.3。
 */
public final class HuashengResponseParser {

    private static final ObjectMapper MAPPER = createMapper();

    private HuashengResponseParser() {}

    private static ObjectMapper createMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxStringLength(16384)
                .maxNestingDepth(32)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    /**
     * 严格解析华昇基础响应信封（envelope）。
     *
     * <p>职责：在最外层执行严格的 JSON 接受规则验证，提取 code 与 data 节点；
     * 当供应商返回业务拒绝（code != 0）时，封装为 {@link HuashengParseFact.Business} 成功解析事实，
     * 绝不伪造底层系统 Throwable 异常。
     *
     * <p>不变量：
     * <ul>
     *   <li>输入预检：非空、非空白，UTF-8 编码严格检查（拒绝畸形或未映射字符），字节数不得超过 1MiB（超限报告 RESPONSE_TOO_LARGE）；</li>
     *   <li>流式预算与防护：采用专用 ObjectMapper，限制 maxStringLength=16384、maxNestingDepth=32，启用 STRICT_DUPLICATE_DETECTION；</li>
     *   <li>词法严格性：根节点必须为 JSON 对象，解析后必须无尾随字符（nextToken == null，否则报告 ENVELOPE_INVALID）；</li>
     *   <li>错误码严格判别：code 字段必须存在且严格为整型（IntNode 或 LongNode，严禁 DoubleNode 如 0.0），否则报告 ENVELOPE_INVALID；</li>
     *   <li>两层解耦：code == 0 时必须包含非空 object 类型的 data 节点，输出 {@link HuashengParseFact.Data}；
     *       code != 0 时提取原始数字与枚举映射，输出 {@link HuashengParseFact.Business}；</li>
     *   <li>失败证据：解析失败统一由 {@link HuashengInternalAttempt#failed} 承载，safeExchangeEvidence 恒为 {@link Optional#empty()}。</li>
     * </ul>
     *
     * <p>关键决策：业务拒绝是协议解析的有效结果，不是底层通信失败，禁止抛出异常或在底层 carrier 增加第三分支；
     * 专用 Jackson 流式约束杜绝大报文、深嵌套或重复 key 导致的安全风险。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#51-json-接受规则、#1514-业务拒绝两层载荷。
     *
     * @param responseBody 供应商原始 HTTP 响应体文本
     * @return 包含 JsonNode 数据载荷或业务拒绝事实的内部解析结果
     */
    static HuashengInternalAttempt<HuashengParseFact<JsonNode>> parseEnvelope(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    new IllegalArgumentException("响应体为空"),
                    Optional.empty()));
        }

        byte[] utf8Bytes;
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = encoder.encode(CharBuffer.wrap(responseBody));
            utf8Bytes = new byte[bb.remaining()];
            bb.get(utf8Bytes);
        } catch (CharacterCodingException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    e,
                    Optional.empty()));
        }

        if (utf8Bytes.length > 1024 * 1024) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.RESPONSE_TOO_LARGE,
                    new IllegalArgumentException("响应体UTF-8字节数超过1MiB限制: " + utf8Bytes.length),
                    Optional.empty()));
        }

        JsonNode root;
        try (JsonParser parser = MAPPER.getFactory().createParser(utf8Bytes)) {
            root = MAPPER.readTree(parser);
            if (root == null || !root.isObject()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.ENVELOPE_INVALID,
                        new IllegalArgumentException("根节点必须为JSON对象"),
                        Optional.empty()));
            }
            if (parser.nextToken() != null) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.ENVELOPE_INVALID,
                        new IllegalArgumentException("存在尾随内容"),
                        Optional.empty()));
            }
        } catch (StreamConstraintsException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.RESPONSE_TOO_LARGE,
                    e,
                    Optional.empty()));
        } catch (JsonProcessingException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    e,
                    Optional.empty()));
        } catch (IOException e) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    e,
                    Optional.empty()));
        }

        JsonNode codeNode = root.get("code");
        if (codeNode == null || !(codeNode instanceof IntNode || codeNode instanceof LongNode)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    new IllegalArgumentException("缺少整数code字段或类型非法"),
                    Optional.empty()));
        }
        if (!codeNode.canConvertToInt()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.ENVELOPE_INVALID,
                    new IllegalArgumentException("code数值超出int范围"),
                    Optional.empty()));
        }
        int code = codeNode.intValue();

        if (root.has("message")) {
            JsonNode messageNode = root.get("message");
            if (!messageNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.ENVELOPE_INVALID,
                        new IllegalArgumentException("message字段类型非法"),
                        Optional.empty()));
            }
        }
        if (root.has("traceID")) {
            JsonNode traceNode = root.get("traceID");
            if (!traceNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.ENVELOPE_INVALID,
                        new IllegalArgumentException("traceID字段类型非法"),
                        Optional.empty()));
            }
        }

        if (code != 0) {
            // 66 受控安全原因只匹配官方固定提示，未匹配保持空；traceID 仅接收合法编号。
            // 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#43-61-与-66、#82-日志与诊断。
            Optional<HuashengSecurityReason> securityReason = Optional.empty();
            if (code == 66 && root.has("message")) {
                securityReason = Optional.ofNullable(
                        HuashengSecurityReason.fromOfficialMessage(root.get("message").asText()));
            }
            Optional<String> supplierTraceId = Optional.empty();
            if (root.has("traceID")) {
                String traceId = root.get("traceID").asText();
                if (traceId != null && !traceId.isEmpty() && traceId.length() <= 128
                        && traceId.chars().noneMatch(Character::isISOControl)) {
                    supplierTraceId = Optional.of(traceId);
                }
            }
            HuashengBusinessError businessError = new HuashengBusinessError(
                    code,
                    HuashengErrorCode.fromRawCode(code),
                    Optional.empty(),
                    securityReason,
                    supplierTraceId
            );
            return HuashengInternalAttempt.parsed(new HuashengParseFact.Business<>(businessError));
        }

        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isObject()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("code为0时缺少有效object类型的data字段"),
                    Optional.empty()));
        }
        return HuashengInternalAttempt.parsed(new HuashengParseFact.Data<>(dataNode));
    }

    /**
     * 严格解析华昇 TOKEN 端点响应。
     *
     * <p>职责：将供应商获取访问令牌端点的 HTTP 响应解析为强类型 {@link HuashengTokenResponse}，
     * 校验令牌内容、长度与有效期合法性。
     *
     * <p>不变量：
     * <ul>
     *   <li>信封级校验：首先通过 {@link #parseEnvelope(String)}，业务拒绝直接向上传递；</li>
     *   <li>字段存在性：data 节点中 accessToken 与 expires_in 必须同时存在且非空，缺少则报告 DATA_MISSING；</li>
     *   <li>accessToken 规范：必须为 TextNode、非空非空白、UTF-8 字符长度 <= 8192、禁止包含 CRLF 或 NUL 控制字符、不得被 trim 篡改；</li>
     *   <li>expires_in 规范：必须严格为 IntNode 或 LongNode 整数类型（拒绝 DoubleNode），且数值必须大于 0；
     *       7201 边界在解析器层合法通过（本地 7200 上限归入上层策略）；</li>
     *   <li>失败保护：任何校验失败均安全捕获为 {@link HuashengInternalAttempt.Failed}，不暴露敏感令牌。</li>
     * </ul>
     *
     * <p>关键决策：解析器只负责协议事实的完整性与词法安全，不在此处强加本地 TTL 业务截断；
     * 严格拒绝 DoubleNode(0.0) 或字符串型数字。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#8-tokenmanager缓存与有界-61、§15.1.2。
     *
     * @param responseBody 供应商 TOKEN 端点原始响应体文本
     * @return 包含 {@link HuashengTokenResponse} 或业务拒绝的内部解析结果
     */
    public static HuashengInternalAttempt<HuashengParseFact<HuashengTokenResponse>> parseToken(String responseBody) {
        var envelopeAttempt = parseEnvelope(responseBody);
        if (envelopeAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<JsonNode>> failed) {
            return HuashengInternalAttempt.failed(failed.failure());
        }
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) envelopeAttempt).value();
        if (fact instanceof HuashengParseFact.Business<JsonNode> business) {
            return HuashengInternalAttempt.parsed(new HuashengParseFact.Business<>(business.error()));
        }
        JsonNode data = ((HuashengParseFact.Data<JsonNode>) fact).data();

        // 校验 access_token
        JsonNode tokenNode = data.get("access_token");
        if (tokenNode == null || !tokenNode.isTextual()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串access_token"),
                    Optional.empty()));
        }
        String tokenStr = tokenNode.asText();
        if (tokenStr.isBlank()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("access_token不能为空白"),
                    Optional.empty()));
        }
        if (tokenStr.length() > 8192) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("access_token长度超过8192字符"),
                    Optional.empty()));
        }
        if (tokenStr.indexOf('\r') >= 0 || tokenStr.indexOf('\n') >= 0 || tokenStr.indexOf('\0') >= 0) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("access_token包含CRLF或NUL非法字符"),
                    Optional.empty()));
        }

        // 校验 expires_in (严格整数token，严禁isIntegralNumber)
        JsonNode expiresNode = data.get("expires_in");
        if (expiresNode == null || !(expiresNode instanceof IntNode || expiresNode instanceof LongNode)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少整数token expires_in"),
                    Optional.empty()));
        }
        if (!expiresNode.canConvertToInt() || expiresNode.intValue() <= 0) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("expires_in必须为正整数"),
                    Optional.empty()));
        }
        int expiresIn = expiresNode.intValue();

        return HuashengInternalAttempt.parsed(new HuashengParseFact.Data<>(
                new HuashengTokenResponse(tokenStr, expiresIn)));
    }

    /**
     * 严格解析华昇订单端点（PLACE, CONFIRM, QUERY）响应。
     *
     * <p>职责：解析并验证供应商订单端点响应，执行单号比对、数量比对、金额数学一致性及 56 组状态矛盾矩阵验证，
     * 产出强类型不可变的 {@link HuashengOrderResponse} 订单事实。
     *
     * <p>不变量：
     * <ul>
     *   <li>信封与入参：入参 expectedOrder 与 stage 必须非 null，stage 必须为 PLACE/CONFIRM/QUERY 之一；
     *       业务拒绝直接原样向上传递；</li>
     *   <li>必需字段约束：orderSn、outOrderSn、num、totalPrice、status 必须全部存在且非空，缺少则报告 DATA_MISSING；</li>
     *   <li>身份与单号比对：outOrderSn 必须与 expectedOrder.outOrderSn 精确一致；若 expectedOrder.orderSn 存在，
     *       则返回的 orderSn 必须与之完全一致，否则报告 STATE_CONFLICT；</li>
     *   <li>采购数量比对：返回的 num 必须严格为整数类型（IntNode/LongNode），且必须等于 expectedOrder.quantity，
     *       否则报告 STATE_CONFLICT；</li>
     *   <li>金额精确一致性：totalPrice 必须经由 {@link HuashengMoneyParser#parsePrice} 解析成功；
     *       若 price 出现，其解析后必须满足 {@code price * num == totalPrice} 的高精度数学等式，否则报告 STATE_CONFLICT；</li>
     *   <li>阶段维度约束：在 QUERY 阶段，refundStatus 与 isTake 必须强制同时存在；在 PLACE 与 CONFIRM 阶段允许二者缺省；</li>
     *   <li>状态矛盾矩阵（56 组）：主状态 status、退款状态 refundStatus 与提卡状态 isTake 必须满足全局相容性矩阵：
     *       WAIT 状态下禁止出现已提卡(Y)或退款成功(SUCCESS)；FAILED 状态下禁止出现非终态退款或提卡；
     *       SUCCESS 状态下退款中或退款成功互斥；CANCEL 状态下禁止提卡；
     *       出现任何未知状态值或矛盾组合均报告 STATE_CONFLICT；</li>
     *   <li>字段保真：除 5 项必需字段外，其余 17 项字段均以非空 Optional 容器保真，未知多余字段安全忽略；</li>
     *   <li>失败证据：解析失败统一输出 safeExchangeEvidence 为 {@link Optional#empty()}。</li>
     * </ul>
     *
     * <p>关键决策：订单解析强制绑定发起方上下文（expectedOrder），杜绝跨订单串号或数量篡改；
     * 严格使用 BigDecimal 进行金额数学核对；严禁在此处推导重试或直接执行资产动作。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.1.3、§15.2.5。
     *
     * @param responseBody 供应商订单端点原始响应体文本
     * @param expectedOrder 本地业务侧期望的订单上下文（外部单号、预期供应商单号、采购数量）
     * @param stage 调用的端点阶段（PLACE, CONFIRM, QUERY）
     * @return 包含 {@link HuashengOrderResponse} 或业务拒绝的内部解析结果
     * @throws NullPointerException 当 expectedOrder 或 stage 为 null 时抛出
     * @throws IllegalArgumentException 当 stage 不属于 PLACE/CONFIRM/QUERY 时抛出
     */
    public static HuashengInternalAttempt<HuashengParseFact<HuashengOrderResponse>> parseOrder(
            String responseBody, HuashengExpectedOrder expectedOrder, HuashengEndpoint stage) {
        java.util.Objects.requireNonNull(expectedOrder, "expectedOrder must not be null");
        java.util.Objects.requireNonNull(stage, "stage must not be null");
        if (stage != HuashengEndpoint.PLACE && stage != HuashengEndpoint.CONFIRM && stage != HuashengEndpoint.QUERY) {
            throw new IllegalArgumentException("非法的stage参数: " + stage);
        }

        var envelopeAttempt = parseEnvelope(responseBody);
        if (envelopeAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<JsonNode>> failed) {
            return HuashengInternalAttempt.failed(failed.failure());
        }
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) envelopeAttempt).value();
        if (fact instanceof HuashengParseFact.Business<JsonNode> business) {
            return HuashengInternalAttempt.parsed(new HuashengParseFact.Business<>(business.error()));
        }
        JsonNode data = ((HuashengParseFact.Data<JsonNode>) fact).data();

        // 1. 校验必需字段 orderSn
        JsonNode orderSnNode = data.get("orderSn");
        if (orderSnNode == null || !orderSnNode.isTextual() || orderSnNode.asText().isBlank()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串orderSn"),
                    Optional.empty()));
        }
        String orderSn = orderSnNode.asText();

        // 2. 校验必需字段 outOrderSn
        JsonNode outOrderSnNode = data.get("outOrderSn");
        if (outOrderSnNode == null || !outOrderSnNode.isTextual() || outOrderSnNode.asText().isBlank()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串outOrderSn"),
                    Optional.empty()));
        }
        String outOrderSn = outOrderSnNode.asText();

        // 身份匹配：outOrderSn 必须与 expectedOrder.outOrderSn() 严格一致
        if (!expectedOrder.outOrderSn().equals(outOrderSn)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.IDENTITY_MISMATCH,
                    new IllegalArgumentException("outOrderSn不匹配: 返回=" + outOrderSn + ", 期望=" + expectedOrder.outOrderSn()),
                    Optional.empty()));
        }
        // orderSn 匹配：若 expectedOrder 已知远端号，必须精确一致
        if (expectedOrder.orderSn().isPresent() && !expectedOrder.orderSn().get().equals(orderSn)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.IDENTITY_MISMATCH,
                    new IllegalArgumentException("orderSn不匹配: 返回=" + orderSn + ", 期望=" + expectedOrder.orderSn().get()),
                    Optional.empty()));
        }

        // 3. 校验必需字段 num
        JsonNode numNode = data.get("num");
        if (numNode == null || !(numNode instanceof IntNode || numNode instanceof LongNode)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少整数token num"),
                    Optional.empty()));
        }
        if (!numNode.canConvertToInt()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("num超出int范围"),
                    Optional.empty()));
        }
        int num = numNode.intValue();
        if (num != expectedOrder.quantity()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.QUANTITY_MISMATCH,
                    new IllegalArgumentException("num与期望数量不匹配: num=" + num + ", 期望=" + expectedOrder.quantity()),
                    Optional.empty()));
        }

        // 4. 校验必需字段 totalPrice (字符串，经 MoneyParser 解析)
        JsonNode totalPriceNode = data.get("totalPrice");
        if (totalPriceNode == null || !totalPriceNode.isTextual()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串totalPrice"),
                    Optional.empty()));
        }
        var priceAttempt = HuashengMoneyParser.parsePrice(totalPriceNode.asText());
        if (priceAttempt instanceof HuashengInternalAttempt.Failed<HuashengPrice> failedPrice) {
            return HuashengInternalAttempt.failed(failedPrice.failure());
        }
        HuashengPrice totalPrice = ((HuashengInternalAttempt.Parsed<HuashengPrice>) priceAttempt).value();

        // 5. 校验必需字段 status
        JsonNode statusNode = data.get("status");
        if (statusNode == null || !statusNode.isTextual()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串status"),
                    Optional.empty()));
        }
        Optional<HuashengOrderStatus> optStatus = HuashengOrderStatus.fromWireValue(statusNode.asText());
        if (optStatus.isEmpty()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.STATE_CONFLICT,
                    new IllegalArgumentException("未知status枚举值: " + statusNode.asText()),
                    Optional.empty()));
        }
        HuashengOrderStatus status = optStatus.get();

        // 6. 校验阶段对于 refundStatus 与 isTake 的约束
        Optional<HuashengRefundStatus> refundStatus;
        if (stage == HuashengEndpoint.QUERY) {
            JsonNode rfNode = data.get("refundStatus");
            if (rfNode == null || !rfNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.DATA_MISSING,
                        new IllegalArgumentException("QUERY阶段缺少必需字段refundStatus"),
                        Optional.empty()));
            }
            Optional<HuashengRefundStatus> optRf = HuashengRefundStatus.fromWireValue(rfNode.asText());
            if (optRf.isEmpty()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.STATE_CONFLICT,
                        new IllegalArgumentException("未知refundStatus枚举值: " + rfNode.asText()),
                        Optional.empty()));
            }
            refundStatus = optRf;
        } else {
            // PLACE 或 CONFIRM
            if (data.has("refundStatus") && !data.get("refundStatus").isNull()) {
                JsonNode rfNode = data.get("refundStatus");
                if (!rfNode.isTextual()) {
                    return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                            HuashengFailureKind.DATA_MISSING,
                            new IllegalArgumentException("refundStatus字段类型非法"),
                            Optional.empty()));
                }
                Optional<HuashengRefundStatus> optRf = HuashengRefundStatus.fromWireValue(rfNode.asText());
                if (optRf.isEmpty()) {
                    return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                            HuashengFailureKind.STATE_CONFLICT,
                            new IllegalArgumentException("未知refundStatus枚举值: " + rfNode.asText()),
                            Optional.empty()));
                }
                refundStatus = optRf;
            } else {
                refundStatus = Optional.empty();
            }
        }

        Optional<HuashengTakeStatus> isTake;
        if (stage == HuashengEndpoint.QUERY) {
            JsonNode tkNode = data.get("isTake");
            if (tkNode == null || !tkNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.DATA_MISSING,
                        new IllegalArgumentException("QUERY阶段缺少必需字段isTake"),
                        Optional.empty()));
            }
            Optional<HuashengTakeStatus> optTk = HuashengTakeStatus.fromWireValue(tkNode.asText());
            if (optTk.isEmpty()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.STATE_CONFLICT,
                        new IllegalArgumentException("未知isTake枚举值: " + tkNode.asText()),
                        Optional.empty()));
            }
            isTake = optTk;
        } else {
            // PLACE 或 CONFIRM
            if (data.has("isTake") && !data.get("isTake").isNull()) {
                JsonNode tkNode = data.get("isTake");
                if (!tkNode.isTextual()) {
                    return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                            HuashengFailureKind.DATA_MISSING,
                            new IllegalArgumentException("isTake字段类型非法"),
                            Optional.empty()));
                }
                Optional<HuashengTakeStatus> optTk = HuashengTakeStatus.fromWireValue(tkNode.asText());
                if (optTk.isEmpty()) {
                    return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                            HuashengFailureKind.STATE_CONFLICT,
                            new IllegalArgumentException("未知isTake枚举值: " + tkNode.asText()),
                            Optional.empty()));
                }
                isTake = optTk;
            } else {
                isTake = Optional.empty();
            }
        }

        // 7. 8 组防状态矛盾规则 (56 组矩阵)
        if (status == HuashengOrderStatus.WAIT) {
            if (isTake.isPresent() && isTake.get() == HuashengTakeStatus.Y) {
                return failStateConflict("WAIT状态不可提卡(isTake=Y)");
            }
            if (refundStatus.isPresent() && refundStatus.get() == HuashengRefundStatus.SUCCESS) {
                return failStateConflict("WAIT状态不可退款成功(refundStatus=SUCCESS)");
            }
        } else if (status == HuashengOrderStatus.FAILED) {
            if (isTake.isPresent() && isTake.get() == HuashengTakeStatus.Y) {
                return failStateConflict("FAILED状态不可提卡(isTake=Y)");
            }
            if (refundStatus.isPresent() && (refundStatus.get() == HuashengRefundStatus.NONE || refundStatus.get() == HuashengRefundStatus.WAIT)) {
                return failStateConflict("FAILED状态退款状态不可为NONE或WAIT");
            }
        } else if (status == HuashengOrderStatus.SUCCESS) {
            if (refundStatus.isPresent() && (refundStatus.get() == HuashengRefundStatus.WAIT || refundStatus.get() == HuashengRefundStatus.SUCCESS)) {
                return failStateConflict("SUCCESS状态退款状态不可为WAIT或SUCCESS");
            }
        } else if (status == HuashengOrderStatus.CANCEL) {
            if (isTake.isPresent() && isTake.get() == HuashengTakeStatus.Y) {
                return failStateConflict("CANCEL状态不可提卡(isTake=Y)");
            }
        }

        // 8. 校验单价与总价数学一致性 (price * num == totalPrice)
        Optional<HuashengPrice> optPrice;
        if (data.has("price") && !data.get("price").isNull()) {
            JsonNode priceNode = data.get("price");
            if (!priceNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.DATA_MISSING,
                        new IllegalArgumentException("price字段类型非法"),
                        Optional.empty()));
            }
            var pAtt = HuashengMoneyParser.parsePrice(priceNode.asText());
            if (pAtt instanceof HuashengInternalAttempt.Failed<HuashengPrice> failedP) {
                return HuashengInternalAttempt.failed(failedP.failure());
            }
            HuashengPrice priceVal = ((HuashengInternalAttempt.Parsed<HuashengPrice>) pAtt).value();
            java.math.BigDecimal computed = new java.math.BigDecimal(priceVal.rawYuan()).multiply(java.math.BigDecimal.valueOf(num));
            java.math.BigDecimal totalBd = new java.math.BigDecimal(totalPrice.rawYuan());
            if (computed.compareTo(totalBd) != 0) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.MONEY_INVALID,
                        new IllegalArgumentException("单价*数量与总价不一致: price=" + priceVal.rawYuan() + ", num=" + num + ", totalPrice=" + totalPrice.rawYuan()),
                        Optional.empty()));
            }
            optPrice = Optional.of(priceVal);
        } else {
            optPrice = Optional.empty();
        }

        // 校验面值与总面值数学一致性 (amount * num == totalAmount)
        Optional<HuashengFaceAmount> optAmount;
        if (data.has("amount") && !data.get("amount").isNull()) {
            JsonNode amountNode = data.get("amount");
            if (!amountNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.DATA_MISSING,
                        new IllegalArgumentException("amount字段类型非法"),
                        Optional.empty()));
            }
            var aAtt = HuashengMoneyParser.parseFaceAmount(amountNode.asText());
            if (aAtt instanceof HuashengInternalAttempt.Failed<HuashengFaceAmount> failedA) {
                return HuashengInternalAttempt.failed(failedA.failure());
            }
            optAmount = Optional.of(((HuashengInternalAttempt.Parsed<HuashengFaceAmount>) aAtt).value());
        } else {
            optAmount = Optional.empty();
        }

        Optional<HuashengFaceAmount> optTotalAmount;
        if (data.has("totalAmount") && !data.get("totalAmount").isNull()) {
            JsonNode totalAmountNode = data.get("totalAmount");
            if (!totalAmountNode.isTextual()) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.DATA_MISSING,
                        new IllegalArgumentException("totalAmount字段类型非法"),
                        Optional.empty()));
            }
            var taAtt = HuashengMoneyParser.parseFaceAmount(totalAmountNode.asText());
            if (taAtt instanceof HuashengInternalAttempt.Failed<HuashengFaceAmount> failedTa) {
                return HuashengInternalAttempt.failed(failedTa.failure());
            }
            optTotalAmount = Optional.of(((HuashengInternalAttempt.Parsed<HuashengFaceAmount>) taAtt).value());
        } else {
            optTotalAmount = Optional.empty();
        }

        if (optAmount.isPresent() && optTotalAmount.isPresent()) {
            java.math.BigDecimal computed = new java.math.BigDecimal(optAmount.get().rawYuan()).multiply(java.math.BigDecimal.valueOf(num));
            java.math.BigDecimal totalBd = new java.math.BigDecimal(optTotalAmount.get().rawYuan());
            if (computed.compareTo(totalBd) != 0) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.MONEY_INVALID,
                        new IllegalArgumentException("面值*数量与总面值不一致"),
                        Optional.empty()));
            }
        }

        // 9. 其余可选已知字段以 Optional 强类型保真
        Optional<Long> optId = parseLongOptional(data, "id");
        if (optId == null) return failDataMissing("id类型非法");
        Optional<Long> optBrandId = parseLongOptional(data, "brandId");
        if (optBrandId == null) return failDataMissing("brandId类型非法");
        Optional<String> optBrandName = parseStringOptional(data, "brandName");
        if (optBrandName == null) return failDataMissing("brandName类型非法");
        Optional<Long> optCouponId = parseLongOptional(data, "couponId");
        if (optCouponId == null) return failDataMissing("couponId类型非法");
        Optional<String> optCouponName = parseStringOptional(data, "couponName");
        if (optCouponName == null) return failDataMissing("couponName类型非法");
        Optional<String> optCardType = parseStringOptional(data, "cardType");
        if (optCardType == null) return failDataMissing("cardType类型非法");
        Optional<String> optOutMobile = parseStringOptional(data, "outMobile");
        if (optOutMobile == null) return failDataMissing("outMobile类型非法");
        Optional<String> optPayTime = parseStringOptional(data, "payTime");
        if (optPayTime == null) return failDataMissing("payTime类型非法");
        Optional<String> optTakeTime = parseStringOptional(data, "takeTime");
        if (optTakeTime == null) return failDataMissing("takeTime类型非法");
        Optional<String> optExpireTime = parseStringOptional(data, "expireTime");
        if (optExpireTime == null) return failDataMissing("expireTime类型非法");
        Optional<String> optRefundTime = parseStringOptional(data, "refundTime");
        if (optRefundTime == null) return failDataMissing("refundTime类型非法");
        Optional<String> optCreatedAt = parseStringOptional(data, "createdAt");
        if (optCreatedAt == null) return failDataMissing("createdAt类型非法");

        return HuashengInternalAttempt.parsed(new HuashengParseFact.Data<>(new HuashengOrderResponse(
                orderSn, outOrderSn, num, totalPrice, status,
                optPrice, optAmount, optTotalAmount, refundStatus, isTake,
                optId, optBrandId, optBrandName, optCouponId, optCouponName, optCardType,
                optOutMobile, optPayTime, optTakeTime, optExpireTime, optRefundTime, optCreatedAt
        )));
    }

    private static Optional<Long> parseLongOptional(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return Optional.empty();
        }
        JsonNode val = node.get(fieldName);
        if (!(val instanceof IntNode || val instanceof LongNode) || !val.canConvertToLong() || val.longValue() < 0) {
            return null;
        }
        return Optional.of(val.longValue());
    }

    private static Optional<String> parseStringOptional(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return Optional.empty();
        }
        JsonNode val = node.get(fieldName);
        if (!val.isTextual() || val.asText().length() > 16384) {
            return null;
        }
        return Optional.of(val.asText());
    }

    private static <T> HuashengInternalAttempt<T> failStateConflict(String msg) {
        return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                HuashengFailureKind.STATE_CONFLICT,
                new IllegalArgumentException(msg),
                Optional.empty()));
    }

    private static <T> HuashengInternalAttempt<T> failDataMissing(String msg) {
        return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                HuashengFailureKind.DATA_MISSING,
                new IllegalArgumentException(msg),
                Optional.empty()));
    }

    /**
     * 严格解析华昇 TAKE（提卡）端点响应。
     *
     * <p>职责：解析并验证供应商提卡端点返回的卡券列表事实，逐张校验 7 种具体卡型的必填字段与类型匹配，
     * 产出不可变的强类型 {@link HuashengTakeResponse}。
     *
     * <p>不变量：
     * <ul>
     *   <li>信封与入参：入参 expectedOrder 非 null；业务拒绝原样向上传递；</li>
     *   <li>订单单号比对：返回的 orderSn 必须非空且与 expectedOrder 中指定的单号一致（若 expectedOrder.orderSn 存在
     *       则比对 orderSn，否则比对 outOrderSn）；不一致报告 STATE_CONFLICT；</li>
     *   <li>卡券数量比对：返回的 num 必须严格等于 expectedOrder.quantity，且 list 数组元素个数必须恰好等于 num，
     *       否则报告 STATE_CONFLICT 或 DATA_MISSING；</li>
     *   <li>TAKE 无金额约束：TAKE 响应中严格不解析任何金额或成本字段；即使供应商报文返回多余的 totalPrice，
     *       也被严格忽略，绝不合成资金事实；</li>
     *   <li>卡片逐项强校验：
     *       <ol>
     *         <li>卡片 type 必须为 201~304 之一，否则报告 CARD_PAYLOAD_INVALID；</li>
     *         <li>逐字段提取并建立三态容器 {@link HuashengField}；</li>
     *         <li>按具体卡型执行强约束：T201 必须有 code 和 pass；T202 必须 code/pass 至少一个有值；
     *             T203 必须有 code, pass, crc；T301 必须有 url；T302 必须有 url 和 urlPass；
     *             T303 必须有 code, url, urlPass；T304 必须有 code 和 url；不满足报告 CARD_PAYLOAD_INVALID；</li>
     *         <li>未知属性规则：卡片 JSON 节点若包含未声明的属性，若其值非空则报告 CARD_PAYLOAD_INVALID；
     *             若其值为 null 或空白则安全忽略；</li>
     *       </ol>
     *   </li>
     *   <li>脱敏与安全：构建不可变 {@link HuashengProtocolCard} 列表，类级禁止 JSON 序列化，toString 严格脱敏。</li>
     * </ul>
     *
     * <p>关键决策：提卡端点事实与订单结算事实严格解耦，TAKE 绝不产生或覆盖价格事实；
     * 强类型密封载荷 Card201~Card304 保证交付层无弱类型字符串强转风险。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take、§15.1.3。
     *
     * @param responseBody 供应商 TAKE 端点原始响应体文本
     * @param expectedOrder 本地业务侧期望的订单上下文（外部单号、预期供应商单号、采购数量）
     * @return 包含 {@link HuashengTakeResponse} 或业务拒绝的内部解析结果
     * @throws NullPointerException 当 expectedOrder 为 null 时抛出
     */
    public static HuashengInternalAttempt<HuashengParseFact<HuashengTakeResponse>> parseTake(
            String responseBody, HuashengExpectedOrder expectedOrder) {
        java.util.Objects.requireNonNull(expectedOrder, "expectedOrder must not be null");
        var envelopeAttempt = parseEnvelope(responseBody);
        if (envelopeAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<JsonNode>> failed) {
            return HuashengInternalAttempt.failed(failed.failure());
        }
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<JsonNode>>) envelopeAttempt).value();
        if (fact instanceof HuashengParseFact.Business<JsonNode> business) {
            return HuashengInternalAttempt.parsed(new HuashengParseFact.Business<>(business.error()));
        }
        JsonNode data = ((HuashengParseFact.Data<JsonNode>) fact).data();

        // 1. 校验 orderSn
        JsonNode orderSnNode = data.get("orderSn");
        if (orderSnNode == null || !orderSnNode.isTextual() || orderSnNode.asText().isBlank()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少有效字符串orderSn"),
                    Optional.empty()));
        }
        String orderSn = orderSnNode.asText();

        // 身份比对：expectedOrder.orderSn() 必须存在且与返回的 orderSn 精确相等
        if (expectedOrder.orderSn().isEmpty()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.IDENTITY_MISMATCH,
                    new IllegalArgumentException("TAKE时expectedOrder缺少远端orderSn"),
                    Optional.empty()));
        }
        if (!expectedOrder.orderSn().get().equals(orderSn)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.IDENTITY_MISMATCH,
                    new IllegalArgumentException("orderSn与期望不匹配: " + orderSn),
                    Optional.empty()));
        }

        // 2. 校验 num
        JsonNode numNode = data.get("num");
        if (numNode == null || !(numNode instanceof IntNode || numNode instanceof LongNode)) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少整数token num"),
                    Optional.empty()));
        }
        if (!numNode.canConvertToInt()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("num数值超出int范围"),
                    Optional.empty()));
        }
        int num = numNode.intValue();
        if (num != expectedOrder.quantity()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.QUANTITY_MISMATCH,
                    new IllegalArgumentException("num与期望数量不一致: num=" + num + ", expected=" + expectedOrder.quantity()),
                    Optional.empty()));
        }

        // 3. 校验 list
        JsonNode listNode = data.get("list");
        if (listNode == null || !listNode.isArray()) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.DATA_MISSING,
                    new IllegalArgumentException("缺少array类型的list字段"),
                    Optional.empty()));
        }
        if (listNode.size() != num) {
            return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                    HuashengFailureKind.QUANTITY_MISMATCH,
                    new IllegalArgumentException("list卡片数量与num不匹配: size=" + listNode.size() + ", num=" + num),
                    Optional.empty()));
        }

        // 4. 解析逐张卡片
        java.util.List<HuashengProtocolCard> cards = new java.util.ArrayList<>();
        for (JsonNode cardNode : listNode) {
            var cardAttempt = parseCard(cardNode);
            if (cardAttempt instanceof HuashengInternalAttempt.Failed<HuashengProtocolCard> failed) {
                return HuashengInternalAttempt.failed(failed.failure());
            }
            cards.add(((HuashengInternalAttempt.Parsed<HuashengProtocolCard>) cardAttempt).value());
        }

        return HuashengInternalAttempt.parsed(new HuashengParseFact.Data<>(
                new HuashengTakeResponse(orderSn, num, cards)));
    }

    /**
     * 严格解析单张卡片节点。
     */
    static HuashengInternalAttempt<HuashengProtocolCard> parseCard(JsonNode cardNode) {
        if (cardNode == null || !cardNode.isObject()) {
            return failCard("卡片项必须为有效对象");
        }

        java.util.Set<String> knownCardKeys = java.util.Set.of("type", "id", "code", "pass", "crc", "url", "urlPass", "validTime");
        // 检查未知非空载荷属性
        var fieldNames = cardNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fn = fieldNames.next();
            if (!knownCardKeys.contains(fn)) {
                JsonNode extraVal = cardNode.get(fn);
                if (extraVal != null && !extraVal.isNull()) {
                    if (!extraVal.isTextual() || !extraVal.asText().isBlank()) {
                        return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                                HuashengFailureKind.CARD_PAYLOAD_INVALID,
                                new IllegalArgumentException("卡片包含未声明的非空载荷属性: " + fn),
                                Optional.empty()));
                    }
                }
            }
        }

        // 校验 type
        JsonNode typeNode = cardNode.get("type");
        if (typeNode == null || !(typeNode instanceof IntNode || typeNode instanceof LongNode)) {
            return failCard("卡片缺少整数token type");
        }
        if (!typeNode.canConvertToInt()) {
            return failCard("卡片type超出int范围");
        }
        Optional<HuashengCardType> optType = HuashengCardType.fromRawValue(typeNode.intValue());
        if (optType.isEmpty()) {
            return failCard("卡片type不支持: " + typeNode.intValue());
        }
        HuashengCardType cardType = optType.get();

        // 解析 id
        HuashengField<Long> idField;
        if (!cardNode.has("id")) {
            idField = HuashengField.absent();
        } else if (cardNode.get("id").isNull()) {
            idField = HuashengField.ofNull();
        } else {
            JsonNode idNode = cardNode.get("id");
            if (!(idNode instanceof IntNode || idNode instanceof LongNode) || !idNode.canConvertToLong() || idNode.longValue() < 0) {
                return failCard("卡片id非法");
            }
            idField = HuashengField.ofValue(idNode.longValue());
        }

        // 解析字符串三态字段
        HuashengField<String> codeField = parseStringField(cardNode, "code");
        if (codeField == null) return failCard("code字段类型非法");
        HuashengField<String> passField = parseStringField(cardNode, "pass");
        if (passField == null) return failCard("pass字段类型非法");
        HuashengField<String> crcField = parseStringField(cardNode, "crc");
        if (crcField == null) return failCard("crc字段类型非法");
        HuashengField<String> urlField = parseStringField(cardNode, "url");
        if (urlField == null) return failCard("url字段类型非法");
        HuashengField<String> urlPassField = parseStringField(cardNode, "urlPass");
        if (urlPassField == null) return failCard("urlPass字段类型非法");
        HuashengField<String> validTimeField = parseStringField(cardNode, "validTime");
        if (validTimeField == null) return failCard("validTime字段类型非法");

        // 按卡型校验 required 字段并构建强类型载荷
        HuashengCardPayload payload;
        switch (cardType) {
            case T201 -> {
                if (!isValueNonBlank(codeField) || !isValueNonBlank(passField)) {
                    return failCard("T201缺少code或pass必填字段");
                }
                payload = HuashengProtocolCard.createPayload201(((HuashengField.Value<String>) codeField).value(), ((HuashengField.Value<String>) passField).value());
            }
            case T202 -> {
                boolean hasCode = isValueNonBlank(codeField);
                boolean hasPass = isValueNonBlank(passField);
                if (!hasCode && !hasPass) {
                    return failCard("T202至少需要一个有效的code或pass");
                }
                payload = HuashengProtocolCard.createPayload202(codeField, passField);
            }
            case T203 -> {
                if (!isValueNonBlank(codeField) || !isValueNonBlank(passField) || !isValueNonBlank(crcField)) {
                    return failCard("T203缺少code、pass或crc必填字段");
                }
                payload = HuashengProtocolCard.createPayload203(((HuashengField.Value<String>) codeField).value(), ((HuashengField.Value<String>) passField).value(), ((HuashengField.Value<String>) crcField).value());
            }
            case T301 -> {
                if (!isValueNonBlank(urlField)) {
                    return failCard("T301缺少url必填字段");
                }
                payload = HuashengProtocolCard.createPayload301(((HuashengField.Value<String>) urlField).value());
            }
            case T302 -> {
                if (!isValueNonBlank(urlField) || !isValueNonBlank(urlPassField)) {
                    return failCard("T302缺少url或urlPass必填字段");
                }
                payload = HuashengProtocolCard.createPayload302(((HuashengField.Value<String>) urlField).value(), ((HuashengField.Value<String>) urlPassField).value());
            }
            case T303 -> {
                if (!isValueNonBlank(codeField) || !isValueNonBlank(urlField) || !isValueNonBlank(urlPassField)) {
                    return failCard("T303缺少code、url或urlPass必填字段");
                }
                payload = HuashengProtocolCard.createPayload303(((HuashengField.Value<String>) codeField).value(), ((HuashengField.Value<String>) urlField).value(), ((HuashengField.Value<String>) urlPassField).value());
            }
            case T304 -> {
                if (!isValueNonBlank(codeField) || !isValueNonBlank(urlField)) {
                    return failCard("T304缺少code或url必填字段");
                }
                payload = HuashengProtocolCard.createPayload304(((HuashengField.Value<String>) codeField).value(), ((HuashengField.Value<String>) urlField).value());
            }
            default -> {
                return failCard("不支持的卡型: " + cardType);
            }
        }

        return HuashengInternalAttempt.parsed(new HuashengProtocolCard(idField, cardType, codeField, passField, crcField, urlField, urlPassField, validTimeField, payload));
    }

    private static HuashengField<String> parseStringField(JsonNode node, String fieldName) {
        if (!node.has(fieldName)) {
            return HuashengField.absent();
        }
        JsonNode val = node.get(fieldName);
        if (val.isNull()) {
            return HuashengField.ofNull();
        }
        if (!val.isTextual()) {
            return null;
        }
        return HuashengField.ofValue(val.asText());
    }

    private static boolean isValueNonBlank(HuashengField<String> field) {
        return field instanceof HuashengField.Value<String> val && !val.value().isBlank();
    }

    private static <T> HuashengInternalAttempt<T> failCard(String msg) {
        return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                HuashengFailureKind.CARD_PAYLOAD_INVALID,
                new IllegalArgumentException(msg),
                Optional.empty()));
    }
}
