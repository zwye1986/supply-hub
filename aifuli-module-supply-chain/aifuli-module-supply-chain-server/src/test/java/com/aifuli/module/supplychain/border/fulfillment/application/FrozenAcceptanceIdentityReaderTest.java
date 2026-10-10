package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 冻结身份只读契约；见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#61-请求准备。 */
class FrozenAcceptanceIdentityReaderTest {
    private final FulfillmentAcceptanceReceiptMapper mapper = mock(FulfillmentAcceptanceReceiptMapper.class);

    @Test
    void missingReceiptDoesNotInventFrozenIdentity() throws Exception {
        assertThat(find()).isEmpty();
        verify(mapper).selectByProjectKey(11L, "ORDER_FROZEN");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void completeBindingUsesReceiptSkuAndSchemaWithoutCurrentCatalog() throws Exception {
        when(mapper.selectByProjectKey(11L, "ORDER_FROZEN")).thenReturn(receipt());
        Object identity = find().orElseThrow();
        assertThat(value(identity, "platformSkuId")).isEqualTo(4L);
        assertThat(value(identity, "schemaCode")).isEqualTo("CARD_TARGET");
        assertThat(value(identity, "schemaVersion")).isEqualTo(1);
        assertThat(value(identity, "accepted")).isEqualTo(true);
        verify(mapper).selectByProjectKey(11L, "ORDER_FROZEN");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void processingBindingRemainsFrozenButIsNotReplayAuthorization() throws Exception {
        var receipt = receipt();
        receipt.setStatus(AcceptanceReceiptStatus.PROCESSING.getCode());
        when(mapper.selectByProjectKey(11L, "ORDER_FROZEN")).thenReturn(receipt);
        assertThat(value(find().orElseThrow(), "accepted")).isEqualTo(false);
    }

    @Test
    void legacyFingerprintFailsClosedInsteadOfReadingCurrentCatalog() throws Exception {
        var receipt = receipt();
        receipt.setFingerprintVersion((short) 0);
        assertUntrusted(receipt);
    }

    @Test
    void missingFrozenSkuFailsClosed() throws Exception {
        var receipt = receipt();
        receipt.setPlatformSkuId(null);
        assertUntrusted(receipt);
    }

    @Test
    void missingFrozenSchemaFailsClosed() throws Exception {
        var receipt = receipt();
        receipt.setTargetSchemaCode("");
        assertUntrusted(receipt);
    }

    @Test
    void missingFrozenSchemaVersionFailsClosed() throws Exception {
        var receipt = receipt();
        receipt.setTargetSchemaVersion(null);
        assertUntrusted(receipt);
    }

    @Test
    void wrongBuyerBindingFailsClosed() throws Exception {
        var receipt = receipt();
        receipt.setBuyerId(99L);
        assertUntrusted(receipt);
    }

    @Test
    void readPointUsesSupplyChainReadOnlyTransaction() throws Exception {
        Transactional transaction = readerType().getMethod("find", Long.class, Long.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.transactionManager()).isEqualTo("supplyChainTransactionManager");
        assertThat(transaction.readOnly()).isTrue();
    }

    private void assertUntrusted(FulfillmentAcceptanceReceiptDO receipt) throws Exception {
        readerType(); // 缺失实现必须先作为明确断言红灯，不把反射类缺失伪装成业务冲突。
        when(mapper.selectByProjectKey(11L, "ORDER_FROZEN")).thenReturn(receipt);
        assertThatThrownBy(this::find).isInstanceOf(FulfillmentAcceptanceLifecycle.IdempotencyConflictException.class);
    }

    private Optional<?> find() throws Exception {
        Class<?> type = readerType();
        Object reader = type.getConstructor(FulfillmentAcceptanceReceiptMapper.class).newInstance(mapper);
        try {
            return (Optional<?>) type.getMethod("find", Long.class, Long.class, String.class)
                    .invoke(reader, 1L, 11L, "ORDER_FROZEN");
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) { throw cause; }
            throw exception;
        }
    }

    private static Class<?> readerType() throws ClassNotFoundException {
        String name = "com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader";
        assertThat(FrozenAcceptanceIdentityReaderTest.class.getClassLoader().getResource(name.replace('.', '/') + ".class"))
                .as("必须提供border冻结身份只读服务").isNotNull();
        return Class.forName(name);
    }

    private static Object value(Object identity, String field) throws Exception {
        return identity.getClass().getMethod(field).invoke(identity);
    }

    private static FulfillmentAcceptanceReceiptDO receipt() {
        return FulfillmentAcceptanceReceiptDO.builder().id(101L).buyerId(1L).projectId(11L)
                .buyerOrderNo("ORDER_FROZEN").buyerSkuCode("BPD_FROZEN").platformSkuId(4L)
                .targetSchemaCode("CARD_TARGET").targetSchemaVersion(1)
                .fingerprintVersion((short) StandardAcceptanceFingerprintPolicy.FORMAT_VERSION)
                .status(AcceptanceReceiptStatus.ACCEPTED.getCode()).build();
    }
}
