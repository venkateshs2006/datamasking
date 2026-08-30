package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.JsonSecureExportConfig;
import com.enterprise.seedm.model.JsonSecureExportJob;
import com.enterprise.seedm.repository.JsonSecureExportJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonSecureExportService {

    private final CosConnectionService cosConnectionService;
    private final IbmCosService ibmCosService;
    private final FormatPreservingEncryptionService fpeService;
    private final JsonSecureExportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final net.datafaker.Faker faker = new net.datafaker.Faker();

    private static final String DEFAULT_EXPORT_DIR = "secure-export";
    private static final byte[] MAGIC_HEADER = "JSONBUNDLE1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_FILE_START = 0x01;
    private static final byte MARKER_FILE_CONTENT = 0x02;
    private static final byte MARKER_FILE_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    public static class JsonSecureExportProgress {
        public final AtomicInteger totalFiles = new AtomicInteger(0);
        public final AtomicInteger processedFiles = new AtomicInteger(0);
        public final AtomicLong totalRecords = new AtomicLong(0);
        public final List<Map<String, Object>> fileProgress = Collections.synchronizedList(new ArrayList<>());
        public final List<String> completedFiles = Collections.synchronizedList(new ArrayList<>());
        public volatile String status = "PENDING";
        public volatile String errorMessage = null;
        public volatile long startTime = System.currentTimeMillis();
        public volatile long completedTime = 0;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("totalFiles", totalFiles.get());
            map.put("processedFiles", processedFiles.get());
            map.put("totalTables", totalFiles.get()); // Compatible with dashboard
            map.put("processedTables", processedFiles.get());
            map.put("totalRecords", totalRecords.get());
            map.put("tableProgress", new ArrayList<>(fileProgress));
            map.put("completedTables", new ArrayList<>(completedFiles));
            map.put("errorMessage", errorMessage);
            map.put("startTime", startTime);
            map.put("completedTime", completedTime);
            long elapsedSeconds = (completedTime > 0 ? (completedTime - startTime) : (System.currentTimeMillis() - startTime)) / 1000;
            map.put("elapsedSeconds", elapsedSeconds);
            return map;
        }
    }

    private final Map<String, JsonSecureExportProgress> progressMap = new ConcurrentHashMap<>();

    public Map<String, Object> scanSourceFiles(JsonSecureExportConfig.StorageConfig source) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (source != null && "cos".equalsIgnoreCase(source.getType())) {
                Long cosId = source.getCosId() != null ? source.getCosId() : source.getId();
                if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection cos = cosConnectionService.getConnection(cosId);
                    if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                        List<Map<String, Object>> cosObjects = ibmCosService.listObjects(cos, null);
                        List<Map<String, Object>> filesList = cosObjects.stream()
                                .filter(o -> {
                                    String name = (String) o.get("name");
                                    return name != null && name.toLowerCase().endsWith(".json");
                                })
                                .toList();

                        response.put("status", "SUCCESS");
                        response.put("path", "cos://" + ibmCosService.getEffectiveBucketName(cos));
                        response.put("files", filesList);
                        response.put("fileCount", filesList.size());
                        return response;
                    }
                }
            }

            Path srcPath = resolveStoragePath(source);
            List<Map<String, Object>> filesList = new ArrayList<>();

            if (Files.exists(srcPath)) {
                if (Files.isRegularFile(srcPath) && srcPath.toString().endsWith(".json")) {
                    Map<String, Object> fInfo = new HashMap<>();
                    fInfo.put("name", srcPath.getFileName().toString());
                    fInfo.put("sizeBytes", Files.size(srcPath));
                    filesList.add(fInfo);
                } else if (Files.isDirectory(srcPath)) {
                    try (Stream<Path> stream = Files.walk(srcPath, 3)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".json"))
                                .forEach(p -> {
                                    Map<String, Object> fInfo = new HashMap<>();
                                    fInfo.put("name", srcPath.relativize(p).toString());
                                    try {
                                        fInfo.put("sizeBytes", Files.size(p));
                                    } catch (IOException e) {
                                        fInfo.put("sizeBytes", 0);
                                    }
                                    filesList.add(fInfo);
                                });
                    }
                }
            }

            response.put("status", "SUCCESS");
            response.put("path", srcPath.toAbsolutePath().toString());
            response.put("files", filesList);
            response.put("fileCount", filesList.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to scan JSON source files", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public Map<String, Object> sampleJsonFields(JsonSecureExportConfig.StorageConfig source, String fileName) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (source != null && "cos".equalsIgnoreCase(source.getType())) {
                Long cosId = source.getCosId() != null ? source.getCosId() : source.getId();
                if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection cos = cosConnectionService.getConnection(cosId);
                    if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                        Set<String> keys = ibmCosService.extractJsonKeys(cos, fileName);
                        response.put("status", "SUCCESS");
                        response.put("fileName", fileName);
                        response.put("fields", new ArrayList<>(keys));
                        return response;
                    }
                }
            }

            Path srcPath = resolveStoragePath(source);
            Path filePath = Files.isDirectory(srcPath) ? srcPath.resolve(fileName) : srcPath;
            if (!Files.exists(filePath)) {
                response.put("status", "ERROR");
                response.put("message", "File not found: " + fileName);
                return response;
            }

            JsonNode rootNode = objectMapper.readTree(filePath.toFile());
            Set<String> fieldPaths = new TreeSet<>();
            extractFieldPaths(rootNode, "", fieldPaths);

            response.put("status", "SUCCESS");
            response.put("fileName", fileName);
            response.put("fields", new ArrayList<>(fieldPaths));
            return response;
        } catch (Exception e) {
            log.error("Failed to sample JSON fields for {}", fileName, e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    private void extractFieldPaths(JsonNode node, String currentPath, Set<String> fieldPaths) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldPath = currentPath.isEmpty() ? entry.getKey() : currentPath + "." + entry.getKey();
                if (entry.getValue().isValueNode()) {
                    fieldPaths.add(fieldPath);
                } else {
                    extractFieldPaths(entry.getValue(), fieldPath, fieldPaths);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < Math.min(arrayNode.size(), 10); i++) {
                extractFieldPaths(arrayNode.get(i), currentPath, fieldPaths);
            }
        }
    }

    public String processJsonExport(String executionId, JsonSecureExportConfig config) {
        JsonSecureExportProgress progress = progressMap.computeIfAbsent(executionId, k -> new JsonSecureExportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        int processedCount = 0;

        try {
            saveJobRecord(executionId, config, "RUNNING", null);

            Path sourceDir = resolveStoragePath(config.getSource());

            // If source is COS, download JSON objects to staging folder
            if (config.getSource() != null && "cos".equalsIgnoreCase(config.getSource().getType())) {
                Long srcCosId = config.getSource().getCosId() != null ? config.getSource().getCosId() : config.getSource().getId();
                if (srcCosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection srcCos = cosConnectionService.getConnection(srcCosId);
                    if (srcCos != null && !"Local".equalsIgnoreCase(srcCos.getStorageType())) {
                        sourceDir = Files.createTempDirectory("cos-json-src-staging");
                        List<Map<String, Object>> cosObjs = ibmCosService.listObjects(srcCos, null);
                        for (Map<String, Object> obj : cosObjs) {
                            String name = (String) obj.get("name");
                            if (name != null && name.toLowerCase().endsWith(".json")) {
                                try {
                                    Path targetP = sourceDir.resolve(name);
                                    if (targetP.getParent() != null) Files.createDirectories(targetP.getParent());
                                    ibmCosService.downloadFile(srcCos, name, targetP);
                                } catch (Exception ex) {
                                    log.warn("Failed to download JSON file {} from COS: {}", name, ex.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            Path destDir = resolveStoragePath(config.getDest() != null ? config.getDest() : config.getStorage());
            Files.createDirectories(destDir);

            if (!Files.exists(sourceDir)) {
                throw new IllegalArgumentException("Source JSON directory does not exist: " + sourceDir);
            }

            final Path effectiveSourceDir = sourceDir;
            String saltKey = config.getRules() != null ? config.getRules().getMaskingKey() : null;

            List<String> targetFiles = new ArrayList<>();
            if (config.getRules() != null) {
                if (config.getRules().getTargetFiles() != null && !config.getRules().getTargetFiles().isEmpty()) {
                    targetFiles.addAll(config.getRules().getTargetFiles());
                } else if (config.getRules().getTargetTables() != null && !config.getRules().getTargetTables().isEmpty()) {
                    targetFiles.addAll(config.getRules().getTargetTables());
                } else if (config.getRules().getTargetCollections() != null && !config.getRules().getTargetCollections().isEmpty()) {
                    targetFiles.addAll(config.getRules().getTargetCollections());
                }
            }

            if (targetFiles.isEmpty()) {
                if (Files.isRegularFile(effectiveSourceDir) && effectiveSourceDir.toString().endsWith(".json")) {
                    targetFiles.add(effectiveSourceDir.getFileName().toString());
                } else if (Files.isDirectory(effectiveSourceDir)) {
                    try (Stream<Path> stream = Files.walk(effectiveSourceDir, 3)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".json"))
                                .forEach(p -> targetFiles.add(effectiveSourceDir.relativize(p).toString()));
                    }
                }
            }

            int totalFiles = targetFiles.size();
            progress.totalFiles.set(totalFiles);
            progress.fileProgress.clear();

            for (String fName : targetFiles) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", fName); // Compatible with UI
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "PENDING");
                progress.fileProgress.add(entry);
            }

            Path tempBundlePath = destDir.resolve("secure-json-export.json");

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempBundlePath), 128 * 1024);
                 DataOutputStream dataOut = new DataOutputStream(out)) {

                dataOut.write(MAGIC_HEADER);

                for (String relFileName : targetFiles) {
                    for (Map<String, Object> fp : progress.fileProgress) {
                        if (relFileName.equals(fp.get("tableName"))) {
                            fp.put("status", "RUNNING");
                            break;
                        }
                    }

                    Path actualSourceFile = Files.isDirectory(sourceDir) ? sourceDir.resolve(relFileName) : sourceDir;
                    int nodeCount = exportJsonFile(dataOut, actualSourceFile, relFileName, config, saltKey, progress);

                    processedCount++;
                    progress.processedFiles.set(processedCount);
                    progress.completedFiles.add(relFileName);

                    for (Map<String, Object> fp : progress.fileProgress) {
                        if (relFileName.equals(fp.get("tableName"))) {
                            fp.put("status", "COMPLETED");
                            fp.put("readCount", nodeCount);
                            fp.put("writeCount", nodeCount);
                            break;
                        }
                    }
                }

                dataOut.writeByte(MARKER_EOF);
                dataOut.flush();
            }

            // Encrypt output bundle file
            Path finalExportFile = tempBundlePath;
            if (saltKey != null && !saltKey.trim().isEmpty()) {
                Path encFilePath = destDir.resolve("secure-json-export.json.enc");
                encryptFileWithSalt(tempBundlePath, encFilePath, saltKey);
                try {
                    Files.deleteIfExists(tempBundlePath);
                } catch (Exception ex) {
                    log.warn("Could not remove unencrypted temp JSON bundle: {}", ex.getMessage());
                }
                finalExportFile = encFilePath;
                log.info("JSON Secure Export encrypted successfully: {}", encFilePath);
            }

            // Upload to destination COS bucket if destination is COS
            JsonSecureExportConfig.StorageConfig destConfig = config.getDest() != null ? config.getDest() : config.getStorage();
            if (destConfig != null && "cos".equalsIgnoreCase(destConfig.getType())) {
                Long dstCosId = destConfig.getCosId() != null ? destConfig.getCosId() : destConfig.getId();
                if (dstCosId != null && cosConnectionService != null && ibmCosService != null) {
                    try {
                        CosConnection dstCos = cosConnectionService.getConnection(dstCosId);
                        if (dstCos != null && !"Local".equalsIgnoreCase(dstCos.getStorageType())) {
                            if (Files.exists(finalExportFile)) {
                                ibmCosService.uploadFile(dstCos, finalExportFile.getFileName().toString(), finalExportFile);
                                log.info("Uploaded secure JSON export to COS bucket {}: {}", ibmCosService.getEffectiveBucketName(dstCos), finalExportFile.getFileName());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to upload secure JSON export to COS bucket", e);
                    }
                }
            }

            progress.status = "COMPLETED";
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "COMPLETED", null);
            log.info("JSON secure export completed for execution ID: {}", executionId);

        } catch (Exception e) {
            log.error("JSON secure export failed for execution ID: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
        }

        return executionId;
    }

    private int exportJsonFile(DataOutputStream dataOut, Path sourceFile, String relFileName,
                               JsonSecureExportConfig config, String saltKey, JsonSecureExportProgress progress) throws IOException {
        byte[] nameBytes = relFileName.getBytes(StandardCharsets.UTF_8);
        dataOut.writeByte(MARKER_FILE_START);
        dataOut.writeShort(nameBytes.length);
        dataOut.write(nameBytes);

        JsonNode rootNode = objectMapper.readTree(sourceFile.toFile());

        int count = maskJsonTree(rootNode, "", relFileName, config, saltKey);
        if (progress != null) {
            progress.totalRecords.addAndGet(count);
        }

        byte[] jsonBytes = objectMapper.writeValueAsBytes(rootNode);

        dataOut.writeByte(MARKER_FILE_CONTENT);
        dataOut.writeInt(jsonBytes.length);
        dataOut.write(jsonBytes);

        dataOut.writeByte(MARKER_FILE_END);
        return count;
    }

    private int maskJsonTree(JsonNode node, String currentPath, String fileName, JsonSecureExportConfig config, String saltKey) {
        if (node == null || config.getRules() == null) return 0;
        int count = 0;

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            while (fields.hasNext()) {
                entries.add(fields.next());
            }

            for (Map.Entry<String, JsonNode> entry : entries) {
                String fieldName = entry.getKey();
                String fieldPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;
                JsonNode val = entry.getValue();

                if (val.isValueNode()) {
                    String ruleType = getMatchingRuleType(fileName, fieldPath, config.getRules());
                    if (ruleType != null) {
                        applyMaskToNode(objectNode, fieldName, val, fieldPath, ruleType, saltKey);
                        count++;
                    }
                } else if (val.isContainerNode()) {
                    count += maskJsonTree(val, fieldPath, fileName, config, saltKey);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode item = arrayNode.get(i);
                if (item.isValueNode()) {
                    String ruleType = getMatchingRuleType(fileName, currentPath, config.getRules());
                    if (ruleType != null) {
                        applyMaskToArrayItem(arrayNode, i, item, currentPath, ruleType, saltKey);
                        count++;
                    }
                } else {
                    count += maskJsonTree(item, currentPath, fileName, config, saltKey);
                }
            }
        }
        return count;
    }

    private String getMatchingRuleType(String fileName, String fieldPath, JsonSecureExportConfig.RulesConfig rules) {
        if (rules == null || fieldPath == null) return null;

        String fullQualifier = fileName + "." + fieldPath;

        // 1. Check dot-notated columns: maskingColumns (SFD)
        if (matchesField(rules.getMaskingColumns(), fullQualifier, fieldPath)) {
            return "SFD";
        }
        // 2. Check dot-notated columns: partialMaskingColumns (PMD)
        if (matchesField(rules.getPartialMaskingColumns(), fullQualifier, fieldPath)) {
            return "PMD";
        }
        // 3. Check dot-notated columns: constraintColumns / constraintFields (FPH)
        if (matchesField(rules.getConstraintColumns(), fullQualifier, fieldPath) ||
                matchesField(rules.getConstraintFields(), fullQualifier, fieldPath)) {
            return "FPH";
        }

        // 4. Check Map-based structures
        if (rules.getMaskingFields() != null && rules.getMaskingFields().containsKey(fileName)) {
            List<String> list = rules.getMaskingFields().get(fileName);
            if (list != null && list.contains(fieldPath)) return "SFD";
        }
        if (rules.getPartialMaskingFields() != null && rules.getPartialMaskingFields().containsKey(fileName)) {
            List<String> list = rules.getPartialMaskingFields().get(fileName);
            if (list != null && list.contains(fieldPath)) return "PMD";
        }

        return null;
    }

    private boolean matchesField(List<String> list, String fullQualifier, String fieldPath) {
        if (list == null || list.isEmpty()) return false;
        for (String item : list) {
            if (item.equalsIgnoreCase(fullQualifier) || item.equalsIgnoreCase(fieldPath)) {
                return true;
            }
        }
        return false;
    }

    private void applyMaskToNode(ObjectNode objectNode, String fieldName, JsonNode val, String fieldPath, String ruleType, String saltKey) {
        if ("PMD".equalsIgnoreCase(ruleType)) {
            String masked = applyPartialMasking(val.asText());
            objectNode.put(fieldName, masked);
        } else if ("SFD".equalsIgnoreCase(ruleType)) {
            String fakeVal = generateFakeData(fieldPath, val);
            if (val.isInt()) {
                try { objectNode.put(fieldName, Integer.parseInt(fakeVal)); } catch (Exception e) { objectNode.put(fieldName, fakeVal); }
            } else if (val.isLong()) {
                try { objectNode.put(fieldName, Long.parseLong(fakeVal)); } catch (Exception e) { objectNode.put(fieldName, fakeVal); }
            } else if (val.isDouble() || val.isFloat()) {
                try { objectNode.put(fieldName, Double.parseDouble(fakeVal)); } catch (Exception e) { objectNode.put(fieldName, fakeVal); }
            } else {
                objectNode.put(fieldName, fakeVal);
            }
        } else {
            // FPH (Format Preserving Encryption/Hashing)
            if (val.isTextual()) {
                Object masked = fpeService.encrypt(val.asText(), "string", saltKey);
                objectNode.put(fieldName, masked != null ? masked.toString() : val.asText());
            } else if (val.isInt()) {
                Object masked = fpeService.encrypt(val.asInt(), "integer", saltKey);
                if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).intValue());
                else objectNode.put(fieldName, masked.toString());
            } else if (val.isLong()) {
                Object masked = fpeService.encrypt(val.asLong(), "long", saltKey);
                if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).longValue());
                else objectNode.put(fieldName, masked.toString());
            } else if (val.isDouble()) {
                Object masked = fpeService.encrypt(val.asDouble(), "double", saltKey);
                if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).doubleValue());
                else objectNode.put(fieldName, masked.toString());
            } else {
                Object masked = fpeService.encrypt(val.asText(), "string", saltKey);
                objectNode.put(fieldName, masked != null ? masked.toString() : val.asText());
            }
        }
    }

    private void applyMaskToArrayItem(ArrayNode arrayNode, int index, JsonNode val, String fieldPath, String ruleType, String saltKey) {
        if ("PMD".equalsIgnoreCase(ruleType)) {
            String masked = applyPartialMasking(val.asText());
            arrayNode.set(index, arrayNode.textNode(masked));
        } else if ("SFD".equalsIgnoreCase(ruleType)) {
            String fakeVal = generateFakeData(fieldPath, val);
            arrayNode.set(index, arrayNode.textNode(fakeVal));
        } else {
            // FPH
            Object masked = fpeService.encrypt(val.asText(), "string", saltKey);
            arrayNode.set(index, arrayNode.textNode(masked != null ? masked.toString() : val.asText()));
        }
    }

    private String applyPartialMasking(String value) {
        if (value == null) return null;
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private String generateFakeData(String fieldPath, JsonNode originalNode) {
        String lower = (fieldPath != null ? fieldPath.toLowerCase() : "");
        if (lower.contains("name") || lower.contains("user")) {
            return faker.name().fullName();
        } else if (lower.contains("email")) {
            return faker.internet().emailAddress();
        } else if (lower.contains("phone") || lower.contains("mobile")) {
            return faker.phoneNumber().phoneNumber();
        } else if (lower.contains("city")) {
            return faker.address().city();
        } else if (lower.contains("street") || lower.contains("address")) {
            return faker.address().fullAddress();
        } else if (lower.contains("country")) {
            return faker.address().country();
        } else if (lower.contains("zip") || lower.contains("postal")) {
            return faker.address().zipCode();
        } else if (lower.contains("company")) {
            return faker.company().name();
        } else if (originalNode != null && originalNode.isNumber()) {
            return String.valueOf(faker.number().numberBetween(1000, 99999));
        }
        return faker.lorem().word();
    }

    public void encryptFileWithSalt(Path sourceFile, Path targetEncryptedFile, String saltKey) throws Exception {
        if (!Files.exists(sourceFile)) {
            log.warn("Source file does not exist for encryption: {}", sourceFile);
            return;
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(saltKey.trim().getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (InputStream in = Files.newInputStream(sourceFile);
             OutputStream out = Files.newOutputStream(targetEncryptedFile)) {
            out.write(iv);
            try (CipherOutputStream cipherOut = new CipherOutputStream(out, cipher)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    cipherOut.write(buffer, 0, bytesRead);
                }
                cipherOut.flush();
            }
        }
    }

    private Path resolveStoragePath(JsonSecureExportConfig.StorageConfig storage) {
        if (storage != null) {
            if ("cos".equalsIgnoreCase(storage.getType()) && storage.getCosId() != null) {
                CosConnection cos = cosConnectionService.getConnection(storage.getCosId());
                if (cos != null && cos.getStorageLocation() != null) {
                    return Paths.get(cos.getStorageLocation());
                }
            }
            if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
                return Paths.get(storage.getPath().trim());
            }
        }
        return Paths.get(DEFAULT_EXPORT_DIR);
    }

    private void saveJobRecord(String executionId, JsonSecureExportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            JsonSecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new JsonSecureExportJob();
                job.setExecutionId(executionId);
                job.setJobName(config.getJobName() != null ? config.getJobName() : "JSON Secure Export");
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            job.setConfigDetails(objectMapper.writeValueAsString(config));
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Failed to save JsonSecureExportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        JsonSecureExportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        JsonSecureExportJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) {
            return Map.of("status", "NOT_FOUND");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("jobName", job.getJobName());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("startTime", job.getCreatedAt());
        map.put("completedTime", job.getCompletedAt() != null ? job.getCompletedAt() : 0);
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonSecureExportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", job.getExecutionId());
            map.put("jobName", job.getJobName());
            map.put("status", job.getStatus());
            map.put("startTime", job.getCreatedAt());
            map.put("completedTime", job.getCompletedAt());
            list.add(map);
        }
        return list;
    }
}
