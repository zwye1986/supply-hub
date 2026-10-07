package com.aifuli.module.supplychain.architecture;

import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.*;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengProtocolException;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.lang.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

/** 首片真实字节结构规则及独立负控制；不修改旧规则。见设计 §3、§9.1、§14. */
class HuashengProtocolArchitectureTest {
    private static final String ROOT="com.aifuli.module.supplychain.";
    private static final String HS=ROOT+"supplieradapter.huasheng.";
    private static final Map<String,String> OWNERS=Map.ofEntries(
            Map.entry(HuashengPrice.class.getName(),HuashengMoneyParser.class.getName()),
            Map.entry(HuashengFaceAmount.class.getName(),HuashengMoneyParser.class.getName()),
            Map.entry(HuashengFailureDiagnostics.class.getName(),HuashengDiagnosticProjector.class.getName()),
            Map.entry(HuashengOrderResponse.class.getName(),HuashengResponseParser.class.getName()),
            Map.entry(HuashengTakeResponse.class.getName(),HuashengResponseParser.class.getName()),
            Map.entry(HuashengTokenResponse.class.getName(),HuashengResponseParser.class.getName()),
            Map.entry(HuashengBusinessError.class.getName(),HuashengResponseParser.class.getName()),
            Map.entry(HuashengProtocolCard.class.getName(),HuashengResponseParser.class.getName()),
            Map.entry(HuashengCardPayload.Card201.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card202.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card203.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card301.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card302.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card303.class.getName(),HuashengProtocolCard.class.getName()),
            Map.entry(HuashengCardPayload.Card304.class.getName(),HuashengProtocolCard.class.getName()));
    private static JavaClasses production() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
    }
    private static ArchRule constructorsOwnedBy(Map<String,String> owners) {
        return classes().should(new ArchCondition<JavaClass>("仅精确生产所有者调用或引用受控构造器") {
            @Override public void check(JavaClass origin, ConditionEvents events) {
                for (var call:origin.getConstructorCallsFromSelf()) {
                    String target=call.getTargetOwner().getName();
                    if (owners.containsKey(target) && !owners.get(target).equals(origin.getName()))
                        events.add(SimpleConditionEvent.violated(call,target+" call from "+origin.getName()));
                }
                for (var reference:origin.getConstructorReferencesFromSelf()) {
                    String target=reference.getTargetOwner().getName();
                    if (owners.containsKey(target) && !owners.get(target).equals(origin.getName()))
                        events.add(SimpleConditionEvent.violated(reference,target+" reference from "+origin.getName()));
                }
            }
        });
    }
    @Test void productionConstructorsHaveExactOwners() { constructorsOwnedBy(OWNERS).check(production()); }
    /** 同一条件用于生产与fixture，集合分开导入，绝不让fixture混入生产规则。 */
    private static ArchRule dependenciesConfined(Predicate<JavaClass> selected, Predicate<JavaClass> forbidden) {
        return classes().should(new ArchCondition<JavaClass>("仅允许首片纯协议依赖") {
            @Override public void check(JavaClass origin,ConditionEvents events) {
                if (!selected.test(origin)) return;
                for (var dependency:origin.getDirectDependenciesFromSelf()) {
                    if (forbidden.test(dependency.getTargetClass()))
                        events.add(SimpleConditionEvent.violated(dependency,dependency.getDescription()));
                }
            }
        });
    }
    private static boolean commonForbidden(JavaClass target) {
        String name=target.getName();
        return name.contains(".dal.") || name.contains(".infrastructure.timer.") || name.contains(".infrastructure.outbox.")
                || name.contains(".tradecore.ledger.") || name.endsWith("OperationExecutorPort")
                || name.endsWith("AdapterMetadata") || name.endsWith("AdapterMetadataRegistry");
    }
    private static boolean protocolForbidden(JavaClass target) {
        String name=target.getName();
        return commonForbidden(target) || name.startsWith("org.springframework.") || name.startsWith("okhttp3.")
                || name.startsWith("org.apache.ibatis.") || name.startsWith("com.baomidou.mybatisplus.")
                || name.startsWith("org.redisson.") || name.startsWith("io.lettuce.")
                || name.contains(".supplieradapter.huasheng.http.");
    }
    @Test void productionHonorsAdapterProtocolAndApplicationDependencyBoundaries() {
        JavaClasses all=production();
        dependenciesConfined(c -> c.getName().startsWith(HS),t -> commonForbidden(t)
                || t.getName().startsWith(ROOT+"border.fulfillment.application.huasheng.")
                || t.getName().contains(".infrastructure.token.") || t.getSimpleName().matches("Huasheng.*(Cache|Manager|Facade).*" )).check(all);
        dependenciesConfined(c -> c.getName().startsWith(HS+"protocol.") || c.getName().startsWith(HS+"failure."),HuashengProtocolArchitectureTest::protocolForbidden).check(all);
        dependenciesConfined(c -> c.getName().startsWith(ROOT+"border.fulfillment.application.huasheng."),t -> commonForbidden(t) || t.getName().contains(".infrastructure.token.")).check(all);
        dependenciesConfined(c -> c.getName().startsWith(ROOT) && !c.getName().startsWith(HS+"failure.")
                && !c.getName().startsWith(HS+"protocol.") && !c.getName().startsWith(HS+"http.")
                && !c.getName().startsWith(ROOT+"border.fulfillment.application.huasheng."),
                t -> t.isEquivalentTo(HuashengProtectedFailure.class) || t.getName().startsWith(HuashengInternalAttempt.class.getName())).check(all);
        for (JavaClass c:all) {
            if (c.getName().startsWith(HS) || c.getName().startsWith(ROOT+"border.fulfillment.application.huasheng.")) {
                assertFalse(c.isMetaAnnotatedWith("org.springframework.stereotype.Component"));
                assertFalse(c.isAnnotatedWith("org.springframework.stereotype.Component"));
            }
        }
    }
    @Test void dependencyRulesRejectRealSpringApplicationAndInternalCarrierFixtures() {
        JavaClasses fixture=new ClassFileImporter().importClasses(BadSpringDependency.class,BadApplicationDependency.class,BadPublicCarrierConsumer.class);
        assertTrue(dependenciesConfined(c -> c.isEquivalentTo(BadSpringDependency.class),HuashengProtocolArchitectureTest::protocolForbidden).evaluate(fixture).hasViolation());
        assertTrue(dependenciesConfined(c -> c.isEquivalentTo(BadApplicationDependency.class),t -> t.isEquivalentTo(HuashengProtocolException.class)).evaluate(fixture).hasViolation());
        assertTrue(dependenciesConfined(c -> c.isEquivalentTo(BadPublicCarrierConsumer.class),t -> t.isEquivalentTo(HuashengProtectedFailure.class) || t.getName().startsWith(HuashengInternalAttempt.class.getName())).evaluate(fixture).hasViolation());
    }
    @Test void constructorRuleRejectsParameterizedCallsAndReferencesForAllThreeTypes() {
        var fixtures=new ClassFileImporter().importClasses(BadConstructorCalls.class,BadConstructorReferences.class,
                FixturePrice.class,FixtureFace.class,FixtureDiagnostics.class,FixtureOrderResponse.class,AllowedOwner.class);
        var owners=Map.of(
                FixturePrice.class.getName(),AllowedOwner.class.getName(),
                FixtureFace.class.getName(),AllowedOwner.class.getName(),
                FixtureDiagnostics.class.getName(),AllowedOwner.class.getName(),
                FixtureOrderResponse.class.getName(),AllowedOwner.class.getName());
        var result=constructorsOwnedBy(owners).evaluate(fixtures);
        assertTrue(result.hasViolation());
        String details=result.getFailureReport().toString();
        for (String type : owners.keySet()) {
            assertTrue(details.contains(type+" call"),details);
            assertTrue(details.contains(type+" reference"),details);
        }
        constructorsOwnedBy(owners).check(new ClassFileImporter().importClasses(AllowedOwner.class,FixturePrice.class,FixtureFace.class,FixtureDiagnostics.class,FixtureOrderResponse.class));
    }
    @Test void controlledTypesHavePackageOrPrivateConstructorsAndSafeShape() throws Exception {
        assertEquals(0,HuashengPrice.class.getDeclaredConstructor(String.class,long.class).getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE));
        assertEquals(0,HuashengFaceAmount.class.getDeclaredConstructor(String.class).getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE));
        for (var c:HuashengFailureDiagnostics.class.getDeclaredConstructors()) assertEquals(0,c.getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE));
        for (var type:List.of(HuashengProtectedFailure.class,HuashengInternalAttempt.class,HuashengInternalAttempt.Parsed.class,HuashengInternalAttempt.Failed.class,HuashengProtocolException.class)) {
            for (var c:type.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(c.getModifiers()));
        }
        assertTrue(HuashengInternalAttempt.class.isSealed());
        assertEquals(Set.of(HuashengInternalAttempt.Parsed.class,HuashengInternalAttempt.Failed.class),Set.of(HuashengInternalAttempt.class.getPermittedSubclasses()));
        assertEquals(0,HuashengProtectedFailure.class.getDeclaredMethod("originalCauseForInternalInspection").getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE));
        assertFalse(java.io.Serializable.class.isAssignableFrom(HuashengProtectedFailure.class));
        assertFalse(java.io.Serializable.class.isAssignableFrom(HuashengInternalAttempt.class));
        assertFalse(HuashengProtectedFailure.class.isRecord());
        for (var type:List.of(HuashengProtectedFailure.class,HuashengInternalAttempt.class,HuashengInternalAttempt.Parsed.class,HuashengInternalAttempt.Failed.class)) {
            var annotation=type.getDeclaredAnnotation(com.fasterxml.jackson.databind.annotation.JsonSerialize.class);
            assertNotNull(annotation); assertEquals(HuashengInternalOnlySerializer.class,annotation.using());
        }
        for (var type:List.of(
                HuashengOrderResponse.class, HuashengTakeResponse.class, HuashengTokenResponse.class,
                HuashengBusinessError.class, HuashengProtocolCard.class,
                HuashengCardPayload.Card201.class, HuashengCardPayload.Card202.class, HuashengCardPayload.Card203.class,
                HuashengCardPayload.Card301.class, HuashengCardPayload.Card302.class, HuashengCardPayload.Card303.class,
                HuashengCardPayload.Card304.class,
                HuashengField.Absent.class, HuashengField.Null.class, HuashengField.Value.class,
                HuashengParseFact.Business.class, HuashengParseFact.Data.class)) {
            for (var c:type.getDeclaredConstructors()) assertEquals(0,c.getModifiers() & (Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE), "Constructor of " + type.getSimpleName() + " must be package-private");
            var annotation=type.getDeclaredAnnotation(com.fasterxml.jackson.databind.annotation.JsonSerialize.class);
            assertNotNull(annotation, type.getSimpleName() + " must have @JsonSerialize");
            assertEquals(HuashengInternalOnlySerializer.class, annotation.using());
        }
        assertEquals(Modifier.PUBLIC, HuashengExpectedOrder.class.getConstructor(String.class, Optional.class, int.class).getModifiers() & Modifier.PUBLIC);
        assertNotNull(HuashengExpectedOrder.class.getDeclaredAnnotation(com.fasterxml.jackson.databind.annotation.JsonSerialize.class));
        for (var field:HuashengFailureDiagnostics.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers())); assertTrue(Modifier.isFinal(field.getModifiers()));
            assertFalse(Throwable.class.isAssignableFrom(field.getType())); assertNotEquals(HuashengProtectedFailure.class,field.getType());
        }
        assertEquals(Set.of("failureKind","reasonCode","causeKind","evidence"),Arrays.stream(HuashengFailureDiagnostics.class.getDeclaredFields()).map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("diagnostics"),Arrays.stream(HuashengProtocolException.class.getDeclaredFields()).map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet()));
    }
    static final class FixturePrice { FixturePrice(String raw,long cents) { } }
    static final class BadSpringDependency { org.springframework.context.ApplicationContext context; }
    static final class BadApplicationDependency { HuashengProtocolException exception; }
    static final class BadPublicCarrierConsumer { HuashengInternalAttempt<String> attempt; HuashengProtectedFailure failure; }
    static final class FixtureFace { FixtureFace(String raw) { } }
    static final class FixtureDiagnostics { FixtureDiagnostics(HuashengFailureDiagnostics.HuashengFailureKind k,HuashengFailureDiagnostics.HuashengFailureReasonCode r,HuashengFailureDiagnostics.HuashengCauseKind c,Optional<HuashengExchangeEvidence> e) { } }
    static final class FixtureOrderResponse { FixtureOrderResponse(String sn) { } }
    static final class AllowedOwner {
        Object price() { return new FixturePrice("1",100); }
        Object face() { return new FixtureFace("1"); }
        Object diagnostics() { return new FixtureDiagnostics(null,null,null,Optional.empty()); }
        Object order() { return new FixtureOrderResponse("1"); }
    }
    static final class BadConstructorCalls {
        Object price() { return new FixturePrice("1",100); }
        Object face() { return new FixtureFace("1"); }
        Object diagnostics() { return new FixtureDiagnostics(null,null,null,Optional.empty()); }
        Object order() { return new FixtureOrderResponse("1"); }
    }
    interface DiagnosticsConstructor { FixtureDiagnostics create(HuashengFailureDiagnostics.HuashengFailureKind k,HuashengFailureDiagnostics.HuashengFailureReasonCode r,HuashengFailureDiagnostics.HuashengCauseKind c,Optional<HuashengExchangeEvidence> e); }
    static final class BadConstructorReferences {
        BiFunction<String,Long,FixturePrice> price=FixturePrice::new;
        Function<String,FixtureFace> face=FixtureFace::new;
        DiagnosticsConstructor diagnostics=FixtureDiagnostics::new;
        Function<String,FixtureOrderResponse> order=FixtureOrderResponse::new;
    }
}
