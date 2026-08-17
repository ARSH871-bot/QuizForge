package com.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>Scanning explicitly covers both {@code com.quizforge} (the module
 * structure built out in M1-M3) and the legacy {@code cs.quizzapp} package,
 * which still serves all current traffic. Because the entry point no longer
 * sits above the legacy package, component, entity, and repository scanning
 * must all be declared by hand - Spring Boot's defaults would otherwise miss
 * every legacy bean. All three legacy entries are removed once M3 completes.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend"})
@EntityScan(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend.models"})
@EnableJpaRepositories(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend.db"})
public class QuizForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
