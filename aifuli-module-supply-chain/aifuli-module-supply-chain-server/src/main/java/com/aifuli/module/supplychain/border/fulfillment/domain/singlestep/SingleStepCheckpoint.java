package com.aifuli.module.supplychain.border.fulfillment.domain.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialSlotIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetSchemaCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepTimeoutContract;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 单步骤原单的非秘密、不可变冻结合同；目标只保存受保护存储引用，不保存手机号或凭据材料。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#31-单步骤检查点v1。
 */
public record SingleStepCheckpoint(int schemaVersion, Binding binding, Business business,
                                   CredentialIdentity credentialIdentity, RecoveryContract recoveryContract) {
    public static final int SCHEMA_VERSION = 1;
    public static final String POLICY_CODE = "SINGLE_STEP_MOBILE_V1";
    public static final String DEADLINE_POLICY_CODE = "FIRST_CREATE_DAY_PLUS_TWO_MIDNIGHT";
    public static final String DEADLINE_ZONE_ID = "Asia/Shanghai";

    public SingleStepCheckpoint {
        require(schemaVersion == SCHEMA_VERSION && binding != null && business != null
                && credentialIdentity != null && recoveryContract != null && binding.adapterStepCode() == AdapterStepCode.SEND);
    }

    public record Binding(String operationNo, long bOrderId, String adapterType, AdapterStepCode adapterStepCode,
                          long supplierId, long connectorVersionId, long offerVersionId, String configDigest) {
        public Binding {
            require(text(operationNo) && bOrderId > 0 && text(adapterType) && adapterStepCode != null
                    && supplierId > 0 && connectorVersionId > 0 && offerVersionId > 0 && digest(configDigest));
        }
    }

    public record TargetReference(long requestId, String requestNo, int targetSchemaVersion,
                                  String targetDigest, String targetKeyVersion) {
        public TargetReference {
            require(requestId > 0 && text(requestNo) && targetSchemaVersion > 0 && digest(targetDigest)
                    && text(targetKeyVersion) && targetKeyVersion.length() <= 64);
        }
    }

    public record Business(String billno, String externalSkuCode, long quantity, String targetSchemaCode,
                           TargetReference protectedTargetReference, String callbackUrl,
                           long settlementUnitPriceMinorUnits, String currency) {
        public Business {
            require(billno != null && billno.matches("[0-9]{24}") && text(externalSkuCode) && quantity == 1
                    && FulfillmentTargetSchemaCode.DIRECT_RECHARGE_TARGET.name().equals(targetSchemaCode)
                    && protectedTargetReference != null && text(callbackUrl) && settlementUnitPriceMinorUnits >= 0
                    && Money.CURRENCY_CNY.equals(currency));
        }
    }

    /** 槽版本保持String，集项版本保持Integer；互斥引用，不含secretRef或秘密。 */
    public record CredentialIdentity(FrozenCredentialSlotIdentity slot, Long setId,
                                     List<FrozenCredentialItemIdentity> items) {
        public CredentialIdentity {
            require(items != null && items.size() <= 16 && (slot != null) != (setId != null));
            items = items.stream().map(Objects::requireNonNull).sorted(Comparator.comparing(FrozenCredentialItemIdentity::itemKey)).toList();
            require(slot != null ? items.isEmpty() : setId > 0 && !items.isEmpty());
            require(items.stream().map(FrozenCredentialItemIdentity::itemId).distinct().count() == items.size()
                    && items.stream().map(FrozenCredentialItemIdentity::itemKey).distinct().count() == items.size());
        }
    }

    public record RecoveryContract(String policyCode, int firstQueryDelaySeconds, int timeoutFirstQueryDelaySeconds,
                                   int processingIntervalSeconds, int notFoundIntervalSeconds,
                                   int notFoundConsecutiveThreshold, int errorBackoffSeconds, int maxQueryClaims,
                                   String deadlinePolicyCode, String deadlineZoneId, int connectTimeoutMs,
                                   int readWriteTimeoutMs, int totalCallTimeoutMs, int executionLeaseMs) {
        public RecoveryContract {
            require(POLICY_CODE.equals(policyCode) && (firstQueryDelaySeconds == 180 || firstQueryDelaySeconds == 600)
                    && timeoutFirstQueryDelaySeconds == 180 && processingIntervalSeconds == 600 && notFoundIntervalSeconds == 180
                    && notFoundConsecutiveThreshold == 3 && errorBackoffSeconds == 600 && maxQueryClaims == 1000
                    && DEADLINE_POLICY_CODE.equals(deadlinePolicyCode) && DEADLINE_ZONE_ID.equals(deadlineZoneId));
            require(new SingleStepTimeoutContract(connectTimeoutMs, readWriteTimeoutMs, totalCallTimeoutMs, executionLeaseMs)
                    .equals(SingleStepTimeoutContract.initial()));
        }

        /** 已确认的平台策略；未生成认领日期、绝对截止或实际lease，见详细设计§4.4。 */
        public static RecoveryContract initial(int firstQueryDelaySeconds) {
            var http = SingleStepTimeoutContract.initial();
            return new RecoveryContract(POLICY_CODE, firstQueryDelaySeconds, 180, 600, 180, 3, 600, 1000,
                    DEADLINE_POLICY_CODE, DEADLINE_ZONE_ID, http.connectTimeoutMs(), http.readWriteTimeoutMs(),
                    http.totalCallTimeoutMs(), http.executionLeaseMs());
        }

        public SingleStepTimeoutContract timeouts() {
            return new SingleStepTimeoutContract(connectTimeoutMs, readWriteTimeoutMs, totalCallTimeoutMs, executionLeaseMs);
        }
    }

    private static boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 4096; }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("单步骤检查点无效"); }
    @Override public String toString() { return "SingleStepCheckpoint[redacted]"; }
}
