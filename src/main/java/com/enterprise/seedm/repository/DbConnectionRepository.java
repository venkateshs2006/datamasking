package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DbConnectionRepository extends JpaRepository<DbConnection, Long> {

    @Query("SELECT c FROM DbConnection c WHERE " +
           "(:department = 'ALL' OR LOWER(c.department) = LOWER(:department)) AND " +
           "(:dbType IS NULL OR :dbType = '' OR LOWER(c.dbType) = LOWER(:dbType)) AND " +
           "(:envType IS NULL OR :envType = '' OR LOWER(c.envType) = LOWER(:envType))")
    List<DbConnection> findByFilters(
            @Param("department") String department, 
            @Param("dbType") String dbType, 
            @Param("envType") String envType);
}
