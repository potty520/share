package com.share.ops.file.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.util.JsonUtil;
import com.share.ops.file.entity.FlFile;
import com.share.ops.support.OpsConstants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分片上传与断点续传（FR-09）。
 * <p>分片落盘在 {@code {root}/chunks/{uploadId}/{index}.part}（index 从 0 开始），
 * 元信息（总分片数、整体 MD5、文件名）写在 {@code {root}/chunks/{uploadId}/meta.json}，
 * 因此无需新增数据表即可支持断点续传：客户端 GET 已收分片清单后只补传缺失分片。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {

    private static final String META_FILE = "meta.json";
    private static final String PART_SUFFIX = ".part";

    private final FileService fileService;
    private final FileStorageService storage;

    /** 上传单个分片（幂等：重复上传同一分片直接覆盖） */
    @Transactional
    public Map<String, Object> uploadChunk(String uploadId, int index, int total, String chunkMd5,
                                           MultipartFile file) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "uploadId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分片内容不能为空");
        }
        if (total <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "total 必须为正数");
        }
        if (index < 0 || index >= total) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分片序号越界: " + index + "，合法范围 0~" + (total - 1));
        }
        Path dir = storage.chunkDir(uploadId);
        storage.ensureDir(dir);
        ChunkMeta meta = readMeta(dir);
        if (meta == null) {
            meta = new ChunkMeta(uploadId, total, storage.chunkSize(), LocalDateTime.now());
            writeMeta(dir, meta);
        } else if (meta.getTotal() != total) {
            // 总分片数变化说明是另一个文件的续传请求，重置元信息
            meta.setTotal(total);
            writeMeta(dir, meta);
        }
        Path part = dir.resolve(index + PART_SUFFIX);
        storage.save(file, part);
        if (StringUtils.hasText(chunkMd5)) {
            String actual = storage.md5(part);
            if (!actual.equalsIgnoreCase(chunkMd5.trim())) {
                storage.deleteQuietly(part);
                throw new BizException(ErrorCode.FILE_CHUNK_MISMATCH,
                        "分片 " + index + " MD5 不匹配，期望 " + chunkMd5 + " 实际 " + actual);
            }
        }
        Map<String, Object> state = buildStatus(uploadId, dir, meta);
        state.put("index", index);
        state.put("partSize", storage.sizeOf(part));
        return state;
    }

    /** 查询已完成分片清单（断点续传入口） */
    public Map<String, Object> status(String uploadId) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "uploadId 不能为空");
        }
        Path dir = storage.chunkDir(uploadId);
        ChunkMeta meta = readMeta(dir);
        if (meta == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("uploadId", uploadId);
            empty.put("exists", false);
            empty.put("received", List.of());
            empty.put("receivedCount", 0);
            empty.put("total", 0);
            empty.put("complete", false);
            empty.put("missing", List.of());
            empty.put("chunkSize", storage.chunkSize());
            return empty;
        }
        Map<String, Object> state = buildStatus(uploadId, dir, meta);
        state.put("exists", true);
        return state;
    }

    /** 合并分片 → 整体校验 → 落 fl_file */
    @Transactional
    public Map<String, Object> complete(String uploadId, String fileName, Long deptId,
                                        Integer secretLevel, String remark) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "uploadId 不能为空");
        }
        Path dir = storage.chunkDir(uploadId);
        ChunkMeta meta = readMeta(dir);
        if (meta == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "分片会话不存在: " + uploadId);
        }
        int total = meta.getTotal();
        List<Integer> received = scanChunks(dir);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.FILE_CHUNK_MISMATCH,
                    "分片不完整，缺少 " + missing.size() + " 个分片: " + firstN(missing, 10));
        }
        String name = StringUtils.hasText(fileName) ? fileName
                : (StringUtils.hasText(meta.getFileName()) ? meta.getFileName()
                : "merged_" + uploadId.substring(0, Math.min(8, uploadId.length())) + ".bin");

        storage.ensureDir(storage.tmpDir());
        Path merged = storage.tmpDir().resolve(uploadId + "-merged.tmp");
        long bytes = 0;
        try (OutputStream out = Files.newOutputStream(merged, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int i = 0; i < total; i++) {
                Path part = dir.resolve(i + PART_SUFFIX);
                bytes += Files.copy(part, out);
            }
        } catch (IOException e) {
            storage.deleteQuietly(merged);
            throw new BizException(ErrorCode.INTERNAL, "分片合并失败: " + e.getMessage());
        }

        String md5 = storage.md5(merged);
        String sha256 = storage.sha256(merged);
        if (StringUtils.hasText(meta.getMd5()) && !meta.getMd5().trim().equalsIgnoreCase(md5)) {
            storage.deleteQuietly(merged);
            throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                    "整体 MD5 校验失败，期望 " + meta.getMd5() + " 实际 " + md5);
        }

        Path target = storage.reserveStoredPath(name);
        try {
            Files.move(merged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            storage.deleteQuietly(merged);
            throw new BizException(ErrorCode.INTERNAL, "文件转存失败: " + e.getMessage());
        }
        FlFile saved = fileService.persistRecord(target, name, bytes, md5, sha256, guessContentType(name),
                total, OpsConstants.FILE_BIZ_CHUNK, deptId, secretLevel, remark);
        storage.deleteTreeQuietly(dir);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("fileId", saved.getId());
        result.put("fileName", saved.getFileName());
        result.put("fileSize", saved.getFileSize());
        result.put("fileSizeText", FileService.humanSize(saved.getFileSize() == null ? 0L : saved.getFileSize()));
        result.put("md5", md5);
        result.put("sha256", sha256);
        result.put("chunkCount", total);
        result.put("status", saved.getStatus());
        result.put("verified", true);
        result.put("downloadUrl", "/api/file/v1/files/" + saved.getId() + "/download");
        log.info("分片上传完成 uploadId={} fileId={} size={} chunks={}", uploadId, saved.getId(), bytes, total);
        return result;
    }

    // ================= 内部方法 =================

    private Map<String, Object> buildStatus(String uploadId, Path dir, ChunkMeta meta) {
        List<Integer> received = scanChunks(dir);
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < meta.getTotal(); i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }
        boolean complete = missing.isEmpty() && meta.getTotal() > 0;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("uploadId", uploadId);
        state.put("fileName", meta.getFileName());
        state.put("total", meta.getTotal());
        state.put("received", received);
        state.put("receivedCount", received.size());
        state.put("missing", missing);
        state.put("complete", complete);
        state.put("chunkSize", meta.getChunkSize());
        long receivedBytes = 0;
        for (Integer idx : received) {
            receivedBytes += storage.sizeOf(dir.resolve(idx + PART_SUFFIX));
        }
        state.put("receivedBytes", receivedBytes);
        state.put("progress", meta.getTotal() == 0 ? 0
                : Math.round(received.size() * 10000.0 / meta.getTotal()) / 100.0);
        return state;
    }

    /** 扫描已完成分片序号（升序） */
    private List<Integer> scanChunks(Path dir) {
        List<Integer> indexes = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return indexes;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(PART_SUFFIX)) {
                    try {
                        indexes.add(Integer.parseInt(n.substring(0, n.length() - PART_SUFFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // 非规范分片文件忽略
                    }
                }
            });
        } catch (IOException e) {
            log.warn("扫描分片目录失败 {}: {}", dir, e.getMessage());
        }
        indexes.sort(Integer::compareTo);
        return new ArrayList<>(new java.util.LinkedHashSet<>(indexes));
    }

    private ChunkMeta readMeta(Path dir) {
        Path metaFile = dir.resolve(META_FILE);
        if (!Files.exists(metaFile)) {
            return null;
        }
        try {
            String json = Files.readString(metaFile);
            return JsonUtil.fromJson(json, ChunkMeta.class);
        } catch (Exception e) {
            log.warn("读取分片元信息失败 {}: {}", metaFile, e.getMessage());
            return null;
        }
    }

    private void writeMeta(Path dir, ChunkMeta meta) {
        try {
            Files.writeString(dir.resolve(META_FILE), JsonUtil.toJson(meta), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "写入分片元信息失败: " + e.getMessage());
        }
    }

    private String guessContentType(String fileName) {
        String ext = storage.extOf(fileName);
        return switch (ext) {
            case "csv" -> "text/csv";
            case "txt", "log" -> "text/plain";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private String firstN(List<Integer> list, int n) {
        return list.size() <= n ? list.toString() : list.subList(0, n) + "...";
    }

    /** 分片会话元信息（JSON 落盘，不新增数据表） */
    @Getter
    @Setter
    public static class ChunkMeta {

        private String uploadId;
        private int total;
        private long chunkSize;
        private LocalDateTime createdAt;
        private String fileName;
        /** 整体文件 MD5（客户端提供时用于合并后校验） */
        private String md5;

        public ChunkMeta() {
        }

        public ChunkMeta(String uploadId, int total, long chunkSize, LocalDateTime createdAt) {
            this.uploadId = uploadId;
            this.total = total;
            this.chunkSize = chunkSize;
            this.createdAt = createdAt;
        }
    }
}
