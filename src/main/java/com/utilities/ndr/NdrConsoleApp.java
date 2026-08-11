package com.utilities.ndr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class NdrConsoleApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String facility_name;

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.fromEnv();

            System.out.println("Starting NDR extraction...");
            System.out.println("API base URL: " + config.apiBaseUrl());
            System.out.println("Database: " + config.jdbcUrl());

            long facilityId = fetchFacilityId(config);
            System.out.println("Resolved facility ID: " + facilityId);

            String token = authenticate(config);
            System.out.println("Authentication successful.");

            if (config.cleanupSqlFile != null && Files.exists(config.cleanupSqlFile)) {
                System.out.println("Executing cleanup SQL: " + config.cleanupSqlFile.toAbsolutePath());
                executeSqlFile(config, config.cleanupSqlFile);
            } else {
                System.out.println("No cleanup SQL file found. Skipping cleanup step.");
            }

            LocalDate today = LocalDate.now();
            LocalDate lastWeek = today.minusDays(config.daysBack);

            triggerNdrGeneration(config, token, facilityId, lastWeek, today);
            System.out.println("NDR generation endpoint invoked.");

            Path xmlDir = waitForXmlDirectoryAndFiles(config, facilityId, config.xmlWaitSeconds);
            if (xmlDir == null) {
                System.out.println("No XML files found for facility " + facilityId + " within wait window.");
                return;
            }

            Path downloadsDir = Paths.get(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloadsDir);

            Path zipPath = downloadsDir.resolve("treatmentxml_" + facility_name + ".zip");
            int zippedCount = zipXmlFiles(xmlDir, zipPath);

            if (zippedCount == 0) {
                System.out.println("No XML files were zipped. Exiting.");
                return;
            }

            if (!isValidZip(zipPath)) {
                System.out.println("ZIP file was created but failed validation: " + zipPath);
                return;
            }

            System.out.println("ZIP created successfully: " + zipPath);
        System.out.println("Files archived: " + zippedCount);

        //String transferUrl = uploadZipUntilSuccess(zipPath, config);
        //System.out.println("Upload complete.");
        //System.out.println("Smash URL: " + transferUrl);

        } catch (Exception ex) {
            System.err.println("Application failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static long fetchFacilityId(AppConfig config) throws SQLException {
        String sql = "select bou.name,bou.id as current_organisation_unit_id from base_application_user bau inner join base_organisation_unit bou on bou.id = bau.current_organisation_unit_id limit 1";

        try (Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.dbUsername, config.dbPassword);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                throw new IllegalStateException("No facility ID found in base_application_user.");
            }
             facility_name = rs.getString("name");
            return rs.getLong("current_organisation_unit_id");
        }
    }

    private static String authenticate(AppConfig config) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String payload = MAPPER.createObjectNode()
                .put("username", config.apiUsername)
                .put("password", config.apiPassword)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiBaseUrl() + "/api/v1/authenticate"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Authentication failed. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = MAPPER.readTree(response.body());
        JsonNode idToken = json.get("id_token");

        if (idToken == null || idToken.asText().isBlank()) {
            throw new IllegalStateException("Authentication succeeded but id_token was missing.");
        }

        return idToken.asText();
    }

    private static void triggerNdrGeneration(AppConfig config, String token, long facilityId,
                                             LocalDate startDate, LocalDate endDate)
            throws IOException, InterruptedException {

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String query = String.format(
                "%s/api/v1/ndr/optimization/date-range?facilityIds=%s&startDate=%s&endDate=%s",
                config.apiBaseUrl(),
                encode(String.valueOf(facilityId)),
                encode(startDate.toString()),
                encode(endDate.toString())
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(query))
                .timeout(Duration.ofMinutes(120))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("NDR generation failed. HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static Path waitForXmlDirectoryAndFiles(AppConfig config, long facilityId, int waitSeconds) throws IOException, InterruptedException {
        Path xmlDir = config.xmlBasePath.resolve(String.valueOf(facilityId));

        long end = System.currentTimeMillis() + (waitSeconds * 1000L);
        while (System.currentTimeMillis() < end) {
            if (Files.isDirectory(xmlDir)) {
                try (Stream<Path> stream = Files.list(xmlDir)) {
                    boolean hasXml = stream.anyMatch(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase().endsWith(".xml"));
                    if (hasXml) {
                        return xmlDir;
                    }
                }
            }
            Thread.sleep(2000);
        }

        return null;
    }

    private static int zipXmlFiles(Path xmlDir, Path zipPath) throws IOException {
        List<Path> xmlFiles;
        try (Stream<Path> stream = Files.list(xmlDir)) {
            xmlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .collect(Collectors.toList());
        }

        if (xmlFiles.isEmpty()) {
            return 0;
        }

        Files.deleteIfExists(zipPath);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path xmlFile : xmlFiles) {
                ZipEntry entry = new ZipEntry(xmlFile.getFileName().toString());
                zos.putNextEntry(entry);
                Files.copy(xmlFile, zos);
                zos.closeEntry();
            }
        }

        return xmlFiles.size();
    }

    private static boolean isValidZip(Path zipPath) {
        try {
            if (!Files.exists(zipPath) || Files.size(zipPath) <= 0) {
                return false;
            }

            try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
                return zipFile.size() > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasInternetConnectivity() {
        List<String> targets = List.of(
                "https://fromsmash.com",
                "https://api.fromsmash.com"
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        for (String target : targets) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(target))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 500) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private static void waitForInternetConnectivity(AppConfig config) throws InterruptedException {
        while (true) {
            if (hasInternetConnectivity()) {
                System.out.println("Internet connectivity detected.");
                return;
            }
            System.out.println("No internet connectivity. Retrying in " + config.connectivityRetrySeconds + " seconds...");
            Thread.sleep(config.connectivityRetrySeconds * 1000L);
        }
    }

    private static String uploadZipUntilSuccess(Path zipPath, AppConfig config) throws InterruptedException, IOException {
        while (true) {
            waitForInternetConnectivity(config);
            try {
                Optional<String> transferUrl = uploadToSmashViaNode(zipPath);
                if (transferUrl.isPresent()) {
                    return transferUrl.get();
                }
                System.out.println("Upload returned no transfer URL. Retrying in " + config.connectivityRetrySeconds + " seconds...");
            } catch (IOException | InterruptedException ex) {
                System.out.println("Upload attempt failed: " + ex.getMessage());
                System.out.println("Retrying in " + config.connectivityRetrySeconds + " seconds...");
            }
            Thread.sleep(config.connectivityRetrySeconds * 1000L);
        }
    }

    private static Optional<String> uploadToSmashViaNode(Path zipPath) throws IOException, InterruptedException {
        String smashApiKey = System.getenv("SMASH_API_KEY");
        String smashRegion = System.getenv("SMASH_REGION");

        if (isBlank(smashApiKey) || isBlank(smashRegion)) {
            System.out.println("SMASH_API_KEY or SMASH_REGION not set. Upload skipped.");
            return Optional.empty();
        }

        Path helperScript = Paths.get("smash-upload.mjs");
        if (!Files.exists(helperScript)) {
            throw new IllegalStateException("Missing helper script: " + helperScript.toAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(
                "node",
                helperScript.toAbsolutePath().toString(),
                zipPath.toAbsolutePath().toString()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Smash upload helper failed:\n" + output);
        }

        JsonNode json = MAPPER.readTree(output);
        JsonNode transferUrl = json.get("transferUrl");

        if (transferUrl != null && !transferUrl.asText().isBlank()) {
            return Optional.of(transferUrl.asText());
        }

        return Optional.empty();
    }

    private static void executeSqlFile(AppConfig config, Path sqlFile) throws IOException, SQLException {
        String raw = Files.readString(sqlFile, StandardCharsets.UTF_8);

        StringBuilder cleaned = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("//") || trimmed.isBlank()) {
                continue;
            }
            cleaned.append(line).append("\n");
        }

        String[] statements = cleaned.toString().split(";");

        try (Connection conn = DriverManager.getConnection(config.jdbcUrl(), config.dbUsername, config.dbPassword)) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    String trimmed = sql.trim();
                    if (!trimmed.isBlank()) {
                        stmt.execute(trimmed);
                    }
                }
            }
            conn.commit();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static class AppConfig {
        final String appHost;
        final int appPort;

        final String dbHost;
        final int dbPort;
        final String dbName;
        final String dbUsername;
        final String dbPassword;

        final String apiUsername;
        final String apiPassword;

        final Path cleanupSqlFile;
        final int xmlWaitSeconds;
        final int connectivityRetrySeconds;
        final int daysBack;
        final Path xmlBasePath;

        AppConfig(String appHost, int appPort,
                  String dbHost, int dbPort, String dbName, String dbUsername, String dbPassword,
                  String apiUsername, String apiPassword,
                  Path cleanupSqlFile, int xmlWaitSeconds, int connectivityRetrySeconds, int daysBack, Path xmlBasePath) {
            this.appHost = appHost;
            this.appPort = appPort;
            this.dbHost = dbHost;
            this.dbPort = dbPort;
            this.dbName = dbName;
            this.dbUsername = dbUsername;
            this.dbPassword = dbPassword;
            this.apiUsername = apiUsername;
            this.apiPassword = apiPassword;
            this.cleanupSqlFile = cleanupSqlFile;
            this.xmlWaitSeconds = xmlWaitSeconds;
            this.connectivityRetrySeconds = connectivityRetrySeconds;
            this.daysBack = daysBack;
            this.xmlBasePath = xmlBasePath;
        }

        static AppConfig fromEnv() {
            Map<String, String> env = new HashMap<>();
            env.putAll(loadEnvFile());
            env.putAll(System.getenv());

            return new AppConfig(
                    envOrDefault("APP_HOST", "localhost", env),
                    envAsInt("APP_PORT", true, 8080, env),

                    envOrDefault("DB_HOST", "localhost", env),
                    envAsInt("DB_PORT", false, 5432, env),
                    env("DB_NAME", env),
                    env("DB_USERNAME", env),
                    env("DB_PASSWORD", env),

                    envOrDefault("API_USERNAME", "guest@lamisplus.org", env),
                    envOrDefault("API_PASSWORD", "12345", env),

                    Paths.get(envOrDefault("CLEANUP_SQL_FILE", "test.sql", env)),
                    envAsInt("XML_WAIT_SECONDS", false, 30, env),
                    envAsInt("CONNECTIVITY_RETRY_SECONDS", false, 30, env),
                    envAsInt("DAYS_BACK", false, 7, env),
                    Paths.get(envOrDefault("XML_BASE_PATH", "runtime/ndr/transfer/temp", env))
            );
        }

        static Map<String, String> loadEnvFile() {
            Path dir = Paths.get(System.getProperty("user.dir"));
            while (dir != null) {
                Path envPath = dir.resolve(".env");
                if (Files.exists(envPath)) {
                    return readEnvFile(envPath);
                }
                dir = dir.getParent();
            }
            return Map.of();
        }

        static Map<String, String> readEnvFile(Path envPath) {
            Map<String, String> env = new HashMap<>();
            try (Stream<String> lines = Files.lines(envPath, StandardCharsets.UTF_8)) {
                lines.map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(line -> {
                            int equals = line.indexOf('=');
                            if (equals > 0) {
                                String key = line.substring(0, equals).trim();
                                String value = line.substring(equals + 1).trim();
                                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                                    value = value.substring(1, value.length() - 1);
                                }
                                env.put(key, value);
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read .env file at " + envPath, e);
            }
            return env;
        }

        static String env(String name, Map<String, String> env) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required environment variable: " + name);
            }
            return value;
        }

        static String envOrDefault(String name, String defaultValue, Map<String, String> env) {
            String value = env.get(name);
            return (value == null || value.isBlank()) ? defaultValue : value;
        }

        static int envAsInt(String name, boolean required, int defaultValue, Map<String, String> env) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                if (required) {
                    throw new IllegalStateException("Missing required environment variable: " + name);
                }
                return defaultValue;
            }
            return Integer.parseInt(value);
        }

        String jdbcUrl() {
            return "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
        }

        String apiBaseUrl() {
            return "http://" + appHost + ":" + appPort;
        }
    }
}
