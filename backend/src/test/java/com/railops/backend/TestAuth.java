package com.railops.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Signs test requests in, so no test builds its own tokens or headers. */
final class TestAuth {

    private TestAuth() {
    }

    /** For {@code @WebMvcTest} requests: an authenticated ADMIN or VIEWER, without going through login. */
    static RequestPostProcessor admin() {
        return as(Role.ADMIN);
    }

    static RequestPostProcessor viewer() {
        return as(Role.VIEWER);
    }

    private static RequestPostProcessor as(Role role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** For full-context tests: logs in through the real endpoint and returns the bearer token. */
    static String login(TestRestTemplate rest, String username, String password) {
        JsonNode response = rest.postForObject("/api/auth/login",
                Map.of("username", username, "password", password), JsonNode.class);
        return response.get("token").asText();
    }

    /** Makes every later request from {@code rest} carry the demo ADMIN's token. */
    static void signInAsAdmin(TestRestTemplate rest) {
        String token = login(rest, "admin", "RailOps#Admin2026");
        rest.getRestTemplate().getInterceptors().clear();
        rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
    }
}
