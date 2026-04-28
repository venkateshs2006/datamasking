package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.CosConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CosConnectionRepository extends JpaRepository<CosConnection, Long> {

    @Query("SELECT c FROM CosConnection c WHERE " +
           "(:departments IS NULL OR 'ALL' IN :departments OR c.department IN :departments) AND " +
           "(:envType IS NULL OR :envType = '' OR LOWER(c.envType) = LOWER(:envType))")
    List<CosConnection> findByFilters(
            @Param("departments") List<String> departments, 
            @Param("envType") String envType);
}
