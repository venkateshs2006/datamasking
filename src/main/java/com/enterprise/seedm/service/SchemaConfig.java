package com.enterprise.seedm.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Data
public class SchemaConfig {
    
    @Value("${seedm.migration.source.schema}")
    private String sourceSchema;
    
    @Value("${seedm.migration.destination.schema}")
    private String destinationSchema;
}
