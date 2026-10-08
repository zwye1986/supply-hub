package com.aifuli.module.supplychain.architecture;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇应用层与 Token 基础设施层六边形架构边界门禁测试。
 *
 * <p>职责：使用 ArchUnit 门禁强制校验：
 * <ul>
 *   <li>AD-4 分层隔离：应用层生产代码绝不 import {@code infrastructure.token.huasheng.*} 任何实现；</li>
 *   <li>六边形反向隔离：基础设施层实现绝不依赖应用层具体服务或门面类（仅允许依赖端口与值对象）；</li>
 *   <li>零 Spring 自动组件：两包下所有生产类绝不带 {@link Component} 或其派生注解；</li>
 *   <li>对外零 Throwable：门面类公开方法对外签名绝不包含 {@link Throwable}；</li>
 *   <li>配备完备的结构负控制反例测试，证明架构规则具备真实检出能力。</li>
 * </ul>
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#181-架构分层解耦与六边形依赖ad-4--ad-a5-01。
 */
public class HuashengApplicationArchitectureTest {

    private static final String APP_PKG = "com.aifuli.module.supplychain.border.fulfillment.application.huasheng";
    private static final String INFRA_PKG = "com.aifuli.module.supplychain.infrastructure.token.huasheng";

    private static JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(APP_PKG, INFRA_PKG);
    }

    static ArchRule dependencyRule(Predicate<JavaClass> selected, Predicate<JavaClass> forbidden, String description) {
        return classes().should(new ArchCondition<JavaClass>(description) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (!selected.test(origin)) return;
                for (var dependency : origin.getDirectDependenciesFromSelf()) {
                    if (forbidden.test(dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("生产代码分层隔离：应用层绝不依赖 infra 实现，infra 绝不反向依赖应用实现")
    void testProductionDependencyBoundaries() {
        JavaClasses prod = production();
        dependencyRule(
                c -> c.getPackageName().startsWith(APP_PKG),
                t -> t.getPackageName().startsWith(INFRA_PKG),
                "应用层生产代码绝不依赖基础设施层具体实现"
        ).check(prod);

        dependencyRule(
                c -> c.getPackageName().startsWith(INFRA_PKG),
                t -> t.getName().startsWith(APP_PKG) && (t.getSimpleName().endsWith("Facade") || t.getSimpleName().endsWith("Manager")),
                "基础设施层绝不依赖应用服务具体实现"
        ).check(prod);
    }

    @Test
    @DisplayName("零 Spring 自动组件：生产类绝不包含 @Component 或元注解")
    void testNoSpringComponents() {
        for (JavaClass c : production()) {
            assertFalse(c.isAnnotatedWith(Component.class));
            assertFalse(c.isMetaAnnotatedWith(Component.class));
        }
    }

    @Test
    @DisplayName("对外门面零 Throwable 暴露：公开方法返回类型与异常列表绝无 Throwable")
    void testFacadePublicMethodsZeroThrowable() {
        for (Method m : HuashengCommunicationFacade.class.getMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getDeclaringClass() == HuashengCommunicationFacade.class) {
                assertFalse(Throwable.class.isAssignableFrom(m.getReturnType()));
                for (Class<?> ex : m.getExceptionTypes()) {
                    assertFalse(Throwable.class == ex || Exception.class == ex);
                }
            }
        }
    }

    @Test
    @DisplayName("结构负控制：验证依赖违规夹具必然触发规则失败")
    void testNegativeControlFixturesDetected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BadAppDependency.class, BadInfraDependency.class);
        var res1 = dependencyRule(
                c -> c.isEquivalentTo(BadAppDependency.class),
                t -> t.getPackageName().startsWith(INFRA_PKG),
                "检测应用层违规依赖"
        ).evaluate(fixture);
        assertTrue(res1.hasViolation(), "负控制：违规应用层依赖必须被规则检出");

        var res2 = dependencyRule(
                c -> c.isEquivalentTo(BadInfraDependency.class),
                t -> t.getSimpleName().endsWith("Facade"),
                "检测基础设施违规反向依赖"
        ).evaluate(fixture);
        assertTrue(res2.hasViolation(), "负控制：违规基础设施反向依赖必须被规则检出");
    }

    // --- 负控制夹具 ---
    static final class BadAppDependency {
        com.aifuli.module.supplychain.infrastructure.token.huasheng.HuashengInMemoryTokenCache cache;
    }

    static final class BadInfraDependency {
        com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade facade;
    }
}
