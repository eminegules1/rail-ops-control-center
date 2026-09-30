package com.railops.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

// A denial thrown from a controller (as @PreAuthorize would) is a 403 problem, not the catch-all's 500.
@WebMvcTest(AccessDeniedAdviceTest.Denying.class)
@Import({SecurityConfig.class, AccessDeniedAdviceTest.Denying.class})
class AccessDeniedAdviceTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void controllerThrownAccessDeniedIsForbidden() throws Exception {
        mvc.perform(get("/api/denied").with(TestAuth.viewer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("This action requires the ADMIN role"));
    }

    @RestController
    static class Denying {

        @GetMapping("/api/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }
    }
}
