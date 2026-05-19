package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.MaskingConfig;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Data Masking Service
 * Masks sensitive data using DataFaker based on configuration
 */
@Service
@Slf4j
public class DataMaskingService {

    private final Faker faker;
    private final FormatPreservingEncryptionService fpeService;
    private final TableDiscoveryService tableDiscoveryService;
    private final MaskingConfigService maskingConfigService;
    
    public DataMaskingService(FormatPreservingEncryptionService fpeService,
                              TableDiscoveryService tableDiscoveryService,
                              MaskingConfigService maskingConfigService) {
        this.faker = new Faker();
        this.fpeService = fpeService;
        this.tableDiscoveryService = tableDiscoveryService;
        this.maskingConfigService = maskingConfigService;
    }

    private Map<String, Set<String>> parseRules(List<String> rules) {
        Map<String, Set<String>> ruleMap = new HashMap<>();
        if (rules != null) {
            for (String rule : rules) {
                int firstDotIndex = rule.indexOf('.');
                if (firstDotIndex > 0 && firstDotIndex < rule.length() - 1) {
                    String tableName = rule.substring(0, firstDotIndex).toLowerCase();
                    String columnName = rule.substring(firstDotIndex + 1); // keep case/dots for nested mongo paths
                    ruleMap.computeIfAbsent(tableName, k -> new HashSet<>()).add(columnName);
                } else {
                    log.warn("Invalid rule format: {}", rule);
                }
            }
        }
        return ruleMap;
    }

    /**
     * Mask a row of data if applicable. Supports flat and nested (dot-notation) structures.
     */
    public Map<String, Object> maskData(String tableName, Map<String, Object> row) {
        String lowerTableName = tableName.toLowerCase();
        
        // Fetch current config dynamically
        MaskingConfig config = maskingConfigService.getConfig();
        Map<String, Set<String>> maskingRules = parseRules(config.getMaskingColumns());
        Map<String, Set<String>> constraintRules = parseRules(config.getConstraintColumns());
        Map<String, Set<String>> partialMaskingRules = parseRules(config.getPartialMaskingColumns());

        // Check if we need to do anything for this table
        boolean hasMasking = maskingRules.containsKey(lowerTableName);
        boolean hasConstraints = constraintRules.containsKey(lowerTableName);
        boolean hasPartialMasking = partialMaskingRules.containsKey(lowerTableName);

        if (!hasMasking && !hasConstraints && !hasPartialMasking) {
            return row;
        }

        Map<String, Object> maskedRow = new HashMap<>(row);
        
        // We might need column metadata for data type if we are encrypting (usually Postgres only)
        List<ColumnMetadata> metadata = null;
        // For MongoDB, metadata is not available from TableDiscoveryService, so we pass null
        // and rely on runtime type checking in generateMaskedValue and getColumnType
        // if (hasConstraints || hasMasking) {
        //      metadata = getCachedMetadata(tableName);
        // }

        // Apply rules traversing potentially nested maps
        applyRulesRecursive(maskedRow, "", lowerTableName, maskingRules, constraintRules, partialMaskingRules, metadata);

        return maskedRow;
    }

    @SuppressWarnings("unchecked")
    private void applyRulesRecursive(Map<String, Object> currentMap, String currentPath, String lowerTableName,
                                     Map<String, Set<String>> maskingRules, Map<String, Set<String>> constraintRules, 
                                     Map<String, Set<String>> partialMaskingRules, List<ColumnMetadata> metadata) {
        
        for (Map.Entry<String, Object> entry : currentMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullPath = currentPath.isEmpty() ? key : currentPath + "." + key;
            
            if (value instanceof Map) {
                // It's a nested document (e.g. MongoDB embedded object)
                applyRulesRecursive((Map<String, Object>) value, fullPath, lowerTableName, maskingRules, constraintRules, partialMaskingRules, metadata);
            } else if (value instanceof List) {
                List<Object> list = (List<Object>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    // For array elements, append "[]" to the path for rule matching
                    String arrayElementPath = fullPath + "[]"; 
                    if (item instanceof Map) {
                        applyRulesRecursive((Map<String, Object>) item, arrayElementPath, lowerTableName, maskingRules, constraintRules, partialMaskingRules, metadata);
                    } else if (item != null) {
                        // Apply masking to primitive list items
                        list.set(i, applyMaskingLogic(arrayElementPath, lowerTableName, item, maskingRules, constraintRules, partialMaskingRules, metadata));
                    }
                }
            } else {
                // Primitive value, check against rules
                if (value != null) {
                    currentMap.put(key, applyMaskingLogic(fullPath, lowerTableName, value, maskingRules, constraintRules, partialMaskingRules, metadata));
                }
            }
        }
    }
    
    private Object applyMaskingLogic(String fullPath, String lowerTableName, Object originalValue,
                                     Map<String, Set<String>> maskingRules, Map<String, Set<String>> constraintRules, 
                                     Map<String, Set<String>> partialMaskingRules, List<ColumnMetadata> metadata) {
                                         
        Object maskedValue = originalValue;

        // 1. Constraint Masking (Format Preserving)
        if (constraintRules.containsKey(lowerTableName) && isPathMatching(constraintRules.get(lowerTableName), fullPath)) {
            String dataType = getColumnType(metadata, fullPath); // metadata will be null for Mongo
            try {
                maskedValue = fpeService.encrypt(maskedValue, dataType);
            } catch (Exception e) {
                log.error("Encryption failed for {}.{}", lowerTableName, fullPath, e);
            }
        }
        
        // 2. Partial Masking
        if (partialMaskingRules.containsKey(lowerTableName) && isPathMatching(partialMaskingRules.get(lowerTableName), fullPath)) {
            maskedValue = applyPartialMasking(maskedValue.toString());
        }

        // 3. Standard Masking (Faker)
        if (maskingRules.containsKey(lowerTableName) && isPathMatching(maskingRules.get(lowerTableName), fullPath)) {
            Integer maxLength = getColumnMaxLength(metadata, fullPath); // metadata will be null for Mongo
            maskedValue = generateMaskedValue(fullPath, maskedValue, maxLength);
        }
        
        return maskedValue;
    }

    private boolean isPathMatching(Set<String> rulePaths, String actualPath) {
        if (rulePaths == null) return false;
        for (String rulePath : rulePaths) {
            // Rule paths from UI might be "arrayField[]" or "arrayField[].nestedField"
            // Actual path from traversal will be "arrayField[]" or "arrayField[].nestedField"
            if (rulePath.equalsIgnoreCase(actualPath)) {
                return true;
            }
        }
        return false;
    }
    
    // Simple cache for metadata to avoid DB hits per row
    private final Map<String, List<ColumnMetadata>> metadataCache = new HashMap<>();
    
    private List<ColumnMetadata> getCachedMetadata(String tableName) {
        return metadataCache.computeIfAbsent(tableName, k -> {
            try {
                return tableDiscoveryService.getTableColumnMetadata(k);
            } catch (Exception e) {
                return List.of();
            }
        });
    }
    
    private String getColumnType(List<ColumnMetadata> metadata, String columnName) {
        // For MongoDB, metadata is null, so we try to infer type from the value itself
        if (metadata == null) return "string"; // Default fallback
        for (ColumnMetadata col : metadata) {
            if (col.getColumnName().equalsIgnoreCase(columnName)) {
                return col.getDataType();
            }
        }
        return "string";
    }

    private Integer getColumnMaxLength(List<ColumnMetadata> metadata, String columnName) {
        // For MongoDB, metadata is null
        if (metadata == null) return null;
        for (ColumnMetadata col : metadata) {
            if (col.getColumnName().equalsIgnoreCase(columnName)) {
                return col.getCharacterMaximumLength();
            }
        }
        return null;
    }

    private String applyPartialMasking(String value) {
        if (value == null || value.isEmpty()) return value;
        
        // Simple heuristic: keep last 4 chars visible
        int length = value.length();
        if (length <= 4) {
            return value; // Too short to mask
        }
        
        int visibleCount = 4;
        int maskCount = length - visibleCount;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskCount; i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c) || Character.isLetter(c)) {
                sb.append('X');
            } else {
                sb.append(c); // Preserve separators like - or space
            }
        }
        sb.append(value.substring(maskCount));
        return sb.toString();
    }

    private Object generateMaskedValue(String columnName, Object originalValue, Integer maxLength) {
        String lowerCol = columnName.toLowerCase();

        // Handle specific types first
        if (originalValue instanceof byte[]) {
            return "MASKED_BLOB".getBytes(StandardCharsets.UTF_8);
        } else if (originalValue instanceof UUID) {
            return UUID.randomUUID();
        } else if (originalValue instanceof java.sql.Array) {
            log.warn("Masking java.sql.Array is not fully supported yet. Returning null for column: {}", columnName);
            return null;
        } else if (originalValue instanceof PGobject) {
            PGobject pgObject = (PGobject) originalValue;
            String type = pgObject.getType();
            if ("json".equals(type) || "jsonb".equals(type)) {
                PGobject maskedJson = new PGobject();
                maskedJson.setType(type);
                try {
                    maskedJson.setValue("{\"masked\": true}");
                } catch (SQLException e) {
                    log.error("Failed to mask JSON column {}", columnName, e);
                    return originalValue;
                }
                return maskedJson;
            }
        }

        // Handle JSON/JSONB (usually comes as String or PGObject)
        if (originalValue.toString().trim().startsWith("{") || originalValue.toString().trim().startsWith("[")) {
            return "{\"masked\": true}";
        }

        Object result;

        // Basic heuristic for masking based on column name
        if (lowerCol.contains("email")) {
            result = faker.internet().emailAddress();
        } else if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) {
            result = faker.name().firstName();
        } else if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) {
            result = faker.name().lastName();
        } else if (lowerCol.contains("name")) {
            result = faker.name().fullName();
        } else if (lowerCol.contains("phone")) {
            result = faker.phoneNumber().cellPhone();
        } else if (lowerCol.contains("address")) {
            result = faker.address().fullAddress();
        } else if (lowerCol.contains("city")) {
            result = faker.address().city();
        } else if (lowerCol.contains("country")) {
            result = faker.address().country();
        } else if (lowerCol.contains("zip") || lowerCol.contains("postal")) {
            result = faker.address().zipCode();
        } else if (lowerCol.contains("card") || lowerCol.contains("credit")) {
            result = faker.finance().creditCard();
        } else if (lowerCol.contains("ssn") || lowerCol.contains("social")) {
            result = faker.idNumber().ssnValid();
        } else if (lowerCol.contains("description") || lowerCol.contains("comment")) {
            result = faker.lorem().sentence();
        } else if (originalValue instanceof Number) {
            result = faker.number().numberBetween(1, 10000);
        } else if (originalValue instanceof java.util.Date || originalValue instanceof java.time.temporal.TemporalAccessor) {
            java.util.Date dummyDate = faker.date().birthday();
            if (originalValue instanceof java.sql.Timestamp || originalValue instanceof java.time.LocalDateTime) {
                result = new java.sql.Timestamp(dummyDate.getTime());
            } else if (originalValue instanceof java.sql.Date || originalValue instanceof java.time.LocalDate) {
                result = new java.sql.Date(dummyDate.getTime());
            } else {
                result = dummyDate;
            }
        } else if (originalValue instanceof String) {
            String strVal = (String) originalValue;
            if (strVal.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                result = new java.sql.Date(faker.date().birthday().getTime()).toString();
            } else if (strVal.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*")) {
                result = new java.sql.Timestamp(faker.date().birthday().getTime()).toString();
            } else if (strVal.matches("^\\d{2}/\\d{2}/\\d{4}$")) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(faker.date().birthday());
                result = String.format("%02d/%02d/%04d", cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.YEAR));
            } else if (lowerCol.contains("date") || lowerCol.contains("time") || lowerCol.contains("dob") || lowerCol.contains("created") || lowerCol.contains("updated")) {
                result = new java.sql.Timestamp(faker.date().birthday().getTime()).toString();
            } else {
                result = faker.lorem().characters(10);
            }
        } else {
            result = faker.lorem().characters(10);
        }

        if (result instanceof String) {
            String strResult = (String) result;
            if (maxLength != null && maxLength > 0) {
                if (strResult.length() > maxLength) {
                    result = strResult.substring(0, maxLength);
                }
            } else if (originalValue instanceof String) {
                String strOriginal = (String) originalValue;
                if (strResult.length() > strOriginal.length() && strOriginal.length() > 0) {
                    result = strResult.substring(0, strOriginal.length());
                }
            }
        }

        return result;
    }
}