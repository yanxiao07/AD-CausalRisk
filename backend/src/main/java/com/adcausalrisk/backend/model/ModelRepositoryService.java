package com.adcausalrisk.backend.model;

import com.adcausalrisk.backend.audit.AuditService;
import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.BusinessQueries;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Uploads and activates model releases. The bytes are kept outside Git under the configured
 * repository directory; the database stores only the immutable SHA-256 identity and manifest.
 *
 * <p>This is a registry, not a deployment engine: enabling a version records operational intent
 * and is audited, but the Python research host must still be released with the corresponding
 * artifact. The console states that boundary explicitly.
 */
@Service
public class ModelRepositoryService {
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_ARTIFACT_BYTES = 10L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 512L * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Path repository;

    public ModelRepositoryService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditService audit,
            @Value("${app.model-repository.dir:runtime/model-repository}") String directory) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.repository = Path.of(directory).toAbsolutePath().normalize();
    }

    public record ModelView(
            String modelVersion,
            boolean enabled,
            boolean registered,
            String manifestSha256,
            String artifactSha256,
            Long artifactSize,
            String uploadedAt,
            String activatedAt,
            String updatedAt) {}

    public List<ModelView> list(AppUserPrincipal user) {
        BusinessQueries.admin(user);
        return jdbc.query(
                "SELECT mc.model_version,mc.enabled,mc.updated_at,"
                        + "r.id AS release_id,r.manifest_sha256,r.artifact_sha256,"
                        + "r.artifact_size,r.uploaded_at,r.activated_at "
                        + "FROM model_configuration mc "
                        + "LEFT JOIN model_release r ON r.model_version=mc.model_version "
                        + "ORDER BY mc.enabled DESC,mc.model_version",
                (rs, rowNum) ->
                        new ModelView(
                                rs.getString("model_version"),
                                rs.getBoolean("enabled"),
                                rs.getObject("release_id") != null,
                                rs.getString("manifest_sha256"),
                                rs.getString("artifact_sha256"),
                                rs.getObject("artifact_size") == null
                                        ? null
                                        : rs.getLong("artifact_size"),
                                timestamp(rs.getTimestamp("uploaded_at")),
                                timestamp(rs.getTimestamp("activated_at")),
                                timestamp(rs.getTimestamp("updated_at"))));
    }

    @Transactional
    public ModelView upload(
            MultipartFile manifestFile,
            MultipartFile artifactFile,
            String declaredArtifactSha256,
            AppUserPrincipal user) {
        BusinessQueries.admin(user);
        byte[] manifestBytes = readManifest(manifestFile);
        JsonNode manifest = parseManifest(manifestBytes);
        String version = manifestVersion(manifest);
        byte[] artifactBytes = readArtifact(artifactFile);
        String artifactSha = sha256(artifactBytes);
        verifyDeclaredSha(declaredArtifactSha256, artifactSha);
        String manifestSha = sha256(manifestBytes);

        Integer existing =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM model_release WHERE model_version=?",
                        Integer.class,
                        version);
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该模型版本已登记");
        }

        // A version can be attempted by two concurrent uploads. Keep every attempt in its own
        // immutable path so the losing database insert can only delete its own bytes, never the
        // files referenced by the winning release.
        String baseName =
                version
                        + "-"
                        + artifactSha.substring(0, 12)
                        + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path artifactPath = repository.resolve(baseName + ".artifact");
        Path manifestPath = repository.resolve(baseName + ".manifest.json");
        try {
            Files.createDirectories(repository);
            Files.write(artifactPath, artifactBytes);
            Files.write(manifestPath, manifestBytes);
        } catch (IOException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型文件保存失败");
        }

        try {
            jdbc.update(
                    "INSERT INTO model_release(model_version,manifest_sha256,artifact_sha256,"
                            + "artifact_filename,artifact_size,manifest_json,storage_path,"
                            + "uploaded_by) VALUES(?,?,?,?,?,?,?,?)",
                    version,
                    manifestSha,
                    artifactSha,
                    safeName(artifactFile.getOriginalFilename(), version + ".artifact"),
                    artifactBytes.length,
                    new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8),
                    baseName,
                    user.userId());
            int configured =
                    jdbc.update(
                            "UPDATE model_configuration SET updated_at=CURRENT_TIMESTAMP"
                                    + " WHERE model_version=?",
                            version);
            if (configured == 0) {
                jdbc.update(
                        "INSERT INTO model_configuration(model_version,enabled,updated_at)"
                                + " VALUES(?,FALSE,CURRENT_TIMESTAMP)",
                        version);
            }
        } catch (DuplicateKeyException duplicate) {
            deleteQuietly(artifactPath);
            deleteQuietly(manifestPath);
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT, "该模型版本已登记");
        } catch (RuntimeException failure) {
            deleteQuietly(artifactPath);
            deleteQuietly(manifestPath);
            throw failure;
        }

        audit.recordQuietly(
                user.userId(),
                "SYSTEM_MODEL_UPLOADED",
                "上传模型 " + version + "，artifact SHA-256 " + artifactSha);
        return view(version);
    }

    @Transactional
    public ModelView rollback(String version, AppUserPrincipal user) {
        BusinessQueries.admin(user);
        requireVersion(version);
        lockModelConfiguration();
        String previous =
                jdbc.query(
                        "SELECT model_version FROM model_configuration WHERE enabled=TRUE"
                                + " ORDER BY model_version",
                        rs -> rs.next() ? rs.getString(1) : null);
        if (version.equals(previous)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "当前已是该模型版本");
        }
        activate(version);
        audit.recordQuietly(
                user.userId(),
                "SYSTEM_MODEL_ROLLED_BACK",
                "回滚模型 " + (previous == null ? "无" : previous) + " -> " + version);
        return view(version);
    }

    @Transactional
    public void setEnabled(String version, boolean enabled, AppUserPrincipal user) {
        BusinessQueries.admin(user);
        requireVersion(version);
        lockModelConfiguration();
        if (enabled) {
            String previous =
                    jdbc.query(
                            "SELECT model_version FROM model_configuration WHERE enabled=TRUE"
                                    + " AND model_version<>? ORDER BY model_version",
                            rs -> rs.next() ? rs.getString(1) : null,
                            version);
            activate(version);
            audit.recordQuietly(
                    user.userId(),
                    "SYSTEM_MODEL_ACTIVATED",
                    previous == null
                            ? "启用模型 " + version
                            : "切换模型 " + previous + " -> " + version);
        } else {
            jdbc.update(
                    "UPDATE model_configuration SET enabled=FALSE,updated_at=CURRENT_TIMESTAMP"
                            + " WHERE model_version=?",
                    version);
            audit.recordQuietly(
                    user.userId(), "SYSTEM_MODEL_DEACTIVATED", "停用模型 " + version);
        }
    }

    private void activate(String version) {
        jdbc.update(
                "UPDATE model_configuration SET enabled=FALSE,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE enabled=TRUE");
        jdbc.update(
                "UPDATE model_configuration SET enabled=TRUE,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE model_version=?",
                version);
        jdbc.update(
                "UPDATE model_release SET activated_at=CURRENT_TIMESTAMP"
                        + " WHERE model_version=?",
                version);
    }

    /**
     * All activation paths lock the same row set before deciding which version to switch. Without
     * this, two enable requests can each disable the current version and then enable themselves,
     * leaving two enabled rows. The transaction boundary in setEnabled/rollback keeps both updates
     * and the audit outcome atomic.
     */
    private void lockModelConfiguration() {
        jdbc.queryForList(
                "SELECT model_version FROM model_configuration ORDER BY model_version FOR UPDATE",
                String.class);
    }

    private void requireVersion(String version) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM model_configuration WHERE model_version=?",
                        Integer.class,
                        version);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "模型版本不存在");
        }
    }

    private ModelView view(String version) {
        return jdbc.queryForObject(
                "SELECT mc.model_version,mc.enabled,mc.updated_at,"
                        + "r.id AS release_id,r.manifest_sha256,r.artifact_sha256,"
                        + "r.artifact_size,r.uploaded_at,r.activated_at "
                        + "FROM model_configuration mc "
                        + "LEFT JOIN model_release r ON r.model_version=mc.model_version "
                        + "WHERE mc.model_version=?",
                (rs, rowNum) ->
                        new ModelView(
                                rs.getString("model_version"),
                                rs.getBoolean("enabled"),
                                rs.getObject("release_id") != null,
                                rs.getString("manifest_sha256"),
                                rs.getString("artifact_sha256"),
                                rs.getObject("artifact_size") == null
                                        ? null
                                        : rs.getLong("artifact_size"),
                                timestamp(rs.getTimestamp("uploaded_at")),
                                timestamp(rs.getTimestamp("activated_at")),
                                timestamp(rs.getTimestamp("updated_at"))),
                version);
    }

    private byte[] readManifest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择模型 Manifest");
        }
        if (file.getSize() > MAX_MANIFEST_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Manifest 不能超过 512 KB");
        }
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Manifest 读取失败");
        }
    }

    private JsonNode parseManifest(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Manifest 必须是 JSON 对象");
            }
            return root;
        } catch (IOException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Manifest 不是合法 JSON");
        }
    }

    private String manifestVersion(JsonNode manifest) {
        String version = text(manifest, "modelVersion");
        if (version.isBlank()) version = text(manifest, "version");
        if (version.isBlank() || !VERSION_PATTERN.matcher(version).matches()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Manifest 缺少合法的 modelVersion");
        }
        return version;
    }

    private byte[] readArtifact(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择模型 Artifact");
        }
        if (file.getSize() > MAX_ARTIFACT_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Artifact 不能超过 10 MB");
        }
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Artifact 读取失败");
        }
    }

    private void verifyDeclaredSha(String declared, String actual) {
        String normalized = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "artifactSha256 必须是 64 位小写十六进制");
        }
        if (!normalized.equals(actual)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Artifact SHA-256 校验失败");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private static String safeName(String original, String fallback) {
        if (original == null || original.isBlank()) return fallback;
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) return fallback;
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private static String timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The database has already refused the release; an orphan file is harmless.
        }
    }
}
