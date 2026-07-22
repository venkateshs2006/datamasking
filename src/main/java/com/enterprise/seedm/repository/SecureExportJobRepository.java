package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.SecureExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecureExportJobRepository extends JpaRepository<SecureExportJob, Long> {
}
