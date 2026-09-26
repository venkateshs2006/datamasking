package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionRequest;
import com.enterprise.seedm.model.AiPiiDetectionResponse;
import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiPiiDetectorService {

    private final TableDiscoveryService tableDiscoveryService;
    private final QwenAiClientService qwenAiClientService;

    @Value("${seedm.ai.qwen.api-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String defaultQwenApiUrl;

    @Value("${seedm.ai.qwen.api-key:}")
    private String defaultQwenApiKey;

    @Value("${seedm.ai.qwen.model:qwen-turbo}")
    private String defaultQwenModel;

    // Detection Rule definitions for local / fallback analysis
    private static class PiiRule {
        final String category;
        final String ruleType; // "SFD", "PMD", "FPH"
        final String fakerMethod;
        final Pattern pattern;
        final double confidence;
        final String reason;

        PiiRule(String category, String ruleType, String fakerMethod, String regex, double confidence, String reason) {
            this.category = category;
            this.ruleType = ruleType;
            this.fakerMethod = fakerMethod;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private static final List<PiiRule> RULES = List.of(
            // Emails
            new PiiRule("EMAIL", "SFD", "faker.internet().emailAddress()",
                    ".*(email|e_mail|mail_addr|contact_email|customer_email|user_email).*", 0.99, "Detected electronic mail address pattern"),

            // Names
            new PiiRule("FIRST_NAME", "SFD", "faker.name().firstName()",
                    ".*(first_name|fname|given_name|forename).*", 0.98, "Detected given/first name column"),
            new PiiRule("LAST_NAME", "SFD", "faker.name().lastName()",
                    ".*(last_name|lname|surname|family_name).*", 0.98, "Detected surname/last name column"),
            new PiiRule("FULL_NAME", "SFD", "faker.name().fullName()",
                    ".*(cust_name|customer_name|staff_name|actor_name|client_name|contact_name|owner_name|full_name|person_name|employee_name|user_name|username).*", 0.95, "Detected personal/user full name entity"),
            new PiiRule("NAME_GENERIC", "SFD", "faker.name().fullName()",
                    "^name$|.*_name$", 0.85, "Detected generic naming convention"),

            // Phone numbers
            new PiiRule("PHONE", "SFD", "faker.phoneNumber().cellPhone()",
                    ".*(phone|mobile|cell_phone|telephone|tel_no|fax|contact_no|phone_no|mobile_no|phone_number).*", 0.98, "Detected telephone/mobile contact number"),

            // Postal / Physical Address
            new PiiRule("STREET_ADDRESS", "SFD", "faker.address().streetAddress()",
                    ".*(address|addr|street|address2|street_addr|residence|line1|line2).*", 0.96, "Detected street or residential address"),
            new PiiRule("CITY", "SFD", "faker.address().city()",
                    ".*(city|district|town|municipality).*", 0.92, "Detected municipal/city location entity"),
            new PiiRule("POSTAL_CODE", "SFD", "faker.address().zipCode()",
                    ".*(postal_code|zip_code|zipcode|postcode|pincode).*", 0.95, "Detected postal/ZIP code identifier"),
            new PiiRule("STATE_COUNTRY", "SFD", "faker.address().country()",
                    ".*(country|country_id|state_name|province|state).*", 0.85, "Detected geographic state or country"),

            // PCI & Financial Data
            new PiiRule("CARD_SECURITY", "SFD", "faker.number().digits(3)",
                    ".*(cvv|cvc|cid|card_security_code|security_code).*", 0.98, "Detected card verification / CVV / CVC code (PCI)"),
            new PiiRule("CARD_EXPIRATION", "PMD", "DateShifter(±60d)",
                    ".*(card_exp|exp_date|exp_month|exp_year|card_expiry).*", 0.95, "Detected credit card expiration date (PCI)"),
            new PiiRule("CREDIT_CARD", "SFD", "faker.finance().creditCard()",
                    ".*(credit_card|card_number|card_no|card_num|cc_num|pan_number).*", 0.98, "Detected credit/debit card numeric identifier (PCI)"),
            new PiiRule("BANK_ACCOUNT", "SFD", "faker.finance().iban()",
                    ".*(iban|swift|bic|account_no|acc_no|acc_num|bank_account|routing_number).*", 0.96, "Detected banking/IBAN account details"),
            new PiiRule("ROUTING_NUMBER", "SFD", "faker.finance().bic()",
                    ".*(routing_number|sort_code|aba_routing).*", 0.96, "Detected banking routing transit / SWIFT code"),
            new PiiRule("SALARY_AMOUNT", "SFD", "faker.commerce().price()",
                    ".*(salary|wage|compensation|bonus|income|remuneration).*", 0.92, "Detected sensitive monetary compensation/salary data"),
            new PiiRule("FINANCIAL_BALANCE", "SFD", "faker.commerce().price()",
                    ".*(balance|amount|total_amount|credit_limit|revenue|fee|price).*", 0.88, "Detected monetary transaction or balance data"),

            // Secure & Sensitive Data (Credentials, Tokens, PHI)
            new PiiRule("PASSWORD_SECRET", "SFD", "faker.internet().password(12, true)",
                    ".*(password|passwd|pwd_hash|pwd|salt|secret_answer|security_answer|password_hash).*", 0.99, "Detected sensitive authentication credential / password / hash"),
            new PiiRule("API_TOKEN", "SFD", "faker.crypto().sha256()",
                    ".*(api_key|apikey|secret_key|private_key|auth_token|access_token|jwt|bearer_token|session_id|refresh_token).*", 0.98, "Detected security API key / secret token / private key"),
            new PiiRule("PHI_HEALTH_DATA", "FPH", "DeterministicFPH()",
                    ".*(mrn|patient_id|medical_record|health_plan|insurance_id|diagnosis|prescription|treatment|health_status).*", 0.96, "Detected Protected Health Information (PHI/HIPAA)"),

            // Digital Footprint & Demographics
            new PiiRule("IP_ADDRESS", "SFD", "faker.internet().ipV4Address()",
                    ".*(ip_address|client_ip|ipv4|ipv6|mac_address|mac_addr).*", 0.95, "Detected network IP / MAC address identifier"),
            new PiiRule("DEMOGRAPHIC", "SFD", "faker.demographic().sex()",
                    ".*(gender|ethnicity|marital_status|religion|nationality).*", 0.90, "Detected demographic personal attribute"),

            // Dates & Birthdays (PMD)
            new PiiRule("DATE_OF_BIRTH", "PMD", "DateShifter(±365d)",
                    ".*(dob|birth_date|birthdate|date_of_birth|birthday).*", 0.97, "Detected date of birth (applied PMD date shifting)"),

            // Government IDs & SSN (FPH)
            new PiiRule("SSN_NATIONAL_ID", "FPH", "DeterministicFPH()",
                    ".*(ssn|social_security|national_id|tax_id|tin|aadhaar|passport|license_no|driver_license|dl_number).*", 0.98, "Detected high-sensitivity national identifier (applied Format-Preserving Encryption)")
    );

    /**
     * Run AI PII detection on requested tables and columns (uses Qwen AI if configured, with heuristic fallback)
     */
    public AiPiiDetectionResponse detectPii(AiPiiDetectionRequest request) {
        log.info("Running AI PII Auto-Detection on tables: {}", request.getTables());
        AiPiiDetectionResponse response = new AiPiiDetectionResponse();

        if (request.getTables() == null || request.getTables().isEmpty()) {
            return response;
        }

        // 1. Gather columns across all requested tables
        Map<String, List<String>> tableColumnsMap = new HashMap<>();
        for (String table : request.getTables()) {
            List<String> columns = null;
            if (request.getTableColumns() != null && request.getTableColumns().containsKey(table)) {
                columns = request.getTableColumns().get(table);
            }

            if (columns == null || columns.isEmpty()) {
                try {
                    columns = tableDiscoveryService.getTableColumns(table);
                } catch (Exception e) {
                    log.warn("Could not retrieve columns dynamically for table {}: {}", table, e.getMessage());
                    continue;
                }
            }

            if (columns != null && !columns.isEmpty()) {
                tableColumnsMap.put(table, columns);
            }
        }

        // 2. Resolve Qwen credentials
        String apiUrl = (request.getQwenApiUrl() != null && !request.getQwenApiUrl().isBlank())
                ? request.getQwenApiUrl().trim() : defaultQwenApiUrl;
        String apiKey = (request.getQwenApiKey() != null && !request.getQwenApiKey().isBlank())
                ? request.getQwenApiKey().trim() : defaultQwenApiKey;
        String model = (request.getQwenModel() != null && !request.getQwenModel().isBlank())
                ? request.getQwenModel().trim() : defaultQwenModel;

        // 3. If API Key is provided, call Qwen LLM API
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                log.info("Attempting PII detection using Qwen AI API with model: {} and endpoint: {}", model, apiUrl);
                Map<String, PiiEntityInfo> qwenEntities = qwenAiClientService.detectPiiWithQwen(tableColumnsMap, apiUrl, apiKey, model);
                if (qwenEntities != null && !qwenEntities.isEmpty()) {
                    response = populateResponseFromEntities(qwenEntities);
                    response.setEngineUsed("Qwen AI LLM (" + model + ")");
                    response.setStatusMessage("PII columns auto-discovered via Qwen LLM API.");
                    log.info("Qwen AI Auto-Detection completed successfully. Found {} PII columns.", response.getTotalPiiColumnsFound());
                    return response;
                }
            } catch (Exception e) {
                log.warn("Qwen AI LLM detection failed: {}. Falling back to built-in heuristic pattern classifier.", e.getMessage());
            }
        }

        // 4. Fallback / Standard Heuristic Pattern Classifier
        response = detectPiiWithHeuristics(tableColumnsMap);
        response.setEngineUsed(apiKey != null && !apiKey.isBlank() ? "Heuristic Engine (Qwen Fallback)" : "In-Memory Heuristic Engine");
        response.setStatusMessage("PII columns classified via pattern recognition.");
        log.info("Heuristic detection completed. Found {} PII columns.", response.getTotalPiiColumnsFound());
        return response;
    }

    /**
     * Run heuristic pattern classifier across tables
     */
    private AiPiiDetectionResponse detectPiiWithHeuristics(Map<String, List<String>> tableColumnsMap) {
        Map<String, PiiEntityInfo> entities = new HashMap<>();
        Set<String> allTables = tableColumnsMap.keySet();

        for (Map.Entry<String, List<String>> entry : tableColumnsMap.entrySet()) {
            String table = entry.getKey();
            List<String> cols = entry.getValue();
            for (String col : cols) {
                String full = table + "." + col;
                PiiEntityInfo info = analyzeColumn(table, col, allTables, cols);
                if (info != null) {
                    entities.put(full, info);
                }
            }
        }

        return populateResponseFromEntities(entities);
    }

    /**
     * Populate response lists (maskingColumns, partialMaskingColumns, constraintColumns) from entities map
     */
    private AiPiiDetectionResponse populateResponseFromEntities(Map<String, PiiEntityInfo> entities) {
        AiPiiDetectionResponse response = new AiPiiDetectionResponse();
        response.setDetectedEntities(entities);

        int keyCount = 0;
        for (Map.Entry<String, PiiEntityInfo> entry : entities.entrySet()) {
            String full = entry.getKey();
            PiiEntityInfo info = entry.getValue();

            if (info.getRuleType() != null) {
                switch (info.getRuleType().toUpperCase()) {
                    case "SFD" -> {
                        if (!response.getMaskingColumns().contains(full)) {
                            response.getMaskingColumns().add(full);
                        }
                    }
                    case "PMD" -> {
                        if (!response.getPartialMaskingColumns().contains(full)) {
                            response.getPartialMaskingColumns().add(full);
                        }
                    }
                    case "FPH" -> {
                        if (!response.getConstraintColumns().contains(full)) {
                            response.getConstraintColumns().add(full);
                        }
                    }
                }
            }

            // Populate key & table mapping classification lists
            String keyType = info.getKeyType();
            String cat = info.getCategory() != null ? info.getCategory().toUpperCase() : "";
            boolean isKey = false;

            if ("PRIMARY_KEY".equalsIgnoreCase(keyType) || "PRIMARY_KEY".equals(cat)) {
                if (!response.getPrimaryKeyColumns().contains(full)) {
                    response.getPrimaryKeyColumns().add(full);
                }
                isKey = true;
            } else if ("FOREIGN_KEY".equalsIgnoreCase(keyType) || "FOREIGN_KEY".equals(cat)) {
                if (!response.getForeignKeyColumns().contains(full)) {
                    response.getForeignKeyColumns().add(full);
                }
                isKey = true;
            } else if ("UNIQUE_KEY".equalsIgnoreCase(keyType) || "UNIQUE_KEY".equals(cat)) {
                if (!response.getUniqueKeyColumns().contains(full)) {
                    response.getUniqueKeyColumns().add(full);
                }
                isKey = true;
            } else if ("TABLE_MAPPING".equalsIgnoreCase(keyType) || "TABLE_MAPPING".equals(cat) || "JUNCTION_KEY".equals(cat)) {
                if (!response.getTableMappingColumns().contains(full)) {
                    response.getTableMappingColumns().add(full);
                }
                isKey = true;
            }

            if (isKey) {
                keyCount++;
            }
        }

        response.setTotalPiiColumnsFound(response.getDetectedEntities().size());
        response.setTotalKeyColumnsFound(keyCount);
        return response;
    }

    /**
     * Analyze an individual column to identify PII, PCI, secure data, and relational schema attributes via heuristics
     */
    public PiiEntityInfo analyzeColumn(String table, String column) {
        return analyzeColumn(table, column, Collections.emptySet(), Collections.emptyList());
    }

    /**
     * Schema-aware column analysis detecting PII, PCI, credentials/tokens/PHI, primary keys, foreign keys, unique keys, and table mapping columns.
     */
    public PiiEntityInfo analyzeColumn(String table, String column, Set<String> allTables, List<String> tableCols) {
        String cleanCol = column.trim();

        // 1. Table Mapping / Junction Key (multi-tenant or junction tables)
        if (isTableMappingColumn(table, cleanCol, allTables, tableCols)) {
            String targetTable = inferTargetTable(cleanCol, allTables);
            return PiiEntityInfo.builder()
                    .table(table)
                    .column(column)
                    .category("TABLE_MAPPING")
                    .ruleType("FPH")
                    .fakerMethod("DeterministicFPH()")
                    .confidence(0.96)
                    .reason("Detected table mapping / junction key for table '" + table + "'")
                    .keyType("TABLE_MAPPING")
                    .targetTable(targetTable)
                    .targetColumn("id")
                    .build();
        }

        // 2. Primary Key
        if (isPrimaryKey(table, cleanCol)) {
            return PiiEntityInfo.builder()
                    .table(table)
                    .column(column)
                    .category("PRIMARY_KEY")
                    .ruleType("FPH")
                    .fakerMethod("DeterministicFPH()")
                    .confidence(0.99)
                    .reason("Detected primary key identifier for table '" + table + "'")
                    .keyType("PRIMARY_KEY")
                    .build();
        }

        // 3. Foreign Key
        if (isForeignKey(table, cleanCol)) {
            String targetTable = inferTargetTable(cleanCol, allTables);
            return PiiEntityInfo.builder()
                    .table(table)
                    .column(column)
                    .category("FOREIGN_KEY")
                    .ruleType("FPH")
                    .fakerMethod("DeterministicFPH()")
                    .confidence(0.95)
                    .reason("Detected foreign key referencing " + (targetTable != null ? targetTable + "(id)" : "parent table"))
                    .keyType("FOREIGN_KEY")
                    .targetTable(targetTable)
                    .targetColumn("id")
                    .build();
        }

        // 4. Standalone Unique Key / Natural Key
        if (cleanCol.matches("(?i)^(uuid|guid|license_key|registration_no|account_key)$")) {
            return PiiEntityInfo.builder()
                    .table(table)
                    .column(column)
                    .category("UNIQUE_KEY")
                    .ruleType("FPH")
                    .fakerMethod("DeterministicFPH()")
                    .confidence(0.92)
                    .reason("Detected unique business identifier / key")
                    .keyType("UNIQUE_KEY")
                    .build();
        }

        // 5. Pattern-based Classification (PII, PCI, Credentials, Tokens, PHI)
        for (PiiRule rule : RULES) {
            if (rule.pattern.matcher(cleanCol).matches()) {
                String keyType = null;
                if (cleanCol.equalsIgnoreCase("username") || cleanCol.equalsIgnoreCase("user_name")) {
                    keyType = "UNIQUE_KEY";
                }

                return PiiEntityInfo.builder()
                        .table(table)
                        .column(column)
                        .category(rule.category)
                        .ruleType(rule.ruleType)
                        .fakerMethod(rule.fakerMethod)
                        .confidence(rule.confidence)
                        .reason(rule.reason)
                        .keyType(keyType)
                        .build();
            }
        }

        return null;
    }

    private boolean isPrimaryKey(String table, String col) {
        String cleanCol = col.trim().toLowerCase();
        String cleanTable = table.trim().toLowerCase();
        String singularTable = stripPlural(cleanTable);

        return cleanCol.equals("id")
                || cleanCol.equals(cleanTable + "_id")
                || cleanCol.equals(singularTable + "_id")
                || cleanCol.equals("pk_" + cleanTable)
                || cleanCol.equals("pk_" + singularTable)
                || cleanCol.equals(cleanTable + "id")
                || cleanCol.equals(singularTable + "id");
    }

    private boolean isForeignKey(String table, String col) {
        String cleanCol = col.trim().toLowerCase();
        // Skip if this is the table's own primary key
        if (isPrimaryKey(table, cleanCol)) {
            return false;
        }
        return cleanCol.endsWith("_id")
                || cleanCol.endsWith("_fk")
                || cleanCol.startsWith("fk_")
                || cleanCol.equals("parent_id")
                || cleanCol.equals("created_by_user_id");
    }

    private String inferTargetTable(String col, Set<String> allTables) {
        String cleanCol = col.trim().toLowerCase();
        String candidate = null;
        if (cleanCol.endsWith("_id")) {
            candidate = cleanCol.substring(0, cleanCol.length() - 3);
        } else if (cleanCol.endsWith("_fk")) {
            candidate = cleanCol.substring(0, cleanCol.length() - 3);
        } else if (cleanCol.startsWith("fk_")) {
            candidate = cleanCol.substring(3);
        } else if (cleanCol.equals("parent_id")) {
            return null;
        }

        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        // Try exact match, plural match, or singular match in allTables
        if (allTables != null && !allTables.isEmpty()) {
            for (String t : allTables) {
                String cleanT = t.trim().toLowerCase();
                if (cleanT.equals(candidate) || stripPlural(cleanT).equals(candidate) || cleanT.equals(candidate + "s")) {
                    return t;
                }
            }
        }

        return candidate;
    }

    private boolean isTableMappingColumn(String table, String col, Set<String> allTables, List<String> tableCols) {
        String cleanCol = col.trim().toLowerCase();
        // Multi-tenant mapping columns
        if (cleanCol.matches(".*(tenant_id|organization_id|org_id|company_id|workspace_id).*")) {
            return true;
        }

        // Check if table is a junction/mapping table
        String cleanTable = table.trim().toLowerCase();
        boolean looksLikeJunctionTable = cleanTable.contains("_")
                && (cleanTable.contains("role") || cleanTable.contains("item") || cleanTable.contains("map")
                    || cleanTable.contains("rel") || cleanTable.contains("xref") || cleanTable.contains("link")
                    || cleanTable.contains("actor") || cleanTable.contains("film") || cleanTable.contains("category")
                    || (tableCols != null && tableCols.stream().filter(c -> c.toLowerCase().endsWith("_id")).count() >= 2));

        if (looksLikeJunctionTable && isForeignKey(table, cleanCol)) {
            return true;
        }

        return false;
    }

    private String stripPlural(String name) {
        if (name == null || name.length() <= 3) {
            return name;
        }
        String lower = name.toLowerCase();
        if (lower.endsWith("ies")) {
            return name.substring(0, name.length() - 3) + "y";
        }
        if (lower.endsWith("sses")) {
            return name.substring(0, name.length() - 2);
        }
        if (lower.endsWith("ses") || lower.endsWith("xes") || lower.endsWith("shes") || lower.endsWith("ches")) {
            return name.substring(0, name.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }
}
