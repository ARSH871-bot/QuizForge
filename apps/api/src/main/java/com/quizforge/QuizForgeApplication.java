package com.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>Component, entity and repository scanning are all left to Spring Boot's
 * defaults. They were declared by hand while the legacy {@code cs.quizzapp}
 * package sat outside this one; with that package gone, the entry point is
 * above everything again and the declarations are noise.
 */
@SpringBootApplication
@EnableScheduling
public class QuizForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
