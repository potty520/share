package com.share.ops.file.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 文件落盘服务（FR-09）：本地 NIO 存储、分片目录管理、MD5/SHA-256 流式计算。
 * <p>根目录由 {@code share.file.root} 配置；单文件路径写库时存相对根目录的定位串，便于搬迁。</p>
 */
@Slf4j
@Service
public class FileStorageService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int BUF_SIZE = 64 * 1024;

    @Value("${share.file.root:./data/files}")
    private String root;

    @Value("${share.file.chunk-size:1048576}")
    private long chunkSize;

    private volatile Path rootPath;

    /** 存储根目录（绝对路径） */
    public Path root() {
        if (rootPath == null) {
            synchronized (this) {
                if (rootPath == null) {
                    rootPath = Paths.get(root).toAbsolutePath().normalize();
                }
            }
        }
        return rootPath;
    }

    public long chunkSize() {
        return chunkSize > 0 ? chunkSize : 1024 * 1024;
    }

    public Path chunkDir(String uploadId) {
        return root().resolve("chunks").resolve(uploadId);
    }

    public Path tmpDir() {
        return root().resolve("tmp");
    }

    /** 建目录（幂等） */
    public void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "创建存储目录失败: " + dir.getFileName());
        }
    }

    /** 把相对定位串解析成绝对路径；绝对路径原样返回。 */
    public Path resolve(String location) {
        if (location == null || location.isBlank()) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        Path p = Paths.get(location);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path abs = root().resolve(p).normalize();
        if (!abs.startsWith(root())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法文件路径");
        }
        return abs;
    }

    /** 绝对路径 → 写库定位串（根目录内用相对路径） */
    public String relative(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (abs.startsWith(root())) {
            return root().relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }

    /** 为上传文件预留正式落盘路径：root/files/yyyyMM/{短UUID}_{安全文件名} */
    public Path reserveStoredPath(String originalName) {
        Path dir = root().resolve("files").resolve(LocalDate.now().format(MONTH));
        ensureDir(dir);
        String safe = sanitize(originalName);
        String prefix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return dir.resolve(prefix + "_" + safe);
    }

    /** 文件名安全化：去掉路径分隔符与 Windows 非法字符，保留中文。 */
    public String sanitize(String originalName) {
        String name = originalName == null ? "unnamed" : originalName;
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (name.isEmpty()) {
            name = "unnamed";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    /** 取扩展名（小写，不含点） */
    public String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(idx + 1).toLowerCase();
        return ext.length() > 16 ? "" : ext;
    }

    /** 计算文件摘要（MD5 / SHA-256），流式读取避免大文件占内存。 */
    public String digest(Path file, String algorithm) {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "计算文件摘要失败: " + e.getMessage());
        }
    }

    public String md5(Path file) {
        return digest(file, "MD5");
    }

    public String sha256(Path file) {
        return digest(file, "SHA-256");
    }

    /** 计算字符串摘要（分片校验等场景） */
    public String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "计算摘要失败: " + e.getMessage());
        }
    }

    /** 输入流落盘（覆盖写） */
    public long write(InputStream in, Path target) {
        ensureDir(target.getParent());
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            return in.transferTo(out);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "文件写入失败: " + e.getMessage());
        }
    }

    /** 入参文件落盘（MultipartFile 场景） */
    public long save(org.springframework.web.multipart.MultipartFile file, Path target) {
        try (InputStream in = file.getInputStream()) {
            return write(in, target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败: " + e.getMessage());
        }
    }

    public long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    public boolean exists(Path file) {
        return file != null && Files.exists(file);
    }

    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除文件失败 {}: {}", file, e.getMessage());
        }
    }

    /** 递归删除分片目录 */
    public void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 分片清理失败不影响主流程
                }
            });
        } catch (IOException e) {
            log.warn("清理分片目录失败 {}: {}", dir, e.getMessage());
        }
    }
}
