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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return maskDataWithRules(tableName, row, maskingColumns, constraintColumns, partialMaskingColumns, null, null);
    }

    public Map<String, Object> maskDataWithRules(String tableName, Map<String, Object> row,
                                                 List<String> maskingColumns,
                                                 List<String> constraintColumns,
                                                 List<String> partialMaskingColumns,
                                                 List<String> nullifyColumns,
                                                 Map<String, String> defaultValues) {
        return maskDataWithRules(tableName, row, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, null, null);
    }

    public Map<String, Object> maskDataWithRules(String tableName, Map<String, Object> row,
                                                 List<String> maskingColumns,
                                                 List<String> constraintColumns,
                                                 List<String> partialMaskingColumns,
                                                 List<String> nullifyColumns,
                                                 Map<String, String> defaultValues,
                                                 String customSalt) {
        return maskDataWithRules(tableName, row, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, null, customSalt);
    }

    public Map<String, Object> maskDataWithRules(String tableName, Map<String, Object> row,
                                                 List<String> maskingColumns,
                                                 List<String> constraintColumns,
                                                 List<String> partialMaskingColumns,
                                                 List<String> nullifyColumns,
                                                 Map<String, String> defaultValues,
                                                 Map<String, String> customFormats) {
        return maskDataWithRules(tableName, row, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, customFormats, null);
    }

    public Map<String, Object> maskDataWithRules(String tableName, Map<String, Object> row,
                                                 List<String> maskingColumns,
                                                 List<String> constraintColumns,
                                                 List<String> partialMaskingColumns,
                                                 List<String> nullifyColumns,
                                                 Map<String, String> defaultValues,
                                                 Map<String, String> customFormats,
                                                 String customSalt) {
        Map<String, Set<String>> maskingRules = parseRules(maskingColumns);
        Map<String, Set<String>> constraintRules = parseRules(constraintColumns);
        Map<String, Set<String>> partialMaskingRules = parseRules(partialMaskingColumns);
        Map<String, Set<String>> nullifyRules = parseRules(nullifyColumns);

        Map<String, Object> maskedRow = new Document(row);
        List<ColumnMetadata> metadata = getCachedMetadata(tableName);
        traverseAndMask(tableName, maskedRow, "", maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, customFormats, metadata, customSalt);
        return maskedRow;
    }

    private Map<String, Object> maskDataInternal(String rootName, Map<String, Object> row, boolean fetchMetadata) {
        MaskingConfig config = maskingConfigService.getConfig();
        Map<String, Set<String>> maskingRules = parseRules(config.getMaskingColumns());
        Map<String, Set<String>> constraintRules = parseRules(config.getConstraintColumns());
        Map<String, Set<String>> partialMaskingRules = parseRules(config.getPartialMaskingColumns());
        Map<String, Set<String>> nullifyRules = parseRules(config.getNullifyColumns());
        Map<String, String> defaultValues = config.getDefaultValues();
        Map<String, String> customFormats = config.getCustomFormats();

        // We create a deep copy to avoid modifying the original object from the reader
        Map<String, Object> maskedRow = new Document(row);

        List<ColumnMetadata> metadata = null;
        if (fetchMetadata) {
            metadata = getCachedMetadata(rootName);
        }

        traverseAndMask(rootName, maskedRow, "", maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, customFormats, metadata, null);

        return maskedRow;
    }

    private void traverseAndMask(String rootName, Map<String, Object> currentMap, String currentPath,
                                 Map<String, Set<String>> maskingRules,
                                 Map<String, Set<String>> constraintRules,
                                 Map<String, Set<String>> partialMaskingRules,
                                 Map<String, Set<String>> nullifyRules,
                                 Map<String, String> defaultValues,
                                 List<ColumnMetadata> metadata) {
        traverseAndMask(rootName, currentMap, currentPath, maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, null, metadata, null);
    }

    private void traverseAndMask(String rootName, Map<String, Object> currentMap, String currentPath,
                                 Map<String, Set<String>> maskingRules,
                                 Map<String, Set<String>> constraintRules,
                                 Map<String, Set<String>> partialMaskingRules,
                                 Map<String, Set<String>> nullifyRules,
                                 Map<String, String> defaultValues,
                                 Map<String, String> customFormats,
                                 List<ColumnMetadata> metadata,
                                 String customSalt) {

        // Use a copy of keys to avoid ConcurrentModificationException
        for (String key : new HashSet<>(currentMap.keySet())) {
            Object value = currentMap.get(key);
            String newPath = currentPath.isEmpty() ? key : currentPath + "." + key;

            // Apply rules to the current field
            Object newValue = applyRulesToField(rootName, newPath, value, maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, customFormats, metadata, customSalt);
            currentMap.put(key, newValue);

            // Recurse if the new value is a map or a list
            if (newValue instanceof Map) {
                traverseAndMask(rootName, (Map<String, Object>) newValue, newPath, maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, customFormats, metadata, customSalt);
            } else if (newValue instanceof List) {
                List<Object> newList = new ArrayList<>();
                for (Object item : (List<?>) newValue) {
                    if (item instanceof Map) {
                        Map<String, Object> itemMap = new Document((Map<String, Object>) item);
                        traverseAndMask(rootName, itemMap, newPath, maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, customFormats, metadata, customSalt);
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
                                     Map<String, Set<String>> nullifyRules,
                                     Map<String, String> defaultValues,
                                     List<ColumnMetadata> metadata) {
        return applyRulesToField(rootName, fieldPath, originalValue, maskingRules, constraintRules, partialMaskingRules, nullifyRules, defaultValues, null, metadata, null);
    }

    private Object applyRulesToField(String rootName, String fieldPath, Object originalValue,
                                     Map<String, Set<String>> maskingRules,
                                     Map<String, Set<String>> constraintRules,
                                     Map<String, Set<String>> partialMaskingRules,
                                     Map<String, Set<String>> nullifyRules,
                                     Map<String, String> defaultValues,
                                     Map<String, String> customFormats,
                                     List<ColumnMetadata> metadata,
                                     String customSalt) {
        String lowerRoot = rootName != null ? rootName.toLowerCase() : "";
        String lowerField = fieldPath != null ? fieldPath.toLowerCase() : "";

        // 1. Check Nullify Rule
        if (isRuleActive(nullifyRules, lowerRoot, lowerField)) {
            return null;
        }

        // 2. Check Custom Default Value Rule
        if (defaultValues != null && !defaultValues.isEmpty()) {
            String defVal = getMatchingDefaultValue(defaultValues, lowerRoot, lowerField);
            if (defVal != null) {
                ColumnMetadata colMeta = getColumnMetadata(metadata, fieldPath);
                return convertDefaultValueToType(defVal, originalValue, colMeta);
            }
        }

        // 3. Check Custom Masking Format (CMF) Rule
        if (customFormats != null && !customFormats.isEmpty()) {
            String cmfRule = getMatchingCustomFormat(customFormats, lowerRoot, lowerField);
            if (cmfRule != null && !cmfRule.isBlank()) {
                ColumnMetadata colMeta = getColumnMetadata(metadata, fieldPath);
                return applyCustomFormat(originalValue, cmfRule, colMeta);
            }
        }

        if (originalValue == null) {
            return null;
        }

        String fullPath = rootName + "." + fieldPath;

        if (isRuleActive(constraintRules, lowerRoot, lowerField)) {
            try {
                ColumnMetadata colMeta = getColumnMetadata(metadata, fieldPath);
                String dataType = getColumnType(rootName, metadata, fieldPath, originalValue);
                Integer precision = colMeta != null ? colMeta.getNumericPrecision() : null;
                Integer scale = colMeta != null ? colMeta.getNumericScale() : null;
                Object encryptedValue = fpeService.encrypt(originalValue, dataType, customSalt, precision, scale);
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

    private String getMatchingCustomFormat(Map<String, String> customFormats, String lowerRoot, String lowerField) {
        if (customFormats == null || customFormats.isEmpty()) return null;
        String simpleRoot = lowerRoot.contains(".") ? lowerRoot.substring(lowerRoot.lastIndexOf('.') + 1) : lowerRoot;
        for (Map.Entry<String, String> entry : customFormats.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().trim().toLowerCase();
            if (key.equals(lowerRoot + "." + lowerField)
                    || key.equals(simpleRoot + "." + lowerField)
                    || key.endsWith("." + simpleRoot + "." + lowerField)
                    || key.endsWith("." + lowerField)
                    || key.equals(lowerField)
                    || key.equals("*." + lowerField)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Applies Custom Masking Format (CMF) for DATE, TEXT/TEMPLATE, or NUMBER
     */
    public Object applyCustomFormat(Object originalValue, String formatRule) {
        return applyCustomFormat(originalValue, formatRule, null);
    }

    /**
     * Applies Custom Masking Format (CMF) for DATE, TEXT/TEMPLATE, or NUMBER
     */
    public Object applyCustomFormat(Object originalValue, String formatRule, ColumnMetadata colMeta) {
        if (formatRule == null || formatRule.isBlank()) {
            return originalValue;
        }
        String rule = formatRule.trim();
        String upperRule = rule.toUpperCase();

        if (upperRule.startsWith("DATE:") || upperRule.startsWith("DATE_PATTERN:") || upperRule.startsWith("DATE_SHIFT:")) {
            return formatCustomDate(originalValue, rule);
        } else if (upperRule.startsWith("TEXT:") || upperRule.startsWith("TEMPLATE:") || upperRule.startsWith("REGEX:")) {
            return formatCustomText(originalValue, rule);
        } else if (upperRule.startsWith("NUM:") || upperRule.startsWith("NUMBER:")) {
            return formatCustomNumber(originalValue, rule, colMeta);
        }

        // Auto-detect type from contents
        if (rule.contains("yyyy") || rule.contains("MM") || rule.contains("dd")) {
            return formatCustomDate(originalValue, "DATE:" + rule);
        }
        if (rule.contains("{rand") || rule.contains("{RAND")) {
            return formatCustomText(originalValue, "TEXT:" + rule);
        }
        if (originalValue instanceof Number) {
            return formatCustomNumber(originalValue, "NUM:" + rule, colMeta);
        }

        return formatCustomText(originalValue, "TEXT:" + rule);
    }

    private Object formatCustomDate(Object originalValue, String rule) {
        String pattern = "yyyy-MM-dd";
        boolean doShift = false;
        int shiftMin = -30;
        int shiftMax = 30;

        String content = rule.contains(":") ? rule.substring(rule.indexOf(':') + 1).trim() : rule.trim();
        if (content.toLowerCase().contains(":shift(") || content.toLowerCase().contains("shift(")) {
            int shiftIdx = content.toLowerCase().indexOf("shift(");
            int closeIdx = content.indexOf(')', shiftIdx);
            if (closeIdx > shiftIdx) {
                String shiftPart = content.substring(shiftIdx + 6, closeIdx);
                String[] shiftParts = shiftPart.split(",");
                if (shiftParts.length >= 2) {
                    try {
                        shiftMin = Integer.parseInt(shiftParts[0].trim());
                        shiftMax = Integer.parseInt(shiftParts[1].trim());
                        doShift = true;
                    } catch (NumberFormatException ignored) {}
                }
            }
            int colonBefore = content.lastIndexOf(':', shiftIdx);
            pattern = colonBefore > 0 ? content.substring(0, colonBefore).trim() : "yyyy-MM-dd";
        } else {
            pattern = content.trim();
        }

        if (pattern.isEmpty()) {
            pattern = "yyyy-MM-dd";
        }

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (Exception e) {
            log.warn("Invalid date pattern '{}', falling back to 'yyyy-MM-dd'", pattern);
            formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        }

        ZonedDateTime zdt = null;
        if (originalValue instanceof java.sql.Timestamp ts) {
            zdt = ts.toInstant().atZone(ZoneId.systemDefault());
        } else if (originalValue instanceof java.sql.Date sqlDate) {
            zdt = sqlDate.toLocalDate().atStartOfDay(ZoneId.systemDefault());
        } else if (originalValue instanceof java.util.Date utilDate) {
            zdt = utilDate.toInstant().atZone(ZoneId.systemDefault());
        } else if (originalValue instanceof ZonedDateTime z) {
            zdt = z;
        } else if (originalValue instanceof OffsetDateTime odt) {
            zdt = odt.toZonedDateTime();
        } else if (originalValue instanceof LocalDateTime ldt) {
            zdt = ldt.atZone(ZoneId.systemDefault());
        } else if (originalValue instanceof LocalDate ld) {
            zdt = ld.atStartOfDay(ZoneId.systemDefault());
        } else if (originalValue != null) {
            String str = originalValue.toString().trim();
            try {
                zdt = ZonedDateTime.parse(str);
            } catch (Exception ex1) {
                try {
                    zdt = OffsetDateTime.parse(str).toZonedDateTime();
                } catch (Exception ex2) {
                    try {
                        zdt = LocalDateTime.parse(str).atZone(ZoneId.systemDefault());
                    } catch (Exception ex3) {
                        try {
                            zdt = LocalDate.parse(str).atStartOfDay(ZoneId.systemDefault());
                        } catch (Exception ex4) {
                            for (String pat : List.of(
                                    "yyyy-MM-dd HH:mm:ss.SSSSSSX",
                                    "yyyy-MM-dd HH:mm:ss.SSSX",
                                    "yyyy-MM-dd HH:mm:ssX",
                                    "yyyy-MM-dd HH:mm:ss.SSSSSS",
                                    "yyyy-MM-dd HH:mm:ss.SSS",
                                    "yyyy-MM-dd HH:mm:ss",
                                    "yyyy-MM-dd'T'HH:mm:ss",
                                    "yyyy-MM-dd",
                                    "dd/MM/yyyy",
                                    "MM/dd/yyyy",
                                    "yyyy/MM/dd",
                                    "dd-MM-yyyy",
                                    "yyyyMMdd"
                            )) {
                                try {
                                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pat);
                                    if (pat.contains("HH") || pat.contains("H")) {
                                        zdt = LocalDateTime.parse(str, dtf).atZone(ZoneId.systemDefault());
                                    } else {
                                        zdt = LocalDate.parse(str, dtf).atStartOfDay(ZoneId.systemDefault());
                                    }
                                    break;
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        }

        if (zdt == null) {
            int year = 1980 + ThreadLocalRandom.current().nextInt(44);
            int month = 1 + ThreadLocalRandom.current().nextInt(12);
            int day = 1 + ThreadLocalRandom.current().nextInt(28);
            int hour = ThreadLocalRandom.current().nextInt(24);
            int min = ThreadLocalRandom.current().nextInt(60);
            int sec = ThreadLocalRandom.current().nextInt(60);
            zdt = ZonedDateTime.of(year, month, day, hour, min, sec, 0, ZoneId.systemDefault());
        }

        if (doShift) {
            int days = ThreadLocalRandom.current().nextInt(shiftMin, shiftMax + 1);
            zdt = zdt.plusDays(days);
        }

        String formatted;
        try {
            formatted = zdt.format(formatter);
        } catch (Exception e) {
            try {
                formatted = zdt.toLocalDateTime().format(formatter);
            } catch (Exception e2) {
                try {
                    formatted = zdt.toLocalDate().format(formatter);
                } catch (Exception e3) {
                    formatted = zdt.toLocalDate().toString();
                }
            }
        }

        if (originalValue instanceof java.sql.Date) {
            try {
                return java.sql.Date.valueOf(zdt.toLocalDate());
            } catch (Exception e) {
                return formatted;
            }
        } else if (originalValue instanceof java.sql.Timestamp) {
            try {
                return java.sql.Timestamp.valueOf(zdt.toLocalDateTime());
            } catch (Exception e) {
                return formatted;
            }
        } else if (originalValue instanceof LocalDate) {
            return zdt.toLocalDate();
        } else if (originalValue instanceof LocalDateTime) {
            return zdt.toLocalDateTime();
        } else if (originalValue instanceof ZonedDateTime) {
            return zdt;
        }

        return formatted;
    }

    private static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String NUMERIC = "0123456789";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private Object formatCustomText(Object originalValue, String rule) {
        String template;
        if (rule.toUpperCase().startsWith("REGEX:")) {
            String regex = rule.substring(6).trim();
            try {
                return faker.regexify(regex);
            } catch (Exception e) {
                log.warn("Regexify failed for pattern: {}", regex);
                return "VAL-" + ThreadLocalRandom.current().nextInt(1000, 9999);
            }
        } else if (rule.toUpperCase().startsWith("TEXT:")) {
            template = rule.substring(5).trim();
        } else if (rule.toUpperCase().startsWith("TEMPLATE:")) {
            template = rule.substring(9).trim();
        } else {
            template = rule;
        }

        Pattern tokenPattern = Pattern.compile("\\{rand(?:_(alpha|num|digit))?:(\\d+)\\}", Pattern.CASE_INSENSITIVE);
        Matcher matcher = tokenPattern.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(1);
            int len = Integer.parseInt(matcher.group(2));
            String replacement = generateRandomString(type, len);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String generateRandomString(String type, int length) {
        if (length <= 0) return "";
        String chars = ALPHANUMERIC;
        if ("alpha".equalsIgnoreCase(type)) {
            chars = ALPHA;
        } else if ("num".equalsIgnoreCase(type) || "digit".equalsIgnoreCase(type)) {
            chars = NUMERIC;
        }
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private Object formatCustomNumber(Object originalValue, String rule, ColumnMetadata colMeta) {
        String content = rule.contains(":") ? rule.substring(rule.indexOf(':') + 1).trim() : rule.trim();
        String[] parts = content.split(":");

        boolean forcePositive = false;
        boolean forceNegative = false;
        boolean isWholeNumber = false;
        Integer scale = null;
        Double rangeMin = null;
        Double rangeMax = null;

        for (String p : parts) {
            String token = p.trim().toUpperCase();
            if ("POSITIVE".equals(token) || "POS".equals(token) || "ABS".equals(token)) {
                forcePositive = true;
            } else if ("NEGATIVE".equals(token) || "NEG".equals(token)) {
                forceNegative = true;
            } else if ("INTEGER".equals(token) || "WHOLE".equals(token)) {
                isWholeNumber = true;
                scale = 0;
            } else if (token.startsWith("SCALE(") && token.endsWith(")")) {
                try {
                    scale = Integer.parseInt(token.substring(6, token.length() - 1).trim());
                } catch (Exception ignored) {}
            } else if (token.startsWith("RANGE(") && token.endsWith(")")) {
                try {
                    String[] bounds = token.substring(6, token.length() - 1).split(",");
                    if (bounds.length == 2) {
                        rangeMin = Double.parseDouble(bounds[0].trim());
                        rangeMax = Double.parseDouble(bounds[1].trim());
                    }
                } catch (Exception ignored) {}
            }
        }

        BigDecimal numVal = null;
        if (originalValue instanceof Number n) {
            numVal = new BigDecimal(n.toString());
        } else if (originalValue != null) {
            try {
                numVal = new BigDecimal(originalValue.toString().replaceAll("[^0-9.-]", ""));
            } catch (Exception ignored) {}
        }

        if (numVal == null) {
            double base = rangeMin != null && rangeMax != null
                    ? ThreadLocalRandom.current().nextDouble(rangeMin, rangeMax)
                    : ThreadLocalRandom.current().nextDouble(10.0, 10000.0);
            numVal = BigDecimal.valueOf(base);
        }

        if (rangeMin != null && numVal.doubleValue() < rangeMin) {
            numVal = BigDecimal.valueOf(rangeMin);
        }
        if (rangeMax != null && numVal.doubleValue() > rangeMax) {
            numVal = BigDecimal.valueOf(rangeMax);
        }

        if (forcePositive) {
            numVal = numVal.abs();
        } else if (forceNegative) {
            numVal = numVal.abs().negate();
        }

        if (scale != null) {
            numVal = numVal.setScale(scale, RoundingMode.HALF_UP);
        }

        if (originalValue instanceof Integer || (colMeta != null && "integer".equalsIgnoreCase(colMeta.getDataType()))) {
            return numVal.intValue();
        } else if (originalValue instanceof Long || (colMeta != null && "bigint".equalsIgnoreCase(colMeta.getDataType()))) {
            return numVal.longValue();
        } else if (originalValue instanceof Float) {
            return numVal.floatValue();
        } else if (originalValue instanceof Double) {
            return numVal.doubleValue();
        } else if (isWholeNumber) {
            return numVal.toBigInteger().longValue();
        }
        return numVal;
    }

    private String getMatchingDefaultValue(Map<String, String> defaultValues, String lowerRoot, String lowerField) {
        if (defaultValues == null || defaultValues.isEmpty()) return null;
        String simpleRoot = lowerRoot.contains(".") ? lowerRoot.substring(lowerRoot.lastIndexOf('.') + 1) : lowerRoot;
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().trim().toLowerCase();
            if (key.equals(lowerRoot + "." + lowerField) 
                    || key.equals(simpleRoot + "." + lowerField) 
                    || key.endsWith("." + simpleRoot + "." + lowerField)
                    || key.endsWith("." + lowerField)
                    || key.equals(lowerField) 
                    || key.equals("*." + lowerField)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Object convertDefaultValueToType(String defVal, Object originalValue, ColumnMetadata colMeta) {
        if (defVal == null || "null".equalsIgnoreCase(defVal.trim())) {
            return null;
        }
        String trimmed = defVal.trim();
        if (originalValue instanceof Integer || (colMeta != null && isIntegerType(colMeta, originalValue))) {
            try { return Integer.parseInt(trimmed); } catch (Exception ignored) {}
        }
        if (originalValue instanceof Long || (colMeta != null && isBigintType(colMeta, originalValue))) {
            try { return Long.parseLong(trimmed); } catch (Exception ignored) {}
        }
        if (originalValue instanceof Double || originalValue instanceof BigDecimal || (colMeta != null && isNumericType(colMeta, originalValue))) {
            try { return new BigDecimal(trimmed); } catch (Exception ignored) {}
        }
        if (originalValue instanceof Boolean) {
            return Boolean.parseBoolean(trimmed);
        }
        if (originalValue instanceof UUID || (colMeta != null && "uuid".equalsIgnoreCase(colMeta.getDataType()))) {
            try { return UUID.fromString(trimmed); } catch (Exception ignored) {}
        }
        return defVal;
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