package com.taskqueue.repository;

import com.taskqueue.model.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    Page<Job> findByStatus(Job.JobStatus status, Pageable pageable);

    Page<Job> findByType(String type, Pageable pageable);

    Page<Job> findByStatusAndType(Job.JobStatus status, String type, Pageable pageable);

    List<Job> findByStatusAndRetryCountLessThan(Job.JobStatus status, int maxRetries);

    @Query("SELECT j.status, COUNT(j) FROM Job j GROUP BY j.status")
    List<Object[]> countByStatus();

    @Query("SELECT j.type, COUNT(j) FROM Job j GROUP BY j.type")
    List<Object[]> countByType();

    @Query("SELECT COUNT(j) FROM Job j WHERE j.createdAt >= :since")
    long countJobsSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(j) FROM Job j WHERE j.status = :status AND j.updatedAt >= :since")
    long countByStatusSince(@Param("status") Job.JobStatus status, @Param("since") LocalDateTime since);

    @Query("SELECT AVG(EXTRACT(EPOCH FROM (j.completedAt - j.startedAt))) FROM Job j WHERE j.status = 'COMPLETED' AND j.completedAt >= :since")
    Double avgCompletionTimeSeconds(@Param("since") LocalDateTime since);

    List<Job> findByWorkerIdAndStatus(String workerId, Job.JobStatus status);
}
