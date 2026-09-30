package com.railops.backend;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Every row of the authorization matrix in the Feature 20 spec, through the real filter chain.
@WebMvcTest({EventController.class, DashboardController.class, AuthController.class})
@Import({SecurityConfig.class, TokenService.class})
class SecurityRulesTest {

    private static final EventResponse EVENT = new EventResponse("EVT-1", "CBTC", "signal-service",
            Severity.CRITICAL, "Signal failure", EventStatus.OPEN, Instant.parse("2026-09-26T14:30:05.123Z"),
            Instant.parse("2026-09-26T14:30:05.456Z"), Instant.parse("2026-09-26T14:30:06Z"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JwtEncoder encoder;

    @MockitoBean
    private EventQueryService events;

    @MockitoBean
    private IncidentStatusService statuses;

    @MockitoBean
    private DashboardQueryService dashboard;

    @ParameterizedTest
    @ValueSource(strings = {"/api/events", "/api/events/EVT-1", "/api/dashboard/summary", "/api/services",
            "/api/dashboard/recent-events", "/api/nothing-here"})
    void anonymousReadsAreUnauthorized(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedIsAProblemThatDoesNotSayWhy() throws Exception {
        mvc.perform(get("/api/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Sign in to continue"));
    }

    @Test
    void garbageTokenIsUnauthorizedWithTheSameAnswer() throws Exception {
        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value("Sign in to continue"));
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(9));
        String expired = mint(issuedAt, issuedAt.plus(Duration.ofHours(8)), "ADMIN");

        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Sign in to continue"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsUnauthorized() throws Exception {
        // Header and payload of a real token with the signature replaced.
        String real = tokens.issue("admin", Role.ADMIN).value();
        String forged = real.substring(0, real.lastIndexOf('.') + 1) + "A".repeat(43);

        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTheBearerSchemeIsUnauthorized() throws Exception {
        String token = tokens.issue("admin", Role.ADMIN).value();

        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminTokenFromTheLoginServiceIsAccepted() throws Exception {
        String token = tokens.issue("admin", Role.ADMIN).value();

        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void viewerAndAdminCanRead() throws Exception {
        mvc.perform(get("/api/events").with(TestAuth.viewer())).andExpect(status().isOk());
        mvc.perform(get("/api/events").with(TestAuth.admin())).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/summary").with(TestAuth.viewer())).andExpect(status().isOk());
    }

    @Test
    void anonymousStatusChangeIsUnauthorizedNotForbidden() throws Exception {
        mvc.perform(statusChange()).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotChangeStatus() throws Exception {
        mvc.perform(statusChange().with(TestAuth.viewer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("This action requires the ADMIN role"));
    }

    @Test
    void adminCanChangeStatus() throws Exception {
        when(statuses.changeStatus("EVT-1", EventStatus.ACKNOWLEDGED)).thenReturn(EVENT);

        mvc.perform(statusChange().with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("EVT-1"));
    }

    @Test
    void everyOtherWriteUnderApiNeedsAdmin() throws Exception {
        mvc.perform(delete("/api/events/EVT-1").with(TestAuth.viewer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/events").with(TestAuth.viewer())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/events/EVT-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsPublic() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operationalAndDocumentationPathsAreNotBehindTheLogin() throws Exception {
        // The slice has no actuator or springdoc, so a request that gets past security is a 404, not a 401.
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/prometheus",
                "/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void otherActuatorEndpointsStayProtected() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void theWebSocketHandshakeIsNotBlockedByTheHttpChain() throws Exception {
        mvc.perform(get("/ws")).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder statusChange() {
        return put("/api/events/EVT-1/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACKNOWLEDGED\"}");
    }

    private String mint(Instant issuedAt, Instant expiresAt, String role) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("admin").claim("role", role)
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
