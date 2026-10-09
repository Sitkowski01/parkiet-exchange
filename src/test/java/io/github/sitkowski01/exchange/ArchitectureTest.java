package io.github.sitkowski01.exchange;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reguly architektury jako test: zlamanie ich wywraca build, a nie czeka na review.
 */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.sitkowski01.exchange");

    @Test
    void engineDependsOnlyOnDomainAndJdk() {
        classes().that().resideInAPackage("..exchange.engine..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("..exchange.engine..", "..exchange.domain..", "java..")
                .because("silnik to tez czysta Java -- Spring tylko go sklada i wystawia na zewnatrz")
                .check(PRODUCTION);
    }

    @Test
    void httpAndDatabaseDoNotKnowEachOther() {
        noClasses().that().resideInAPackage("..exchange.api..")
                .should().dependOnClassesThat().resideInAPackage("..exchange.persistence..")
                .because("API rozmawia z silnikiem, a silnik oddaje zdarzenia do bazy przez EventSink -- "
                        + "kontroler piszacy do bazy z pominieciem silnika rozjechalby dziennik z arkuszem")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..exchange.persistence..")
                .should().dependOnClassesThat().resideInAPackage("..exchange.api..")
                .check(PRODUCTION);
    }

    @Test
    void domainDependsOnlyOnJdk() {
        classes().that().resideInAPackage("..exchange.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("..exchange.domain..", "java..")
                .because("silnik kojarzenia ma byc czysta Java: bez Springa, bazy i sieci, "
                        + "zeby dalo sie go testowac w milisekundach i przeniesc gdziekolwiek")
                .check(PRODUCTION);
    }
}
