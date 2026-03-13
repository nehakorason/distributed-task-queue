package com.taskqueue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.dto.JobDto;
import com.taskqueue.model.Job;
import com.taskqueue.repository.JobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final JobRepository jobRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${task-queue.redis.queue-key:task:queue}")
    private String queueKey;

    @Value("${task-queue.redis.dead-letter-key:task:dlq}")
    private String deadLetterKey;

    private Counter jobSubmittedCounter;
    private Counter jobCompletedCounter;
    private Counter jobFailedCounter;

    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        jobSubmittedCounter = meterRegistry.counter("jobs.submitted.total");
        jobCompletedCounter = meterRegistry.counter("jobs.completed.total");
        jobFailedCounter = meterRegistry.counter("jobs.failed.total");
    }

    @Transactional
    public JobDto.JobResponse submitJob(JobDto.SubmitRequest request) {
        Job job = Job.builder()
            .id(UUID.randomUUID().toString())
            .type(request.getType())
            .payload(request.getPayload())
            .status(Job.JobStatus.PENDING)
            .priority(request.getPriority() != null ? request.getPriority() : Job.JobPriority.NORMAL)
            .maxRetries(request.getMaxRetries())
            .scheduledAt(request.getScheduledAt())
            .build();

        job = jobRepository.save(job);

        // If not scheduled for future, enqueue immediately
        if (request.getScheduledAt() == null || request.getScheduledAt().isBefore(LocalDateTime.now())) {
            enqueueJob(job);
        }

        log.info("[JOB_SUBMITTED] id={} type={} priority={}", job.getId(), job.getType(), job.getPriority());
        jobSubmittedCounter.increment();

        return JobDto.JobResponse.from(job);
    }

    @Transactional
    public void enqueueJob(Job job) {
        // Use sorted set with priority score for priority queue
        double score = job.getPriority().getScore() * 1000.0 - System.currentTimeMillis() / 1000000.0;
        redisTemplate.opsForZSet().add(queueKey, job.getId(), score);
        job.setStatus(Job.JobStatus.QUEUED);
        jobRepository.save(job);
        log.info("[JOB_QUEUED] id={} priority={} score={}", job.getId(), job.getPriority(), score);
    }

    public Optional<JobDto.JobResponse> getJob(String jobId) {
        return jobRepository.findById(jobId).map(JobDto.JobResponse::from);
    }

    public JobDto.PagedResponse<JobDto.JobResponse> listJobs(
            Job.JobStatus status, String type, Pageable pageable) {

        Page<Job> page;
        if (status != null && type != null) {
            page = jobRepository.findByStatusAndType(status, type, pageable);
        } else if (status != null) {
            page = jobRepository.findByStatus(status, pageable);
        } else if (type != null) {
            page = jobRepository.findByType(type, pageable);
        } else {
            page = jobRepository.findAll(pageable);
        }

        return JobDto.PagedResponse.<JobDto.JobResponse>builder()
            .content(page.getContent().stream().map(JobDto.JobResponse::from).collect(Collectors.toList()))
            .page(page.getNumber())
            .size(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .first(page.isFirst())
            .last(page.isLast())
            .build();
    }

    @Transactional
    public Optional<JobDto.JobResponse> cancelJob(String jobId) {
        return jobRepository.findById(jobId).map(job -> {
            if (job.getStatus() == Job.JobStatus.RUNNING) {
                throw new IllegalStateException("Cannot cancel a running job");
            }
            if (job.getStatus() == Job.JobStatus.COMPLETED || job.getStatus() == Job.JobStatus.CANCELLED) {
                throw new IllegalStateException("Job is already " + job.getStatus());
            }
            redisTemplate.opsForZSet().remove(queueKey, jobId);
            job.setStatus(Job.JobStatus.CANCELLED);
            job.setUpdatedAt(LocalDateTime.now());
            Job saved = jobRepository.save(job);
            log.info("[JOB_CANCELLED] id={}", jobId);
            return JobDto.JobResponse.from(saved);
        });
    }

    @Transactional
    public Optional<JobDto.JobResponse> requeueJob(String jobId) {
        return jobRepository.findById(jobId).map(job -> {
            if (job.getStatus() != Job.JobStatus.FAILED && job.getStatus() != Job.JobStatus.DEAD_LETTER) {
                throw new IllegalStateException("Only FAILED or DEAD_LETTER jobs can be requeued");
            }
            job.setStatus(Job.JobStatus.PENDING);
            job.setRetryCount(0);
            job.setErrorMessage(null);
            job = jobRepository.save(job);
            enqueueJob(job);
            log.info("[JOB_REQUEUED] id={}", jobId);
            return JobDto.JobResponse.from(job);
        });
    }

    @Transactional
    public void markJobStarted(String jobId, String workerId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(Job.JobStatus.RUNNING);
            job.setWorkerId(workerId);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);
            log.info("[JOB_STARTED] id={} worker={}", jobId, workerId);
        });
    }

    @Transactional
    public void markJobCompleted(String jobId, String result) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(Job.JobStatus.COMPLETED);
            job.setResult(result);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
            jobCompletedCounter.increment();
            log.info("[JOB_COMPLETED] id={} worker={}", jobId, job.getWorkerId());
        });
    }

    @Transactional
    public void markJobFailed(String jobId, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setErrorMessage(errorMessage);

            if (job.getRetryCount() >= job.getMaxRetries()) {
                job.setStatus(Job.JobStatus.DEAD_LETTER);
                job.setCompletedAt(LocalDateTime.now());
                // Move to DLQ in Redis
                redisTemplate.opsForList().rightPush(deadLetterKey, jobId);
                log.warn("[JOB_DEAD_LETTER] id={} retries={}", jobId, job.getRetryCount());
            } else {
                job.setStatus(Job.JobStatus.FAILED);
                log.warn("[JOB_FAILED] id={} retries={}/{}", jobId, job.getRetryCount(), job.getMaxRetries());
            }
            jobRepository.save(job);
            jobFailedCounter.increment();
        });
    }

    public JobDto.StatsResponse getStats() {
        List<Object[]> statusCounts = jobRepository.countByStatus();
        Map<String, Long> countMap = new HashMap<>();
        for (Object[] row : statusCounts) {
            countMap.put(((Job.JobStatus) row[0]).name(), (Long) row[1]);
        }

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long jobsLastHour = jobRepository.countJobsSince(oneHourAgo);
        long completedLastHour = jobRepository.countByStatusSince(Job.JobStatus.COMPLETED, oneHourAgo);
        long failedLastHour = jobRepository.countByStatusSince(Job.JobStatus.FAILED, oneHourAgo);
        Double avgTime = jobRepository.avgCompletionTimeSeconds(oneHourAgo);

        long completed = countMap.getOrDefault("COMPLETED", 0L);
        long failed = countMap.getOrDefault("FAILED", 0L) + countMap.getOrDefault("DEAD_LETTER", 0L);
        long total = completed + failed;
        double successRate = total > 0 ? (double) completed / total * 100 : 0.0;

        return JobDto.StatsResponse.builder()
            .totalJobs(jobRepository.count())
            .pendingJobs(countMap.getOrDefault("PENDING", 0L))
            .queuedJobs(countMap.getOrDefault("QUEUED", 0L))
            .runningJobs(countMap.getOrDefault("RUNNING", 0L))
            .completedJobs(completed)
            .failedJobs(countMap.getOrDefault("FAILED", 0L))
            .cancelledJobs(countMap.getOrDefault("CANCELLED", 0L))
            .deadLetterJobs(countMap.getOrDefault("DEAD_LETTER", 0L))
            .jobsLastHour(jobsLastHour)
            .completedLastHour(completedLastHour)
            .failedLastHour(failedLastHour)
            .avgCompletionTimeSeconds(avgTime)
            .successRate(successRate)
            .build();
    }

    // Called by scheduler to retry failed jobs with exponential backoff
    @Transactional
    public void retryFailedJobs() {
        List<Job> failedJobs = jobRepository.findByStatusAndRetryCountLessThan(Job.JobStatus.FAILED, 10);
        for (Job job : failedJobs) {
            enqueueJob(job);
            log.info("[JOB_RETRY_SCHEDULED] id={} attempt={}", job.getId(), job.getRetryCount() + 1);
        }
    }

    // Schedule any PENDING jobs that are due
    @Transactional
    public void processPendingScheduledJobs() {
        List<Job> pendingJobs = jobRepository.findByStatusAndRetryCountLessThan(Job.JobStatus.PENDING, 1);
        for (Job job : pendingJobs) {
            if (job.getScheduledAt() == null || job.getScheduledAt().isBefore(LocalDateTime.now())) {
                enqueueJob(job);
            }
        }
    }

    public void updateWorkerHeartbeat(JobDto.WorkerHeartbeat heartbeat) {
        try {
            String key = "worker:heartbeat:" + heartbeat.getWorkerId();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(heartbeat));
            redisTemplate.expire(key, java.time.Duration.ofSeconds(60));
        } catch (Exception e) {
            log.error("Failed to update worker heartbeat: {}", e.getMessage());
        }
    }

    public List<JobDto.WorkerHeartbeat> getActiveWorkers() {
        try {
            Set<String> keys = redisTemplate.keys("worker:heartbeat:*");
            if (keys == null) return List.of();
            List<JobDto.WorkerHeartbeat> workers = new ArrayList<>();
            for (String key : keys) {
                Object val = redisTemplate.opsForValue().get(key);
                if (val != null) {
                    workers.add(objectMapper.readValue(val.toString(), JobDto.WorkerHeartbeat.class));
                }
            }
            return workers;
        } catch (Exception e) {
            log.error("Failed to get active workers: {}", e.getMessage());
            return List.of();
        }
    }
}
