package com.taskqueue.service;

import com.taskqueue.dto.JobDto;
import com.taskqueue.model.Job;
import com.taskqueue.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobService Unit Tests")
class JobServiceTest {

    @Mock private JobRepository jobRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ZSetOperations<String, Object> zSetOps;
    @Mock private ListOperations<String, Object> listOps;
    @Mock private ValueOperations<String, Object> valueOps;

    private JobService jobService;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        jobService = new JobService(jobRepository, redisTemplate, mapper, meterRegistry);
        jobService.initMetrics();

        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(zSetOps.add(anyString(), any(), anyDouble())).thenReturn(true);
        lenient().when(listOps.rightPush(anyString(), any())).thenReturn(1L);
        lenient().when(zSetOps.remove(anyString(), any())).thenReturn(1L);
    }

    // ── Submit ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitJob()")
    class SubmitJobTests {

        @Test
        @DisplayName("should persist job and enqueue immediately when no scheduledAt")
        void submitJob_noSchedule_persistsAndEnqueues() {
            var request = JobDto.SubmitRequest.builder()
                .type("data_processing")
                .payload("{\"records\":100}")
                .priority(Job.JobPriority.HIGH)
                .maxRetries(3)
                .build();

            Job savedJob = Job.builder()
                .id("test-id-1")
                .type("data_processing")
                .status(Job.JobStatus.QUEUED)
                .priority(Job.JobPriority.HIGH)
                .maxRetries(3)
                .build();

            when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

            JobDto.JobResponse response = jobService.submitJob(request);

            assertThat(response).isNotNull();
            assertThat(response.getType()).isEqualTo("data_processing");
            verify(jobRepository, atLeast(1)).save(any(Job.class));
            verify(zSetOps).add(anyString(), eq("test-id-1"), anyDouble());
        }

        @Test
        @DisplayName("should NOT enqueue when scheduledAt is in the future")
        void submitJob_futureSchedule_doesNotEnqueue() {
            var request = JobDto.SubmitRequest.builder()
                .type("report_generation")
                .scheduledAt(LocalDateTime.now().plusHours(2))
                .maxRetries(3)
                .build();

            Job savedJob = Job.builder()
                .id("future-job-1")
                .type("report_generation")
                .status(Job.JobStatus.PENDING)
                .priority(Job.JobPriority.NORMAL)
                .scheduledAt(LocalDateTime.now().plusHours(2))
                .build();

            when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

            jobService.submitJob(request);

            verify(zSetOps, never()).add(anyString(), any(), anyDouble());
        }

        @Test
        @DisplayName("should default priority to NORMAL when not specified")
        void submitJob_noPriority_defaultsToNormal() {
            var request = JobDto.SubmitRequest.builder()
                .type("email_notification")
                .maxRetries(3)
                .build();

            Job savedJob = Job.builder()
                .id("id1").type("email_notification")
                .status(Job.JobStatus.QUEUED)
                .priority(Job.JobPriority.NORMAL)
                .build();
            when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

            JobDto.JobResponse response = jobService.submitJob(request);

            assertThat(response).isNotNull();
        }
    }

    // ── Cancel ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelJob()")
    class CancelJobTests {

        @Test
        @DisplayName("should cancel a QUEUED job")
        void cancelJob_queued_succeeds() {
            Job job = Job.builder().id("j1").status(Job.JobStatus.QUEUED).type("test").build();
            when(jobRepository.findById("j1")).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Optional<JobDto.JobResponse> result = jobService.cancelJob("j1");

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
        }

        @Test
        @DisplayName("should throw when cancelling a RUNNING job")
        void cancelJob_running_throwsIllegalState() {
            Job job = Job.builder().id("j2").status(Job.JobStatus.RUNNING).type("test").build();
            when(jobRepository.findById("j2")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> jobService.cancelJob("j2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel a running job");
        }

        @Test
        @DisplayName("should throw when cancelling an already COMPLETED job")
        void cancelJob_completed_throwsIllegalState() {
            Job job = Job.builder().id("j3").status(Job.JobStatus.COMPLETED).type("test").build();
            when(jobRepository.findById("j3")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> jobService.cancelJob("j3"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should return empty when job not found")
        void cancelJob_notFound_returnsEmpty() {
            when(jobRepository.findById("missing")).thenReturn(Optional.empty());
            assertThat(jobService.cancelJob("missing")).isEmpty();
        }
    }

    // ── Retry / DLQ ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markJobFailed() — retry and DLQ logic")
    class MarkJobFailedTests {

        @Test
        @DisplayName("should set FAILED and increment retryCount when under limit")
        void markJobFailed_underMaxRetries_setsFailed() {
            Job job = Job.builder().id("j4").status(Job.JobStatus.RUNNING)
                .retryCount(0).maxRetries(3).type("test").build();
            when(jobRepository.findById("j4")).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            jobService.markJobFailed("j4", "connection timeout");

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Job.JobStatus.FAILED);
            assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
            assertThat(captor.getValue().getErrorMessage()).isEqualTo("connection timeout");
        }

        @Test
        @DisplayName("should promote to DEAD_LETTER when retries exhausted")
        void markJobFailed_exhaustedRetries_movesToDLQ() {
            Job job = Job.builder().id("j5").status(Job.JobStatus.RUNNING)
                .retryCount(2).maxRetries(3).type("test").build();
            when(jobRepository.findById("j5")).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            jobService.markJobFailed("j5", "persistent error");

            ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(Job.JobStatus.DEAD_LETTER);
            assertThat(captor.getValue().getRetryCount()).isEqualTo(3);
        }
    }

    // ── Requeue ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("requeueJob()")
    class RequeueJobTests {

        @Test
        @DisplayName("should reset retry count and re-enqueue DEAD_LETTER job")
        void requeueJob_deadLetter_resetsAndEnqueues() {
            Job job = Job.builder().id("j6").status(Job.JobStatus.DEAD_LETTER)
                .retryCount(3).maxRetries(3).errorMessage("old error").type("test").build();
            when(jobRepository.findById("j6")).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Optional<JobDto.JobResponse> result = jobService.requeueJob("j6");

            assertThat(result).isPresent();
            verify(zSetOps).add(anyString(), eq("j6"), anyDouble());
        }

        @Test
        @DisplayName("should throw when requeueing a non-failed job")
        void requeueJob_running_throwsIllegalState() {
            Job job = Job.builder().id("j7").status(Job.JobStatus.RUNNING).type("test").build();
            when(jobRepository.findById("j7")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> jobService.requeueJob("j7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED or DEAD_LETTER");
        }
    }

    // ── Priority ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Priority queue scoring")
    class PriorityScoreTests {

        @Test
        @DisplayName("CRITICAL > HIGH > NORMAL > LOW")
        void priorityOrdering_correctHierarchy() {
            assertThat(Job.JobPriority.CRITICAL.getScore())
                .isGreaterThan(Job.JobPriority.HIGH.getScore());
            assertThat(Job.JobPriority.HIGH.getScore())
                .isGreaterThan(Job.JobPriority.NORMAL.getScore());
            assertThat(Job.JobPriority.NORMAL.getScore())
                .isGreaterThan(Job.JobPriority.LOW.getScore());
        }
    }
}
