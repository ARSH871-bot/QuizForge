package com.quizforge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.quizforge", "cs.quizzapp");
    }

    @Test
    void newCodeMustNotDependOnLegacyCode() {
        noClasses()
                .that().resideInAPackage("com.quizforge..")
                .should().dependOnClassesThat().resideInAPackage("cs.quizzapp..")
                .because("new modules must not couple themselves to code that is being deleted")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void controllersMustNotTalkDirectlyToRepositories() {
        noClasses()
                .that().resideInAPackage("com.quizforge..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers talk to application services, never to repositories")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void entitiesMustNotBeReturnedFromControllers() {
        noClasses()
                .that().resideInAPackage("com.quizforge..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().resideInAPackage("..domain.entity..")
                .because("entities are mapped to DTOs at the boundary, never serialised directly")
                .allowEmptyShould(true)
                .check(allClasses);
    }
}
