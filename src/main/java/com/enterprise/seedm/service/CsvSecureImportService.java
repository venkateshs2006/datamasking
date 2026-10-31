package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.CsvSecureImportConfig;
import com.enterprise.seedm.model.CsvSecureImportJob;
import com.enterprise.seedm.repository.CsvSecureImportJobRepository;
import com.enterprise.seedm.util.BackupFileUtils;
import com.enterprise.seedm.util.CsvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CsvSecureImportService {

    @Autowired(required = false)
    private CosConnectionService cosConnectionService;

    @Autowired(required = false)
    private IbmCosService ibmCosService;

    @Autowired(required = false)
    private CsvSecureImportJobRepository jobRepository;

    private final ObjectMapper objectMapper;

    public CsvSecureImportService(CosConnectionService cosConnectionService, IbmCosService ibmCosService, CsvSecureImportJobRepository jobRepository, ObjectMapper objectMapper) {
        this.cosConnectionService = cosConnectionService;
        this.ibmCosService = ibmCosService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static final byte[] MAGIC_HEADER = "CSVBUNDLE1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_FILE_START = 0x01;
    private static final byte MARKER_FILE_CONTENT = 0x02;
    private static final byte MARKER_FILE_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    public static class CsvSecureImportProgress {
        public final AtomicInteger totalFiles = new AtomicInteger(0);
        public final AtomicInteger processedFiles = new AtomicInteger(0);
        public final AtomicLong totalRecords = new AtomicLong(0);
        public final List<Map<String, Object>> fileProgress = Collections.synchronizedList(new ArrayList<>());
        public final List<String> completedFiles = Collections.synchronizedList(new ArrayList<>());
        public volatile String status = "PENDING";
        public volatile String errorMessage = null;
        public volatile long startTime = System.currentTimeMillis();
        public volatile long completedTime = 0;
    }

    private final Map<String, CsvSecureImportProgress> progressMap = new ConcurrentHashMap<>();

    public Path resolveStoragePath(CsvSecureImportConfig.StorageConfig storage) {
        if (storage == null) return Paths.get("./secure-csv-export");
        Long cosId = storage.getId() != null ? storage.getId() : storage.getCosId();
        if (cosId != null && cosConnectionService != null) {
            CosConnection conn = cosConnectionService.getConnection(cosId);
            if (conn != null && "Local".equalsIgnoreCase(conn.getStorageType()) && conn.getStorageLocation() != null) {
                return Paths.get(conn.getStorageLocation());
            }
        }
        if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
            return Paths.get(storage.getPath().trim());
        }
        return Paths.get("./secure-csv-export");
    }

    public Map<String, Object> scanStorage(CsvSecureImportConfig.StorageConfig storage) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (storage != null && "cos".equalsIgnoreCase(storage.getType())) {
                Long cosId = storage.getId() != null ? storage.getId() : storage.getCosId();
                if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection cos = cosConnectionService.getConnection(cosId);
                    if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                        List<Map<String, Object>> cosObjects = ibmCosService.listObjects(cos, null);
                        List<Map<String, Object>> filesList = cosObjects.stream()
                                .filter(o -> {
                                    String name = (String) o.get("name");
                                    return name != null && name.toLowerCase().endsWith(".csv.enc");
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

            Path dirPath = resolveStoragePath(storage);
            List<Map<String, Object>> filesList = new ArrayList<>();

            if (Files.exists(dirPath)) {
                if (Files.isRegularFile(dirPath)) {
                    String fname = dirPath.getFileName().toString();
                    if (fname.toLowerCase().endsWith(".csv.enc")) {
                        Map<String, Object> fInfo = new HashMap<>();
                        fInfo.put("name", fname);
                        fInfo.put("sizeBytes", Files.size(dirPath));
                        fInfo.put("encrypted", true);
                        filesList.add(fInfo);
                    }
                } else if (Files.isDirectory(dirPath)) {
                    try (var stream = Files.list(dirPath)) {
                        List<Path> filePaths = stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".csv.enc") || p.toString().toLowerCase().endsWith(".csv"))
                                .collect(Collectors.toList());
                        List<Path> sorted = BackupFileUtils.sortBackupFiles(filePaths);
                        sorted.forEach(p -> {
                            Map<String, Object> fInfo = new HashMap<>();
                            fInfo.put("name", p.getFileName().toString());
                            try {
                                fInfo.put("sizeBytes", Files.size(p));
                            } catch (IOException e) {
                                fInfo.put("sizeBytes", 0);
                            }
                            fInfo.put("encrypted", p.getFileName().toString().endsWith(".enc"));
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
            log.error("Failed to scan CSV storage", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public void validateSecretKey(CsvSecureImportConfig config, String secretKey) throws Exception {
        List<Path> sourceFiles = resolveSourceFiles(config);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No CSV backup files found to validate in source path.");
        }
        Path sampleFile = sourceFiles.get(0);
        try (InputStream in = BackupFileUtils.createDecryptedInputStream(sampleFile, secretKey)) {
            byte[] magic = new byte[MAGIC_HEADER.length];
            int read = in.read(magic);
            if (read < MAGIC_HEADER.length || !Arrays.equals(magic, MAGIC_HEADER)) {
                throw new IllegalArgumentException("Invalid secret key or corrupted CSV backup file: MAGIC header mismatch.");
            }
        }
    }

    public List<Path> resolveSourceFiles(CsvSecureImportConfig config) throws Exception {
        if (config == null || config.getStorage() == null) {
            return Collections.emptyList();
        }

        CsvSecureImportConfig.StorageConfig storage = config.getStorage();
        boolean isCos = "cos".equalsIgnoreCase(storage.getType());

        if (isCos) {
            if (cosConnectionService == null || ibmCosService == null) {
                throw new IllegalStateException("COS services are not configured.");
            }
            Long cosId = storage.getCosId();
            CosConnection cos = cosConnectionService.getConnection(cosId);
            if (cos == null) {
                throw new IllegalArgumentException("COS connection not found: " + cosId);
            }

            Path tempDownloadDir = Files.createTempDirectory("csv-import-cos-");
            String prefix = (storage.getPath() != null) ? storage.getPath().trim() : "";
            List<Map<String, Object>> objects = ibmCosService.listObjects(cos, prefix);

            List<Path> downloaded = new ArrayList<>();
            for (Map<String, Object> obj : objects) {
                String key = (String) obj.get("name");
                if (key != null && (key.endsWith(".csv.enc") || key.endsWith(".csv"))) {
                    String baseFileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    Path localFile = tempDownloadDir.resolve(baseFileName);
                    ibmCosService.downloadFile(cos, key, localFile);
                    downloaded.add(localFile);
                }
            }
            return BackupFileUtils.sortBackupFiles(downloaded);
        }

        String localPathStr = storage.getPath();
        if (localPathStr == null || localPathStr.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Path localPath = Paths.get(localPathStr.trim());
        if (Files.isRegularFile(localPath)) {
            if (storage.getFileName() != null && !storage.getFileName().trim().isEmpty()) {
                Path parent = localPath.getParent() != null ? localPath.getParent() : Paths.get(".");
                return BackupFileUtils.findBackupFiles(parent, "secure-csv-export", ".csv.enc");
            }
            return Collections.singletonList(localPath);
        }

        if (Files.isDirectory(localPath)) {
            List<Path> found = BackupFileUtils.findBackupFiles(localPath, "secure-csv-export", ".csv.enc");
            if (found.isEmpty()) {
                try (var s = Files.list(localPath)) {
                    found = s.filter(p -> p.getFileName().toString().endsWith(".csv.enc")).collect(Collectors.toList());
                }
            }
            return BackupFileUtils.sortBackupFiles(found);
        }

        return Collections.emptyList();
    }

    public String processCsvImport(String executionId, CsvSecureImportConfig config, String secretKey) {
        CsvSecureImportProgress progress = progressMap.computeIfAbsent(executionId, k -> new CsvSecureImportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        saveJobRecord(executionId, config, "RUNNING", null);

        try {
            List<Path> sourceFiles = resolveSourceFiles(config);
            if (sourceFiles.isEmpty()) {
                throw new IllegalStateException("No CSV backup files found to import.");
            }

            Path destDir = resolveDestDirectory(config);
            Files.createDirectories(destDir);

            for (Path sourceFile : sourceFiles) {
                try (InputStream rawIn = BackupFileUtils.createDecryptedInputStream(sourceFile, secretKey);
                     DataInputStream dataIn = new DataInputStream(new BufferedInputStream(rawIn, 128 * 1024))) {

                    byte[] header = new byte[MAGIC_HEADER.length];
                    dataIn.readFully(header);
                    if (!Arrays.equals(header, MAGIC_HEADER)) {
                        throw new IllegalArgumentException("Invalid file format: MAGIC header mismatch in file: " + sourceFile.getFileName());
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
                                if (targetFile.getParent() != null) {
                                    Files.createDirectories(targetFile.getParent());
                                }
                                Files.write(targetFile, contentBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                                long lineCount = 0;
                                try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))) {
                                    r.readLine(); // skip header
                                    while (r.readLine() != null) lineCount++;
                                }
                                progress.totalRecords.addAndGet(lineCount);
                                markFileCompleted(progress, currentFile, lineCount);
                            }
                        }
                    }
                }
            }

            progress.status = "COMPLETED";
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "COMPLETED", null);

        } catch (Exception e) {
            log.error("CSV secure import failed for execution ID: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
        }

        return executionId;
    }

    private void registerFileIfAbsent(CsvSecureImportProgress progress, String fileName) {
        boolean exists = progress.fileProgress.stream().anyMatch(m -> fileName.equals(m.get("tableName")));
        if (!exists) {
            Map<String, Object> map = new ConcurrentHashMap<>();
            map.put("tableName", fileName);
            map.put("readCount", 0);
            map.put("writeCount", 0);
            map.put("status", "RUNNING");
            progress.fileProgress.add(map);
            progress.totalFiles.incrementAndGet();
        }
    }

    private void markFileCompleted(CsvSecureImportProgress progress, String fileName, long records) {
        for (Map<String, Object> map : progress.fileProgress) {
            if (fileName.equals(map.get("tableName"))) {
                map.put("readCount", records);
                map.put("writeCount", records);
                map.put("status", "COMPLETED");
                break;
            }
        }
        if (!progress.completedFiles.contains(fileName)) {
            progress.completedFiles.add(fileName);
            progress.processedFiles.incrementAndGet();
        }
    }

    private Path resolveDestDirectory(CsvSecureImportConfig config) {
        if (config.getDest() != null && config.getDest().getPath() != null && !config.getDest().getPath().trim().isEmpty()) {
            return Paths.get(config.getDest().getPath().trim());
        }
        return Paths.get("./data/csv_import_dest");
    }

    public Map<String, Object> getProgress(String executionId) {
        CsvSecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("executionId", executionId);
            map.put("status", progress.status);
            map.put("errorMessage", progress.errorMessage);
            map.put("startTime", progress.startTime);
            map.put("endTime", progress.completedTime);
            map.put("totalFiles", progress.totalFiles.get());
            map.put("processedFiles", progress.processedFiles.get());
            map.put("totalTables", progress.totalFiles.get());
            map.put("processedTables", progress.processedFiles.get());
            map.put("totalRecords", progress.totalRecords.get());
            map.put("fileProgress", new ArrayList<>(progress.fileProgress));
            map.put("tableProgress", new ArrayList<>(progress.fileProgress));
            map.put("completedTables", new ArrayList<>(progress.completedFiles));
            return map;
        }

        if (jobRepository == null) return Map.of("status", "NOT_FOUND");
        CsvSecureImportJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) return Map.of("status", "NOT_FOUND");
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("jobName", job.getJobName());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("startTime", job.getCreatedAt());
        map.put("endTime", job.getCompletedAt());
        map.put("totalFiles", job.getTotalFiles());
        map.put("processedFiles", job.getProcessedFiles());
        map.put("totalTables", job.getTotalFiles());
        map.put("processedTables", job.getProcessedFiles());
        map.put("totalRecords", job.getTotalRecords());
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Map.Entry<String, CsvSecureImportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().status);
            execMap.put("startTime", entry.getValue().startTime);
            executions.add(execMap);
            seenIds.add(entry.getKey());
        }

        if (jobRepository != null) {
            for (CsvSecureImportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
                if (!seenIds.contains(job.getExecutionId())) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", job.getExecutionId());
                    execMap.put("status", job.getStatus());
                    execMap.put("startTime", job.getCreatedAt());
                    executions.add(execMap);
                    seenIds.add(job.getExecutionId());
                }
            }
        }
        return executions;
    }

    private void saveJobRecord(String executionId, CsvSecureImportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            CsvSecureImportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new CsvSecureImportJob();
                job.setExecutionId(executionId);
                job.setJobName((config != null && config.getJobName() != null) ? config.getJobName() : executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            CsvSecureImportProgress progress = progressMap.get(executionId);
            if (progress != null) {
                job.setTotalFiles(progress.totalFiles.get());
                job.setProcessedFiles(progress.processedFiles.get());
                job.setTotalRecords(progress.totalRecords.get());
                if (!progress.fileProgress.isEmpty()) {
                    job.setProgressDetails(objectMapper.writeValueAsString(progress.fileProgress));
                }
            }
            if (config != null) {
                job.setConfigDetails(objectMapper.writeValueAsString(config));
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            jobRepository.saveAndFlush(job);
        } catch (Exception e) {
            log.error("Failed to save CsvSecureImportJob", e);
        }
    }
}
