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
     * Mask a row of SQL relational data (fetches table metadata)
     */
    public Map<String, Object> maskData(String tableName, Map<String, Object> row) {
        return maskDataInternal(tableName, row, true);
    }
    
    /**
     * Mask a row of NoSQL data (skips table metadata fetching)
     */
    public Map<String, Object> maskNoSqlData(String collectionName, Map<String, Object> row) {
        return maskDataInternal(collectionName, row, false);
    }

    private Map<String, Object> maskDataInternal(String tableName, Map<String, Object> row, boolean fetchMetadata) {
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

        // We might need column metadata for data type if we are encrypting
        List<ColumnMetadata> metadata = null;
        if (fetchMetadata && (hasConstraints || hasMasking)) {
            metadata = getCachedMetadata(tableName);
        }

        // 1. Apply Constraint Masking (Deterministic Encryption)
        if (hasConstraints) {
            Set<String> constraintColumns = constraintRules.get(lowerTableName);
            for (String column : constraintColumns) {
                String matchingKey = findMatchingKey(maskedRow.keySet(), column);
                if (matchingKey != null) {
                    Object originalValue = maskedRow.get(matchingKey);
                    if (originalValue != null) {
                        String dataType = getColumnType(metadata, matchingKey);
                        try {
                            Object encryptedValue = fpeService.encrypt(originalValue, dataType);
                            if ("uuid".equalsIgnoreCase(dataType) && encryptedValue instanceof String) {
                                // Convert back to UUID if the original type was UUID
                                try {
                                    maskedRow.put(matchingKey, UUID.fromString((String) encryptedValue));
                                } catch (IllegalArgumentException e) {
                                    log.warn("Encrypted value for {}.{} is not a valid UUID. Falling back to random UUID.", tableName, column);
                                    maskedRow.put(matchingKey, UUID.randomUUID());
                                }
                            } else {
                                maskedRow.put(matchingKey, encryptedValue);
                            }
                        } catch (Exception e) {
                            log.error("Encryption failed for {}.{}", tableName, column, e);
                        }
                    }
                }
            }
        }

        // 2. Apply Partial Masking (Redaction)
        if (hasPartialMasking) {
            Set<String> partialColumns = partialMaskingRules.get(lowerTableName);
            for (String column : partialColumns) {
                String matchingKey = findMatchingKey(maskedRow.keySet(), column);
                if (matchingKey != null) {
                    Object originalValue = maskedRow.get(matchingKey);
                    if (originalValue != null) {
                        maskedRow.put(matchingKey, applyPartialMasking(originalValue.toString()));
                    }
                }
            }
        }

        // 3. Apply Standard Masking (Faker)
        if (hasMasking) {
            Set<String> columnsToMask = maskingRules.get(lowerTableName);
            for (String column : columnsToMask) {
                String matchingKey = findMatchingKey(maskedRow.keySet(), column);
                if (matchingKey != null) {
                    Object originalValue = maskedRow.get(matchingKey);
                    if (originalValue != null) {
                        Integer maxLength = getColumnMaxLength(metadata, matchingKey);
                        maskedRow.put(matchingKey, generateMaskedValue(column, originalValue, maxLength));
                    }
                }
            }
        }

        return maskedRow;
    }

    // Simple cache for metadata to avoid DB hits per row
    private final Map<String, List<ColumnMetadata>> metadataCache = new HashMap<>();

    private List<ColumnMetadata> getCachedMetadata(String tableName) {
        try {
            return metadataCache.computeIfAbsent(tableName, k -> tableDiscoveryService.getTableColumnMetadata(k));
        } catch (Exception e) {
            log.warn("Failed to fetch column metadata for {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private String getColumnType(List<ColumnMetadata> metadata, String columnName) {
        if (metadata == null) return "string"; // Default fallback
        for (ColumnMetadata col : metadata) {
            if (col.getColumnName().equalsIgnoreCase(columnName)) {
                return col.getDataType();
            }
        }
        return "string";
    }

    private Integer getColumnMaxLength(List<ColumnMetadata> metadata, String columnName) {
        if (metadata == null) return null;
        for (ColumnMetadata col : metadata) {
            if (col.getColumnName().equalsIgnoreCase(columnName)) {
                return col.getCharacterMaximumLength();
            }
        }
        return null;
    }

    private String findMatchingKey(Set<String> keys, String target) {
        for (String key : keys) {
            if (key.equalsIgnoreCase(target)) {
                return key;
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