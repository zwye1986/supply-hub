package com.aifuli.module.supplychain.supplieradapter.renyue;

import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import static com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepCallbackException.Code.*;

/** 仁跃form与认证事实边界；设计见详细设计§5.1.3，秘密仅借用原单Ticket。 */
public final class RenyueCallbackParser {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("OrderNo", "RechargeAccount", "RechargeStatus", "StoreNo", "TimeStamp", "ReturnSign");
    private RenyueCallbackParser() { }

    /** 只读有界正文，不合并Servlet参数或查询串；拒绝重复键、未知扩展及替换式UTF-8解码。 */
    public static Map<String, String> readForm(InputStream input) {
        byte[] bytes = null;
        try {
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw new SingleStepCallbackException(INVALID_CALLBACK);
            var fields = new LinkedHashMap<String, String>();
            for (String part : decodeUtf8(bytes).split("&", -1)) {
                var pair = part.split("=", 2);
                if (pair.length != 2) throw new SingleStepCallbackException(INVALID_CALLBACK);
                String name = decodeComponent(pair[0]); String value = decodeComponent(pair[1]);
                if (!FIELDS.contains(name) || fields.putIfAbsent(name, value) != null) throw new SingleStepCallbackException(INVALID_CALLBACK);
            }
            validate(fields);
            return Map.copyOf(fields);
        } catch (IOException | IllegalArgumentException failure) {
            throw new SingleStepCallbackException(INVALID_CALLBACK);
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /** 四业务字段全部受回执保护；认证后核原手机号、商户单号及已绑定外部号，TimeStamp不作认证依据。 */
    public static SingleStepSupplierFact parse(Map<String, String> fields, OperationTicket ticket,
            SingleStepCheckpoint frozen, LocalDateTime receivedAt) {
        validate(fields);
        var protectedFields = new LinkedHashMap<String, String>();
        for (String name : List.of("OrderNo", "RechargeAccount", "RechargeStatus", "StoreNo")) protectedFields.put(name, fields.get(name));
        if (!RenyueProtocol.matchesReceipt(fields.get("ReturnSign"), protectedFields, ticket.resolvedCredentialMaterial())) {
            throw new SingleStepCallbackException(AUTHENTICATION_FAILED);
        }
        if (!ticket.billno().equals(fields.get("StoreNo")) || !ticket.rechargeAccount().equals(fields.get("RechargeAccount"))
                || ticket.externalOrderNo() != null && !ticket.externalOrderNo().equals(fields.get("OrderNo"))) {
            throw new SingleStepCallbackException(IDENTITY_CONFLICT);
        }
        var state = switch (fields.get("RechargeStatus")) {
            case "2" -> SingleStepSupplierFact.Observation.SUCCESS;
            case "3" -> SingleStepSupplierFact.Observation.FAILURE;
            default -> SingleStepSupplierFact.Observation.RESPONSE_UNKNOWN;
        };
        return new SingleStepSupplierFact(frozen.binding(), SingleStepSupplierFact.Source.CALLBACK, ticket.billno(),
                frozen.business().protectedTargetReference().targetDigest(), 1, SingleStepSupplierFact.Verification.VERIFIED,
                state, fields.get("OrderNo"), receivedAt, digest(fields.get("ReturnSign").getBytes(StandardCharsets.US_ASCII)), null, false);
    }

    private static void validate(Map<String, String> fields) {
        if (!FIELDS.containsAll(fields.keySet())) throw new SingleStepCallbackException(INVALID_CALLBACK);
        for (String name : List.of("OrderNo", "RechargeAccount", "RechargeStatus", "StoreNo", "ReturnSign")) {
            String value = fields.get(name);
            if (value == null || value.isBlank() || value.length() > (name.equals("ReturnSign") ? MAX_BODY_BYTES : 128)) {
                throw new SingleStepCallbackException(INVALID_CALLBACK);
            }
        }
        if (!fields.get("StoreNo").matches("[0-9]{24}") || fields.containsKey("TimeStamp") && fields.get("TimeStamp").length() > 32) {
            throw new SingleStepCallbackException(INVALID_CALLBACK);
        }
    }

    private static String decodeComponent(String value) throws IOException {
        byte[] source = value.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream(source.length);
        for (int i = 0; i < source.length; i++) {
            int b = source[i] & 255;
            if (b == '%') {
                if (i + 2 >= source.length) throw new SingleStepCallbackException(INVALID_CALLBACK);
                int hi = Character.digit((char) source[++i], 16); int lo = Character.digit((char) source[++i], 16);
                if (hi < 0 || lo < 0) throw new SingleStepCallbackException(INVALID_CALLBACK);
                out.write(hi * 16 + lo);
            } else out.write(b == '+' ? ' ' : b);
        }
        byte[] decoded = out.toByteArray();
        try { return decodeUtf8(decoded); }
        finally { Arrays.fill(source, (byte) 0); Arrays.fill(decoded, (byte) 0); }
    }
    private static String decodeUtf8(byte[] value) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
    }
    static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
