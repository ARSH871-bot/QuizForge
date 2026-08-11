package com.quizforge.identity.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Confirms that the {@code /v1/auth/**} path is reachable without
 * authentication. Kept separate from {@code AuthController} so the security
 * configuration can be verified before any authentication endpoint exists.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthHealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
