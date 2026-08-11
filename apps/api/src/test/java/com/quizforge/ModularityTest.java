package com.quizforge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    static final ApplicationModules MODULES =
            ApplicationModules.of(QuizForgeApplication.class);

    /**
     * Fails the build if any module reaches outside the dependencies it
     * declares in its {@code package-info.java}. Passes trivially while the
     * modules are empty - that is deliberate. The guardrail exists before the
     * code it guards, so the first violation is caught the moment it appears.
     */
    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        MODULES.verify();
    }

    // Module documentation generation (Documenter -> C4 / PlantUML diagrams) is
    // deferred to M1. Spring Modulith 1.4.3's Documenter fails to parse its own
    // generated javadoc.json under Spring Boot 3.5.6, and it would produce empty
    // diagrams for empty modules regardless. Reintroduce it once the modules
    // hold real components and the incompatibility can be evaluated against
    // output that actually has value.
}
