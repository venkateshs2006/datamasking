package com.enterprise.seedm;

import com.github.javafaker.Faker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Anonify Platform Main Application
 * Data Anonymization and Migration Platform
 *
 * This application provides:
 * - High-performance database migration using Spring Batch
 * - In-memory data masking with FPE and Faker
 * - Support for legacy databases (Oracle, DB2, SQL Server)
 * - Virtual threads for parallel processing
 * - Real-time monitoring via SSE
 */
@SpringBootApplication
@EnableAsync
@EnableTransactionManagement
@ConfigurationPropertiesScan
public class AnonifyApplication {
	public static void main(String[] args) {
		// Enable virtual threads for high concurrency
		System.setProperty("spring.threads.virtual.enabled", "true");
		SpringApplication.run(AnonifyApplication.class, args);
	}

	@Bean
	public Faker faker() {
		return new Faker();
	}

	@Bean("secureExportTaskExecutor")
	public TaskExecutor secureExportTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(10);
		executor.setQueueCapacity(25);
		executor.setThreadNamePrefix("secure-export-");
		executor.initialize();
		return executor;
	}

	@Bean("applicationTaskExecutor")
	public TaskExecutor applicationTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(10);
		executor.setQueueCapacity(25);
		executor.setThreadNamePrefix("application-");
		executor.initialize();
		return executor;
	}
}