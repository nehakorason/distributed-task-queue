package com.taskqueue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobSchedulerService {

    private final JobService jobService;

    @Scheduled(fixedDelay = 5000)  // Every 5 seconds
    public void processScheduledJobs() {
        try {
            jobService.processPendingScheduledJobs();
        } catch (Exception e) {
            log.error("Error processing scheduled jobs: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30000)  // Every 30 seconds
    public void retryFailedJobs() {
        try {
            jobService.retryFailedJobs();
        } catch (Exception e) {
            log.error("Error retrying failed jobs: {}", e.getMessage());
        }
    }
}
