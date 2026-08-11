package com.quizforge.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordConfig {

    /**
     * Argon2id with OWASP's recommended parameters: 19 MiB memory, 2
     * iterations, 1 degree of parallelism.
     *
     * <p>Wrapped in a {@link DelegatingPasswordEncoder} so hashes carry an
     * {@code {argon2}} prefix. Legacy BCrypt hashes remain verifiable, which
     * is what allows existing accounts to be migrated on next login rather
     * than invalidated.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
        delegating.setDefaultPasswordEncoderForMatches(
                PasswordEncoderFactories.createDelegatingPasswordEncoder());

        return delegating;
    }
}
