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
    private final FormatPreservingEncryptionService fpeService;
    private final JsonSecureExportJobRepository jobRepository;
    private final ObjectMapper objectMapper;

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
            Path destDir = resolveStoragePath(config.getDest());
            Files.createDirectories(destDir);

            if (!Files.exists(sourceDir)) {
                throw new IllegalArgumentException("Source JSON directory does not exist: " + sourceDir);
            }

            String saltKey = config.getRules() != null ? config.getRules().getMaskingKey() : null;

            List<String> targetFiles = new ArrayList<>();
            if (config.getRules() != null && config.getRules().getTargetFiles() != null && !config.getRules().getTargetFiles().isEmpty()) {
                targetFiles.addAll(config.getRules().getTargetFiles());
            } else {
                if (Files.isRegularFile(sourceDir) && sourceDir.toString().endsWith(".json")) {
                    targetFiles.add(sourceDir.getFileName().toString());
                } else if (Files.isDirectory(sourceDir)) {
                    try (Stream<Path> stream = Files.walk(sourceDir, 3)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".json"))
                                .forEach(p -> targetFiles.add(sourceDir.relativize(p).toString()));
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
            if (saltKey != null && !saltKey.trim().isEmpty()) {
                Path encFilePath = destDir.resolve("secure-json-export.json.enc");
                encryptFileWithSalt(tempBundlePath, encFilePath, saltKey);
                try {
                    Files.deleteIfExists(tempBundlePath);
                } catch (Exception ex) {
                    log.warn("Could not remove unencrypted temp JSON bundle: {}", ex.getMessage());
                }
                log.info("JSON Secure Export encrypted successfully: {}", encFilePath);
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
        List<String> fieldsToMask = null;
        if (config.getRules() != null && config.getRules().getMaskingFields() != null) {
            fieldsToMask = config.getRules().getMaskingFields().get(relFileName);
        }

        int count = maskJsonTree(rootNode, "", fieldsToMask, saltKey);
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

    private int maskJsonTree(JsonNode node, String currentPath, List<String> fieldsToMask, String saltKey) {
        if (node == null || fieldsToMask == null || fieldsToMask.isEmpty()) return 0;
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

                if (fieldsToMask.contains(fieldPath) && val.isValueNode()) {
                    if (val.isTextual()) {
                        Object masked = fpeService.encrypt(val.asText(), "string", saltKey);
                        objectNode.put(fieldName, masked != null ? masked.toString() : val.asText());
                        count++;
                    } else if (val.isInt()) {
                        Object masked = fpeService.encrypt(val.asInt(), "integer", saltKey);
                        if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).intValue());
                        else objectNode.put(fieldName, masked.toString());
                        count++;
                    } else if (val.isLong()) {
                        Object masked = fpeService.encrypt(val.asLong(), "long", saltKey);
                        if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).longValue());
                        else objectNode.put(fieldName, masked.toString());
                        count++;
                    } else if (val.isDouble()) {
                        Object masked = fpeService.encrypt(val.asDouble(), "double", saltKey);
                        if (masked instanceof Number) objectNode.put(fieldName, ((Number) masked).doubleValue());
                        else objectNode.put(fieldName, masked.toString());
                        count++;
                    }
                } else if (val.isContainerNode()) {
                    count += maskJsonTree(val, fieldPath, fieldsToMask, saltKey);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode item : arrayNode) {
                count += maskJsonTree(item, currentPath, fieldsToMask, saltKey);
            }
        }
        return count;
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
