package com.railops.producer;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProduceController.class)
class ProduceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventPublisher publisher;

    @Test
    void countDefaultsToOne() throws Exception {
        when(publisher.produce(1)).thenReturn(new ProduceResult(1, 0, 0));

        mvc.perform(post("/produce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.invalid").value(0));
    }

    @Test
    void returnsSentDuplicateAndInvalidCounts() throws Exception {
        when(publisher.produce(50)).thenReturn(new ProduceResult(50, 3, 2));

        mvc.perform(post("/produce").param("count", "50"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"sent\":50,\"duplicates\":3,\"invalid\":2}", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "-5", "abc"})
    void rejectsInvalidCountWithProblemDetail(String count) throws Exception {
        mvc.perform(post("/produce").param("count", count))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verify(publisher, never()).produce(anyInt());
    }

    @Test
    void kafkaFailureReturns503ProblemDetail() throws Exception {
        when(publisher.produce(10)).thenThrow(new PublishException(10, 4, new RuntimeException("broker down")));

        mvc.perform(post("/produce").param("count", "10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Kafka unavailable"))
                .andExpect(jsonPath("$.requested").value(10))
                .andExpect(jsonPath("$.confirmed").value(4));
    }
}
