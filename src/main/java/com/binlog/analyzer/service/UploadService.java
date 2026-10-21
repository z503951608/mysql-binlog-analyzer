package com.binlog.analyzer.service;

import com.binlog.analyzer.config.BinlogProperties;
import com.binlog.analyzer.model.UploadInfo;
import com.binlog.analyzer.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UploadService {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-f0-9]{32}");
    private static final byte[] BINLOG_MAGIC = new byte[]{(byte) 0xFE, 0x62, 0x69, 0x6E};

    private final BinlogProperties properties;
    private final ObjectMapper objectMapper;
    private Path directory;

    public UploadService(BinlogProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try {
            directory = new File(properties.getUploadDir()).toPath().toAbsolutePath().normalize();
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建离线 binlog 保存目录: " + e.getMessage(), e);
        }
    }

    public UploadInfo save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要上传的 binlog 文件");
        }
        String originalName = sanitizeName(file.getOriginalFilename());
        String id = UUID.randomUUID().toString().replace("-", "");
        Path part = directory.resolve(id + ".binlog.part");
        Path target = directory.resolve(id + ".binlog");
        try {
            file.transferTo(part.toFile());
            if (Files.size(part) < BINLOG_MAGIC.length || !hasBinlogMagic(part)) {
                throw new ApiException("文件不是有效的 MySQL binlog（文件头校验失败）");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            UploadInfo info = new UploadInfo();
            info.id = id;
            info.originalName = originalName;
            info.size = Files.size(target);
            info.uploadedAt = System.currentTimeMillis();
            writeMeta(info);
            return info;
        } catch (ApiException e) {
            deleteQuietly(part);
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            deleteQuietly(part);
            deleteQuietly(target);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存上传文件失败: " + e.getMessage());
        }
    }

    public List<UploadInfo> list() {
        List<UploadInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.binlog")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".binlog".length());
                if (!ID_PATTERN.matcher(id).matches()) {
                    continue;
                }
                UploadInfo info = readMeta(id);
                if (info != null) {
                    result.add(info);
                }
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取已上传文件列表失败: " + e.getMessage());
        }
        result.sort(Comparator.comparingLong((UploadInfo info) -> info.uploadedAt).reversed());
        return result;
    }

    public ResolvedUpload resolve(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new ApiException("离线 binlog 文件标识不合法，请重新上传");
        }
        Path file = directory.resolve(id + ".binlog");
        if (!Files.isRegularFile(file)) {
            throw new ApiException("找不到已上传的离线 binlog 文件，请重新上传");
        }
        UploadInfo info = readMeta(id);
        if (info == null) {
            info = new UploadInfo();
            info.id = id;
            info.originalName = id + ".binlog";
            try {
                info.size = Files.size(file);
            } catch (IOException e) {
                info.size = 0;
            }
            info.uploadedAt = file.toFile().lastModified();
        }
        return new ResolvedUpload(info, file.toFile());
    }

    private boolean hasBinlogMagic(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = new byte[BINLOG_MAGIC.length];
            int read = in.read(header);
            if (read != BINLOG_MAGIC.length) {
                return false;
            }
            for (int i = 0; i < BINLOG_MAGIC.length; i++) {
                if (header[i] != BINLOG_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private String sanitizeName(String name) {
        if (name == null) {
            return "binlog";
        }
        String value = name.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isEmpty()) {
            return "binlog";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private void writeMeta(UploadInfo info) throws IOException {
        Path meta = directory.resolve(info.id + ".json");
        objectMapper.writeValue(meta.toFile(), info);
    }

    private UploadInfo readMeta(String id) {
        Path meta = directory.resolve(id + ".json");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            UploadInfo info = objectMapper.readValue(meta.toFile(), UploadInfo.class);
            if (info.id == null) {
                info.id = id;
            }
            return info;
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    public static final class ResolvedUpload {
        public final UploadInfo info;
        public final File file;

        public ResolvedUpload(UploadInfo info, File file) {
            this.info = info;
            this.file = file;
        }
    }
}
