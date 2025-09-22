package cs.quizzapp.prokect.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(
                                "http://localhost:3000",  // Local development
                                "https://*.railway.app",  // Railway frontend
                                "https://*.vercel.app",   // Vercel frontend (if you use it)
                                "https://*.netlify.app"   // Netlify frontend (if you use it)
                        )
                        .allowedOriginPatterns("https://*.railway.app") // Pattern matching for Railway
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Authorization", "Content-Type", "Accept")
                        .allowCredentials(true);
            }
        };
    }
}