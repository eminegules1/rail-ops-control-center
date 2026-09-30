package com.railops.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, TokenService.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private ObjectMapper json;

    @ParameterizedTest
    @CsvSource({"admin, RailOps#Admin2026, ADMIN", "viewer, RailOps#Viewer2026, VIEWER"})
    void signsInADemoUserWithARoleToken(String username, String password, String role) throws Exception {
        String body = mvc.perform(login(username, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value(role))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json.readTree(body);
        Jwt token = decoder.decode(response.get("token").asText());
        assertThat(token.getSubject()).isEqualTo(username);
        assertThat(token.<String>getClaim("role")).isEqualTo(role);
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofHours(8));
        // An ISO-8601 UTC instant like the API's other timestamps, equal to the token's exp.
        assertThat(response.get("expiresAt").isTextual()).isTrue();
        assertThat(Instant.parse(response.get("expiresAt").asText())).isEqualTo(token.getExpiresAt());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameProblem() throws Exception {
        String wrongPassword = mvc.perform(login("admin", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid credentials"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(content().string(not(containsString("token"))))
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mvc.perform(login("nobody", "RailOps#Admin2026"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(unknownUser).get("detail")).isEqualTo(json.readTree(wrongPassword).get("detail"));
        assertThat(json.readTree(unknownUser).get("title")).isEqualTo(json.readTree(wrongPassword).get("title"));
    }

    @Test
    void rejectsBlankFieldsNamingThem() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(containsString("username is required")))
                .andExpect(jsonPath("$.detail").value(containsString("password is required")));
    }

    @Test
    void rejectsOverlongFieldsWithoutEchoingThem() throws Exception {
        String longPassword = "p".repeat(73);
        mvc.perform(login("a".repeat(51), longPassword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("username must be at most 50 characters")))
                .andExpect(jsonPath("$.detail").value(containsString("password must be at most 72 characters")))
                .andExpect(content().string(not(containsString(longPassword))));
    }

    @Test
    void rejectsAMissingBodyWithALoginHint() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail")
                        .value("request body must be JSON like {\"username\":\"...\",\"password\":\"...\"}"));
    }

    @Test
    void rejectsMalformedJsonWithALoginHint() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(not(containsString("ACKNOWLEDGED"))))
                .andExpect(jsonPath("$.detail").value(containsString("username")));
    }

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }
}
