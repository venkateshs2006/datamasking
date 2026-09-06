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
                    ".*(address|addr|street|address2|street_addr|residence).*", 0.96, "Detected street or residential address"),
            new PiiRule("CITY", "SFD", "faker.address().city()",
                    ".*(city|district|town|municipality).*", 0.92, "Detected municipal/city location entity"),
            new PiiRule("POSTAL_CODE", "SFD", "faker.address().zipCode()",
                    ".*(postal_code|zip_code|zipcode|postcode|pincode).*", 0.95, "Detected postal/ZIP code identifier"),
            new PiiRule("STATE_COUNTRY", "SFD", "faker.address().country()",
                    ".*(country|country_id|state_name|province).*", 0.85, "Detected geographic state or country"),

            // Financial & Banking
            new PiiRule("CREDIT_CARD", "SFD", "faker.finance().creditCard()",
                    ".*(credit_card|card_number|card_no|card_num|cc_num|pan_number|cvv|cvc|card_exp).*", 0.98, "Detected credit/debit card numeric identifier"),
            new PiiRule("BANK_ACCOUNT", "SFD", "faker.finance().iban()",
                    ".*(iban|swift|bic|account_no|acc_no|bank_account|routing_number).*", 0.96, "Detected banking/IBAN account details"),
            new PiiRule("SALARY_AMOUNT", "SFD", "faker.commerce().price()",
                    ".*(salary|wage|compensation|bonus|amount|balance|revenue).*", 0.90, "Detected sensitive monetary/financial data"),

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

        for (Map.Entry<String, List<String>> entry : tableColumnsMap.entrySet()) {
            String table = entry.getKey();
            for (String col : entry.getValue()) {
                String full = table + "." + col;
                PiiEntityInfo info = analyzeColumn(table, col);
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

        for (Map.Entry<String, PiiEntityInfo> entry : entities.entrySet()) {
            String full = entry.getKey();
            PiiEntityInfo info = entry.getValue();

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

        response.setTotalPiiColumnsFound(response.getDetectedEntities().size());
        return response;
    }

    /**
     * Analyze an individual column to identify PII classification via heuristics
     */
    public PiiEntityInfo analyzeColumn(String table, String column) {
        String cleanCol = column.trim();
        // Skip common non-PII keys unless specifically marked
        if (cleanCol.equalsIgnoreCase("id") || cleanCol.equalsIgnoreCase(table + "_id")) {
            return null;
        }

        for (PiiRule rule : RULES) {
            if (rule.pattern.matcher(cleanCol).matches()) {
                return PiiEntityInfo.builder()
                        .table(table)
                        .column(column)
                        .category(rule.category)
                        .ruleType(rule.ruleType)
                        .fakerMethod(rule.fakerMethod)
                        .confidence(rule.confidence)
                        .reason(rule.reason)
                        .build();
            }
        }

        return null;
    }
}
