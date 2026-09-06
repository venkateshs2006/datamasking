package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class QwenAiClientService {

    private final ObjectMapper objectMapper;
    private static final int TIMEOUT_MS = 30000; // 30s timeout

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /**
     * Call Qwen LLM API to detect PII across database tables and columns
     */
    public Map<String, PiiEntityInfo> detectPiiWithQwen(
            Map<String, List<String>> tableColumns,
            String apiUrl,
            String apiKey,
            String model) throws Exception {

        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions";
        }
        if (model == null || model.isBlank()) {
            model = "qwen-turbo";
        }

        log.info("Invoking Qwen LLM API at {} with model: {}", apiUrl, model);

        String systemPrompt = """
                You are an expert Data Privacy & PII Auto-Detection Engine for enterprise database migration & synthetic data masking.
                Analyze the provided database tables and column names to accurately identify Personally Identifiable Information (PII) and sensitive attributes.

                For each identified PII column, recommend the best masking strategy:
                1. "SFD" (Synthetic Faker Data) for names, emails, phones, addresses, financial cards, bank accounts, monetary amounts, etc.
                2. "PMD" (Partial Masking / Date Shifting) for dates of birth and birthdays.
                3. "FPH" (Format-Preserving Encryption) for government IDs, SSNs, national identifiers, passport numbers, tax IDs.

                Provide exact Java Faker expressions for SFD, such as:
                - Emails: "faker.internet().emailAddress()"
                - First Name: "faker.name().firstName()"
                - Last Name: "faker.name().lastName()"
                - Full Name / Username: "faker.name().fullName()"
                - Phone Number: "faker.phoneNumber().cellPhone()"
                - Street Address: "faker.address().streetAddress()"
                - City: "faker.address().city()"
                - Postal/Zip Code: "faker.address().zipCode()"
                - Country/State: "faker.address().country()"
                - Credit Card / PAN: "faker.finance().creditCard()"
                - Bank Account / IBAN: "faker.finance().iban()"
                - Salary / Monetary Amount: "faker.commerce().price()"
                - Date of Birth: "DateShifter(±365d)" (with ruleType: "PMD")
                - SSN / Tax ID / Passport: "DeterministicFPH()" (with ruleType: "FPH")

                Standard non-PII surrogate primary keys (e.g. id, customer_id, rental_id, payment_id) should NOT be flagged as PII unless they contain sensitive information.

                Return ONLY a valid JSON object matching this exact structure:
                {
                  "detectedEntities": {
                    "tableName.columnName": {
                      "table": "tableName",
                      "column": "columnName",
                      "category": "EMAIL | FIRST_NAME | LAST_NAME | FULL_NAME | PHONE | STREET_ADDRESS | CITY | POSTAL_CODE | STATE_COUNTRY | CREDIT_CARD | BANK_ACCOUNT | SALARY_AMOUNT | DATE_OF_BIRTH | SSN_NATIONAL_ID",
                      "ruleType": "SFD | PMD | FPH",
                      "fakerMethod": "faker.internet().emailAddress()",
                      "confidence": 0.98,
                      "reason": "Clear explanation of detection rationale"
                    }
                  }
                }
                """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Analyze these tables and columns for PII:\n\n");
        for (Map.Entry<String, List<String>> entry : tableColumns.entrySet()) {
            userPrompt.append("Table: `").append(entry.getKey()).append("`\n");
            userPrompt.append("Columns: ").append(String.join(", ", entry.getValue())).append("\n\n");
        }

        // Build OpenAI/Qwen compatible request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.1);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt.toString()));
        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey.trim());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = createRestTemplate();

        ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Qwen API returned status " + response.getStatusCode() + ": " + response.getBody());
        }

        String rawContent = extractContentFromQwenResponse(response.getBody());
        return parseEntitiesFromJson(rawContent);
    }

    /**
     * Test connection to Qwen API endpoint
     */
    public boolean testConnection(String apiUrl, String apiKey, String model) {
        try {
            if (apiUrl == null || apiUrl.isBlank()) {
                apiUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions";
            }
            if (model == null || model.isBlank()) {
                model = "qwen-turbo";
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 10);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", "Ping test. Respond with OK.")
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.setBearerAuth(apiKey.trim());
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            RestTemplate restTemplate = createRestTemplate();

            ResponseEntity<String> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Qwen API connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extract assistant message content from Qwen response JSON
     */
    private String extractContentFromQwenResponse(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            return message.path("content").asText();
        }
        throw new RuntimeException("No valid choices in Qwen API response");
    }

    /**
     * Parse entities map from raw JSON content (handling markdown code fences if present)
     */
    private Map<String, PiiEntityInfo> parseEntitiesFromJson(String rawContent) {
        Map<String, PiiEntityInfo> result = new HashMap<>();
        if (rawContent == null || rawContent.isBlank()) {
            return result;
        }

        String cleanJson = rawContent.trim();
        // Strip markdown code fences if LLM wrapped output in ```json ... ```
        if (cleanJson.startsWith("```")) {
            int firstNewline = cleanJson.indexOf('\n');
            int lastFence = cleanJson.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                cleanJson = cleanJson.substring(firstNewline + 1, lastFence).trim();
            }
        }

        try {
            JsonNode root = objectMapper.readTree(cleanJson);
            JsonNode entitiesNode = root.path("detectedEntities");
            if (entitiesNode.isMissingNode() || !entitiesNode.isObject()) {
                entitiesNode = root; // in case root was directly the map
            }

            Iterator<Map.Entry<String, JsonNode>> fields = entitiesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode val = field.getValue();
                if (val.isObject()) {
                    PiiEntityInfo info = PiiEntityInfo.builder()
                            .table(val.path("table").asText(key.contains(".") ? key.split("\\.")[0] : ""))
                            .column(val.path("column").asText(key.contains(".") ? key.split("\\.")[1] : key))
                            .category(val.path("category").asText("PII"))
                            .ruleType(val.path("ruleType").asText("SFD").toUpperCase())
                            .fakerMethod(val.path("fakerMethod").asText("faker.name().fullName()"))
                            .confidence(val.path("confidence").asDouble(0.95))
                            .reason(val.path("reason").asText("Identified by Qwen AI"))
                            .build();
                    result.put(key, info);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Qwen JSON response: {}", e.getMessage(), e);
        }

        return result;
    }
}
