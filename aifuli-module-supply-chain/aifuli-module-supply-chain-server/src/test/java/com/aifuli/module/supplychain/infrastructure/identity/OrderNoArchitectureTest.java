package com.aifuli.module.supplychain.infrastructure.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class OrderNoArchitectureTest {
    @Test
    void newOrderAndBillCannotUseGenericUlidInProduction() throws Exception {
        Path source = Path.of("src/main/java/com/aifuli/module/supplychain");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file))
                        .as(file.toString())
                        .doesNotContain(
                                "next(BusinessNoPrefixes.SC)", "next(BusinessNoPrefixes.BILL)");
            }
        }
    }

    @Test
    void numberingDoesNotCreatePoolsAndFacadesDelegateToCoordinators() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.aifuli.module.supplychain");
        noClasses()
                .that()
                .haveNameMatching(".*(OrderNo|OrderCreationSupport).*")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.zaxxer.hikari..",
                        "com.alibaba.druid..",
                        "java.util.concurrent.Executors")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..application.facade..")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("OrderNoAllocationService")
                .check(classes);
    }
}
