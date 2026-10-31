package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.CsvSecureExportConfig;
import com.enterprise.seedm.model.CsvSecureExportJob;
import com.enterprise.seedm.repository.CsvSecureExportJobRepository;
import com.enterprise.seedm.util.BackupFileUtils;
import com.enterprise.seedm.util.CsvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class CsvSecureExportService {

    @Autowired(required = false)
    private CosConnectionService cosConnectionService;

    @Autowired(required = false)
    private IbmCosService ibmCosService;

    @Autowired(required = false)
    private FormatPreservingEncryptionService fpeService;

    @Autowired(required = false)
    private CsvSecureExportJobRepository jobRepository;

    private final ObjectMapper objectMapper;
    private final DataMaskingService dataMaskingService;
    private final net.datafaker.Faker faker = new net.datafaker.Faker();

    @Value("${seedm.export.split-file-size:${seedm.export.max-file-size:}}")
    private String configuredSplitFileSize;

    public void setSplitFileSize(String splitFileSize) {
        this.configuredSplitFileSize = splitFileSize;
    }

    public long resolveMaxFileSizeBytes() {
        if (configuredSplitFileSize != null && !configuredSplitFileSize.trim().isEmpty()) {
            try {
                return BackupFileUtils.parseDataSizeToBytes(configuredSplitFileSize);
            } catch (Exception e) {
                log.warn("Failed to parse configured split file size: {}", configuredSplitFileSize, e);
            }
        }
        return BackupFileUtils.getMaxFileSizeBytes();
    }

    public CsvSecureExportService(CosConnectionService cosConnectionService, IbmCosService ibmCosService, FormatPreservingEncryptionService fpeService, CsvSecureExportJobRepository jobRepository, ObjectMapper objectMapper) {
        this(cosConnectionService, ibmCosService, fpeService, jobRepository, objectMapper, null);
    }

    @Autowired
    public CsvSecureExportService(CosConnectionService cosConnectionService, IbmCosService ibmCosService, FormatPreservingEncryptionService fpeService, CsvSecureExportJobRepository jobRepository, ObjectMapper objectMapper, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService) {
        this.cosConnectionService = cosConnectionService;
        this.ibmCosService = ibmCosService;
        this.fpeService = fpeService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.dataMaskingService = dataMaskingService;
    }

    private static final String DEFAULT_EXPORT_DIR = "secure-export";
    private static final byte[] MAGIC_HEADER = "CSVBUNDLE1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_FILE_START = 0x01;
    private static final byte MARKER_FILE_CONTENT = 0x02;
    private static final byte MARKER_FILE_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    public static class CsvSecureExportProgress {
        public final AtomicInteger totalFiles = new AtomicInteger(0);
        public final AtomicInteger processedFiles = new AtomicInteger(0);
        public final AtomicLong totalRecords = new AtomicLong(0);
        public final List<Map<String, Object>> fileProgress = Collections.synchronizedList(new ArrayList<>());
        public final List<String> completedFiles = Collections.synchronizedList(new ArrayList<>());
        public volatile String fileEncryptionKey = null;
        public volatile String status = "PENDING";
        public volatile String errorMessage = null;
        public volatile long startTime = System.currentTimeMillis();
        public volatile long completedTime = 0;
    }

    private final Map<String, CsvSecureExportProgress> progressMap = new ConcurrentHashMap<>();

    public Path resolveStoragePath(CsvSecureExportConfig.StorageConfig storage) {
        if (storage == null) return Paths.get("./data");
        Long cosId = storage.getCosId() != null ? storage.getCosId() : storage.getId();
        if (cosId != null && cosConnectionService != null) {
            CosConnection conn = cosConnectionService.getConnection(cosId);
            if (conn != null && "Local".equalsIgnoreCase(conn.getStorageType()) && conn.getStorageLocation() != null) {
                return Paths.get(conn.getStorageLocation());
            }
        }
        if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
            return Paths.get(storage.getPath().trim());
        }
        if (storage.getDestDir() != null && !storage.getDestDir().trim().isEmpty()) {
            return Paths.get(storage.getDestDir().trim());
        }
        return Paths.get("./data");
    }

    public Map<String, Object> scanSourceFiles(CsvSecureExportConfig.StorageConfig source) {
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
                                    return name != null && name.toLowerCase().endsWith(".csv");
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
                if (Files.isRegularFile(srcPath) && srcPath.toString().toLowerCase().endsWith(".csv")) {
                    Map<String, Object> fInfo = new HashMap<>();
                    fInfo.put("name", srcPath.getFileName().toString());
                    fInfo.put("sizeBytes", Files.size(srcPath));
                    filesList.add(fInfo);
                } else if (Files.isDirectory(srcPath)) {
                    try (Stream<Path> stream = Files.walk(srcPath, 3)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                                .forEach(p -> {
                                    Map<String, Object> fInfo = new HashMap<>();
                                    fInfo.put("name", srcPath.relativize(p).toString().replace('\\', '/'));
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
            log.error("Failed to scan CSV source files", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public Map<String, Object> sampleCsvHeaders(CsvSecureExportConfig.StorageConfig source, String fileName) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (source != null && "cos".equalsIgnoreCase(source.getType())) {
                Long cosId = source.getCosId() != null ? source.getCosId() : source.getId();
                if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection cos = cosConnectionService.getConnection(cosId);
                    if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                        Set<String> headers = ibmCosService.extractCsvHeaders(cos, fileName);
                        response.put("status", "SUCCESS");
                        response.put("fileName", fileName);
                        response.put("headers", new ArrayList<>(headers));
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

            List<String> headers = CsvUtils.readHeader(filePath);
            response.put("status", "SUCCESS");
            response.put("fileName", fileName);
            response.put("headers", headers);
            return response;
        } catch (Exception e) {
            log.error("Failed to sample CSV headers for {}", fileName, e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public String processCsvExport(String executionId, CsvSecureExportConfig config) {
        CsvSecureExportProgress progress = progressMap.computeIfAbsent(executionId, k -> new CsvSecureExportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        String fileEncryptionKey = config.getFileEncryptionKey();
        String saltKey = (config.getRules() != null) ? config.getRules().getMaskingKey() : null;
        if (saltKey == null || saltKey.trim().isEmpty()) {
            saltKey = "DefaultCsvExportSaltKey2026!";
        }
        progress.fileEncryptionKey = (fileEncryptionKey != null && !fileEncryptionKey.trim().isEmpty()) ? fileEncryptionKey : saltKey;

        saveJobRecord(executionId, config, "RUNNING", null);

        try {
            Path sourceDir = resolveSourceDirectory(config);
            Path destDir = resolveDestDirectory(config);
            Files.createDirectories(destDir);

            List<String> targetFiles = (config.getRules() != null && config.getRules().getTargetFiles() != null && !config.getRules().getTargetFiles().isEmpty())
                    ? config.getRules().getTargetFiles()
                    : ((config.getRules() != null && config.getRules().getTargetTables() != null && !config.getRules().getTargetTables().isEmpty())
                    ? config.getRules().getTargetTables()
                    : discoverSourceFiles(sourceDir));

            if (targetFiles.isEmpty()) {
                throw new IllegalStateException("No CSV files discovered to export in: " + sourceDir);
            }

            progress.totalFiles.set(targetFiles.size());
            progress.fileProgress.clear();
            for (String relFile : targetFiles) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", relFile);
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "PENDING");
                progress.fileProgress.add(entry);
            }

            String encryptKey = progress.fileEncryptionKey;
            long maxFileSizeBytes = resolveMaxFileSizeBytes();
            List<Path> finalExportFiles;

            try (CsvBundleRotator rotator = new CsvBundleRotator(destDir, "secure-csv-export", encryptKey, maxFileSizeBytes)) {
                for (String relFileName : targetFiles) {
                    for (Map<String, Object> fp : progress.fileProgress) {
                        if (relFileName.equals(fp.get("tableName"))) {
                            fp.put("status", "RUNNING");
                            break;
                        }
                    }

                    Path actualSourceFile = Files.isDirectory(sourceDir) ? sourceDir.resolve(relFileName) : sourceDir;
                    int rowCount = exportCsvFile(rotator, actualSourceFile, relFileName, config, saltKey, progress);

                    progress.processedFiles.incrementAndGet();
                    progress.completedFiles.add(relFileName);

                    for (Map<String, Object> fp : progress.fileProgress) {
                        if (relFileName.equals(fp.get("tableName"))) {
                            fp.put("status", "COMPLETED");
                            fp.put("readCount", rowCount);
                            fp.put("writeCount", rowCount);
                            break;
                        }
                    }
                }
                finalExportFiles = rotator.closeAndGetFiles();
            }

            // Upload to COS if destination is COS
            boolean isCosDest = isCosDestination(config);
            if (isCosDest) {
                CosConnection cos = getDestinationCos(config);
                if (cos != null && ibmCosService != null) {
                    String prefix = getDestinationPrefix(config);
                    for (Path exportedPart : finalExportFiles) {
                        String objectKey = prefix.isEmpty() ? exportedPart.getFileName().toString() : prefix + "/" + exportedPart.getFileName().toString();
                        ibmCosService.uploadFile(cos, objectKey, exportedPart);
                        Files.deleteIfExists(exportedPart);
                    }
                }
            }

            progress.status = "COMPLETED";
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "COMPLETED", null);
            log.info("CSV secure export completed successfully with {} backup file(s) for execution ID: {}", finalExportFiles.size(), executionId);

        } catch (Exception e) {
            log.error("CSV secure export failed for execution ID: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
        }

        return executionId;
    }

    public static class CsvBundleRotator implements AutoCloseable {
        private final Path destDir;
        private final String baseName;
        private final String encryptKey;
        private final long maxSizeBytes;
        private final List<Path> exportedFiles = new ArrayList<>();

        private int partNumber = 1;
        private long currentFileBytes = 0;
        private Path currentTempPath;
        private DataOutputStream currentDataOut;

        public CsvBundleRotator(Path destDir, String baseName, String encryptKey, long maxSizeBytes) throws IOException {
            this.destDir = destDir;
            this.baseName = baseName;
            this.encryptKey = encryptKey;
            this.maxSizeBytes = maxSizeBytes;
            openPart(1, false);
        }

        private void openPart(int partNum, boolean isRotated) throws IOException {
            this.partNumber = partNum;
            String tempFileName = isRotated ? (baseName + "-part" + partNum + ".csv") : (baseName + ".csv");
            this.currentTempPath = destDir.resolve(tempFileName);
            this.currentDataOut = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(currentTempPath), 128 * 1024));
            this.currentDataOut.write(MAGIC_HEADER);
            this.currentFileBytes = MAGIC_HEADER.length;
        }

        public void writeCsvFile(String relFileName, byte[] csvBytes) throws Exception {
            byte[] nameBytes = relFileName.getBytes(StandardCharsets.UTF_8);
            int recordSize = 1 + 2 + nameBytes.length + 1 + 4 + csvBytes.length + 1;
            if (currentFileBytes > MAGIC_HEADER.length && (currentFileBytes + recordSize >= maxSizeBytes)) {
                rotate();
            }

            currentDataOut.writeByte(MARKER_FILE_START);
            currentDataOut.writeShort(nameBytes.length);
            currentDataOut.write(nameBytes);

            currentDataOut.writeByte(MARKER_FILE_CONTENT);
            currentDataOut.writeInt(csvBytes.length);
            currentDataOut.write(csvBytes);

            currentDataOut.writeByte(MARKER_FILE_END);
            currentFileBytes += recordSize;
        }

        private void rotate() throws Exception {
            currentDataOut.writeByte(MARKER_EOF);
            currentDataOut.flush();
            currentDataOut.close();

            Path targetFile;
            if (partNumber == 1) {
                targetFile = destDir.resolve(baseName + "-part1.csv.enc");
            } else {
                targetFile = destDir.resolve(baseName + "-part" + partNumber + ".csv.enc");
            }

            if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                BackupFileUtils.encryptFileWithSalt(currentTempPath, targetFile, encryptKey);
                try {
                    Files.deleteIfExists(currentTempPath);
                } catch (Exception ex) {
                    log.warn("Could not delete temp CSV bundle: {}", ex.getMessage());
                }
                exportedFiles.add(targetFile);
            } else {
                Path plainTarget = destDir.resolve(baseName + "-part" + partNumber + ".csv");
                if (!currentTempPath.equals(plainTarget)) {
                    Files.move(currentTempPath, plainTarget, StandardCopyOption.REPLACE_EXISTING);
                    exportedFiles.add(plainTarget);
                } else {
                    exportedFiles.add(currentTempPath);
                }
            }

            openPart(partNumber + 1, true);
        }

        @Override
        public void close() throws Exception {
            if (currentDataOut != null) {
                currentDataOut.writeByte(MARKER_EOF);
                currentDataOut.flush();
                currentDataOut.close();

                Path targetFile;
                if (partNumber == 1 && exportedFiles.isEmpty()) {
                    targetFile = destDir.resolve(baseName + ".csv.enc");
                } else {
                    targetFile = destDir.resolve(baseName + "-part" + partNumber + ".csv.enc");
                }

                if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                    BackupFileUtils.encryptFileWithSalt(currentTempPath, targetFile, encryptKey);
                    try {
                        Files.deleteIfExists(currentTempPath);
                    } catch (Exception ex) {
                        log.warn("Could not delete temp CSV bundle: {}", ex.getMessage());
                    }
                    exportedFiles.add(targetFile);
                } else {
                    Path plainTarget = destDir.resolve((partNumber == 1 && exportedFiles.isEmpty()) ? (baseName + ".csv") : (baseName + "-part" + partNumber + ".csv"));
                    if (!currentTempPath.equals(plainTarget)) {
                        Files.move(currentTempPath, plainTarget, StandardCopyOption.REPLACE_EXISTING);
                        exportedFiles.add(plainTarget);
                    } else {
                        exportedFiles.add(currentTempPath);
                    }
                }
                currentDataOut = null;
            }
        }

        public List<Path> closeAndGetFiles() throws Exception {
            close();
            return new ArrayList<>(exportedFiles);
        }
    }

    private int exportCsvFile(CsvBundleRotator rotator, Path sourceFile, String relFileName,
                              CsvSecureExportConfig config, String saltKey, CsvSecureExportProgress progress) throws Exception {
        if (!Files.exists(sourceFile)) {
            log.warn("Source CSV file not found: {}", sourceFile);
            return 0;
        }

        List<String> header = CsvUtils.readHeader(sourceFile);
        if (header.isEmpty()) {
            return 0;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int rowCount = 0;
        try (BufferedReader br = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            bw.write(headerLine);
            bw.newLine();

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> values = CsvUtils.parseLine(line);
                List<String> maskedValues = new ArrayList<>(values.size());

                for (int i = 0; i < values.size(); i++) {
                    String col = (i < header.size()) ? header.get(i).trim() : ("col_" + i);
                    String val = values.get(i);
                    String masked = maskValue(relFileName, col, val, config, saltKey);
                    maskedValues.add(masked);
                }

                bw.write(CsvUtils.formatLine(maskedValues));
                bw.newLine();
                rowCount++;
            }
        }

        rotator.writeCsvFile(relFileName, baos.toByteArray());
        progress.totalRecords.addAndGet(rowCount);
        return rowCount;
    }

    private String maskValue(String fileName, String colName, String value, CsvSecureExportConfig config, String saltKey) {
        if (value == null || config == null || config.getRules() == null) {
            return value;
        }
        CsvSecureExportConfig.RulesConfig rules = config.getRules();
        String fullPath = fileName + "." + colName;

        if (rules.getNullifyColumns() != null && (rules.getNullifyColumns().contains(fullPath) || rules.getNullifyColumns().contains(colName))) {
            return "";
        }
        if (rules.getDefaultValues() != null) {
            if (rules.getDefaultValues().containsKey(fullPath)) return rules.getDefaultValues().get(fullPath);
            if (rules.getDefaultValues().containsKey(colName)) return rules.getDefaultValues().get(colName);
        }
        if (rules.getCustomFormats() != null) {
            String cmf = rules.getCustomFormats().get(fullPath);
            if (cmf == null) cmf = rules.getCustomFormats().get(colName);
            if (cmf != null) {
                StringBuilder sb = new StringBuilder();
                for (char c : cmf.toCharArray()) {
                    if (c == '#') sb.append(faker.number().randomDigit());
                    else if (c == 'X') sb.append((char) ('A' + faker.number().numberBetween(0, 26)));
                    else sb.append(c);
                }
                return sb.toString();
            }
        }
        boolean isPartial = (rules.getPartialMaskingColumns() != null && (rules.getPartialMaskingColumns().contains(fullPath) || rules.getPartialMaskingColumns().contains(colName)))
                || (rules.getPartialMaskingFields() != null && (
                        (rules.getPartialMaskingFields().containsKey(fileName) && rules.getPartialMaskingFields().get(fileName).contains(colName))
                        || (rules.getPartialMaskingFields().containsKey("*") && rules.getPartialMaskingFields().get("*").contains(colName))
                   ));
        if (isPartial) {
            if (value.length() <= 3) return "*".repeat(value.length());
            int keep = Math.min(2, value.length() / 4);
            return value.substring(0, keep) + "*".repeat(value.length() - keep * 2) + value.substring(value.length() - keep);
        }
        if ((rules.getConstraintColumns() != null && (rules.getConstraintColumns().contains(fullPath) || rules.getConstraintColumns().contains(colName))) ||
                (rules.getConstraintFields() != null && (rules.getConstraintFields().contains(fullPath) || rules.getConstraintFields().contains(colName)))) {
            String key = (rules.getMaskingKey() != null && !rules.getMaskingKey().trim().isEmpty()) ? rules.getMaskingKey() : saltKey;
            try {
                if (fpeService != null) {
                    Object enc = fpeService.encrypt(value, "VARCHAR", key);
                    if (enc != null) return enc.toString();
                }
            } catch (Exception ignored) {}
            return "ENC_" + Math.abs(value.hashCode() ^ key.hashCode());
        }
        boolean isMasking = (rules.getMaskingColumns() != null && (rules.getMaskingColumns().contains(fullPath) || rules.getMaskingColumns().contains(colName)))
                || (rules.getMaskingFields() != null && (
                        (rules.getMaskingFields().containsKey(fileName) && rules.getMaskingFields().get(fileName).contains(colName))
                        || (rules.getMaskingFields().containsKey("*") && rules.getMaskingFields().get("*").contains(colName))
                   ));
        if (isMasking) {
            String lower = colName.toLowerCase();
            if (lower.contains("email")) return faker.internet().emailAddress();
            if (lower.contains("name")) return faker.name().fullName();
            if (lower.contains("phone")) return faker.phoneNumber().cellPhone();
            if (lower.contains("address")) return faker.address().streetAddress();
            if (lower.contains("city")) return faker.address().city();
            return faker.lorem().word();
        }
        return value;
    }

    private Path resolveSourceDirectory(CsvSecureExportConfig config) {
        if (config.getSource() != null) {
            if (config.getSource().getPath() != null && !config.getSource().getPath().trim().isEmpty()) {
                return Paths.get(config.getSource().getPath().trim());
            }
            if (config.getSource().getDestDir() != null && !config.getSource().getDestDir().trim().isEmpty()) {
                return Paths.get(config.getSource().getDestDir().trim());
            }
        }
        if (config.getStorage() != null && config.getStorage().getPath() != null) {
            return Paths.get(config.getStorage().getPath().trim());
        }
        return Paths.get("./data");
    }

    private Path resolveDestDirectory(CsvSecureExportConfig config) {
        if (config.getDest() != null && config.getDest().getPath() != null && !config.getDest().getPath().trim().isEmpty()) {
            return Paths.get(config.getDest().getPath().trim());
        }
        return Paths.get(DEFAULT_EXPORT_DIR);
    }

    private boolean isCosDestination(CsvSecureExportConfig config) {
        if (config.getDest() != null && "cos".equalsIgnoreCase(config.getDest().getType())) return true;
        if (config.getStorage() != null && "cos".equalsIgnoreCase(config.getStorage().getType())) return true;
        return false;
    }

    private CosConnection getDestinationCos(CsvSecureExportConfig config) {
        if (cosConnectionService == null) return null;
        Long cosId = null;
        if (config.getDest() != null) cosId = config.getDest().getCosId() != null ? config.getDest().getCosId() : config.getDest().getId();
        if (cosId == null && config.getStorage() != null) cosId = config.getStorage().getCosId() != null ? config.getStorage().getCosId() : config.getStorage().getId();
        return cosId != null ? cosConnectionService.getConnection(cosId) : null;
    }

    private String getDestinationPrefix(CsvSecureExportConfig config) {
        if (config.getDest() != null && config.getDest().getPath() != null) return config.getDest().getPath();
        return "secure-csv-export";
    }

    private List<String> discoverSourceFiles(Path sourceDir) throws IOException {
        if (!Files.exists(sourceDir)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().toLowerCase().endsWith(".csv")) {
                    result.add(sourceDir.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    public Map<String, Object> getProgress(String executionId) {
        CsvSecureExportProgress progress = progressMap.get(executionId);
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
        CsvSecureExportJob job = jobRepository.findByExecutionId(executionId);
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

        for (Map.Entry<String, CsvSecureExportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().status);
            execMap.put("startTime", entry.getValue().startTime);
            executions.add(execMap);
            seenIds.add(entry.getKey());
        }

        if (jobRepository != null) {
            for (CsvSecureExportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
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

    private void saveJobRecord(String executionId, CsvSecureExportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            CsvSecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new CsvSecureExportJob();
                job.setExecutionId(executionId);
                job.setJobName((config != null && config.getJobName() != null) ? config.getJobName() : executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            CsvSecureExportProgress progress = progressMap.get(executionId);
            if (progress != null) {
                job.setFileEncryptionKey(progress.fileEncryptionKey);
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
            log.error("Failed to save CsvSecureExportJob", e);
        }
    }
}
