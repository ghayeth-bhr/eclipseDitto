package org.naruprof.twin.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Pyramid rules for the twin module (cf. examples/petstore/tests/architecture-tests). */
class PyramidArchitectureTest {

    static JavaClasses main;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.naruprof");
    }

    @Test
    @DisplayName("Layering: ws -> service-api; service-impl -> dal-api; adapters -> dal-api; only the app wires")
    void layers() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Infra").definedBy("org.naruprof.twin.infra..")
                .layer("ServiceApi").definedBy("org.naruprof.twin.service.api..")
                .layer("ServiceImpl").definedBy("org.naruprof.twin.service.impl..")
                .layer("DalApi").definedBy("org.naruprof.twin.dal.api..")
                .layer("DalAdapters").definedBy("org.naruprof.twin.dal.ditto..", "org.naruprof.twin.dal.jpa..",
                        "org.naruprof.twin.dal.narugrpc..")
                .layer("Ws").definedBy("org.naruprof.twin.ws..")
                .layer("App").definedBy("org.naruprof.twin.app..")
                .whereLayer("App").mayNotBeAccessedByAnyLayer()
                .whereLayer("Ws").mayOnlyBeAccessedByLayers("App")
                .whereLayer("ServiceImpl").mayOnlyBeAccessedByLayers("App")
                .whereLayer("DalAdapters").mayOnlyBeAccessedByLayers("App")
                .whereLayer("DalApi").mayOnlyBeAccessedByLayers("ServiceImpl", "DalAdapters", "App")
                .whereLayer("ServiceApi").mayOnlyBeAccessedByLayers("ServiceImpl", "Ws", "App")
                .check(main);
    }

    @Test
    @DisplayName("Controllers never reach the DAL (ports or adapters) or service internals")
    void controllerToDal() {
        noClasses().that().resideInAPackage("org.naruprof.twin.ws..")
                .should().dependOnClassesThat().resideInAnyPackage("org.naruprof.twin.dal..",
                        "org.naruprof.twin.service.impl..", "org.naruprof.contract..")
                .check(main);
    }

    @Test
    @DisplayName("Services depend on semantic ports, never on concrete DAL adapters or vendor clients")
    void servicesUseOnlyPorts() {
        noClasses().that().resideInAPackage("org.naruprof.twin.service.impl..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.naruprof.twin.dal.ditto..", "org.naruprof.twin.dal.jpa..", "org.naruprof.twin.dal.narugrpc..",
                        "org.naruprof.contract..", "io.grpc..", "com.google.protobuf..", "jakarta.persistence..",
                        "org.springframework..", "com.fasterxml.jackson..", "io.github.resilience4j..", "java.net.http..")
                .check(main);
    }

    @Test
    @DisplayName("Ports are vendor-neutral")
    void portsAreVendorNeutral() {
        noClasses().that().resideInAnyPackage("org.naruprof.twin.dal.api..", "org.naruprof.twin.service.api..",
                        "org.naruprof.twin.infra..")
                .should().dependOnClassesThat().resideInAnyPackage("io.grpc..", "com.google.protobuf..",
                        "jakarta.persistence..", "org.springframework..", "com.fasterxml.jackson..", "java.net.http..",
                        "org.naruprof.contract..")
                .check(main);
    }

    @Test
    @DisplayName("Concrete DAL adapters are independent of each other and of services")
    void adaptersIsolated() {
        slices().matching("org.naruprof.twin.dal.(ditto|jpa|narugrpc)..").should().notDependOnEachOther().check(main);
        noClasses().that().resideInAnyPackage("org.naruprof.twin.dal.ditto..", "org.naruprof.twin.dal.jpa..",
                        "org.naruprof.twin.dal.narugrpc..")
                .should().dependOnClassesThat().resideInAnyPackage("org.naruprof.twin.service..", "org.naruprof.twin.ws..")
                .check(main);
    }

    @Test
    @DisplayName("NARU's generated stubs are used only by the gRPC adapter (and the fixture publisher)")
    void generatedContractConfined() {
        classes().that().resideInAPackage("org.naruprof.contract..")
                .should().onlyBeAccessed().byClassesThat().resideInAnyPackage("org.naruprof.contract..",
                        "org.naruprof.twin.dal.narugrpc..", "org.naruprof.twin.fixture..")
                .check(main);
    }

    @Test
    @DisplayName("Cross-module: the fake NARU peer and the twin never reach into each other's internals")
    void fixtureIsolation() {
        noClasses().that().resideInAPackage("org.naruprof.twin.fixture..")
                .should().dependOnClassesThat().resideInAnyPackage("org.naruprof.twin.infra..",
                        "org.naruprof.twin.service..", "org.naruprof.twin.dal..", "org.naruprof.twin.ws..", "org.naruprof.twin.app..")
                .check(main);
        noClasses().that().resideOutsideOfPackage("org.naruprof.twin.fixture..")
                .should().dependOnClassesThat().resideInAPackage("org.naruprof.twin.fixture..")
                .check(main);
    }

    @Test
    @DisplayName("Only the composition root wires Spring beans; services and adapters carry no stereotypes")
    void onlyCompositionRootWires() {
        noClasses().that().resideOutsideOfPackages("org.naruprof.twin.app..", "org.naruprof.twin.ws..",
                        "org.naruprof.twin.dal.jpa..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.stereotype..")
                .check(main);
        noClasses().that().resideOutsideOfPackage("org.naruprof.twin.app..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.context.annotation.Bean")
                .check(main);
    }
}
