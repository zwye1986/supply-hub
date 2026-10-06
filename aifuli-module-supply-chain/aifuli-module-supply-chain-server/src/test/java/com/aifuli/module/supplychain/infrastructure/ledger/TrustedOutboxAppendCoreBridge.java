package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;

import javax.sql.DataSource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.LocalDateTime;

/**
 * 仅 src/test 的同模块 privateLookupIn 反射桥：驱动 {@link OutboxEventRepository} 内 main
 * 私有嵌套可信追加底座（TrustedOutboxAppendCore）。不复制算法、不进入 main 资源／运行配置／jar。
 * JDK21 同 unnamed module，无需 add-opens。fixture 不提供 eventId／slotId／nonce／绑定证明。
 */
final class TrustedOutboxAppendCoreBridge {

    private static final String HOST = "com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository";

    private static final MethodHandles.Lookup LOOKUP = lookup();

    private static final Class<?> CORE_CLASS = forName(HOST + "$TrustedOutboxAppendCore");
    private static final Class<?> COMMAND_CLASS = forName(HOST + "$VerifiedAppendCommand");
    private static final Class<?> PROOF_CLASS = forName(HOST + "$BindingProof");

    private static final MethodHandle CORE_CTOR = findConstructor(CORE_CLASS,
            MethodType.methodType(void.class, OutboxEventMapper.class, BusinessNoGenerator.class,
                    OutboxAppendIdentityMapper.class, DataSource.class));
    private static final MethodHandle COMMAND_CTOR = findConstructor(COMMAND_CLASS,
            MethodType.methodType(void.class, String.class, String.class, Long.class, String.class,
                    String.class, String.class, Integer.class, String.class, String.class,
                    String.class, String.class, String.class, LocalDateTime.class));
    private static final MethodHandle PROOF_CTOR = findConstructor(PROOF_CLASS,
            MethodType.methodType(void.class, Long.class, byte[].class, String.class));
    private static final MethodHandle APPEND = findVirtual(CORE_CLASS, "append",
            MethodType.methodType(String.class, COMMAND_CLASS));
    private static final MethodHandle GUARD_RESERVATION = findVirtual(CORE_CLASS, "guardReservation",
            MethodType.methodType(void.class, String.class));
    private static final MethodHandle PROOF_NONCE_COPY = findVirtual(PROOF_CLASS, "nonceCopy",
            MethodType.methodType(byte[].class));

    private TrustedOutboxAppendCoreBridge() {
    }

    static Object newCore(OutboxEventMapper sourceMapper, BusinessNoGenerator businessNoGenerator,
                          OutboxAppendIdentityMapper ledgerMapper, DataSource supplyChainDataSource) {
        try {
            return CORE_CTOR.invoke(sourceMapper, businessNoGenerator, ledgerMapper, supplyChainDataSource);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    static Object newCommand(String aggregateType, String aggregateId, Long aggregateVersion,
                             String eventType, String targetType, String targetName,
                             Integer payloadVersion, String comparisonProtocol, String payloadJson,
                             String traceId, String correlationId, String causationId,
                             LocalDateTime occurredAt) {
        try {
            return COMMAND_CTOR.invoke(aggregateType, aggregateId, aggregateVersion, eventType,
                    targetType, targetName, payloadVersion, comparisonProtocol, payloadJson,
                    traceId, correlationId, causationId, occurredAt);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    static String append(Object core, Object command) {
        try {
            return (String) APPEND.invoke(core, command);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    static void guardReservation(Object core, String key) {
        try {
            GUARD_RESERVATION.invoke(core, key);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    static Object newProof(Long slotId, byte[] nonce, String eventId) {
        try {
            return PROOF_CTOR.invoke(slotId, nonce, eventId);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    static byte[] proofNonceCopy(Object proof) {
        try {
            return (byte[]) PROOF_NONCE_COPY.invoke(proof);
        } catch (Throwable failure) {
            throw sneaky(failure);
        }
    }

    private static MethodHandles.Lookup lookup() {
        try {
            return MethodHandles.privateLookupIn(OutboxEventRepository.class, MethodHandles.lookup());
        } catch (IllegalAccessException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Class<?> forName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static MethodHandle findConstructor(Class<?> target, MethodType type) {
        try {
            return LOOKUP.findConstructor(target, type);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static MethodHandle findVirtual(Class<?> target, String name, MethodType type) {
        try {
            return LOOKUP.findVirtual(target, name, type);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 保留原始异常类型与 cause 链；仅测试桥使用。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable failure) throws E {
        throw (E) failure;
    }
}
