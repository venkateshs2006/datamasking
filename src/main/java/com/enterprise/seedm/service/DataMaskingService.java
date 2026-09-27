package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.ConstraintMetadata;
import com.enterprise.seedm.model.MaskingConfig;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import net.datafaker.providers.base.Finance;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
                if (rule == null || rule.trim().isEmpty()) continue;
                String clean = rule.trim().toLowerCase();
                String[] parts = clean.split("\\.");
                if (parts.length >= 3) {
                    // e.g. public.users.email
                    String table = parts[parts.length - 2];
                    String col = parts[parts.length - 1];
                    ruleMap.computeIfAbsent(table, k -> new HashSet<>()).add(col);
                    ruleMap.computeIfAbsent(clean.substring(0, clean.lastIndexOf('.')), k -> new HashSet<>()).add(col);
                    ruleMap.computeIfAbsent("*", k -> new HashSet<>()).add(col);
                } else if (parts.length == 2) {
                    // e.g. users.email
                    ruleMap.computeIfAbsent(parts[0], k -> new HashSet<>()).add(parts[1]);
                    ruleMap.computeIfAbsent("*", k -> new HashSet<>()).add(parts[1]);
                } else if (parts.length == 1) {
                    // e.g. email
                    ruleMap.computeIfAbsent("*", k -> new HashSet<>()).add(parts[0]);
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

    public Map<String, Object> maskDataWithRules(String tableName, Map<String, Object> row,
                                                 List<String> maskingColumns,
                                                 List<String> constraintColumns,
                                                 List<String> partialMaskingColumns) {
        Map<String, Set<String>> maskingRules = parseRules(maskingColumns);
        Map<String, Set<String>> constraintRules = parseRules(constraintColumns);
        Map<String, Set<String>> partialMaskingRules = parseRules(partialMaskingColumns);

        Map<String, Object> maskedRow = new Document(row);
        List<ColumnMetadata> metadata = getCachedMetadata(tableName);
        traverseAndMask(tableName, maskedRow, "", maskingRules, constraintRules, partialMaskingRules, metadata);
        return maskedRow;
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
                        Map<String, Object> itemMap = new Document((Map<String, Object>) item);
                        traverseAndMask(rootName, itemMap, newPath, maskingRules, constraintRules, partialMaskingRules, metadata);
                        newList.add(itemMap);
                    } else {
                        newList.add(item);
                    }
                }
                currentMap.put(key, newList);
            }
        }
    }

    private final Map<String, String> fkUnifiedDataTypeCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fkConnectedComponents = new ConcurrentHashMap<>();
    private volatile boolean fkRelationshipsLoaded = false;

    public void resetForeignKeyRelationships() {
        fkUnifiedDataTypeCache.clear();
        fkConnectedComponents.clear();
        fkRelationshipsLoaded = false;
    }

    private synchronized void loadForeignKeyRelationships() {
        if (fkRelationshipsLoaded) return;
        try {
            List<ConstraintMetadata> fks = tableDiscoveryService.getAllForeignKeys();
            if (fks != null && !fks.isEmpty()) {
                Map<String, Set<String>> graph = new HashMap<>();
                for (ConstraintMetadata fk : fks) {
                    if (fk.getTableName() != null && fk.getColumnName() != null 
                            && fk.getForeignTableName() != null && fk.getForeignColumnName() != null) {
                        String childTable = fk.getTableName().contains(".") ? fk.getTableName().substring(fk.getTableName().lastIndexOf('.') + 1) : fk.getTableName();
                        String parentTable = fk.getForeignTableName().contains(".") ? fk.getForeignTableName().substring(fk.getForeignTableName().lastIndexOf('.') + 1) : fk.getForeignTableName();
                        
                        String child = (childTable + "." + fk.getColumnName()).toLowerCase();
                        String parent = (parentTable + "." + fk.getForeignColumnName()).toLowerCase();
                        graph.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
                        graph.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
                    }
                }

                Set<String> visited = new HashSet<>();
                for (String node : graph.keySet()) {
                    if (!visited.contains(node)) {
                        Set<String> component = new HashSet<>();
                        Queue<String> queue = new LinkedList<>();
                        queue.add(node);
                        visited.add(node);
                        while (!queue.isEmpty()) {
                            String curr = queue.poll();
                            component.add(curr);
                            for (String neighbor : graph.getOrDefault(curr, Collections.emptySet())) {
                                if (!visited.contains(neighbor)) {
                                    visited.add(neighbor);
                                    queue.add(neighbor);
                                }
                            }
                        }

                        // Check if ANY member in this component is smallint / int2
                        boolean hasSmallint = false;
                        for (String member : component) {
                            String[] parts = member.split("\\.");
                            if (parts.length == 2) {
                                List<ColumnMetadata> meta = getCachedMetadata(parts[0]);
                                ColumnMetadata col = getColumnMetadata(meta, parts[1]);
                                if (col != null && col.getDataType() != null) {
                                    String dt = col.getDataType().toLowerCase();
                                    if (dt.equals("smallint") || dt.equals("int2") || dt.equals("smallserial") || dt.equals("short")) {
                                        hasSmallint = true;
                                        break;
                                    }
                                }
                            }
                        }

                        for (String member : component) {
                            fkConnectedComponents.put(member, component);
                            if (hasSmallint) {
                                fkUnifiedDataTypeCache.put(member, "smallint");
                            }
                        }
                    }
                }
            }
            fkRelationshipsLoaded = true;
        } catch (Exception e) {
            log.warn("Could not load foreign key relationship graph: {}", e.getMessage());
            fkRelationshipsLoaded = true;
        }
    }

    private boolean isRuleActive(Map<String, Set<String>> ruleMap, String lowerRoot, String lowerField) {
        if (ruleMap == null || ruleMap.isEmpty()) return false;
        String simpleRoot = lowerRoot.contains(".") ? lowerRoot.substring(lowerRoot.lastIndexOf('.') + 1) : lowerRoot;
        if (ruleMap.getOrDefault(lowerRoot, Collections.emptySet()).contains(lowerField)
                || ruleMap.getOrDefault(simpleRoot, Collections.emptySet()).contains(lowerField)
                || ruleMap.getOrDefault("*", Collections.emptySet()).contains(lowerField)) {
            return true;
        }

        // Check if any connected foreign key column has this rule active
        loadForeignKeyRelationships();
        String fullKey = simpleRoot + "." + lowerField;
        Set<String> component = fkConnectedComponents.get(fullKey);
        if (component != null) {
            for (String member : component) {
                String[] parts = member.split("\\.");
                if (parts.length == 2) {
                    if (ruleMap.getOrDefault(parts[0], Collections.emptySet()).contains(parts[1])
                            || ruleMap.getOrDefault("*", Collections.emptySet()).contains(parts[1])) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        String lowerRoot = rootName != null ? rootName.toLowerCase() : "";
        String lowerField = fieldPath != null ? fieldPath.toLowerCase() : "";

        if (isRuleActive(constraintRules, lowerRoot, lowerField)) {
            try {
                ColumnMetadata colMeta = getColumnMetadata(metadata, fieldPath);
                String dataType = getColumnType(rootName, metadata, fieldPath, originalValue);
                Integer precision = colMeta != null ? colMeta.getNumericPrecision() : null;
                Integer scale = colMeta != null ? colMeta.getNumericScale() : null;
                Object encryptedValue = fpeService.encrypt(originalValue, dataType, null, precision, scale);
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

        if (isRuleActive(maskingRules, lowerRoot, lowerField)) {
            ColumnMetadata colMeta = getColumnMetadata(metadata, fieldPath);
            return generateMaskedValue(rootName, fieldPath, originalValue, colMeta);
        }

        if (isRuleActive(partialMaskingRules, lowerRoot, lowerField)) {
            return applyPartialMasking(originalValue.toString());
        }

        return originalValue;
    }

    private final Map<String, List<ColumnMetadata>> metadataCache = new HashMap<>();

    private List<ColumnMetadata> getCachedMetadata(String tableName) {
        try {
            return metadataCache.computeIfAbsent(tableName, k -> tableDiscoveryService.getTableColumnMetadata(tableName));
        } catch (Exception e) {
            log.warn("Failed to fetch column metadata for {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private ColumnMetadata getColumnMetadata(List<ColumnMetadata> metadata, String columnName) {
        if (metadata != null && columnName != null) {
            for (ColumnMetadata col : metadata) {
                if (col.getColumnName().equalsIgnoreCase(columnName)) {
                    return col;
                }
            }
        }
        return null;
    }

    private String getColumnType(String rootName, List<ColumnMetadata> metadata, String columnName, Object originalValue) {
        loadForeignKeyRelationships();
        String simpleTable = rootName != null && rootName.contains(".")
                ? rootName.substring(rootName.lastIndexOf('.') + 1)
                : rootName;
        String fullKey = (simpleTable != null ? simpleTable.toLowerCase() + "." : "") + (columnName != null ? columnName.toLowerCase() : "");
        if (fkUnifiedDataTypeCache.containsKey(fullKey)) {
            return fkUnifiedDataTypeCache.get(fullKey);
        }

        ColumnMetadata col = getColumnMetadata(metadata, columnName);
        if (col != null && col.getDataType() != null) {
            return col.getDataType();
        }
        if (originalValue instanceof Integer) return "integer";
        if (originalValue instanceof Long) return "long";
        if (originalValue instanceof Short) return "short";
        if (originalValue instanceof Byte) return "byte";
        if (originalValue instanceof BigDecimal) return "numeric";
        if (originalValue instanceof BigInteger) return "bigint";
        if (originalValue instanceof Double) return "double";
        if (originalValue instanceof Float) return "float";
        if (originalValue instanceof Boolean) return "boolean";
        if (originalValue instanceof UUID) return "uuid";
        return "string";
    }

    private String applyPartialMasking(String value) {
        if (value == null || value.length() <= 4) return value;
        int maskCount = value.length() - 4;
        return "X".repeat(maskCount) + value.substring(maskCount);
    }

    private boolean isSmallintType(String rootName, ColumnMetadata colMeta, Object originalValue) {
        String simpleTable = rootName != null && rootName.contains(".")
                ? rootName.substring(rootName.lastIndexOf('.') + 1)
                : rootName;
        String fullKey = (simpleTable != null ? simpleTable.toLowerCase() + "." : "")
                + (colMeta != null ? colMeta.getColumnName().toLowerCase() : "");
        if ("smallint".equalsIgnoreCase(fkUnifiedDataTypeCache.get(fullKey))) {
            return true;
        }
        if (colMeta != null && colMeta.getDataType() != null) {
            String dt = colMeta.getDataType().toLowerCase();
            if (dt.equals("smallint") || dt.equals("int2") || dt.equals("smallserial") || dt.equals("short")) return true;
        }
        return originalValue instanceof Short;
    }

    private boolean isIntegerType(ColumnMetadata colMeta, Object originalValue) {
        if (colMeta != null && colMeta.getDataType() != null) {
            String dt = colMeta.getDataType().toLowerCase();
            if (dt.equals("integer") || dt.equals("int") || dt.equals("int4") || dt.equals("serial")) return true;
        }
        return originalValue instanceof Integer;
    }

    private boolean isBigintType(ColumnMetadata colMeta, Object originalValue) {
        if (colMeta != null && colMeta.getDataType() != null) {
            String dt = colMeta.getDataType().toLowerCase();
            if (dt.equals("bigint") || dt.equals("int8") || dt.equals("long") || dt.equals("bigserial")) return true;
        }
        return originalValue instanceof Long || originalValue instanceof BigInteger;
    }

    private boolean isByteType(ColumnMetadata colMeta, Object originalValue) {
        if (colMeta != null && colMeta.getDataType() != null) {
            String dt = colMeta.getDataType().toLowerCase();
            if (dt.equals("byte") || dt.equals("tinyint")) return true;
        }
        return originalValue instanceof Byte;
    }

    private boolean isNumericType(ColumnMetadata colMeta, Object originalValue) {
        if (colMeta != null && colMeta.getDataType() != null) {
            String dt = colMeta.getDataType().toLowerCase();
            if (dt.equals("numeric") || dt.equals("decimal") || dt.equals("money") || dt.equals("double precision") || dt.equals("float8")) return true;
        }
        return originalValue instanceof BigDecimal || originalValue instanceof Double || originalValue instanceof Float;
    }

    private Object generateMaskedValue(String rootName, String columnName, Object originalValue, ColumnMetadata colMeta) {
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
        else if (isSmallintType(rootName, colMeta, originalValue)) {
            result = (short) faker.number().numberBetween(1, 32767);
        }
        else if (isByteType(colMeta, originalValue)) {
            result = (byte) faker.number().numberBetween(1, 127);
        }
        else if (isBigintType(colMeta, originalValue)) {
            result = faker.number().numberBetween(1L, 1000000000L);
        }
        else if (isIntegerType(colMeta, originalValue)) {
            result = faker.number().numberBetween(1, 100000);
        }
        else if (isNumericType(colMeta, originalValue)) {
            int precision = (colMeta != null && colMeta.getNumericPrecision() != null)
                    ? colMeta.getNumericPrecision()
                    : (originalValue instanceof BigDecimal ? ((BigDecimal) originalValue).precision() : 10);
            int scale = (colMeta != null && colMeta.getNumericScale() != null)
                    ? colMeta.getNumericScale()
                    : (originalValue instanceof BigDecimal ? ((BigDecimal) originalValue).scale() : 2);
            int intDigits = Math.max(1, Math.min(precision - scale, 9));
            long maxInt = (long) Math.pow(10, intDigits) - 1;
            long whole = faker.number().numberBetween(1L, Math.max(2L, maxInt));
            long maxFrac = (scale > 0) ? (long) Math.pow(10, scale) - 1 : 0;
            long frac = (scale > 0) ? faker.number().numberBetween(0L, maxFrac) : 0;
            result = BigDecimal.valueOf(whole).add(BigDecimal.valueOf(frac, scale)).setScale(scale, RoundingMode.HALF_UP);
        }
        else if (originalValue instanceof Number) {
            result = faker.number().numberBetween(1, 10000);
        }
        else if (originalValue instanceof Date) {
            Date dummyDate = Date.from(faker.timeAndDate().birthday(20,50).atStartOfDay(ZoneId.systemDefault()).toInstant());
            if (originalValue instanceof java.sql.Timestamp) result = new java.sql.Timestamp(dummyDate.getTime());
            else if (originalValue instanceof java.sql.Date) result = new java.sql.Date(dummyDate.getTime());
            else result = dummyDate;
        }
        else result = faker.lorem().characters(10);

        Integer maxLength = colMeta != null ? colMeta.getCharacterMaximumLength() : null;
        if (result instanceof String) {
            String strResult = (String) result;
            if (maxLength != null && strResult.length() > maxLength) {
                return strResult.substring(0, maxLength);
            }
        }
        return result;
    }
}