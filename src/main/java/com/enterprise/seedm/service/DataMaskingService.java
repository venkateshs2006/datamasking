package com.enterprise.seedm.service;

import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data Masking Service
 * Masks sensitive data using DataFaker based on configuration
 */
@Service
@Slf4j
public class DataMaskingService {

    private final Faker faker;
    private final Map<String, Set<String>> maskingRules;
    private final Map<String, Set<String>> constraintRules;
    private final FormatPreservingEncryptionService fpeService;

    public DataMaskingService(@Value("${seedm.migration.masking-columns:}") List<String> maskingColumns,
                              @Value("${seedm.migration.masking-constraints:}") List<String> maskingConstraints,
                              FormatPreservingEncryptionService fpeService) {
        this.faker = new Faker();
        this.fpeService = fpeService;
        this.maskingRules = parseRules(maskingColumns);
        this.constraintRules = parseRules(maskingConstraints);
        
        log.info("Initialized DataMaskingService with {} masking rules and {} constraint rules", 
                maskingRules.size(), constraintRules.size());
    }

    private Map<String, Set<String>> parseRules(List<String> rules) {
        Map<String, Set<String>> ruleMap = new HashMap<>();
        if (rules != null) {
            for (String rule : rules) {
                String[] parts = rule.split("\\.");
                if (parts.length == 2) {
                    String tableName = parts[0].toLowerCase();
                    String columnName = parts[1].toLowerCase();
                    ruleMap.computeIfAbsent(tableName, k -> new HashSet<>()).add(columnName);
                } else {
                    log.warn("Invalid rule format: {}", rule);
                }
            }
        }
        return ruleMap;
    }

    /**
     * Mask a row of data if applicable
     */
    public Map<String, Object> maskData(String tableName, Map<String, Object> row) {
        String lowerTableName = tableName.toLowerCase();
        
        // Check if we need to do anything for this table
        boolean hasMasking = maskingRules.containsKey(lowerTableName);
        boolean hasConstraints = constraintRules.containsKey(lowerTableName);
        
        if (!hasMasking && !hasConstraints) {
            return row;
        }

        Map<String, Object> maskedRow = new HashMap<>(row);

        // Apply Constraint Masking (FPE) first
        if (hasConstraints) {
            Set<String> constraintColumns = constraintRules.get(lowerTableName);
            for (String column : constraintColumns) {
                String matchingKey = findMatchingKey(maskedRow.keySet(), column);
                if (matchingKey != null) {
                    Object originalValue = maskedRow.get(matchingKey);
                    if (originalValue != null) {
                        maskedRow.put(matchingKey, fpeService.encrypt(originalValue));
                    }
                }
            }
        }

        // Apply Standard Masking (Faker)
        if (hasMasking) {
            Set<String> columnsToMask = maskingRules.get(lowerTableName);
            for (String column : columnsToMask) {
                String matchingKey = findMatchingKey(maskedRow.keySet(), column);
                if (matchingKey != null) {
                    Object originalValue = maskedRow.get(matchingKey);
                    if (originalValue != null) {
                        maskedRow.put(matchingKey, generateMaskedValue(column, originalValue));
                    }
                }
            }
        }

        return maskedRow;
    }

    private String findMatchingKey(Set<String> keys, String target) {
        for (String key : keys) {
            if (key.equalsIgnoreCase(target)) {
                return key;
            }
        }
        return null;
    }

    private Object generateMaskedValue(String columnName, Object originalValue) {
        String lowerCol = columnName.toLowerCase();
        
        // Basic heuristic for masking based on column name
        if (lowerCol.contains("email")) {
            return faker.internet().emailAddress();
        } else if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) {
            return faker.name().firstName();
        } else if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) {
            return faker.name().lastName();
        } else if (lowerCol.contains("name")) {
            return faker.name().fullName();
        } else if (lowerCol.contains("phone")) {
            return faker.phoneNumber().cellPhone();
        } else if (lowerCol.contains("address")) {
            return faker.address().fullAddress();
        } else if (lowerCol.contains("city")) {
            return faker.address().city();
        } else if (lowerCol.contains("country")) {
            return faker.address().country();
        } else if (lowerCol.contains("zip") || lowerCol.contains("postal")) {
            return faker.address().zipCode();
        } else if (lowerCol.contains("card") || lowerCol.contains("credit")) {
            return faker.finance().creditCard();
        } else if (lowerCol.contains("ssn") || lowerCol.contains("social")) {
            return faker.idNumber().ssnValid();
        } else if (lowerCol.contains("description") || lowerCol.contains("comment")) {
            return faker.lorem().sentence();
        }
        
        // Fallback: preserve type if possible, or return string
        if (originalValue instanceof Number) {
             return faker.number().numberBetween(1, 10000);
        }
        
        return faker.lorem().characters(10);
    }
}
