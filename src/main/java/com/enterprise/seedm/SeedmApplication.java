package com.enterprise.seedm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * SeEDM Platform Main Application
 * Secure Enterprise Data Masking and Migration Platform
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
public class SeedmApplication {
	public static void main(String[] args) {
		// Enable virtual threads for high concurrency
		System.setProperty("spring.threads.virtual.enabled", "true");
		SpringApplication.run(SeedmApplication.class, args);
	}
}