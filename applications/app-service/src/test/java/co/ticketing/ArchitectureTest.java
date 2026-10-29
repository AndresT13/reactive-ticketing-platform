package co.ticketing;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Enforces the Clean Architecture dependency rule and the reactive coding rules (R1-R12, ADR-014). */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("co.ticketing");
    }

    @Test
    void domainModelDependsOnNothingButJavaAndReactor() {
        classes().that().resideInAPackage("co.ticketing.model..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "co.ticketing.model..", "java..", "reactor..", "org.reactivestreams..")
                .check(classes);
    }

    @Test
    void useCasesDependOnlyOnTheDomain() {
        classes().that().resideInAPackage("co.ticketing.usecase..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "co.ticketing.model..", "co.ticketing.usecase..", "java..", "reactor..", "org.reactivestreams..")
                .check(classes);
    }

    @Test
    void domainNeverDependsOnInfrastructure() {
        noClasses().that().resideInAnyPackage("co.ticketing.model..", "co.ticketing.usecase..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "software.amazon..", "tools.jackson..", "com.fasterxml..",
                        "co.ticketing.web..", "co.ticketing.dynamodb..", "co.ticketing.sqs..")
                .check(classes);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        noClasses().that().resideInAPackage("co.ticketing.dynamodb..")
                .should().dependOnClassesThat().resideInAnyPackage("co.ticketing.sqs..", "co.ticketing.web..")
                .check(classes);
        noClasses().that().resideInAPackage("co.ticketing.web..")
                .should().dependOnClassesThat().resideInAnyPackage("co.ticketing.dynamodb..", "co.ticketing.sqs..")
                .check(classes);
    }

    @Test
    void productionCodeNeverBlocks() {
        noClasses().should().callMethodWhere(blockingCall()).check(classes);
    }

    @Test
    void useCasesAreStatelessAndImmutable() {
        classes().that().haveSimpleNameEndingWith("UseCase")
                .should().haveOnlyFinalFields()
                .check(classes);
    }

    private static DescribedPredicate<JavaMethodCall> blockingCall() {
        return DescribedPredicate.describe("a blocking call (block*, Thread.sleep, parallelStream, supplyAsync)", call -> {
            String name = call.getTarget().getName();
            var owner = call.getTarget().getOwner();
            boolean reactorBlock = (owner.isAssignableTo(Mono.class) || owner.isAssignableTo(Flux.class)) && name.startsWith("block");
            boolean sleep = owner.isEquivalentTo(Thread.class) && name.equals("sleep");
            boolean parallelStream = owner.isAssignableTo(Collection.class) && name.equals("parallelStream");
            boolean supplyAsync = owner.isEquivalentTo(CompletableFuture.class) && name.equals("supplyAsync");
            return reactorBlock || sleep || parallelStream || supplyAsync;
        });
    }
}
