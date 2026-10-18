package com.aifuli.module.supplychain.supplieradapter.renyue;

import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.supplieradapter.singlestep.SingleStepHttpCallFactory;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import okhttp3.*;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepSupplierFact.*;

/**
 * 仁跃临时本地协议执行边界：事务外一次HTTP，仅输出认证事实，不拥有订单/资金。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。
 * 生产Metadata未注册，不能因本Adapter存在就发布供给；HTTP时限只取原单检查点。
 */
@Component
@Slf4j
public class RenyueSupplierAdapter implements OperationExecutorPort {
    public static final String TYPE = "RENYUE_RECHARGE_V2";
    private static final String ACCEPTED_CODE = "1000";
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(1024 * 1024).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SingleStepHttpCallFactory calls = new SingleStepHttpCallFactory();
    @Override public boolean supports(String type) { return TYPE.equals(type); }
    @Override public OperationOutcome execute(OperationTicket ticket) { throw new IllegalStateException("必须提供单步执行截止"); }

    /** marker/lease由Runner保证；本边界不重试、不重定向，I/O因果交由Runner持久化未知。 */
    @Override public OperationOutcome execute(OperationTicket ticket, long deadlineNanos) {
        SingleStepCheckpoint frozen;
        byte[] checkpoint = ticket.checkpointPayload();
        try {
            if (!supports(ticket.adapterType()) || checkpoint == null || checkpoint.length > 65536) throw new IllegalArgumentException();
            frozen = JSON.readValue(checkpoint, SingleStepCheckpoint.class);
            if (!frozen.binding().operationNo().equals(ticket.operationNo()) || !frozen.binding().adapterType().equals(ticket.adapterType())
                    || !frozen.business().billno().equals(ticket.billno())
                    || frozen.binding().connectorVersionId() != ticket.connectorVersionId()
                    || frozen.binding().offerVersionId() != ticket.offerVersionId()) throw new IllegalArgumentException();
        } catch (IOException | RuntimeException invalid) { throw new IllegalArgumentException("仁跃原单检查点不可用"); }
        finally { if (checkpoint != null) Arrays.fill(checkpoint, (byte) 0); }
        var form = RenyueProtocol.buildRequest(ticket, Instant.now());
        boolean create = ticket.operationKind() == FulfillmentOperationKind.FULFILL;
        var body = new FormBody.Builder(StandardCharsets.UTF_8); form.forEach(body::add);
        var url = Objects.requireNonNull(HttpUrl.parse(ticket.baseUrl())).newBuilder()
                .encodedPath(create ? "/api/v2/getOrderRechargeInfo" : "/api/v2/getCardInquiryInfo").build();
        var request = new Request.Builder().url(url).post(body.build()).build();
        String callId = UUID.randomUUID().toString(); long started = System.nanoTime();
        log.info("[renyueRequest][仁跃外呼开始] callId={}, operationNo={}, remoteAction={}, attempt={}", callId, ticket.operationNo(), create ? Source.CREATE : Source.QUERY, 1);
        byte[] bytes = null;
        try (var response = calls.newCall(request, frozen.recoveryContract().timeouts(), deadlineNanos).execute()) {
            bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(RenyueCallbackParser.MAX_BODY_BYTES + 1);
            var fact = parse(response.isSuccessful(), bytes, form, ticket, frozen, create);
            log.info("[renyueResponse][仁跃外呼结束] callId={}, httpStatus={}, outcome={}, verification={}, elapsedMs={}",
                    callId, response.code(), fact.observation(), fact.verification(), (System.nanoTime() - started) / 1_000_000);
            return OperationOutcome.builder().outcomeClass(switch (fact.observation()) {
                case SUCCESS -> OperationOutcomeClass.SUCCEEDED;
                case ACCEPTED, PROCESSING -> OperationOutcomeClass.ACCEPTED;
                case EXISTS_UNKNOWN -> OperationOutcomeClass.MANUAL_REQUIRED;
                default -> OperationOutcomeClass.RESULT_UNKNOWN;
            }).externalOrderNo(fact.externalOrderNo()).protocolFact(fact).build();
        } catch (IOException failure) {
            log.warn("[renyueFailure][仁跃外呼结果未知] callId={}, errorCode={}, elapsedMs={}", callId, "TRANSPORT_UNKNOWN", (System.nanoTime() - started) / 1_000_000);
            throw new IllegalStateException("仁跃网络调用未完成", failure);
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private SingleStepSupplierFact parse(boolean httpOk, byte[] bytes, Map<String, String> form,
            OperationTicket ticket, SingleStepCheckpoint frozen, boolean create) {
        Verification verification = Verification.UNVERIFIED;
        Observation observation = Observation.RESPONSE_UNKNOWN;
        if (httpOk && bytes.length <= RenyueCallbackParser.MAX_BODY_BYTES) {
            JsonNode node = null;
            try { node = JSON.readTree(bytes); }
            catch (IOException invalid) { /* 有界解析失败是未知，不将Jackson原文暴露到普通异常/日志。 */ }
            if (node != null && node.isObject()) {
                var expected = new LinkedHashMap<String, String>();
                if (create) { expected.putAll(form); expected.remove("CallbackUrl"); expected.remove("SignSecret"); }
                else { expected.put("RegState", text(node, "RegState")); expected.put("ThirdOrderNo", ticket.billno()); }
                if (!RenyueProtocol.matchesReceipt(text(node, "ReturnSign"), expected, ticket.resolvedCredentialMaterial())) {
                    verification = Verification.AUTHENTICATION_FAILED;
                } else {
                    verification = Verification.VERIFIED;
                    // 短回执不认证响应OrderNo/金额，只保留原来已认证绑定的外部号。
                    boolean conflict = node.has("ThirdOrderNo") && !ticket.billno().equals(text(node, "ThirdOrderNo"))
                            || node.has("MerchantNo") && !ticket.merchantAccount().equals(text(node, "MerchantNo"))
                            || node.has("RechargeAccount") && !ticket.rechargeAccount().equals(text(node, "RechargeAccount"))
                            || ticket.externalOrderNo() != null && node.has("OrderNo") && !ticket.externalOrderNo().equals(text(node, "OrderNo"));
                    if (conflict) verification = Verification.IDENTITY_CONFLICT;
                    else if (ACCEPTED_CODE.equals(text(node, "CodeRes"))) observation = create ? Observation.ACCEPTED : switch (text(node, "RegState")) {
                        case "01" -> Observation.PROCESSING; case "02" -> Observation.SUCCESS;
                        case "03" -> Observation.FAILURE; case "04" -> Observation.EXISTS_UNKNOWN;
                        default -> Observation.RESPONSE_UNKNOWN;
                    };
                }
            }
        }
        return new SingleStepSupplierFact(frozen.binding(), create ? Source.CREATE : Source.QUERY,
                ticket.billno(), frozen.business().protectedTargetReference().targetDigest(), 1, verification, observation,
                ticket.externalOrderNo(), LocalDateTime.now(ZoneId.of(SingleStepCheckpoint.DEADLINE_ZONE_ID)), RenyueCallbackParser.digest(bytes), null, false);
    }
    private static String text(JsonNode node, String name) {
        var value = node.path(name);
        return value.isTextual() ? value.textValue() : value.isIntegralNumber() && name.equals("CodeRes") ? value.asText() : "";
    }
}
