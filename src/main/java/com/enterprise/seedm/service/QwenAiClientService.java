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
     * Builds an OpenAI-compatible ChatModel via Spring AI configured for Qwen /
     * DashScope
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
     * Call Qwen LLM API via Spring AI to detect PII across database tables and
     * columns
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
                You are an expert Enterprise Data Security, Privacy & Relational Schema Intelligence Engine.
                Analyze the provided database schema (tables and column names) to comprehensively and accurately identify:
                1. Personally Identifiable Information (PII)
                2. Payment Card Industry (PCI) and Financial Data
                3. Secure & Sensitive Data (passwords, hashes, tokens, API keys, secrets, PHI / HIPAA healthcare data)
                4. Relational Keys & Mapping Columns (Primary Keys, Foreign Keys, Unique Keys, and Table Mapping / Junction columns across tables)

                ### DETECTION CATEGORIES & CLASSIFICATION RULES:

                1. PERSONALLY IDENTIFIABLE INFORMATION (PII):
                   - EMAIL: Electronic mail addresses (e.g. email, user_email, mail_addr) -> ruleType: "SFD", fakerMethod: "faker.internet().emailAddress()"
                   - FIRST_NAME: Given / first name (e.g. first_name, fname, given_name) -> ruleType: "SFD", fakerMethod: "faker.name().firstName()"
                   - LAST_NAME: Family / surname (e.g. last_name, lname, surname) -> ruleType: "SFD", fakerMethod: "faker.name().lastName()"
                   - FULL_NAME: Combined personal name / username (e.g. full_name, name, customer_name, username) -> ruleType: "SFD", fakerMethod: "faker.name().fullName()"
                   - PHONE: Mobile, telephone, cellular, fax (e.g. phone, mobile, cell_phone, tel_no) -> ruleType: "SFD", fakerMethod: "faker.phoneNumber().cellPhone()"
                   - STREET_ADDRESS: Physical / residential street address (e.g. address, street, street_addr, address1, address2) -> ruleType: "SFD", fakerMethod: "faker.address().streetAddress()"
                   - CITY: City, town, municipality (e.g. city, town, district) -> ruleType: "SFD", fakerMethod: "faker.address().city()"
                   - POSTAL_CODE: ZIP / postal code (e.g. postal_code, zip_code, zipcode, pincode) -> ruleType: "SFD", fakerMethod: "faker.address().zipCode()"
                   - STATE_COUNTRY: State, province, region, country (e.g. state, country, province) -> ruleType: "SFD", fakerMethod: "faker.address().country()"
                   - DATE_OF_BIRTH: Date of birth, birthday (e.g. dob, birth_date, birthdate, birthday) -> ruleType: "PMD", fakerMethod: "DateShifter(±365d)"
                   - SSN_NATIONAL_ID: Government / national identifiers (e.g. ssn, national_id, aadhaar, passport, tax_id, tin, driver_license, dl_number) -> ruleType: "FPH", fakerMethod: "DeterministicFPH()"
                   - IP_ADDRESS: Network IP or MAC address (e.g. ip_address, client_ip, mac_addr) -> ruleType: "SFD", fakerMethod: "faker.internet().ipV4Address()"
                   - DEMOGRAPHIC: Personal demographics, gender, ethnicity, religion, marital status -> ruleType: "SFD", fakerMethod: "faker.demographic().sex()"

                2. PCI & FINANCIAL DATA:
                   - CREDIT_CARD: Primary Account Number (PAN), credit/debit card numbers (e.g. card_number, credit_card, cc_num, pan_number) -> ruleType: "SFD", fakerMethod: "faker.finance().creditCard()"
                   - CARD_SECURITY: Card verification / CVV / CVC (e.g. cvv, cvc, cid, card_security_code) -> ruleType: "SFD", fakerMethod: "faker.number().digits(3)"
                   - CARD_EXPIRATION: Card expiration date (e.g. card_exp, exp_date, exp_month) -> ruleType: "PMD", fakerMethod: "DateShifter(±60d)"
                   - BANK_ACCOUNT: Bank account numbers, IBAN (e.g. iban, account_no, acc_num, bank_account) -> ruleType: "SFD", fakerMethod: "faker.finance().iban()"
                   - ROUTING_NUMBER: Bank routing, SWIFT, BIC (e.g. swift, bic, routing_number, sort_code) -> ruleType: "SFD", fakerMethod: "faker.finance().bic()"
                   - SALARY_AMOUNT: Sensitive monetary compensation / salary (e.g. salary, wage, compensation, bonus, income) -> ruleType: "SFD", fakerMethod: "faker.commerce().price()"
                   - FINANCIAL_BALANCE: Monetary balances, transactions, credit limits (e.g. balance, amount, total_amount, credit_limit) -> ruleType: "SFD", fakerMethod: "faker.commerce().price()"

                3. SECURE & SENSITIVE DATA:
                   - PASSWORD_SECRET: Passwords, password hashes, salts, security answers (e.g. password, passwd, pwd_hash, salt, secret_answer) -> ruleType: "SFD", fakerMethod: "faker.internet().password(12, true)"
                   - API_TOKEN: API keys, access tokens, JWT, secrets, private keys (e.g. api_key, auth_token, secret_key, private_key, jwt_token, session_id) -> ruleType: "SFD", fakerMethod: "faker.crypto().sha256()"
                   - PHI_HEALTH_DATA: Protected Health Information / HIPAA (e.g. mrn, patient_id, medical_record_no, health_plan_id, diagnosis, prescription) -> ruleType: "FPH", fakerMethod: "DeterministicFPH()"

                4. IDENTIFIERS, KEYS & TABLE MAPPING COLUMNS:
                   - PRIMARY_KEY: Unique identifier column for rows in this table (e.g. `id`, `<tableName>_id`, `uuid`, `pk_*`).
                     Mark with keyType: "PRIMARY_KEY". Recommend ruleType: "FPH", fakerMethod: "DeterministicFPH()" to maintain relational uniqueness if anonymized.
                   - FOREIGN_KEY: Reference to a primary key in another table (e.g. `customer_id` in orders, `<parentTable>_id`, `created_by_user_id`, `parent_id`, `fk_*`).
                     Mark with keyType: "FOREIGN_KEY". Set `targetTable` to the referenced table and `targetColumn` to the referenced column (e.g. "id" or "<parentTable>_id").
                     Recommend ruleType: "FPH", fakerMethod: "DeterministicFPH()" to preserve parent-child referential integrity across tables when masked.
                   - UNIQUE_KEY: Columns with unique constraints or natural keys (e.g. unique `username`, `email` when unique login, `account_number`, `uuid`, `code`, `slug`, `tracking_number`).
                     Mark with keyType: "UNIQUE_KEY". Recommend ruleType: "FPH", fakerMethod: "DeterministicFPH()" or "SFD".
                   - TABLE_MAPPING: Columns in junction / pivot / associative mapping tables connecting multiple entities (e.g. `user_id` and `role_id` in `user_roles`, `order_id` and `item_id` in `order_items`) or multi-tenant partition mapping columns (`tenant_id`, `organization_id`, `company_id`).
                     Mark with keyType: "TABLE_MAPPING". Set `targetTable` and `targetColumn` if pointing to an external entity.
                     Recommend ruleType: "FPH", fakerMethod: "DeterministicFPH()" to preserve table join relationships.

                ### MASKING STRATEGY GUIDANCE (ruleType):
                - "SFD" (Synthetic Faker Data): For standard PII, PCI, names, addresses, phones, emails, monetary numbers.
                - "PMD" (Partial Masking / Date Shifting): For dates, timestamps, birth dates, card expiration.
                - "FPH" (Format-Preserving Encryption / Deterministic Hash): For government IDs, secret tokens, and ALL relational Keys/IDs (Primary Keys, Foreign Keys, Unique Keys, Table Mapping Columns) so that table joins and relational constraints remain intact.

                Return ONLY a valid JSON object matching this exact structure:
                {
                  "detectedEntities": {
                    "tableName.columnName": {
                      "table": "tableName",
                      "column": "columnName",
                      "category": "EMAIL | FIRST_NAME | LAST_NAME | FULL_NAME | PHONE | STREET_ADDRESS | CITY | POSTAL_CODE | STATE_COUNTRY | DATE_OF_BIRTH | SSN_NATIONAL_ID | IP_ADDRESS | DEMOGRAPHIC | CREDIT_CARD | CARD_SECURITY | CARD_EXPIRATION | BANK_ACCOUNT | ROUTING_NUMBER | SALARY_AMOUNT | FINANCIAL_BALANCE | PASSWORD_SECRET | API_TOKEN | PHI_HEALTH_DATA | PRIMARY_KEY | FOREIGN_KEY | UNIQUE_KEY | TABLE_MAPPING",
                      "ruleType": "SFD | PMD | FPH",
                      "fakerMethod": "faker.internet().emailAddress() | faker.finance().creditCard() | DeterministicFPH() | DateShifter(±365d)",
                      "confidence": 0.98,
                      "reason": "Clear explanation of detection rationale, security classification, or foreign key relationship",
                      "keyType": "PRIMARY_KEY | FOREIGN_KEY | UNIQUE_KEY | TABLE_MAPPING | NONE",
                      "targetTable": "referenced parent table name (e.g. 'customer' for 'customer_id') or null",
                      "targetColumn": "referenced column name (e.g. 'id' or 'customer_id') or null"
                    }
                  }
                }
                """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append(
                "Analyze these database tables and columns to identify all PII, PCI, secure credentials/tokens, and relational schema attributes (Primary Keys, Foreign Keys, Unique Keys, Table Mapping columns):\n\n");
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
     * Parse entities map from raw JSON content (handling markdown code fences if
     * present)
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
                    String keyType = val.hasNonNull("keyType") ? val.path("keyType").asText() : null;
                    String targetTable = val.hasNonNull("targetTable") ? val.path("targetTable").asText() : null;
                    String targetColumn = val.hasNonNull("targetColumn") ? val.path("targetColumn").asText() : null;

                    PiiEntityInfo info = PiiEntityInfo.builder()
                            .table(val.path("table").asText(key.contains(".") ? key.split("\\.")[0] : ""))
                            .column(val.path("column").asText(key.contains(".") ? key.split("\\.")[1] : key))
                            .category(val.path("category").asText("PII"))
                            .ruleType(val.path("ruleType").asText("SFD").toUpperCase())
                            .fakerMethod(val.path("fakerMethod").asText("faker.name().fullName()"))
                            .confidence(val.path("confidence").asDouble(0.95))
                            .reason(val.path("reason").asText("Identified by Qwen AI"))
                            .keyType(keyType)
                            .targetTable(targetTable)
                            .targetColumn(targetColumn)
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
