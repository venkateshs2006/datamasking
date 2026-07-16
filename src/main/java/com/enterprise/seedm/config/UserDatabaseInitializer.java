package com.enterprise.seedm.config;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.repository.CosConnectionRepository;
import com.enterprise.seedm.repository.DbConnectionRepository;
import com.enterprise.seedm.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Date;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDatabaseInitializer {

    private final UserRepository userRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final CosConnectionRepository cosConnectionRepository;
    private final DataSource dataSource;

    @PostConstruct
    public void init() {
        try {
            // First, run the schema creation script
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("db-scripts.sql"));
            populator.setContinueOnError(true); // Tables might already exist
            populator.execute(dataSource);

           // Populate sample data if users table is empty
           if (userRepository.count() == 0) {
               BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();



               log.info("Successfully populated sample users and roles.");
           }

           if (cosConnectionRepository.count() == 0) {
               cosConnectionRepository.save(new CosConnection(null, "Finance Prod Bucket", "COS", null, "us-south", "dummy-api-key", "crn:v1:bluemix:public:cloud-object-storage:global:a/12345:12345::", "dummy-access", "dummy-secret", "https://s3.us-south.cloud-object-storage.appdomain.cloud", "bucket-123", "fin-prod-data", "IAM", "Finance", "source", "admin", System.currentTimeMillis()));
               cosConnectionRepository.save(new CosConnection(null, "Finance QA Bucket", "COS", null, "us-south", "dummy-api-key", "crn:v1:bluemix:public:cloud-object-storage:global:a/12345:67890::", "dummy-access", "dummy-secret", "https://s3.us-south.cloud-object-storage.appdomain.cloud", "bucket-456", "fin-qa-data", "IAM", "Finance", "destination", "admin", System.currentTimeMillis()));
               log.info("Successfully populated sample COS connections.");
           }

        } catch (Exception e) {
            log.error("Failed to initialize user database", e);
        }
    }
}