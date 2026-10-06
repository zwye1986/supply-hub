package com.aifuli.module.supplychain.architecture;

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

import java.time.LocalDateTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * R3-02O 可信追加底座访问边界（D3）：OutboxAppendIdentityMapper 写方法仅嵌套底座（及测试）
 * 访问，其它生产类禁依赖；生产不得裸 new OutboxEventRepository、不得自调用 appendLocal。
 * 独立新增测试，不改既有 SupplyChainModuleArchitectureTest。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721（R3-02O-S）。
 */
class OutboxAppendIsolationArchitectureTest {

    private static JavaClasses importedClasses;

    /** Repository只负责固定构造注入；唯有私有嵌套底座可调用Mapper三个写/当前锁读方法。 */
    static final ArchRule outbox_append_identity_mapper_only_from_trusted_core = noClasses()
            .that().doNotHaveFullyQualifiedName(OutboxEventRepository.class.getName())
            .and().doNotHaveFullyQualifiedName(OutboxEventRepository.class.getName() + "$TrustedOutboxAppendCore")
            .and().doNotHaveFullyQualifiedName("com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper")
            .should().dependOnClassesThat().haveSimpleName("OutboxAppendIdentityMapper");

    /** 即使外层Repository允许固定构造注入，也不得在底座之外调用账本三方法。 */
    static final ArchRule outbox_mapper_writes_only_core =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that().doNotHaveFullyQualifiedName(OutboxEventRepository.class.getName() + "$TrustedOutboxAppendCore")
                    .should(new ArchCondition<JavaClass>("账本三个写/锁读方法仅私有底座可调用") {
                        @Override
                        public void check(JavaClass clazz, ConditionEvents events) {
                            clazz.getMethodCallsFromSelf().stream()
                                    .filter(call -> "OutboxAppendIdentityMapper".equals(call.getTargetOwner().getSimpleName())
                                            && java.util.Set.of("insertSlotOnDuplicateKeyUpdate", "selectForUpdate", "bindEventCas")
                                            .contains(call.getTarget().getName()))
                                    .forEach(call -> events.add(SimpleConditionEvent.violated(call,
                                            clazz.getFullName() + " 越界调用账本Mapper方法")));
                        }
                    });

    /** OutboxAppendIdentityDO 的生产构造／字段入口仅底座与账本 DAL 持有。 */
    static final ArchRule outbox_append_identity_do_only_from_core_and_dal = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.outbox..").and().resideOutsideOfPackage("..infrastructure.ledger.dal..")
            .should().dependOnClassesThat().haveSimpleName("OutboxAppendIdentityDO");

    /** TimerScheduleIdentityMapper 仅账本服务 Timer 路径（及测试）允许。 */
    static final ArchRule timer_schedule_identity_mapper_only_from_ledger_package = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.ledger..")
            .should().dependOnClassesThat().haveSimpleName("TimerScheduleIdentityMapper");

    /** 生产不得裸new Repository，覆盖所有有参/无参构造签名。 */
    static final ArchRule production_never_bare_news_outbox_event_repository =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
            .should(new ArchCondition<JavaClass>("不直接构造OutboxEventRepository") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    clazz.getConstructorCallsFromSelf().stream()
                            .filter(call -> call.getTargetOwner().isEquivalentTo(OutboxEventRepository.class))
                            .forEach(call -> events.add(SimpleConditionEvent.violated(call,
                                    clazz.getFullName() + " 裸构造Repository绕过隔离advisor")));
                }
            });

    /** 同一规则同时用于真实Repository与仅test的结构负控制。 */
    static final ArchRule outbox_event_repository_never_self_calls_append_local =
            selfCallRuleFor(OutboxEventRepository.class.getName());

    private static ArchRule selfCallRuleFor(String ownerName) {
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that().haveFullyQualifiedName(ownerName)
                .should(new ArchCondition<JavaClass>("不得自调用appendLocal绕过advisor") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        clazz.getMethodCallsFromSelf().stream()
                                .filter(call -> "appendLocal".equals(call.getTarget().getName())
                                        && ownerName.equals(call.getTargetOwner().getName()))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(call,
                                        ownerName + " 自调用appendLocal绕过隔离advisor")));
                    }
                });
    }

    /** 显式导入生产类并由 JUnit 5 执行规则，避免假绿。 */
    @BeforeAll
    static void importProductionClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.aifuli.module.supplychain");
    }

    @Test
    void outboxAppendIdentityMapperOnlyFromTrustedCore() {
        outbox_append_identity_mapper_only_from_trusted_core.check(importedClasses);
        outbox_mapper_writes_only_core.check(importedClasses);
    }

    @Test
    void outboxAppendIdentityDoOnlyFromCoreAndDal() {
        outbox_append_identity_do_only_from_core_and_dal.check(importedClasses);
    }

    @Test
    void timerScheduleIdentityMapperOnlyFromLedgerPackage() {
        timer_schedule_identity_mapper_only_from_ledger_package.check(importedClasses);
    }

    @Test
    void productionNeverBareNewsOutboxEventRepository() {
        production_never_bare_news_outbox_event_repository.check(importedClasses);
    }

    @Test
    void negativeControlCatchesParameterizedBareConstructorAndSamePackageMapperWrite() {
        JavaClasses illegal = new ClassFileImporter().importClasses(
                com.aifuli.module.supplychain.infrastructure.outbox.OutboxAppendBypassArchitectureFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                production_never_bare_news_outbox_event_repository.check(illegal)).isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                outbox_append_identity_mapper_only_from_trusted_core.allowEmptyShould(true).check(illegal)).isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                outbox_mapper_writes_only_core.check(illegal)).isInstanceOf(AssertionError.class);
    }

    @Test
    void negativeControlRejectsSelfCallWithTheProductionRule() {
        Class<?> fixture = com.aifuli.module.supplychain.infrastructure.outbox.OutboxAppendBypassArchitectureFixture.class;
        JavaClasses illegal = new ClassFileImporter().importClasses(fixture);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                selfCallRuleFor(fixture.getName()).check(illegal)).isInstanceOf(AssertionError.class);
    }

    @Test
    void outboxEventRepositoryNeverSelfCallsAppendLocal() {
        outbox_event_repository_never_self_calls_append_local.check(importedClasses);
    }
}
