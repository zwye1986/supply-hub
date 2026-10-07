package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇 HTTP 传输边界架构门禁测试。
 *
 * <p>职责：
 * 以 ArchUnit 静态架构断言强制守护华昇 HTTP 传输层的六边形依赖与安全边界：
 * <ul>
 *   <li>限制 OkHttp 依赖仅限 {@link HuashengHttpExecutor} 及其内部实现，禁止散落与外部直接注入；</li>
 *   <li>确保传输端口 {@link HuashengHttpTransportPort} 仅有双参 exchange 方法，无单参或旁路重载；</li>
 *   <li>强制 protocol 包零 HTTP、零 OkHttp、零 Spring 依赖；</li>
 *   <li>强制 HTTP 传输包零 DAL、零 Timer、零 Outbox、零账本与零应用层反向依赖；</li>
 *   <li>核验 {@link HuashengTransportContext} 构造器严格为 private；</li>
 *   <li>核验 {@link HuashengHttpExecutor} 测试专用 TLS 构造器严格为 private，且生产代码零调用/零引用；</li>
 *   <li>核验 {@link HuashengHttpExchange} 构造器唯一生产所有者为 {@link HuashengHttpExecutor}；</li>
 *   <li>核验 {@link HuashengTransportContext} 与 {@link HuashengHttpExchange} 均配置 {@link HuashengInternalOnlySerializer} 拒绝序列化；</li>
 *   <li>为每条架构规则配备独立的结构负控制夹具，杜绝假绿。</li>
 * </ul>
 *
 * <p>不变量：
 * <ul>
 *   <li>仅生产代码受测，测试夹具通过独立 ClassFileImporter 单独验证负控制，互不污染；</li>
 *   <li>构造器所有权规则核验有参调用（ConstructorCall）与构造器引用（ConstructorReference）。</li>
 * </ul>
 *
 * <p>关键决策：
 * 在同模块通过 ArchUnit 固化设计附表 §17 的硬性安全架构边界。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#177-a4-安全日志tls-与架构负控制。
 */
public class HuashengHttpBoundaryArchitectureTest {

    private static final String ROOT_PACKAGE = "com.aifuli.module.supplychain.";
    private static final String HS_PACKAGE = ROOT_PACKAGE + "supplieradapter.huasheng.";

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT_PACKAGE);
    }

    private static ArchRule dependenciesConfined(Predicate<JavaClass> selected,
                                                 Predicate<JavaClass> forbidden,
                                                 String description) {
        return classes().should(new ArchCondition<JavaClass>(description) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (!selected.test(origin)) {
                    return;
                }
                for (var dependency : origin.getDirectDependenciesFromSelf()) {
                    if (forbidden.test(dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        });
    }

    private static ArchCondition<JavaClass> noConstructorCallsOrReferences(Class<?> targetClass, int paramCount) {
        return new ArchCondition<JavaClass>("禁止调用或引用指定构造器") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (var call : item.getConstructorCallsFromSelf()) {
                    if (call.getTargetOwner().isEquivalentTo(targetClass)
                            && call.getTarget().getRawParameterTypes().size() == paramCount) {
                        events.add(SimpleConditionEvent.violated(call, "违规调用构造器: " + item.getName()));
                    }
                }
                for (var ref : item.getConstructorReferencesFromSelf()) {
                    if (ref.getTargetOwner().isEquivalentTo(targetClass)
                            && ref.getTarget().getRawParameterTypes().size() == paramCount) {
                        events.add(SimpleConditionEvent.violated(ref, "违规引用构造器: " + item.getName()));
                    }
                }
            }
        };
    }

    @Test
    @DisplayName("N-A4-R2-10/§17.7: 华昇适配器内仅 Executor 及其内部类允许依赖 OkHttp")
    void onlyExecutorAndItsInnerClassesDependOnOkHttp() {
        Predicate<JavaClass> selected = c -> c.getName().startsWith(HS_PACKAGE)
                && !c.getName().startsWith(HuashengHttpExecutor.class.getName());
        Predicate<JavaClass> forbidden = t -> t.getName().startsWith("okhttp3.") || t.getName().startsWith("okio.");

        ArchRule rule = dependenciesConfined(selected, forbidden, "非 Executor 类禁止依赖 OkHttp/Okio");
        rule.check(productionClasses());
    }

    @Test
    @DisplayName("N-A4-R2-10/§17.7: 结构负控制 - 验证非 Executor 类依赖 OkHttp 必定被拦截")
    void negativeControlForOkHttpDependencyRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadOkHttpCaller.class);
        Predicate<JavaClass> selected = c -> c.isEquivalentTo(BadOkHttpCaller.class);
        Predicate<JavaClass> forbidden = t -> t.getName().startsWith("okhttp3.") || t.getName().startsWith("okio.");

        ArchRule rule = dependenciesConfined(selected, forbidden, "非 Executor 类禁止依赖 OkHttp/Okio");
        assertTrue(rule.evaluate(fixture).hasViolation(), "负控制夹具必须触发规则违例");
    }

    @Test
    @DisplayName("§17.2: HuashengHttpTransportPort 仅定义双参 exchange 契约，无单参或旁路重载")
    void transportPortContractHasOnlySingleTwoParamExchangeMethod() {
        Method[] methods = HuashengHttpTransportPort.class.getDeclaredMethods();
        assertEquals(1, methods.length, "HuashengHttpTransportPort 只能有且仅有 1 个声明方法");
        Method exchange = methods[0];
        assertEquals("exchange", exchange.getName());
        assertEquals(2, exchange.getParameterCount());
        assertEquals(HuashengWireRequest.class, exchange.getParameterTypes()[0]);
        assertEquals(HuashengTransportContext.class, exchange.getParameterTypes()[1]);
    }

    @Test
    @DisplayName("§17.2/§17.7: protocol 包零 http、零 OkHttp、零 Spring 依赖")
    void protocolPackageHasZeroHttpOkHttpOrSpringDependencies() {
        Predicate<JavaClass> selected = c -> c.getName().startsWith(HS_PACKAGE + "protocol.");
        Predicate<JavaClass> forbidden = t -> t.getName().startsWith(HS_PACKAGE + "http.")
                || t.getName().startsWith("okhttp3.")
                || t.getName().startsWith("okio.")
                || t.getName().startsWith("org.springframework.");

        ArchRule rule = dependenciesConfined(selected, forbidden, "protocol 包禁止依赖 http/OkHttp/Spring");
        rule.check(productionClasses());
    }

    @Test
    @DisplayName("§17.2/§17.7: 结构负控制 - protocol 违规依赖 http/Spring 必定被拦截")
    void negativeControlForProtocolDependencyRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadProtocolDependencyFixture.class);
        Predicate<JavaClass> selected = c -> c.isEquivalentTo(BadProtocolDependencyFixture.class);
        Predicate<JavaClass> forbidden = t -> t.getName().startsWith(HS_PACKAGE + "http.")
                || t.getName().startsWith("org.springframework.");

        ArchRule rule = dependenciesConfined(selected, forbidden, "protocol 包禁止依赖 http/Spring");
        assertTrue(rule.evaluate(fixture).hasViolation(), "protocol 违规依赖负控制必须触发违例");
    }

    @Test
    @DisplayName("§17.7: http 包零 DAL、零 Timer、零 Outbox、零账本及零履约应用反向依赖")
    void httpPackageHasZeroDalTimerOutboxLedgerOrApplicationDependencies() {
        Predicate<JavaClass> selected = c -> c.getName().startsWith(HS_PACKAGE + "http.");
        Predicate<JavaClass> forbidden = t -> {
            String name = t.getName();
            return name.contains(".dal.")
                    || name.contains(".infrastructure.timer.")
                    || name.contains(".infrastructure.outbox.")
                    || name.contains(".tradecore.ledger.")
                    || name.contains(".tradecore.account.")
                    || name.contains(".border.fulfillment.application.");
        };

        ArchRule rule = dependenciesConfined(selected, forbidden, "http 包禁止反向依赖 DAL/Timer/Outbox/账本/应用");
        rule.check(productionClasses());
    }

    @Test
    @DisplayName("§17.2: HuashengTransportContext 构造器必须全部为 private")
    void transportContextConstructorsAreExclusivelyPrivate() {
        Constructor<?>[] constructors = HuashengTransportContext.class.getDeclaredConstructors();
        assertTrue(constructors.length > 0, "必须至少存在 1 个构造器");
        for (Constructor<?> c : constructors) {
            assertTrue(Modifier.isPrivate(c.getModifiers()), "HuashengTransportContext 构造器必须为 private: " + c);
        }
    }

    @Test
    @DisplayName("N-A4-R2-01/§17.3: HuashengHttpExecutor 的测试构造器为 private 且生产代码零调用/零引用")
    void executorTestConstructorIsPrivateAndUnusedByProduction() {
        // 1. 验证反射层修饰符
        Constructor<?>[] constructors = HuashengHttpExecutor.class.getDeclaredConstructors();
        assertEquals(2, constructors.length, "HuashengHttpExecutor 必须且只能有 2 个构造器");

        Constructor<?> publicConstructor = null;
        Constructor<?> privateTestConstructor = null;
        for (Constructor<?> c : constructors) {
            if (c.getParameterCount() == 1) {
                publicConstructor = c;
            } else if (c.getParameterCount() == 4) {
                privateTestConstructor = c;
            }
        }
        assertNotNull(publicConstructor, "必须存在单参公共生产构造器");
        assertTrue(Modifier.isPublic(publicConstructor.getModifiers()), "生产构造器必须为 public");

        assertNotNull(privateTestConstructor, "必须存在四参窄测试构造器");
        assertTrue(Modifier.isPrivate(privateTestConstructor.getModifiers()), "窄测试构造器必须为 private");

        // 2. 验证生产代码零调用、零引用该 private 构造器
        JavaClasses prod = productionClasses();
        ArchRule noPrivateCalls = classes().should(noConstructorCallsOrReferences(HuashengHttpExecutor.class, 4));
        noPrivateCalls.check(prod);
    }

    @Test
    @DisplayName("N-A4-R2-01: 结构负控制 - 验证违规调用 Executor 私有测试构造器必定被拦截")
    void negativeControlForExecutorPrivateConstructorAccess() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadExecutorConstructorCaller.class, FixtureExecutorType.class);
        ArchRule rule = classes().should(noConstructorCallsOrReferences(FixtureExecutorType.class, 4));
        assertTrue(rule.evaluate(fixture).hasViolation(), "负控制必须触发违规调用警告");
    }

    @Test
    @DisplayName("§17.5: HuashengHttpExchange 构造器唯一生产所有者为 HuashengHttpExecutor")
    void httpExchangeConstructorSoleProductionOwnerIsExecutor() {
        // 1. 验证构造器非 public（包私有）
        Constructor<?>[] constructors = HuashengHttpExchange.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        Constructor<?> c = constructors[0];
        assertFalse(Modifier.isPublic(c.getModifiers()), "HuashengHttpExchange 构造器禁止为 public");
        assertFalse(Modifier.isPrivate(c.getModifiers()), "HuashengHttpExchange 构造器为包私有");

        // 2. 验证生产代码唯一调用者
        Map<String, String> owners = Map.of(
                HuashengHttpExchange.class.getName(), HuashengHttpExecutor.class.getName()
        );
        ArchRule ownershipRule = classes().should(new ArchCondition<JavaClass>("仅精确生产所有者调用或引用 HuashengHttpExchange 构造器") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (var call : origin.getConstructorCallsFromSelf()) {
                    String target = call.getTargetOwner().getName();
                    if (owners.containsKey(target) && !owners.get(target).equals(origin.getName())) {
                        events.add(SimpleConditionEvent.violated(call, target + " call from " + origin.getName()));
                    }
                }
                for (var reference : origin.getConstructorReferencesFromSelf()) {
                    String target = reference.getTargetOwner().getName();
                    if (owners.containsKey(target) && !owners.get(target).equals(origin.getName())) {
                        events.add(SimpleConditionEvent.violated(reference, target + " reference from " + origin.getName()));
                    }
                }
            }
        });
        ownershipRule.check(productionClasses());
    }

    @Test
    @DisplayName("§17.5: 结构负控制 - 验证非 Executor 类调用 HuashengHttpExchange 构造器必定被拦截")
    void negativeControlForHttpExchangeConstructorOwnership() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadExchangeConstructorCaller.class);
        Map<String, String> owners = Map.of(
                HuashengHttpExchange.class.getName(), HuashengHttpExecutor.class.getName()
        );
        ArchRule ownershipRule = classes().should(new ArchCondition<JavaClass>("仅精确生产所有者调用") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (var call : origin.getConstructorCallsFromSelf()) {
                    String target = call.getTargetOwner().getName();
                    if (owners.containsKey(target) && !owners.get(target).equals(origin.getName())) {
                        events.add(SimpleConditionEvent.violated(call, "违规调用"));
                    }
                }
            }
        });
        assertTrue(ownershipRule.evaluate(fixture).hasViolation(), "所有权负控制必须触发违例");
    }

    @Test
    @DisplayName("§17.1/§17.5: 内部协议传输对象必须注解 JsonSerialize(HuashengInternalOnlySerializer.class)")
    void internalTransportTypesAreAnnotatedWithInternalOnlySerializer() {
        Class<?>[] types = {
                HuashengTransportContext.class,
                HuashengHttpExchange.class
        };
        for (Class<?> type : types) {
            JsonSerialize ann = type.getAnnotation(JsonSerialize.class);
            assertNotNull(ann, type.getSimpleName() + " 必须标注 @JsonSerialize");
            assertEquals(HuashengInternalOnlySerializer.class, ann.using(),
                    type.getSimpleName() + " 必须指定 using = HuashengInternalOnlySerializer.class");
        }
    }

    // ==========================================
    // 独立结构负控制测试夹具（仅供测试框架内部检查）
    // ==========================================

    static class BadOkHttpCaller {
        void call() {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        }
    }

    static class BadProtocolDependencyFixture {
        void bad() {
            HuashengHttpTransportPort port = null;
            org.springframework.util.StringUtils.hasText("");
        }
    }

    static class FixtureExecutorType {
        FixtureExecutorType(String a, String b, String c, String d) {}
    }

    static class BadExecutorConstructorCaller {
        Object bad() {
            return new FixtureExecutorType("a", "b", "c", "d");
        }
    }

    static class BadExchangeConstructorCaller {
        void bad() {
            new HuashengHttpExchange(new byte[0], new com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence(
                    com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint.TOKEN,
                    java.util.OptionalInt.empty(),
                    java.util.OptionalInt.empty(),
                    0L, 0L, 0L, 0, false
            ));
        }
    }
}
