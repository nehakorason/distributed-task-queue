package com.taskqueue.repository;

import com.taskqueue.model.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Job Model Tests")
class JobModelTest {

    @Nested
    @DisplayName("JobStatus enum")
    class StatusTests {
        @Test
        void allStatusValuesExist() {
            assertThat(Job.JobStatus.values()).containsExactlyInAnyOrder(
                Job.JobStatus.PENDING, Job.JobStatus.QUEUED, Job.JobStatus.RUNNING,
                Job.JobStatus.COMPLETED, Job.JobStatus.FAILED,
                Job.JobStatus.CANCELLED, Job.JobStatus.DEAD_LETTER
            );
        }
    }

    @Nested
    @DisplayName("JobPriority enum")
    class PriorityTests {
        @Test
        void criticalHasHighestScore() {
            int max = 0;
            for (Job.JobPriority p : Job.JobPriority.values()) {
                if (p.getScore() > max) max = p.getScore();
            }
            assertThat(Job.JobPriority.CRITICAL.getScore()).isEqualTo(max);
        }

        @Test
        void lowHasLowestScore() {
            int min = Integer.MAX_VALUE;
            for (Job.JobPriority p : Job.JobPriority.values()) {
                if (p.getScore() < min) min = p.getScore();
            }
            assertThat(Job.JobPriority.LOW.getScore()).isEqualTo(min);
        }
    }

    @Nested
    @DisplayName("Job.builder()")
    class BuilderTests {
        @Test
        void defaultRetryCountIsZero() {
            Job job = Job.builder().type("test").build();
            assertThat(job.getRetryCount()).isEqualTo(0);
        }

        @Test
        void defaultMaxRetriesIsThree() {
            Job job = Job.builder().type("test").build();
            assertThat(job.getMaxRetries()).isEqualTo(3);
        }
    }
}
