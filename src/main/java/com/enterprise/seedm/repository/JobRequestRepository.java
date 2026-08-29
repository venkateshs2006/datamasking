package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.JobRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobRequestRepository extends JpaRepository<JobRequest, Long> {
    List<JobRequest> findAllByOrderByCreatedAtDesc();

    @Query("SELECT j FROM JobRequest j WHERE LOWER(j.department) IN :departments ORDER BY j.createdAt DESC")
    List<JobRequest> findByDepartmentInIgnoreCaseOrderByCreatedAtDesc(@Param("departments") List<String> departments);

    @Query("SELECT DISTINCT j FROM JobRequest j WHERE LOWER(j.department) IN :departments OR LOWER(j.submittedBy) = :username ORDER BY j.createdAt DESC")
    List<JobRequest> findByDepartmentsOrSubmittedBy(@Param("departments") List<String> departments, @Param("username") String username);

    @Query("SELECT j FROM JobRequest j WHERE LOWER(j.submittedBy) = :username ORDER BY j.createdAt DESC")
    List<JobRequest> findBySubmittedByIgnoreCaseOrderByCreatedAtDesc(@Param("username") String username);
}