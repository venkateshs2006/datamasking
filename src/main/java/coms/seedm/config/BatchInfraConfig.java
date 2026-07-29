package coms.seedm.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class BatchInfraConfig {

    /**
     * Runs jobs on a background thread so POST /api/seedm/run returns immediately
     * with a job execution id instead of blocking until the whole export finishes.
     * Swap SimpleAsyncTaskExecutor for a bounded pool if you need to cap concurrency.
     */
    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("seedm-job-"));
        launcher.afterPropertiesSet();
        return launcher;
    }
}
