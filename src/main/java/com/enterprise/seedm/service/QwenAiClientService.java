package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class QwenAiClientService {

    private final ObjectMapper objectMapper;
    private static final int TIMEOUT_MS = 30000; // 30s timeout
    private static final String DEFAULT_QWEN_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_QWEN_MODEL = "qwen-turbo";

    /**
     * Builds an OpenAI-compatible ChatModel via Spring AI configured for Qwen / DashScope
     */
    public ChatModel buildChatModel(String apiUrl, String apiKey, String model, Double temperature, Integer maxTokens) {
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_QWEN_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_QWEN_MODEL;
        }

        String trimmedUrl = apiUrl.trim();
        while (trimmedUrl.endsWith("/")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 1);
        }

        String baseUrl;
        String completionsPath;
        if (trimmedUrl.endsWith("/chat/completions")) {
            baseUrl = trimmedUrl.substring(0, trimmedUrl.length() - "/chat/completions".length());
            completionsPath = "/chat/completions";
        } else if (trimmedUrl.endsWith("/v1")) {
            baseUrl = trimmedUrl;
            completionsPath = "/chat/completions";
        } else {
            baseUrl = trimmedUrl;
            completionsPath = "/v1/chat/completions";
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(TIMEOUT_MS));
        requestFactory.setReadTimeout(Duration.ofMillis(TIMEOUT_MS));

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(completionsPath)
                .restClientBuilder(restClientBuilder);

        if (apiKey != null && !apiKey.isBlank()) {
            apiBuilder.apiKey(apiKey.trim());
        } else {
            apiBuilder.apiKey("none");
        }

        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model);

        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        if (maxTokens != null) {
            optionsBuilder.maxTokens(maxTokens);
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * Call Qwen LLM API via Spring AI to detect PII across database tables and columns
     */
    public Map<String, PiiEntityInfo> detectPiiWithQwen(
            Map<String, List<String>> tableColumns,
            String apiUrl,
            String apiKey,
            String model) throws Exception {

        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_QWEN_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_QWEN_MODEL;
        }

        log.info("Invoking Qwen LLM via Spring AI at {} with model: {}", apiUrl, model);

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

        ChatModel chatModel = buildChatModel(apiUrl, apiKey, model, 0.1, null);
        ChatClient chatClient = ChatClient.create(chatModel);

        String rawContent = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt.toString())
                .call()
                .content();

        log.debug("Raw response from Spring AI Qwen model: {}", rawContent);
        return parseEntitiesFromJson(rawContent);
    }

    /**
     * Test connection to Qwen API endpoint via Spring AI
     */
    public boolean testConnection(String apiUrl, String apiKey, String model) {
        try {
            ChatModel chatModel = buildChatModel(apiUrl, apiKey, model, 0.1, 10);
            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .user("Ping test. Respond with OK.")
                    .call()
                    .content();
            return response != null && !response.isBlank();
        } catch (Exception e) {
            log.warn("Qwen API connection test failed via Spring AI: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parse entities map from raw JSON content (handling markdown code fences if present)
     */
    public Map<String, PiiEntityInfo> parseEntitiesFromJson(String rawContent) {
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
