package com.taskqueue.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskqueue.dto.JobDto;
import com.taskqueue.model.Job;
import com.taskqueue.service.JobService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(JobController.class)
@DisplayName("JobController REST Tests")
class JobControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private JobService jobService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private JobDto.JobResponse sampleJob(String id, Job.JobStatus status) {
        return JobDto.JobResponse.builder()
            .id(id)
            .type("data_processing")
            .payload("{\"records\":100}")
            .status(status)
            .priority(Job.JobPriority.NORMAL)
            .retryCount(0)
            .maxRetries(3)
            .createdAt(LocalDateTime.now())
            .build();
    }

    // ── POST /api/v1/jobs ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/jobs")
    class SubmitTests {

        @Test
        @DisplayName("should return 201 with job response on valid submission")
        void submit_valid_returns201() throws Exception {
            var request = new JobDto.SubmitRequest("data_processing",
                "{\"records\":500}", Job.JobPriority.HIGH, 3, null);
            var response = sampleJob("abc-123", Job.JobStatus.QUEUED);
            when(jobService.submitJob(any())).thenReturn(response);

            mockMvc.perform(post("/api/v1/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc-123"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.type").value("data_processing"));
        }

        @Test
        @DisplayName("should return 400 when type is missing")
        void submit_missingType_returns400() throws Exception {
            String json = "{\"payload\":\"{}\",\"priority\":\"NORMAL\",\"maxRetries\":3}";

            mockMvc.perform(post("/api/v1/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.type").exists());
        }

        @Test
        @DisplayName("should return 400 when type exceeds 100 characters")
        void submit_longType_returns400() throws Exception {
            String longType = "x".repeat(101);
            String json = String.format("{\"type\":\"%s\",\"maxRetries\":3}", longType);

            mockMvc.perform(post("/api/v1/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
                .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/v1/jobs/:id ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/jobs/{jobId}")
    class GetJobTests {

        @Test
        @DisplayName("should return 200 with job when found")
        void getJob_found_returns200() throws Exception {
            var job = sampleJob("job-1", Job.JobStatus.RUNNING);
            when(jobService.getJob("job-1")).thenReturn(Optional.of(job));

            mockMvc.perform(get("/api/v1/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @DisplayName("should return 404 when job not found")
        void getJob_notFound_returns404() throws Exception {
            when(jobService.getJob("missing")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/jobs/missing"))
                .andExpect(status().isNotFound());
        }
    }

    // ── GET /api/v1/jobs ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/jobs (list)")
    class ListJobsTests {

        @Test
        @DisplayName("should return paged list with default params")
        void listJobs_defaults_returns200() throws Exception {
            var paged = JobDto.PagedResponse.<JobDto.JobResponse>builder()
                .content(List.of(sampleJob("j1", Job.JobStatus.COMPLETED)))
                .page(0).size(20).totalElements(1).totalPages(1)
                .first(true).last(true).build();
            when(jobService.listJobs(any(), any(), any())).thenReturn(paged);

            mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value("j1"));
        }

        @Test
        @DisplayName("should cap page size at 100")
        void listJobs_sizeOver100_clampedTo100() throws Exception {
            var paged = JobDto.PagedResponse.<JobDto.JobResponse>builder()
                .content(List.of()).page(0).size(100).totalElements(0)
                .totalPages(0).first(true).last(true).build();
            when(jobService.listJobs(any(), any(), any())).thenReturn(paged);

            mockMvc.perform(get("/api/v1/jobs?size=999"))
                .andExpect(status().isOk());

            verify(jobService).listJobs(
                isNull(), isNull(),
                argThat(p -> p.getPageSize() == 100)
            );
        }
    }

    // ── DELETE /api/v1/jobs/:id ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/jobs/{jobId}")
    class CancelTests {

        @Test
        @DisplayName("should return 200 with cancelled job")
        void cancel_found_returns200() throws Exception {
            var cancelled = sampleJob("j2", Job.JobStatus.CANCELLED);
            when(jobService.cancelJob("j2")).thenReturn(Optional.of(cancelled));

            mockMvc.perform(delete("/api/v1/jobs/j2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("should return 409 when job is RUNNING")
        void cancel_running_returns409() throws Exception {
            when(jobService.cancelJob("j3"))
                .thenThrow(new IllegalStateException("Cannot cancel a running job"));

            mockMvc.perform(delete("/api/v1/jobs/j3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot cancel a running job"));
        }
    }

    // ── GET /api/v1/jobs/stats ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/jobs/stats")
    class StatsTests {

        @Test
        @DisplayName("should return stats with all fields")
        void getStats_returns200() throws Exception {
            var stats = JobDto.StatsResponse.builder()
                .totalJobs(500L).pendingJobs(10L).queuedJobs(5L)
                .runningJobs(8L).completedJobs(450L).failedJobs(20L)
                .cancelledJobs(5L).deadLetterJobs(2L)
                .jobsLastHour(100L).completedLastHour(90L).failedLastHour(5L)
                .avgCompletionTimeSeconds(3.45).successRate(95.74)
                .build();
            when(jobService.getStats()).thenReturn(stats);

            mockMvc.perform(get("/api/v1/jobs/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(500))
                .andExpect(jsonPath("$.successRate").value(95.74))
                .andExpect(jsonPath("$.runningJobs").value(8));
        }
    }

    // ── POST /api/v1/jobs/:id/requeue ─────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/jobs/{jobId}/requeue")
    class RequeueTests {

        @Test
        @DisplayName("should return 200 when requeue succeeds")
        void requeue_deadLetter_returns200() throws Exception {
            var requeued = sampleJob("j4", Job.JobStatus.QUEUED);
            when(jobService.requeueJob("j4")).thenReturn(Optional.of(requeued));

            mockMvc.perform(post("/api/v1/jobs/j4/requeue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        }

        @Test
        @DisplayName("should return 409 when job is not in a requeue-able state")
        void requeue_invalidState_returns409() throws Exception {
            when(jobService.requeueJob("j5"))
                .thenThrow(new IllegalStateException("Only FAILED or DEAD_LETTER jobs can be requeued"));

            mockMvc.perform(post("/api/v1/jobs/j5/requeue"))
                .andExpect(status().isConflict());
        }
    }
}
