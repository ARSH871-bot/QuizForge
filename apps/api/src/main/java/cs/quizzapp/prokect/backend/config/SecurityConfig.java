package cs.quizzapp.prokect.backend.config;

import cs.quizzapp.prokect.backend.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    private final UserService userService;

    public SecurityConfig(@Lazy UserService userService) {
        this.userService = userService;
    }

    // The PasswordEncoder bean moved to com.quizforge.identity.security.PasswordConfig,
    // which supplies Argon2id and still verifies the BCrypt hashes written here
    // previously, so existing accounts keep working and upgrade on next login.

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Disable CSRF for API testing
                .authorizeHttpRequests(auth -> auth
                        //.requestMatchers("/api/auth/login", "/api/auth/register").permitAll() // Public endpoints
                        // .requestMatchers("/api/quizzes/").authenticated() // Protect quiz-related endpoints
                        .anyRequest().permitAll() // Allow all other endpoints
                );
        //.sessionManagement(session -> {
        // SessionManagementConfigurer<HttpSecurity> httpSecuritySessionManagementConfigurer = session
        //.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        // return httpSecuritySessionManagementConfigurer;
        //  } // Stateless session
        //  )
        //.httpBasic(httpBasicCustomizer -> {}); // Use basic authentication for simplicity

        return http.build();
    }
}