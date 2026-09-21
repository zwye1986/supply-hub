package com.aifuli.module.supplychain.error;

import com.aifuli.framework.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_LEGACY_API_DEPRECATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ENTRY_URL_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OPENING_ALREADY_DONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳定错误目录与 RetryDirective 映射单测。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁}。</p>
 */
class SupplyChainErrorMappingTest {

    @ParameterizedTest
    @CsvSource({
            "1050001001,DO_NOT_RETRY",
            "1050001002,DO_NOT_RETRY",
            "1050001003,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001005,DO_NOT_RETRY",
            "1050001006,DO_NOT_RETRY",
            "1050001007,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001010,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001011,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001012,QUERY_BY_BUSINESS_KEY",
            "1050001013,QUERY_BY_BUSINESS_KEY",
            "1050001014,QUERY_BY_BUSINESS_KEY",
            "1050001015,MANUAL",
            "1050001017,RETRY_SAME_KEY",
            "1050001020,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001021,DO_NOT_RETRY",
            "1050001023,DO_NOT_RETRY",
            "1050001024,QUERY_BY_BUSINESS_KEY",
            "1050001025,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001026,DO_NOT_RETRY",
            "1050001027,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001028,DO_NOT_RETRY",
            "1050001029,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001030,DO_NOT_RETRY",
            "1050001031,QUERY_BY_BUSINESS_KEY",
            "1050001032,QUERY_BY_BUSINESS_KEY",
            "1050001033,MANUAL",
            "1050001034,QUERY_BY_BUSINESS_KEY",
            "1050001035,QUERY_BY_BUSINESS_KEY",
            "1050001036,DO_NOT_RETRY",
            "1050001037,DO_NOT_RETRY",
            "1050001038,DO_NOT_RETRY",
            "1050001039,DO_NOT_RETRY",
            "1050001040,MANUAL",
            "1050001041,QUERY_BY_BUSINESS_KEY",
            "1050001042,MANUAL",
            "1050001043,DO_NOT_RETRY",
            "1050001044,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001045,RETRY_SAME_KEY",
            "1050001046,RETRY_SAME_KEY",
            "1050001047,DO_NOT_RETRY",
            "1050001048,DO_NOT_RETRY",
            "1050001049,DO_NOT_RETRY",
            "1050001050,DO_NOT_RETRY",
            "1050001051,DO_NOT_RETRY",
            "1050001052,DO_NOT_RETRY",
            "1050001053,DO_NOT_RETRY",
            "1050001054,DO_NOT_RETRY",
            "1050001055,DO_NOT_RETRY",
            "1050001056,DO_NOT_RETRY",
            "1050001057,DO_NOT_RETRY",
            "1050001058,DO_NOT_RETRY",
            "1050001059,DO_NOT_RETRY",
            "1050001060,DO_NOT_RETRY",
            "1050001061,DO_NOT_RETRY",
            "1050001062,DO_NOT_RETRY",
            "1050001063,DO_NOT_RETRY",
            "1050001064,DO_NOT_RETRY",
            "1050001065,DO_NOT_RETRY",
            "1050001066,DO_NOT_RETRY",
            "1050001067,DO_NOT_RETRY",
            "1050001068,DO_NOT_RETRY",
            "1050001069,DO_NOT_RETRY",
            "1050001070,DO_NOT_RETRY",
            "1050001071,DO_NOT_RETRY",
            "1050001072,DO_NOT_RETRY",
            "1050001073,DO_NOT_RETRY",
            "1050001074,DO_NOT_RETRY",
            "1050001075,DO_NOT_RETRY",
            "1050001076,DO_NOT_RETRY",
            "1050001107,DO_NOT_RETRY",
            "1050001108,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001109,DO_NOT_RETRY",
            "1050001110,RETRY_SAME_KEY_AFTER_CONDITION_CHANGE",
            "1050001111,DO_NOT_RETRY",
            "1050001116,DO_NOT_RETRY",
            "1050001117,DO_NOT_RETRY",
            "1050001118,DO_NOT_RETRY",
            "1050001119,DO_NOT_RETRY",
            "1050001120,DO_NOT_RETRY",
            "1050001121,DO_NOT_RETRY"
    })
    void mapsStableErrorCodesToRetryDirectives(int code, RetryDirective expected) {
        assertThat(SupplyChainErrorCodeConstants.retryDirective(code)).isEqualTo(expected);
    }

    @Test
    void enumNotOpenAliasesSupplierEnumNotOpen() {
        assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN)
                .isSameAs(SupplyChainErrorCodeConstants.SUPPLIER_ENUM_NOT_OPEN);
        assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN.getCode()).isEqualTo(1_050_001_052);
        assertThat(SupplyChainErrorCodeConstants.ENUM_NOT_OPEN.getMsg()).isEqualTo("未知枚举值");
    }

    @Test
    void unknownCodeDefaultsToDoNotRetry() {
        assertThat(SupplyChainErrorCodeConstants.retryDirective(9_999_999))
                .isEqualTo(RetryDirective.DO_NOT_RETRY);
    }

    @Test
    void preservesInstoreMemberEntryErrorContracts() {
        assertErrorContract(INSTORE_CREDENTIAL_REVOKED, 1_050_001_043,
                "到店购入口凭证已撤销", RetryDirective.DO_NOT_RETRY);
        assertErrorContract(INSTORE_MEMBER_PROFILE_INVALID, 1_050_001_044,
                "到店购会员资料无效", RetryDirective.RETRY_SAME_KEY_AFTER_CONDITION_CHANGE);
        assertErrorContract(INSTORE_MEMBER_PROFILE_UNAVAILABLE, 1_050_001_045,
                "到店购会员资料暂时不可用", RetryDirective.RETRY_SAME_KEY);
        assertErrorContract(INSTORE_ENTRY_URL_UNAVAILABLE, 1_050_001_046,
                "到店购入口地址暂时不可用", RetryDirective.RETRY_SAME_KEY);
        assertErrorContract(SUPPLIER_OPENING_ALREADY_DONE, 1_050_001_050,
                "供应商期初已入账，禁止再改余额字段", RetryDirective.DO_NOT_RETRY);
    }

    /**
     * 旧品牌创建接口终止写入后，调用方必须迁移到新接口，不能重试旧请求。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}。
     */
    @Test
    void preservesBrandLegacyApiRetirementContract() {
        assertErrorContract(BRAND_LEGACY_API_DEPRECATED, 1_050_001_106,
                "品牌旧接口已废弃，请使用 POST /supply-chain/brands", RetryDirective.DO_NOT_RETRY);
    }

    @Test
    void everyDeclaredErrorCodeHasRetryDirective() throws Exception {
        Set<Integer> declared = new HashSet<>();
        for (Field field : SupplyChainErrorCodeConstants.class.getFields()) {
            if (ErrorCode.class.equals(field.getType())) {
                ErrorCode errorCode = (ErrorCode) field.get(null);
                declared.add(errorCode.getCode());
                assertThat(SupplyChainErrorCodeConstants.RETRY_DIRECTIVES)
                        .containsKey(errorCode.getCode());
            }
        }
        assertThat(declared).hasSameSizeAs(SupplyChainErrorCodeConstants.RETRY_DIRECTIVES.keySet());
        assertThat(declared).containsExactlyInAnyOrderElementsOf(
                SupplyChainErrorCodeConstants.RETRY_DIRECTIVES.keySet());
    }

    private static void assertErrorContract(ErrorCode errorCode, int expectedCode, String expectedMessage,
                                            RetryDirective expectedDirective) {
        assertThat(errorCode.getCode()).isEqualTo(expectedCode);
        assertThat(errorCode.getMsg()).isEqualTo(expectedMessage);
        assertThat(SupplyChainErrorCodeConstants.retryDirective(errorCode.getCode()))
                .isEqualTo(expectedDirective);
    }
}
