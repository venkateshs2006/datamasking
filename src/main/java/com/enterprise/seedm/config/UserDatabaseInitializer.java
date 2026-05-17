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

                // Admin User
                AppUser admin = new AppUser();
                admin.setUsername("admin");
                admin.setPassword(encoder.encode("admin123"));
                admin.setRoles(Set.of("ADMIN"));
                admin.setDepartments(Set.of("ALL"));
                userRepository.save(admin);

                // Finance Scheduler
                AppUser finSched = new AppUser();
                finSched.setUsername("finance_sched");
                finSched.setPassword(encoder.encode("pass123"));
                finSched.setRoles(Set.of("SCHEDULER"));
                finSched.setDepartments(Set.of("Finance"));
                userRepository.save(finSched);

                // HR Scheduler
                AppUser hrSched = new AppUser();
                hrSched.setUsername("hr_sched");
                hrSched.setPassword(encoder.encode("pass123"));
                hrSched.setRoles(Set.of("SCHEDULER"));
                hrSched.setDepartments(Set.of("HR"));
                userRepository.save(hrSched);

                // IT Scheduler
                AppUser itSched = new AppUser();
                itSched.setUsername("it_sched");
                itSched.setPassword(encoder.encode("pass123"));
                itSched.setRoles(Set.of("SCHEDULER"));
                itSched.setDepartments(Set.of("IT"));
                userRepository.save(itSched);
                
                // Admin Dept Scheduler
                AppUser adminSched = new AppUser();
                adminSched.setUsername("admin_sched");
                adminSched.setPassword(encoder.encode("pass123"));
                adminSched.setRoles(Set.of("SCHEDULER"));
                adminSched.setDepartments(Set.of("Admin"));
                userRepository.save(adminSched);

                // Finance Approver
                AppUser finAppr = new AppUser();
                finAppr.setUsername("finance_appr");
                finAppr.setPassword(encoder.encode("pass123"));
                finAppr.setRoles(Set.of("APPROVER"));
                finAppr.setDepartments(Set.of("Finance"));
                userRepository.save(finAppr);

                // HR Approver
                AppUser hrAppr = new AppUser();
                hrAppr.setUsername("hr_appr");
                hrAppr.setPassword(encoder.encode("pass123"));
                hrAppr.setRoles(Set.of("APPROVER"));
                hrAppr.setDepartments(Set.of("HR"));
                userRepository.save(hrAppr);
                
                // IT Approver
                AppUser itAppr = new AppUser();
                itAppr.setUsername("it_appr");
                itAppr.setPassword(encoder.encode("pass123"));
                itAppr.setRoles(Set.of("APPROVER"));
                itAppr.setDepartments(Set.of("IT"));
                userRepository.save(itAppr);
                
                // Multi-department user test
                AppUser multiUser = new AppUser();
                multiUser.setUsername("manager_appr");
                multiUser.setPassword(encoder.encode("pass123"));
                multiUser.setRoles(Set.of("APPROVER", "SCHEDULER"));
                multiUser.setDepartments(Set.of("Finance", "HR"));
                userRepository.save(multiUser);

                log.info("Successfully populated sample users and roles.");
            }
            
            // Populate sample connections if db_connections table is empty
            if (dbConnectionRepository.count() == 0) {
                dbConnectionRepository.save(new DbConnection(null, "Finance Prod DB", "Finance", "postgres", "source", "jdbc:postgresql://localhost:5432/finance_prod", "fin_user", "fin_pass"));
                dbConnectionRepository.save(new DbConnection(null, "Finance QA DB", "Finance", "postgres", "destination", "jdbc:postgresql://localhost:5432/finance_qa", "qa_user", "qa_pass"));
                dbConnectionRepository.save(new DbConnection(null, "Finance Prod Mongo", "Finance", "mongo", "source", "mongodb://localhost:27017/fin_prod", "fin_user", "fin_pass"));
                dbConnectionRepository.save(new DbConnection(null, "Finance Test Mongo", "Finance", "mongo", "destination", "mongodb://localhost:27017/fin_test", "fin_test", "fin_test"));

                dbConnectionRepository.save(new DbConnection(null, "HR Prod Mongo", "HR", "mongo", "source", "mongodb://localhost:27017/hr_prod", "hr_user", "hr_pass"));
                dbConnectionRepository.save(new DbConnection(null, "HR Test Mongo", "HR", "mongo", "destination", "mongodb://localhost:27017/hr_test", "hr_test", "hr_test"));
                
                dbConnectionRepository.save(new DbConnection(null, "IT Logs Dir", "IT", "json", "source", "/var/logs/it/prod", "", ""));
                dbConnectionRepository.save(new DbConnection(null, "IT Masked Logs Dir", "IT", "json", "destination", "/var/logs/it/masked", "", ""));
                
                dbConnectionRepository.save(new DbConnection(null, "Admin Master DB", "Admin", "postgres", "source", "jdbc:postgresql://localhost:5432/admin_master", "admin_db", "admin_db"));
                dbConnectionRepository.save(new DbConnection(null, "Admin Masked DB", "Admin", "postgres", "destination", "jdbc:postgresql://localhost:5432/admin_masked", "admin_db", "admin_db"));
                dbConnectionRepository.save(new DbConnection(null, "Admin Prod Mongo", "Admin", "mongo", "source", "mongodb://localhost:27017/admin_prod", "admin_db", "admin_db"));
                dbConnectionRepository.save(new DbConnection(null, "Admin Test Mongo", "Admin", "mongo", "destination", "mongodb://localhost:27017/admin_test", "admin_test", "admin_test"));
                
                log.info("Successfully populated sample database connections.");
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
