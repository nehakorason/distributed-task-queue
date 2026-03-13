package com.taskqueue.dto;

import com.taskqueue.model.Job;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class JobDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {
        @NotBlank(message = "Job type is required")
        @Size(max = 100, message = "Job type must be <= 100 characters")
        private String type;

        private String payload;

        @Builder.Default
        private Job.JobPriority priority = Job.JobPriority.NORMAL;

        @Builder.Default
        private int maxRetries = 3;

        private LocalDateTime scheduledAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JobResponse {
        private String id;
        private String type;
        private String payload;
        private Job.JobStatus status;
        private Job.JobPriority priority;
        private int retryCount;
        private int maxRetries;
        private String workerId;
        private String result;
        private String errorMessage;
        private LocalDateTime scheduledAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static JobResponse from(Job job) {
            return JobResponse.builder()
                .id(job.getId())
                .type(job.getType())
                .payload(job.getPayload())
                .status(job.getStatus())
                .priority(job.getPriority())
                .retryCount(job.getRetryCount())
                .maxRetries(job.getMaxRetries())
                .workerId(job.getWorkerId())
                .result(job.getResult())
                .errorMessage(job.getErrorMessage())
                .scheduledAt(job.getScheduledAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsResponse {
        private long totalJobs;
        private long pendingJobs;
        private long queuedJobs;
        private long runningJobs;
        private long completedJobs;
        private long failedJobs;
        private long cancelledJobs;
        private long deadLetterJobs;
        private long jobsLastHour;
        private long completedLastHour;
        private long failedLastHour;
        private Double avgCompletionTimeSeconds;
        private double successRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerHeartbeat {
        private String workerId;
        private String status;
        private int activeJobs;
        private long processedJobs;
        private long failedJobs;
        private LocalDateTime lastSeen;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PagedResponse<T> {
        private java.util.List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean first;
        private boolean last;
    }
}
