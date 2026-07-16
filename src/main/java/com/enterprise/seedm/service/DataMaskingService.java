package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.MaskingConfig;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import net.datafaker.providers.base.Finance;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;

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
                if (parts.length >= 2) { // Allow for nested paths
                    String tableName = parts[0].toLowerCase();
                    String fieldPath = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));
                    ruleMap.computeIfAbsent(tableName, k -> new HashSet<>()).add(fieldPath);
                } else {
                    log.warn("Invalid rule format: {}", rule);
                }
            }
        }
        return ruleMap;
    }

    public Map<String, Object> maskData(String tableName, Map<String, Object> row) {
        return maskDataInternal(tableName, row, true);
    }

    public Map<String, Object> maskNoSqlData(String collectionName, Map<String, Object> row) {
        return maskDataInternal(collectionName, row, false);
    }

    private Map<String, Object> maskDataInternal(String rootName, Map<String, Object> row, boolean fetchMetadata) {
        MaskingConfig config = maskingConfigService.getConfig();
        Map<String, Set<String>> maskingRules = parseRules(config.getMaskingColumns());
        Map<String, Set<String>> constraintRules = parseRules(config.getConstraintColumns());
        Map<String, Set<String>> partialMaskingRules = parseRules(config.getPartialMaskingColumns());

        // We create a deep copy to avoid modifying the original object from the reader
        Map<String, Object> maskedRow = new Document(row);

        List<ColumnMetadata> metadata = null;
        if (fetchMetadata) {
            metadata = getCachedMetadata(rootName);
        }

        traverseAndMask(rootName, maskedRow, "", maskingRules, constraintRules, partialMaskingRules, metadata);

        return maskedRow;
    }

    private void traverseAndMask(String rootName, Map<String, Object> currentMap, String currentPath,
                                 Map<String, Set<String>> maskingRules,
                                 Map<String, Set<String>> constraintRules,
                                 Map<String, Set<String>> partialMaskingRules,
                                 List<ColumnMetadata> metadata) {

        // Use a copy of keys to avoid ConcurrentModificationException
        for (String key : new HashSet<>(currentMap.keySet())) {
            Object value = currentMap.get(key);
            String newPath = currentPath.isEmpty() ? key : currentPath + "." + key;

            // Apply rules to the current field
            Object newValue = applyRulesToField(rootName, newPath, value, maskingRules, constraintRules, partialMaskingRules, metadata);
            currentMap.put(key, newValue);

            // Recurse if the new value is a map or a list
            if (newValue instanceof Map) {
                traverseAndMask(rootName, (Map<String, Object>) newValue, newPath, maskingRules, constraintRules, partialMaskingRules, metadata);
            } else if (newValue instanceof List) {
                List<Object> newList = new ArrayList<>();
                for (Object item : (List<?>) newValue) {
                    if (item instanceof Map) {
                        Map<String, Object> newMapItem = new Document((Map<String, Object>) item);
                        traverseAndMask(rootName, newMapItem, newPath, maskingRules, constraintRules, partialMaskingRules, metadata);
                        newList.add(newMapItem);
                    } else {
                        newList.add(applyRulesToField(rootName, newPath, item, maskingRules, constraintRules, partialMaskingRules, metadata));
                    }
                }
                currentMap.put(key, newList);
            }
        }
    }

    private Object applyRulesToField(String rootName, String fieldPath, Object originalValue,
                                     Map<String, Set<String>> maskingRules,
                                     Map<String, Set<String>> constraintRules,
                                     Map<String, Set<String>> partialMaskingRules,
                                     List<ColumnMetadata> metadata) {
        if (originalValue == null) {
            return null;
        }

        String fullPath = rootName + "." + fieldPath;

        if (constraintRules.getOrDefault(rootName.toLowerCase(), Collections.emptySet()).contains(fieldPath.toLowerCase())) {
            try {
                String dataType = getColumnType(metadata, fieldPath);
                Object encryptedValue = fpeService.encrypt(originalValue, dataType);
                if ("uuid".equalsIgnoreCase(dataType) && encryptedValue instanceof String) {
                    try {
                        return UUID.fromString((String) encryptedValue);
                    } catch (IllegalArgumentException e) {
                        log.warn("Encrypted value for {} is not a valid UUID. Falling back to random UUID.", fullPath);
                        return UUID.randomUUID();
                    }
                }
                return encryptedValue;
            } catch (Exception e) {
                log.error("Encryption failed for {}", fullPath, e);
                return originalValue;
            }
        }

        if (maskingRules.getOrDefault(rootName.toLowerCase(), Collections.emptySet()).contains(fieldPath.toLowerCase())) {
            Integer maxLength = getColumnMaxLength(metadata, fieldPath);
            return generateMaskedValue(fieldPath, originalValue, maxLength);
        }

        if (partialMaskingRules.getOrDefault(rootName.toLowerCase(), Collections.emptySet()).contains(fieldPath.toLowerCase())) {
            return applyPartialMasking(originalValue.toString());
        }

        return originalValue;
    }

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
        if (metadata == null) return "string";
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

    private String applyPartialMasking(String value) {
        if (value == null || value.length() <= 4) return value;
        int maskCount = value.length() - 4;
        return "X".repeat(maskCount) + value.substring(maskCount);
    }

    private Object generateMaskedValue(String columnName, Object originalValue, Integer maxLength) {
        String lowerCol = columnName.toLowerCase();

        if (originalValue instanceof UUID) return UUID.randomUUID();
        if (originalValue instanceof byte[]) return "MASKED_BLOB".getBytes(StandardCharsets.UTF_8);

        Object result;
        if (lowerCol.contains("email")) result = faker.internet().emailAddress();
        else if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) result = faker.name().firstName();
        else if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) result = faker.name().lastName();
        else if (lowerCol.contains("name")) result = faker.name().fullName();
        else if (lowerCol.contains("phone")) result = faker.phoneNumber().cellPhone();
        else if (lowerCol.contains("address")) result = faker.address().fullAddress();
        else if (lowerCol.contains("city")) result = faker.address().city();
        else if (lowerCol.contains("country")) result = faker.address().country();
        else if (lowerCol.contains("zip") || lowerCol.contains("postal")) result = faker.address().zipCode();
        else if (lowerCol.contains("card") || lowerCol.contains("debit card") || lowerCol.contains("credit card")) result = faker.finance().creditCard(Finance.CreditCardType.MASTERCARD);
        else if (lowerCol.contains("ssn")) result = faker.idNumber().ssnValid();
        else if (originalValue instanceof Number) result = faker.number().numberBetween(1, 10000);
        else if (originalValue instanceof Date) {

            Date dummyDate =Date.from(faker.timeAndDate().birthday(20,50).atStartOfDay(ZoneId.systemDefault()).toInstant());
            if (originalValue instanceof java.sql.Timestamp) result = new java.sql.Timestamp(dummyDate.getTime());
            else if (originalValue instanceof java.sql.Date) result = new java.sql.Date(dummyDate.getTime());
            else result = dummyDate;
        }
        else result = faker.lorem().characters(10);

        if (result instanceof String) {
            String strResult = (String) result;
            if (maxLength != null && strResult.length() > maxLength) {
                return strResult.substring(0, maxLength);
            }
        }
        return result;
    }
}