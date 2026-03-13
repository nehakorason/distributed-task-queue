package com.taskqueue.controller;

import com.taskqueue.dto.JobDto;
import com.taskqueue.model.Job;
import com.taskqueue.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class JobController {

    private final JobService jobService;

    @PostMapping
    public ResponseEntity<JobDto.JobResponse> submitJob(
            @Valid @RequestBody JobDto.SubmitRequest request) {
        JobDto.JobResponse response = jobService.submitJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobDto.JobResponse> getJob(@PathVariable String jobId) {
        return jobService.getJob(jobId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<JobDto.PagedResponse<JobDto.JobResponse>> listJobs(
            @RequestParam(required = false) Job.JobStatus status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("ASC")
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), sort);
        return ResponseEntity.ok(jobService.listJobs(status, type, pageable));
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<JobDto.JobResponse> cancelJob(@PathVariable String jobId) {
        return jobService.cancelJob(jobId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{jobId}/requeue")
    public ResponseEntity<JobDto.JobResponse> requeueJob(@PathVariable String jobId) {
        return jobService.requeueJob(jobId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<JobDto.StatsResponse> getStats() {
        return ResponseEntity.ok(jobService.getStats());
    }

    @GetMapping("/workers")
    public ResponseEntity<List<JobDto.WorkerHeartbeat>> getActiveWorkers() {
        return ResponseEntity.ok(jobService.getActiveWorkers());
    }

    // Internal worker endpoints
    @PostMapping("/{jobId}/start")
    public ResponseEntity<Void> markJobStarted(
            @PathVariable String jobId,
            @RequestParam String workerId) {
        jobService.markJobStarted(jobId, workerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{jobId}/complete")
    public ResponseEntity<Void> markJobCompleted(
            @PathVariable String jobId,
            @RequestBody Map<String, String> body) {
        jobService.markJobCompleted(jobId, body.get("result"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{jobId}/fail")
    public ResponseEntity<Void> markJobFailed(
            @PathVariable String jobId,
            @RequestBody Map<String, String> body) {
        jobService.markJobFailed(jobId, body.get("errorMessage"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> workerHeartbeat(
            @RequestBody JobDto.WorkerHeartbeat heartbeat) {
        jobService.updateWorkerHeartbeat(heartbeat);
        return ResponseEntity.ok().build();
    }
}
