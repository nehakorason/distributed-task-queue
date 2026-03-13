package com.taskqueue.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.dto.JobDto;
import com.taskqueue.model.Job;
import com.taskqueue.repository.JobRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Integration Tests — Full Stack")
class JobIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("taskqueue_test")
        .withUsername("taskqueue")
        .withPassword("taskqueue");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JobRepository jobRepository;
    @Autowired private ObjectMapper objectMapper;

    private static String createdJobId;

    @BeforeEach
    void cleanup() {
        // Only clean before the first test
    }

    @AfterAll
    static void teardown() {
        // Testcontainers auto-cleanup
    }

    @Test
    @Order(1)
    @DisplayName("1. Submit a job — should persist and return QUEUED")
    void submitJob_persistedAndQueued() throws Exception {
        var request = JobDto.SubmitRequest.builder()
            .type("data_processing")
            .payload("{\"records\":250}")
            .priority(Job.JobPriority.HIGH)
            .maxRetries(3)
            .build();

        String response = mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.type").value("data_processing"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andReturn().getResponse().getContentAsString();

        createdJobId = objectMapper.readTree(response).get("id").asText();
        assertThat(createdJobId).isNotBlank();

        // Verify DB persistence
        assertThat(jobRepository.findById(createdJobId)).isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("2. Get job by ID — should return correct job")
    void getJob_returnsCorrectJob() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/{id}", createdJobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(createdJobId))
            .andExpect(jsonPath("$.type").value("data_processing"))
            .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    @Order(3)
    @DisplayName("3. List jobs — should include submitted job")
    void listJobs_includesSubmittedJob() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(4)
    @DisplayName("4. List jobs filtered by status QUEUED — should find job")
    void listJobs_filteredByStatus_findsJob() throws Exception {
        mockMvc.perform(get("/api/v1/jobs?status=QUEUED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.id == '" + createdJobId + "')]").exists());
    }

    @Test
    @Order(5)
    @DisplayName("5. Mark job as started — should transition to RUNNING")
    void markJobStarted_transitionsToRunning() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/{id}/start", createdJobId)
                .param("workerId", "integration-worker-1"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/jobs/{id}", createdJobId))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.workerId").value("integration-worker-1"));
    }

    @Test
    @Order(6)
    @DisplayName("6. Mark job as completed — should transition to COMPLETED")
    void markJobCompleted_transitionsToCompleted() throws Exception {
        String body = "{\"result\":\"{\\\"processed\\\":250}\"}";
        mockMvc.perform(post("/api/v1/jobs/{id}/complete", createdJobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/jobs/{id}", createdJobId))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    @Order(7)
    @DisplayName("7. Cannot cancel a COMPLETED job — should return 409")
    void cancelCompletedJob_returns409() throws Exception {
        mockMvc.perform(delete("/api/v1/jobs/{id}", createdJobId))
            .andExpect(status().isConflict());
    }

    @Test
    @Order(8)
    @DisplayName("8. Stats endpoint — should reflect completed job")
    void getStats_reflectsCompletedJob() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completedJobs").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.totalJobs").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(9)
    @DisplayName("9. Submit + fail + requeue — full retry lifecycle")
    void retryLifecycle_failAndRequeue() throws Exception {
        // Submit
        var request = JobDto.SubmitRequest.builder()
            .type("email_notification")
            .payload("{\"to\":\"test@example.com\"}")
            .priority(Job.JobPriority.NORMAL)
            .maxRetries(2)
            .build();

        String submitResponse = mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String failJobId = objectMapper.readTree(submitResponse).get("id").asText();

        // Start
        mockMvc.perform(post("/api/v1/jobs/{id}/start", failJobId)
                .param("workerId", "test-worker")).andExpect(status().isOk());

        // Fail (retryCount 0→1, still under maxRetries=2)
        mockMvc.perform(post("/api/v1/jobs/{id}/fail", failJobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"errorMessage\":\"SMTP timeout\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/jobs/{id}", failJobId))
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.retryCount").value(1));

        // Fail again (retryCount 1→2, equals maxRetries → DEAD_LETTER)
        mockMvc.perform(post("/api/v1/jobs/{id}/start", failJobId)
                .param("workerId", "test-worker")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/jobs/{id}/fail", failJobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"errorMessage\":\"SMTP timeout again\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/jobs/{id}", failJobId))
            .andExpect(jsonPath("$.status").value("DEAD_LETTER"))
            .andExpect(jsonPath("$.retryCount").value(2));

        // Requeue from DLQ
        mockMvc.perform(post("/api/v1/jobs/{id}/requeue", failJobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.retryCount").value(0));
    }

    @Test
    @Order(10)
    @DisplayName("10. Validate 404 on unknown job ID")
    void getJob_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/does-not-exist-at-all"))
            .andExpect(status().isNotFound());
    }
}
