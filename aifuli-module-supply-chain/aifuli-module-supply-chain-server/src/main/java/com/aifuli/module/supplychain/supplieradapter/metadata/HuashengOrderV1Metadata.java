package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 华昇首期type201的封闭能力、配置与安全映射。无自动注册注解，Task5统一条件装配。
 * 外部SUCCESS仅是协议中间事实，不能直接提交资金。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#32-metadata-与发布约束。
 */
public final class HuashengOrderV1Metadata implements AdapterMetadata {
    public static final String TYPE = "HUA_SHENG_ORDER_V1";
    public static final String APP_SECRET = "appSecret";
    public static final String PRIVATE_KEY = "privateKey";
    public static final String CALLBACK_PUBLIC_KEY = "callbackPublicKey";
    public static final Set<String> OUTBOUND_KEYS = Set.of(APP_SECRET, PRIVATE_KEY);
    public static final String CALLBACK_PATH = "/callbacks/suppliers/huasheng";
    public static final int DEFAULT_EXPIRE_SECONDS = 300;
    public static final int MIN_EXPIRE_SECONDS = 60;
    public static final int MAX_EXPIRE_SECONDS = 1800;
    private static final int SCHEMA_VERSION = 1;
    private static final int APP_ID_BYTES = 16;
    private static final int MOBILE_BYTES = 64;
    private static final String SCHEMA_ROOT = "supply-chain/adapter-schemas/huasheng-order-v1-";

    @Override public String adapterType() { return TYPE; }
    @Override public CredentialBindingMode credentialBindingMode() { return CredentialBindingMode.CREDENTIAL_SET; }
    @Override public Set<String> credentialKeys() { return Set.of(APP_SECRET, PRIVATE_KEY, CALLBACK_PUBLIC_KEY); }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.ISSUE_ASSET, Capability.QUERY_ORDER, Capability.RECEIVE_CALLBACK); }
    @Override public String connectorSchemaResource() { return SCHEMA_ROOT + "connector.schema.json"; }
    @Override public String offerSchemaResource() { return SCHEMA_ROOT + "offer.schema.json"; }
    @Override public String extraConfigSchemaResource() { return SCHEMA_ROOT + "extra.schema.json"; }
    @Override public int connectorSchemaVersion() { return SCHEMA_VERSION; }
    @Override public int offerSchemaVersion() { return SCHEMA_VERSION; }
    @Override public void validateConnectorConfig(int version, JsonNode config) { empty(version, config); }
    @Override public void validateOfferConfig(int version, JsonNode config) { empty(version, config); }
    @Override public void validateMerchantAccount(String account) {
        require(account != null && account.getBytes(StandardCharsets.UTF_8).length == APP_ID_BYTES);
    }
    @Override public void validateExtraConfig(JsonNode config) {
        require(config != null && config.isObject());
        config.fieldNames().forEachRemaining(key -> require(Set.of("expireTime", "outMobile").contains(key)));
        if (config.has("expireTime")) {
            var value = config.get("expireTime");
            require(value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= MIN_EXPIRE_SECONDS && value.intValue() <= MAX_EXPIRE_SECONDS);
        }
        if (config.has("outMobile")) {
            var value = config.get("outMobile");
            require(value.isTextual() && !value.textValue().isBlank()
                    && value.textValue().getBytes(StandardCharsets.UTF_8).length <= MOBILE_BYTES
                    && value.textValue().chars().noneMatch(Character::isISOControl));
        }
    }
    @Override public ExecutionProfileDefaults defaultExecutionProfile() {
        return new ExecutionProfileDefaults(CompletionMode.QUERY_OR_CALLBACK, CreateIdempotencyMode.DEDUP_ONLY,
                UnknownResultPolicy.QUERY_ORDER, PartialDeliveryMode.FORBIDDEN, ResultMultiplicity.SINGLE, "{}");
    }
    @Override public List<String> fulfillmentSteps() { return List.of("PLACE_ORDER", "CONFIRM_ORDER", "TAKE_CARD", "QUERY_ORDER"); }
    /** 华昇十行非终态模板与空错误表是发布不变量；设计见 docs/superpowers/specs/2026-10-12-huasheng-fulfillment-hardening-design.md#3-映射管理契约。 */
    @Override public boolean statusMappingAppendAllowed() { return false; }
    @Override public boolean errorMappingAppendAllowed() { return false; }
    @Override public String mappingDescription() {
        return "华昇采用封闭模板：CREATE 四行、QUERY 四行、CALLBACK 两行，共十行非终态映射，错误映射表必须为空；"
                + "仅支持一次完整初始化，不支持逐条追加。外部状态只是协议事实，资金结果由受控履约流程核实，不能通过填表配置退款、切供或无界重试。";
    }
    @Override public MappingTemplates mappingTemplates() {
        var rows = new ArrayList<MappingTemplates.StatusMappingTemplate>();
        for (String phase : List.of("CREATE", "QUERY", "CALLBACK")) {
            for (String status : phase.equals("CALLBACK") ? List.of("SUCCESS", "FAILED") : List.of("WAIT", "SUCCESS", "FAILED", "CANCEL")) {
                rows.add(new MappingTemplates.StatusMappingTemplate("status=" + status, "", phase,
                        status.equals("WAIT") || status.equals("SUCCESS") ? "ACCEPTED" : "RESULT_UNKNOWN", false));
            }
        }
        return new MappingTemplates(List.copyOf(rows), List.of());
    }

    /** 发布和运行时均核对恰十行非终态模板，防止验证后改映射绕过资金边界。设计同§3.2。 */
    @Override
    public void validateMappings(List<StatusMappingDefinitionDO> statuses, List<SupplierErrorCodeMapDO> errors) {
        var expected = new HashSet<>(mappingTemplates().statusMappings());
        require(statuses != null && statuses.size() == expected.size() && errors != null && errors.isEmpty());
        for (var row : statuses) {
            require(row.getTerminalFlag() != null && row.getTerminalFlag() == 0);
            require(expected.remove(new MappingTemplates.StatusMappingTemplate(row.getExternalStatus(),
                    row.getExternalSubStatus(), row.getBusinessPhase(), row.getFactType(), false)));
        }
        require(expected.isEmpty());
    }

    /** 外部商品编码只接受ASCII正整数，禁止trim、符号、小数与溢出。设计同§3.2。 */
    public static long couponId(String externalSkuCode) {
        require(externalSkuCode != null && externalSkuCode.matches("[0-9]+"));
        try { long id = Long.parseLong(externalSkuCode); require(id > 0); return id; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("华昇配置不符合封闭契约"); }
    }
    private static void empty(int version, JsonNode config) { require(version == SCHEMA_VERSION && config != null && config.isObject() && config.isEmpty()); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("华昇配置不符合封闭契约"); }
}
