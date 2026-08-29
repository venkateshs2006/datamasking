package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.JsonSecureImportConfig;
import com.enterprise.seedm.model.JsonSecureImportJob;
import com.enterprise.seedm.repository.JsonSecureImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonSecureImportService {

    private final CosConnectionService cosConnectionService;
    private final JsonSecureImportJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_IMPORT_DIR = "secure-export";
    private static final byte[] MAGIC_HEADER = "JSONBUNDLE1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_FILE_START = 0x01;
    private static final byte MARKER_FILE_CONTENT = 0x02;
    private static final byte MARKER_FILE_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    public static class JsonSecureImportProgress {
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

    private final Map<String, JsonSecureImportProgress> progressMap = new ConcurrentHashMap<>();

    public Map<String, Object> scanStorage(JsonSecureImportConfig.StorageConfig storage) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path dirPath = resolveStoragePath(storage);
            List<Map<String, Object>> filesList = new ArrayList<>();

            if (Files.exists(dirPath)) {
                if (Files.isRegularFile(dirPath)) {
                    Map<String, Object> fInfo = new HashMap<>();
                    fInfo.put("name", dirPath.getFileName().toString());
                    fInfo.put("sizeBytes", Files.size(dirPath));
                    fInfo.put("encrypted", dirPath.toString().endsWith(".enc"));
                    filesList.add(fInfo);
                } else if (Files.isDirectory(dirPath)) {
                    try (Stream<Path> stream = Files.list(dirPath)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".json.enc") || p.toString().endsWith(".enc"))
                                .forEach(p -> {
                                    Map<String, Object> fInfo = new HashMap<>();
                                    fInfo.put("name", p.getFileName().toString());
                                    try {
                                        fInfo.put("sizeBytes", Files.size(p));
                                    } catch (IOException e) {
                                        fInfo.put("sizeBytes", 0);
                                    }
                                    fInfo.put("encrypted", p.toString().endsWith(".enc"));
                                    filesList.add(fInfo);
                                });
                    }
                }
            }

            response.put("status", "SUCCESS");
            response.put("path", dirPath.toAbsolutePath().toString());
            response.put("files", filesList);
            response.put("fileCount", filesList.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to scan storage for JSON import", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public void validateSecretKey(JsonSecureImportConfig config, String secretKey) {
        Path sourceFile = resolveSourceFile(config);
        if (!Files.exists(sourceFile)) {
            throw new IllegalArgumentException("Encrypted JSON backup file not found: " + sourceFile);
        }

        if (!sourceFile.toString().endsWith(".enc")) {
            return; // Plain unencrypted bundle
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key is required for encrypted file");
        }

        try (InputStream in = Files.newInputStream(sourceFile)) {
            byte[] iv = new byte[16];
            int bytesRead = in.read(iv);
            if (bytesRead < 16) {
                throw new IllegalArgumentException("File is too small to be a valid encrypted archive");
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);

            byte[] encryptedBuffer = new byte[64];
            int readEnc = in.read(encryptedBuffer);
            if (readEnc > 0) {
                byte[] decrypted = cipher.update(encryptedBuffer, 0, readEnc);
                if (decrypted != null && decrypted.length >= MAGIC_HEADER.length) {
                    for (int i = 0; i < MAGIC_HEADER.length; i++) {
                        if (decrypted[i] != MAGIC_HEADER[i]) {
                            throw new IllegalArgumentException("Invalid secret key. Header verification failed.");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Secret key validation failed for file {}: {}", sourceFile, e.getMessage());
            throw new IllegalArgumentException("Invalid secret key. Decryption verification failed: " + e.getMessage());
        }
    }

    public String processJsonImport(String executionId, JsonSecureImportConfig config, String secretKey) {
        JsonSecureImportProgress progress = progressMap.computeIfAbsent(executionId, k -> new JsonSecureImportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        int processedFiles = 0;

        try {
            saveJobRecord(executionId, config, "RUNNING", null);

            Path sourceFile = resolveSourceFile(config);
            if (!Files.exists(sourceFile)) {
                throw new IllegalArgumentException("JSON import file not found: " + sourceFile);
            }

            Path destDir = resolveDestPath(config.getDest());
            Files.createDirectories(destDir);

            boolean isEncrypted = sourceFile.toString().endsWith(".enc");
            try (InputStream rawIn = new BufferedInputStream(Files.newInputStream(sourceFile), 128 * 1024)) {

                InputStream streamToRead = rawIn;

                if (isEncrypted) {
                    byte[] iv = new byte[16];
                    int read = rawIn.read(iv);
                    if (read < 16) {
                        throw new IllegalArgumentException("Encrypted file too short for IV");
                    }
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
                    SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
                    IvParameterSpec ivSpec = new IvParameterSpec(iv);

                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);
                    streamToRead = new CipherInputStream(rawIn, cipher);
                }

                try (DataInputStream dataIn = new DataInputStream(new BufferedInputStream(streamToRead, 128 * 1024))) {
                    // Check magic header
                    byte[] header = new byte[MAGIC_HEADER.length];
                    dataIn.readFully(header);
                    if (!Arrays.equals(header, MAGIC_HEADER)) {
                        throw new IllegalArgumentException("Invalid file format: MAGIC header mismatch");
                    }

                    boolean reading = true;
                    String currentFile = null;

                    while (reading) {
                        int marker = dataIn.read();
                        if (marker == -1 || marker == (MARKER_EOF & 0xFF)) {
                            reading = false;
                            break;
                        }

                        if (marker == MARKER_FILE_START) {
                            int nameLen = dataIn.readUnsignedShort();
                            byte[] nameBytes = new byte[nameLen];
                            dataIn.readFully(nameBytes);
                            currentFile = new String(nameBytes, StandardCharsets.UTF_8);

                            registerFileIfAbsent(progress, currentFile);
                        } else if (marker == MARKER_FILE_CONTENT) {
                            int contentLen = dataIn.readInt();
                            byte[] contentBytes = new byte[contentLen];
                            dataIn.readFully(contentBytes);

                            if (currentFile != null) {
                                Path targetFile = destDir.resolve(currentFile);
                                Files.createDirectories(targetFile.getParent());
                                Files.write(targetFile, contentBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                                progress.totalRecords.incrementAndGet();
                                incrementFileRecord(progress, currentFile);
                            }
                        } else if (marker == MARKER_FILE_END) {
                            if (currentFile != null) {
                                markFileComplete(progress, currentFile);
                                processedFiles++;
                                progress.processedFiles.set(processedFiles);
                                currentFile = null;
                            }
                        }
                    }

                    if (currentFile != null) {
                        markFileComplete(progress, currentFile);
                        processedFiles++;
                        progress.processedFiles.set(processedFiles);
                    }
                }

                progress.status = "COMPLETED";
                progress.completedTime = System.currentTimeMillis();
                saveJobRecord(executionId, config, "COMPLETED", null);
                log.info("JSON secure import completed for execution ID: {}", executionId);
            }
        } catch (Exception e) {
            log.error("JSON secure import failed for execution ID: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
        }

        return executionId;
    }

    private void registerFileIfAbsent(JsonSecureImportProgress progress, String fileName) {
        synchronized (progress.fileProgress) {
            boolean exists = false;
            for (Map<String, Object> fp : progress.fileProgress) {
                if (fileName.equals(fp.get("tableName"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", fileName);
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "RUNNING");
                progress.fileProgress.add(entry);
                progress.totalFiles.incrementAndGet();
            }
        }
    }

    private void incrementFileRecord(JsonSecureImportProgress progress, String fileName) {
        if (fileName == null) return;
        synchronized (progress.fileProgress) {
            for (Map<String, Object> fp : progress.fileProgress) {
                if (fileName.equals(fp.get("tableName"))) {
                    int r = (int) fp.getOrDefault("writeCount", 0) + 1;
                    fp.put("readCount", r);
                    fp.put("writeCount", r);
                    fp.put("status", "RUNNING");
                    break;
                }
            }
        }
    }

    private void markFileComplete(JsonSecureImportProgress progress, String fileName) {
        if (fileName == null) return;
        if (!progress.completedFiles.contains(fileName)) {
            progress.completedFiles.add(fileName);
        }
        synchronized (progress.fileProgress) {
            for (Map<String, Object> fp : progress.fileProgress) {
                if (fileName.equals(fp.get("tableName"))) {
                    cpPutCompleted(fp);
                    break;
                }
            }
        }
    }

    private void cpPutCompleted(Map<String, Object> fp) {
        fp.put("status", "COMPLETED");
        if ((int) fp.getOrDefault("writeCount", 0) == 0) {
            fp.put("readCount", 1);
            fp.put("writeCount", 1);
        }
    }

    private Path resolveStoragePath(JsonSecureImportConfig.StorageConfig storage) {
        if (storage != null) {
            if ("cos".equalsIgnoreCase(storage.getType()) && storage.getId() != null) {
                CosConnection cos = cosConnectionService.getConnection(storage.getId());
                if (cos != null && cos.getStorageLocation() != null) {
                    return Paths.get(cos.getStorageLocation());
                }
            }
            if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
                return Paths.get(storage.getPath().trim());
            }
        }
        return Paths.get(DEFAULT_IMPORT_DIR);
    }

    private Path resolveDestPath(JsonSecureImportConfig.DestinationConfig dest) {
        if (dest != null) {
            if ("cos".equalsIgnoreCase(dest.getType()) && dest.getCosId() != null) {
                CosConnection cos = cosConnectionService.getConnection(dest.getCosId());
                if (cos != null && cos.getStorageLocation() != null) {
                    return Paths.get(cos.getStorageLocation());
                }
            }
            if (dest.getPath() != null && !dest.getPath().trim().isEmpty()) {
                return Paths.get(dest.getPath().trim());
            }
        }
        return Paths.get("data-imported");
    }

    private Path resolveSourceFile(JsonSecureImportConfig config) {
        Path basePath = resolveStoragePath(config.getStorage());
        if (config.getStorage() != null && config.getStorage().getFileName() != null && !config.getStorage().getFileName().trim().isEmpty()) {
            String fileName = config.getStorage().getFileName().trim();
            if (Files.isDirectory(basePath)) {
                return basePath.resolve(fileName);
            }
            return Paths.get(fileName);
        }
        if (Files.isRegularFile(basePath)) {
            return basePath;
        }
        Path defaultEnc = basePath.resolve("secure-json-export.json.enc");
        if (Files.exists(defaultEnc)) return defaultEnc;
        return basePath.resolve("secure-json-export.json");
    }

    private void saveJobRecord(String executionId, JsonSecureImportConfig config, String status, String errorMessage) {
        try {
            JsonSecureImportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new JsonSecureImportJob();
                job.setExecutionId(executionId);
                job.setJobName(config.getJobName() != null ? config.getJobName() : "JSON Secure Import");
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
            log.error("Failed to save JsonSecureImportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        JsonSecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        JsonSecureImportJob job = jobRepository.findByExecutionId(executionId);
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
        for (JsonSecureImportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
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
