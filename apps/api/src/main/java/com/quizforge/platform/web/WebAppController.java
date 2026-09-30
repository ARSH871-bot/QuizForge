package com.quizforge.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Hands the web app's own routes to its single page.
 *
 * <p>The web app is served from the same origin as the API, so the session and
 * CSRF cookies need no cross-origin configuration. The routes are listed
 * rather than matched by a wildcard, so a mistyped API path still answers as an
 * API — with a problem document, not a web page.
 *
 * <p>Where no web build is present (running the API on its own), the forward
 * finds nothing and the response is a plain 404.
 */
@Controller
public class WebAppController {

    @GetMapping({"/", "/sign-in", "/app", "/app/new", "/app/t/{id}", "/t/{id}"})
    public String app() {
        return "forward:/index.html";
    }
}
