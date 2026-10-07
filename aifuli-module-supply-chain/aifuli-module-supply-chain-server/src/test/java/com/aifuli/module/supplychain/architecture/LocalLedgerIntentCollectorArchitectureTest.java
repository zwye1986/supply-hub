package com.aifuli.module.supplychain.architecture;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainTransactionObservationManager;
import com.aifuli.module.supplychain.infrastructure.ledger.LocalLedgerIntentCollector;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * R3-03 §7.2.2(5)(6)(9) 架构门禁（C-ARCH，独立新文件，不改既有 OutboxAppendIsolationArchitectureTest）：
 * 1. 生产 ledger/outbox 的 TransactionSynchronization 实现不得覆写 flush()（防 triggerFlush 重入）；
 * 2. 禁止生产 Outbox 执行通道：除 OutboxEventRepository 自身外任何生产类不得依赖私有底座
 *    TrustedOutboxAppendCore（N9：规则豁免宿主全部嵌套类；负控制沿用模板参数化模式）；
 * 3. CollationOrderingMapper 仅 collector 可调用（测试除外）。
 * 每条规则带结构负控制，防止规则自身假绿。
 */
class LocalLedgerIntentCollectorArchitectureTest {

    private static JavaClasses importedClasses;

    /** 守卫同步不得覆写 flush()（§7.2.2(5)）；仅统计类自身声明的方法。 */
    static final ArchCondition<JavaClass> FLUSH_OVERRIDE_FORBIDDEN =
            new ArchCondition<JavaClass>("不得覆写flush()") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    clazz.getMethods().stream()
                            .filter(method -> "flush".equals(method.getName())
                                    && method.getOwner().equals(clazz))
                            .forEach(method -> events.add(SimpleConditionEvent.violated(method,
                                    clazz.getFullName() + " 覆写TransactionSynchronization.flush()")));
                }
            };

    static final ArchRule production_synchronization_must_not_override_flush =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that().implement(TransactionSynchronization.class)
                    .and().resideInAnyPackage("..infrastructure.ledger..", "..infrastructure.outbox..")
                    .should(FLUSH_OVERRIDE_FORBIDDEN);

    /** 禁止生产通道模板（N9：豁免宿主类及其全部嵌套类，避免规则自身假阳性）。 */
    static ArchRule noProductionDependencyOnNestedClass(String hostFqn, String nestedSimpleName) {
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("生产类不得依赖宿主私有嵌套底座 " + nestedSimpleName) {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        String name = clazz.getName();
                        if (name.equals(hostFqn) || name.startsWith(hostFqn + "$")) {
                            return;
                        }
                        clazz.getDirectDependenciesFromSelf().stream()
                                .filter(dependency -> nestedSimpleName.equals(
                                        dependency.getTargetClass().getSimpleName()))
                                .forEach(dependency -> events.add(SimpleConditionEvent.violated(dependency,
                                        clazz.getFullName() + " 依赖 " + nestedSimpleName)));
                    }
                });
    }

    /** CollationOrderingMapper 仅 collector 可调用（测试代码不在生产导入集内）。 */
    static final ArchRule collation_ordering_mapper_only_from_collector = noClasses()
            .that().doNotHaveFullyQualifiedName(LocalLedgerIntentCollector.class.getName())
            .should().dependOnClassesThat().haveSimpleName("CollationOrderingMapper");

    @BeforeAll
    static void importProductionClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.aifuli.module.supplychain");
    }

    @Test
    void productionSynchronizationNeverOverridesFlush() {
        production_synchronization_must_not_override_flush.check(importedClasses);
    }

    @Test
    void noProductionClassDependsOnTrustedOutboxAppendCore() {
        noProductionDependencyOnNestedClass(
                OutboxEventRepository.class.getName(), "TrustedOutboxAppendCore").check(importedClasses);
    }

    @Test
    void collationOrderingMapperOnlyFromCollector() {
        collation_ordering_mapper_only_from_collector.check(importedClasses);
    }

    @Test
    void negativeControlCatchesFlushOverrideViolation() {
        // 负控制：夹具与生产规则包过滤不匹配（architecture 测试包），以同条件 + 无包过滤规则验证
        // FLUSH_OVERRIDE_FORBIDDEN 检测核心（owner 判定）能捕获违规覆写，防止规则自身假绿。
        JavaClasses illegal = new ClassFileImporter().importClasses(FlushOverridingSynchronizationFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                        .that().implement(TransactionSynchronization.class)
                        .should(FLUSH_OVERRIDE_FORBIDDEN)
                        .allowEmptyShould(true).check(illegal))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void negativeControlCatchesNestedClassDependencyViolation() {
        JavaClasses illegal = new ClassFileImporter().importClasses(ScopeDependentFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                noProductionDependencyOnNestedClass(
                        SupplyChainTransactionObservationManager.class.getName(), "Scope")
                        .allowEmptyShould(true).check(illegal))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void negativeControlCatchesCollationOrderingMapperViolation() {
        JavaClasses illegal = new ClassFileImporter().importClasses(CollationOrderingMapperDependentFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                collation_ordering_mapper_only_from_collector.allowEmptyShould(true).check(illegal))
                .isInstanceOf(AssertionError.class);
    }

    /** 违规夹具：覆写 flush() 的同步实现（规则必须捕获）。 */
    static class FlushOverridingSynchronizationFixture implements TransactionSynchronization {
        @Override
        public void flush() {
            throw new UnsupportedOperationException("violation fixture");
        }
    }

    /** 违规夹具：依赖公开嵌套类 Scope（模板规则必须捕获；TrustedOutboxAppendCore 为私有不可直接引用）。 */
    static class ScopeDependentFixture {
        @SuppressWarnings("unused")
        private final SupplyChainTransactionObservationManager.Scope scope;

        ScopeDependentFixture(SupplyChainTransactionObservationManager.Scope scope) {
            this.scope = scope;
        }
    }

    /** 违规夹具：collector 之外依赖 CollationOrderingMapper（规则必须捕获）。 */
    static class CollationOrderingMapperDependentFixture {
        @SuppressWarnings("unused")
        private final com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.CollationOrderingMapper mapper;

        CollationOrderingMapperDependentFixture(
                com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.CollationOrderingMapper mapper) {
            this.mapper = mapper;
        }
    }
}
