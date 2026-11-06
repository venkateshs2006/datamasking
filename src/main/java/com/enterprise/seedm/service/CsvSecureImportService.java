package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.CsvSecureExportConfig;
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
    private FormatPreservingEncryptionService fpeService;

    @Autowired(required = false)
    private CsvSecureImportJobRepository jobRepository;

    private final ObjectMapper objectMapper;
    private final DataMaskingService dataMaskingService;
    private final net.datafaker.Faker faker = new net.datafaker.Faker();

    public CsvSecureImportService(CosConnectionService cosConnectionService, IbmCosService ibmCosService, CsvSecureImportJobRepository jobRepository, ObjectMapper objectMapper) {
        this(cosConnectionService, ibmCosService, jobRepository, objectMapper, null, null);
    }

    @Autowired
    public CsvSecureImportService(CosConnectionService cosConnectionService, IbmCosService ibmCosService, CsvSecureImportJobRepository jobRepository, ObjectMapper objectMapper, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService, @Autowired(required = false) FormatPreservingEncryptionService fpeService) {
        this.cosConnectionService = cosConnectionService;
        this.ibmCosService = ibmCosService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.dataMaskingService = dataMaskingService;
        this.fpeService = fpeService;
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
                                    return name != null && (name.toLowerCase().endsWith(".csv.enc") || name.toLowerCase().endsWith(".csv"));
                                })
                                .map(o -> {
                                    Map<String, Object> fInfo = new HashMap<>(o);
                                    String name = (String) o.get("name");
                                    fInfo.put("encrypted", name != null && name.toLowerCase().endsWith(".enc"));
                                    return fInfo;
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
                    if (fname.toLowerCase().endsWith(".csv.enc") || fname.toLowerCase().endsWith(".csv")) {
                        Map<String, Object> fInfo = new HashMap<>();
                        fInfo.put("name", fname);
                        fInfo.put("sizeBytes", Files.size(dirPath));
                        fInfo.put("encrypted", fname.toLowerCase().endsWith(".enc"));
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
        if (!sampleFile.getFileName().toString().endsWith(".enc")) {
            return; // Plain unencrypted file, no key required
        }
        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key is required for encrypted CSV package.");
        }
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
                    if (storage.getFileName() != null && !storage.getFileName().trim().isEmpty() && !storage.getFileName().equals(baseFileName)) {
                        continue;
                    }
                    Path localFile = tempDownloadDir.resolve(baseFileName);
                    ibmCosService.downloadFile(cos, key, localFile);
                    downloaded.add(localFile);
                }
            }
            return BackupFileUtils.sortBackupFiles(downloaded);
        }

        String localPathStr = storage.getPath();
        if (localPathStr == null || localPathStr.trim().isEmpty()) {
            Long id = storage.getId() != null ? storage.getId() : storage.getCosId();
            if (id != null && cosConnectionService != null) {
                CosConnection conn = cosConnectionService.getConnection(id);
                if (conn != null && conn.getStorageLocation() != null) {
                    localPathStr = conn.getStorageLocation();
                }
            }
        }
        if (localPathStr == null || localPathStr.trim().isEmpty()) {
            localPathStr = "./secure-csv-export";
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
            if (storage.getFileName() != null && !storage.getFileName().trim().isEmpty()) {
                Path direct = localPath.resolve(storage.getFileName().trim());
                if (Files.exists(direct)) {
                    if (storage.getFileName().contains("part")) {
                        return BackupFileUtils.findBackupFiles(localPath, "secure-csv-export", ".csv.enc");
                    }
                    return Collections.singletonList(direct);
                }
            }
            List<Path> found = BackupFileUtils.findBackupFiles(localPath, "secure-csv-export", ".csv.enc");
            if (found.isEmpty()) {
                try (var s = Files.list(localPath)) {
                    found = s.filter(p -> p.getFileName().toString().endsWith(".csv.enc") || p.getFileName().toString().endsWith(".csv")).collect(Collectors.toList());
                }
            }
            return BackupFileUtils.sortBackupFiles(found);
        }

        return Collections.emptyList();
    }

    public Map<String, Object> inspectSource(CsvSecureImportConfig.StorageConfig storage, String secretKey) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> files = new ArrayList<>();
            Map<String, List<String>> columnsMap = new LinkedHashMap<>();

            CsvSecureImportConfig tempConfig = new CsvSecureImportConfig();
            tempConfig.setStorage(storage);
            List<Path> resolvedFiles = resolveSourceFiles(tempConfig);

            if (resolvedFiles.isEmpty()) {
                response.put("status", "ERROR");
                response.put("message", "No CSV files or packages found in specified storage.");
                return response;
            }

            for (Path p : resolvedFiles) {
                String fname = p.getFileName().toString();
                boolean isEnc = fname.endsWith(".enc");

                if (isEnc) {
                    if (secretKey == null || secretKey.trim().isEmpty()) {
                        response.put("status", "KEY_REQUIRED");
                        response.put("encrypted", true);
                        response.put("package", fname);
                        response.put("message", "Secret key required to unlock and inspect encrypted CSV package.");
                        return response;
                    }

                    try (InputStream rawIn = BackupFileUtils.createDecryptedInputStream(p, secretKey);
                         DataInputStream dataIn = new DataInputStream(new BufferedInputStream(rawIn, 128 * 1024))) {

                        byte[] header = new byte[MAGIC_HEADER.length];
                        dataIn.readFully(header);
                        if (!Arrays.equals(header, MAGIC_HEADER)) {
                            throw new IllegalArgumentException("MAGIC header mismatch");
                        }

                        boolean reading = true;
                        while (reading) {
                            int marker = dataIn.read();
                            if (marker == -1 || marker == (MARKER_EOF & 0xFF)) {
                                break;
                            }
                            if (marker == MARKER_FILE_START) {
                                int nameLen = dataIn.readUnsignedShort();
                                byte[] nameBytes = new byte[nameLen];
                                dataIn.readFully(nameBytes);
                                String bundledFileName = new String(nameBytes, StandardCharsets.UTF_8);
                                if (!files.contains(bundledFileName)) {
                                    files.add(bundledFileName);
                                }

                                int contentMarker = dataIn.read();
                                if (contentMarker == MARKER_FILE_CONTENT) {
                                    int contentLen = dataIn.readInt();
                                    byte[] contentBytes = new byte[contentLen];
                                    dataIn.readFully(contentBytes);

                                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(contentBytes), StandardCharsets.UTF_8))) {
                                        String firstLine = br.readLine();
                                        if (firstLine != null && !firstLine.trim().isEmpty()) {
                                            columnsMap.put(bundledFileName, CsvUtils.parseLine(firstLine));
                                        } else {
                                            columnsMap.put(bundledFileName, new ArrayList<>());
                                        }
                                    }
                                }
                            } else if (marker == MARKER_FILE_CONTENT) {
                                int contentLen = dataIn.readInt();
                                dataIn.skipBytes(contentLen);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to decrypt CSV bundle {} with provided secret key", fname, ex);
                        response.put("status", "INVALID_KEY");
                        response.put("encrypted", true);
                        response.put("package", fname);
                        response.put("message", "Invalid secret key. Decryption failed for package: " + fname);
                        return response;
                    }
                } else if (fname.toLowerCase().endsWith(".csv")) {
                    if (!files.contains(fname)) {
                        files.add(fname);
                    }
                    List<String> headers = CsvUtils.readHeader(p);
                    columnsMap.put(fname, headers);
                }
            }

            response.put("status", "SUCCESS");
            response.put("files", files);
            response.put("columns", columnsMap);
            response.put("fileCount", files.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to inspect CSV storage", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public Map<String, Object> sampleCsvHeaders(CsvSecureImportConfig.StorageConfig storage, String fileName, String secretKey) {
        Map<String, Object> inspect = inspectSource(storage, secretKey);
        Map<String, Object> response = new HashMap<>();
        if (!"SUCCESS".equals(inspect.get("status"))) {
            return inspect;
        }
        @SuppressWarnings("unchecked")
        Map<String, List<String>> colsMap = (Map<String, List<String>>) inspect.get("columns");
        List<String> headers = (colsMap != null && fileName != null && colsMap.containsKey(fileName))
                ? colsMap.get(fileName)
                : (colsMap != null && !colsMap.isEmpty() ? colsMap.values().iterator().next() : Collections.emptyList());

        response.put("status", "SUCCESS");
        response.put("fileName", fileName);
        response.put("headers", headers);
        response.put("fields", headers);
        response.put("columns", headers);
        return response;
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
            String saltKey = (config.getRules() != null && config.getRules().getMaskingKey() != null)
                    ? config.getRules().getMaskingKey()
                    : (secretKey != null ? secretKey : "DefaultImportSaltKey2026!");

            List<Path> restoredFiles = new ArrayList<>();

            for (Path sourceFile : sourceFiles) {
                try (InputStream rawIn = BackupFileUtils.createDecryptedInputStream(sourceFile, secretKey);
                     DataInputStream dataIn = new DataInputStream(new BufferedInputStream(rawIn, 128 * 1024))) {

                    dataIn.mark(MAGIC_HEADER.length + 4);
                    byte[] header = new byte[MAGIC_HEADER.length];
                    int read = dataIn.read(header);
                    boolean isBundle = (read == MAGIC_HEADER.length && Arrays.equals(header, MAGIC_HEADER));

                    if (isBundle) {
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
                                    if (config.getRules() != null && config.getRules().getTargetFiles() != null && !config.getRules().getTargetFiles().isEmpty()) {
                                        if (!config.getRules().getTargetFiles().contains(currentFile)) {
                                            continue;
                                        }
                                    }

                                    Path targetFile = destDir.resolve(currentFile);
                                    if (targetFile.getParent() != null) {
                                        Files.createDirectories(targetFile.getParent());
                                    }

                                    byte[] finalBytes = applyMaskingToCsvBytes(currentFile, contentBytes, config.getRules(), saltKey);
                                    Files.write(targetFile, finalBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                    restoredFiles.add(targetFile);

                                    long lineCount = 0;
                                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(finalBytes), StandardCharsets.UTF_8))) {
                                        r.readLine(); // skip header
                                        while (r.readLine() != null) lineCount++;
                                    }
                                    progress.totalRecords.addAndGet(lineCount);
                                    markFileCompleted(progress, currentFile, lineCount);
                                }
                            }
                        }
                    } else {
                        // Plain CSV file
                        String currentFile = sourceFile.getFileName().toString();
                        if (currentFile.endsWith(".enc")) {
                            currentFile = currentFile.substring(0, currentFile.length() - 4);
                        }
                        registerFileIfAbsent(progress, currentFile);

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        if (read > 0) baos.write(header, 0, read);
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = dataIn.read(buffer)) != -1) {
                            baos.write(buffer, 0, n);
                        }
                        byte[] contentBytes = baos.toByteArray();

                        Path targetFile = destDir.resolve(currentFile);
                        if (targetFile.getParent() != null) {
                            Files.createDirectories(targetFile.getParent());
                        }

                        byte[] finalBytes = applyMaskingToCsvBytes(currentFile, contentBytes, config.getRules(), saltKey);
                        Files.write(targetFile, finalBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        restoredFiles.add(targetFile);

                        long lineCount = 0;
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(finalBytes), StandardCharsets.UTF_8))) {
                            r.readLine(); // skip header
                            while (r.readLine() != null) lineCount++;
                        }
                        progress.totalRecords.addAndGet(lineCount);
                        markFileCompleted(progress, currentFile, lineCount);
                    }
                }
            }

            // Upload to COS if destination is COS
            if (isCosDestination(config)) {
                CosConnection cos = getDestinationCos(config);
                if (cos != null && ibmCosService != null) {
                    String prefix = getDestinationPrefix(config);
                    for (Path f : restoredFiles) {
                        String rel = destDir.relativize(f).toString().replace('\\', '/');
                        String objectKey = prefix.isEmpty() ? rel : prefix + "/" + rel;
                        ibmCosService.uploadFile(cos, objectKey, f);
                        Files.deleteIfExists(f);
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

    private byte[] applyMaskingToCsvBytes(String fileName, byte[] inputBytes, CsvSecureExportConfig.RulesConfig rules, String saltKey) throws IOException {
        if (rules == null || !hasAnyMaskingRules(rules)) {
            return inputBytes;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(inputBytes), StandardCharsets.UTF_8));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return inputBytes;

            bw.write(headerLine);
            bw.newLine();

            List<String> headers = CsvUtils.parseLine(headerLine);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> values = CsvUtils.parseLine(line);
                List<String> masked = new ArrayList<>(values.size());
                for (int i = 0; i < values.size(); i++) {
                    String col = (i < headers.size()) ? headers.get(i).trim() : ("col_" + i);
                    String val = values.get(i);
                    masked.add(maskValue(fileName, col, val, rules, saltKey));
                }
                bw.write(CsvUtils.formatLine(masked));
                bw.newLine();
            }
        }
        return baos.toByteArray();
    }

    private boolean hasAnyMaskingRules(CsvSecureExportConfig.RulesConfig rules) {
        if (rules == null) return false;
        return (rules.getMaskingColumns() != null && !rules.getMaskingColumns().isEmpty())
                || (rules.getPartialMaskingColumns() != null && !rules.getPartialMaskingColumns().isEmpty())
                || (rules.getConstraintColumns() != null && !rules.getConstraintColumns().isEmpty())
                || (rules.getNullifyColumns() != null && !rules.getNullifyColumns().isEmpty())
                || (rules.getDefaultValues() != null && !rules.getDefaultValues().isEmpty())
                || (rules.getCustomFormats() != null && !rules.getCustomFormats().isEmpty())
                || (rules.getMaskingFields() != null && !rules.getMaskingFields().isEmpty())
                || (rules.getPartialMaskingFields() != null && !rules.getPartialMaskingFields().isEmpty());
    }

    public String maskValue(String fileName, String colName, String value, CsvSecureExportConfig.RulesConfig rules, String saltKey) {
        if (value == null || rules == null) {
            return value;
        }
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

    private boolean isCosDestination(CsvSecureImportConfig config) {
        return config != null && config.getDest() != null && "cos".equalsIgnoreCase(config.getDest().getType());
    }

    private CosConnection getDestinationCos(CsvSecureImportConfig config) {
        if (config == null || config.getDest() == null || cosConnectionService == null) return null;
        Long cosId = config.getDest().getCosId();
        return cosId != null ? cosConnectionService.getConnection(cosId) : null;
    }

    private String getDestinationPrefix(CsvSecureImportConfig config) {
        if (config == null || config.getDest() == null || config.getDest().getPath() == null) return "";
        String p = config.getDest().getPath().trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
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
