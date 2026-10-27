package com.mercatus.order;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mercatus.order");

    @Test
    void layeredArchitectureIsRespected() {
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..order.domain..")
                .layer("Application").definedBy("..order.application..")
                .layer("Infrastructure").definedBy("..order.infrastructure..")
                .layer("Web").definedBy("..order.web..")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Web", "Infrastructure")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Web");

        rule.check(classes);
    }

    @Test
    void applicationOnlyDependsOnDomainAndItself() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "..infrastructure..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void drivenPortsAreOnlyUsedByApplicationAndInfrastructure() {
        ArchRule rule = classes()
                .that().resideInAPackage("..application..port.out..")
                .and().areInterfaces()
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void drivenPortsAreImplementedInInfrastructure() {
        classes().that().implement(resideInAPackage("..application..port.out.."))
                .should().resideInAPackage("..infrastructure..")
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnOtherLayers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..web..", "..infrastructure..", "..application..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void domainIsFreeOfFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        "com.fasterxml.jackson..", "tools.jackson..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void jpaEntitiesLiveOnlyInInfrastructure() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage("..infrastructure..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void controllersLiveOnlyInWeb() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage("..web..")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void persistenceQueriesLiveInRepositories() {
        methods().that().areAnnotatedWith(org.springframework.data.jpa.repository.Query.class)
                .should().beDeclaredInClassesThat().haveSimpleNameEndingWith("JpaRepository")
                .check(classes);

        noClasses().that().resideInAPackage("..infrastructure.persistence..")
                .and().haveSimpleNameEndingWith("RepositoryAdapter")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "jakarta.persistence..", "org.hibernate.."
                )
                .check(classes);
    }
}
