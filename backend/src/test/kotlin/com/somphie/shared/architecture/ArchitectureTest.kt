package com.somphie.shared.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** Protege las reglas estructurales mínimas acordadas para el monolito modular. */
class ArchitectureTest {
    /** Asegura que el dominio siga siendo Kotlin puro, independiente de transporte y persistencia. */
    @Test
    fun `domain does not depend on framework or outer layers`() {
        val classes = ClassFileImporter().importPackages("com.somphie")
        noClasses()
            .that().resideInAPackage("com.somphie..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.somphie..api..", "com.somphie..application..", "com.somphie..infrastructure..")
            .check(classes)
    }
}
