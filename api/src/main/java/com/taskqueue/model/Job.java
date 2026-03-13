package com.taskqueue.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs", indexes = {
    @Index(name = "idx_jobs_status", columnList = "status"),
    @Index(name = "idx_jobs_created_at", columnList = "created_at"),
    @Index(name = "idx_jobs_priority", columnList = "priority")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private JobPriority priority;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 3;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = JobStatus.PENDING;
        }
        if (priority == null) {
            priority = JobPriority.NORMAL;
        }
    }

    public enum JobStatus {
        PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, DEAD_LETTER
    }

    public enum JobPriority {
        LOW(1), NORMAL(5), HIGH(10), CRITICAL(20);

        private final int score;

        JobPriority(int score) {
            this.score = score;
        }

        public int getScore() {
            return score;
        }
    }
}
