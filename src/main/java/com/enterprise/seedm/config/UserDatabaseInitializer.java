package com.enterprise.seedm.config;

import com.enterprise.seedm.model.AppUser;
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
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDatabaseInitializer {

    private final UserRepository userRepository;
    private final DataSource dataSource;

    @PostConstruct
    public void init() {
        try {
            // First, run the schema creation script
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema-user.sql"));
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
        } catch (Exception e) {
            log.error("Failed to initialize user database", e);
        }
    }
}
