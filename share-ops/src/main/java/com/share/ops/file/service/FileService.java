package com.share.ops.file.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.ops.file.entity.FlFile;
import com.share.ops.file.repository.FlFileRepository;
import com.share.ops.file.repository.FlTransferLogRepository;
import com.share.ops.file.repository.FlTransferTaskRepository;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.criteria.Predicate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文件交换服务（FR-09）：上传/查询/下载/删除/完整性校验/统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FlFileRepository fileRepository;
    private final FlTransferLogRepository transferLogRepository;
    private final FlTransferTaskRepository transferTaskRepository;
    private final FileStorageService storage;
    private final MinioStorageService minioStorage;

    // ================= 上传与落盘 =================

    /** 单文件上传：落盘 + MD5/SHA-256 + fl_file 记录 */
    @Transactional
    public FlFile upload(MultipartFile file, Long deptId, Integer secretLevel, String remark) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "unnamed";
        }
        Path target = storage.reserveStoredPath(originalName);
        long size = storage.save(file, target);
        String md5 = storage.md5(target);
        String sha256 = storage.sha256(target);
        return persistRecord(target, originalName, size, md5, sha256, file.getContentType(),
                1, OpsConstants.FILE_BIZ_UPLOAD, deptId, secretLevel, remark);
    }

    /** 生成 fl_file 记录（直传 / 分片合并共用） */
    @Transactional
    public FlFile persistRecord(Path target, String originalName, long size, String md5, String sha256,
                                String contentType, int chunkCount, String bizType,
                                Long deptId, Integer secretLevel, String remark) {
        FlFile entity = new FlFile();
        entity.setFileName(originalName);
        entity.setStoredName(target.getFileName().toString());
        entity.setFilePath(storage.relative(target));
        entity.setFileSize(size);
        entity.setContentType(contentType);
        entity.setExtName(storage.extOf(originalName));
        entity.setMd5(md5);
        entity.setSha256(sha256);
        entity.setUploader(UserContext.username());
        entity.setUploaderId(UserContext.userId());
        entity.setDeptId(deptId != null ? deptId : UserContext.deptId());
        entity.setStatus(OpsConstants.FILE_STATUS_SUCCESS);
        entity.setSecretLevel(secretLevel == null ? 1 : secretLevel);
        entity.setChunkCount(chunkCount);
        entity.setBizType(bizType == null ? OpsConstants.FILE_BIZ_UPLOAD : bizType);
        entity.setRemark(remark);
        String objectKey = "files/" + entity.getFilePath().replace('\\', '/');
        String uploadedKey = minioStorage.upload(target, objectKey);
        if (uploadedKey != null) {
            entity.setStorageType("MINIO");
            entity.setObjectKey(uploadedKey);
        } else {
            entity.setStorageType("LOCAL");
        }
        return fileRepository.save(entity);
    }

    // ================= 查询 =================

    @Transactional(readOnly = true)
    public Map<String, Object> page(String keyword, Long deptId, String uploader, String status,
                                    String bizType, LocalDateTime from, LocalDateTime to,
                                    Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<FlFile> spec = (root, query, cb) -> {
            List<Predicate> ps2 = new ArrayList<>();
            ps2.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps2.add(cb.or(
                        cb.like(cb.lower(root.get("fileName")), kw),
                        cb.like(cb.lower(root.get("storedName")), kw),
                        cb.like(cb.lower(root.get("md5")), kw),
                        cb.like(cb.lower(root.get("uploader")), kw)));
            }
            if (deptId != null) {
                ps2.add(cb.equal(root.get("deptId"), deptId));
            }
            if (StringUtils.hasText(uploader)) {
                ps2.add(cb.equal(root.get("uploader"), uploader));
            }
            if (StringUtils.hasText(status)) {
                ps2.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(bizType)) {
                ps2.add(cb.equal(root.get("bizType"), bizType));
            }
            if (from != null) {
                ps2.add(cb.greaterThanOrEqualTo(root.get("createTime"), from));
            }
            if (to != null) {
                ps2.add(cb.lessThanOrEqualTo(root.get("createTime"), to));
            }
            return cb.and(ps2.toArray(new Predicate[0]));
        };
        Page<FlFile> page = fileRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "createTime")));
        return PageSupport.of(page, this::toView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        FlFile f = find(id);
        Map<String, Object> view = toView(f);
        view.put("sha256", f.getSha256());
        view.put("chunkCount", f.getChunkCount());
        view.put("deptId", f.getDeptId());
        return view;
    }

    @Transactional(readOnly = true)
    public FlFile find(Long id) {
        return fileRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.FILE_NOT_FOUND));
    }

    /** 下载用：返回实体与磁盘绝对路径 */
    @Transactional(readOnly = true)
    public FlFile findForDownload(Long id) {
        FlFile f = find(id);
        Path p = storage.resolve(f.getFilePath());
        if (!storage.exists(p) && minioStorage.open(f.getObjectKey()) == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "磁盘文件已丢失");
        }
        return f;
    }

    /** 打开文件流：本地优先，缺失时从 MinIO 回读。 */
    public java.io.InputStream openStream(FlFile f) {
        Path p = storage.resolve(f.getFilePath());
        try {
            if (Files.exists(p)) {
                return Files.newInputStream(p);
            }
            java.io.InputStream remote = minioStorage.open(f.getObjectKey());
            if (remote != null) {
                return remote;
            }
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "读取文件失败: " + e.getMessage());
        }
        throw new BizException(ErrorCode.FILE_NOT_FOUND, "磁盘与对象存储均未找到文件");
    }

    public Path pathOf(FlFile f) {
        return storage.resolve(f.getFilePath());
    }

    // ================= 删除 / 校验 =================

    /** 删除文件：逻辑删除记录 + 物理删除磁盘文件 */
    @Transactional
    public void delete(Long id) {
        FlFile f = find(id);
        storage.deleteQuietly(storage.resolve(f.getFilePath()));
        if (minioStorage.available()) {
            minioStorage.delete(f.getObjectKey());
        }
        f.setDeleted(true);
        fileRepository.save(f);
        log.info("文件已删除 id={} name={}", id, f.getFileName());
    }

    /** 完整性校验：重算 MD5/SHA-256 与记录比对，并回写状态 */
    @Transactional
    public Map<String, Object> verify(Long id) {
        FlFile f = find(id);
        Path p = storage.resolve(f.getFilePath());
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", f.getId());
        result.put("fileName", f.getFileName());
        result.put("filePath", f.getFilePath());
        result.put("expectedMd5", f.getMd5());
        result.put("expectedSha256", f.getSha256());
        if (!storage.exists(p)) {
            f.setStatus(OpsConstants.FILE_STATUS_CORRUPTED);
            fileRepository.save(f);
            result.put("exists", false);
            result.put("actualMd5", null);
            result.put("actualSha256", null);
            result.put("md5Match", false);
            result.put("sha256Match", false);
            result.put("verified", false);
            result.put("message", "磁盘文件不存在，判定为丢失");
            return result;
        }
        String actualMd5 = storage.md5(p);
        String actualSha = storage.sha256(p);
        boolean md5Match = Objects.equals(f.getMd5(), actualMd5);
        boolean shaMatch = Objects.equals(f.getSha256(), actualSha);
        boolean verified = md5Match && shaMatch;
        f.setStatus(verified ? OpsConstants.FILE_STATUS_VERIFIED : OpsConstants.FILE_STATUS_CORRUPTED);
        fileRepository.save(f);
        result.put("exists", true);
        result.put("actualMd5", actualMd5);
        result.put("actualSha256", actualSha);
        result.put("md5Match", md5Match);
        result.put("sha256Match", shaMatch);
        result.put("verified", verified);
        result.put("fileSize", storage.sizeOf(p));
        result.put("costMs", System.currentTimeMillis() - start);
        result.put("message", verified ? "文件完整性校验通过" : "文件完整性校验失败，内容与登记信息不一致");
        return result;
    }

    // ================= 统计 =================

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        long fileCount = fileRepository.countByDeletedFalse();
        Long totalBytes = fileRepository.sumFileSize();
        long success = transferLogRepository.countByStatusAndDeletedFalse(OpsConstants.TRANSFER_SUCCESS);
        long fail = transferLogRepository.countByStatusAndDeletedFalse(OpsConstants.TRANSFER_FAIL);
        long total = success + fail;
        double rate = total == 0 ? 100.0 : Math.round(success * 10000.0 / total) / 100.0;
        LocalDateTime todayStart = LocalDateTime.now().with(LocalTime.MIN);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("fileCount", fileCount);
        stats.put("totalBytes", totalBytes == null ? 0L : totalBytes);
        stats.put("totalSizeText", humanSize(totalBytes == null ? 0L : totalBytes));
        stats.put("todayUploads", fileRepository.countByDeletedFalseAndCreateTimeAfter(todayStart));
        stats.put("successTransfers", success);
        stats.put("failedTransfers", fail);
        stats.put("transferTotal", total);
        stats.put("transferSuccessRate", rate);
        stats.put("todayTransferBytes", nz(transferLogRepository.sumSuccessBytes(todayStart, todayStart.plusDays(1))));
        stats.put("transferTaskCount", transferTaskRepository.countByDeletedFalse());
        stats.put("enabledTransferTasks", transferTaskRepository.countByEnabledTrueAndDeletedFalse());
        stats.put("chunkUploads", fileRepository.countByBizTypeAndDeletedFalse(OpsConstants.FILE_BIZ_CHUNK));
        List<Object[]> uploaders = fileRepository.groupByUploader();
        List<Map<String, Object>> topUploaders = new ArrayList<>();
        for (Object[] row : uploaders) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uploader", row[0]);
            item.put("fileCount", ((Number) row[1]).longValue());
            item.put("totalBytes", ((Number) row[2]).longValue());
            topUploaders.add(item);
            if (topUploaders.size() >= 5) {
                break;
            }
        }
        stats.put("topUploaders", topUploaders);
        stats.put("recentTransfers", PageSupport.mapList(
                transferLogRepository.findTop20ByDeletedFalseOrderByStartTimeDesc(), this::toLogView));
        stats.put("chunkSize", storage.chunkSize());
        stats.put("objectStorage", minioStorage.status());
        return stats;
    }

    /** 传输日志视图（文件交换页与传输任务详情共用） */
    public Map<String, Object> toLogView(com.share.ops.file.entity.FlTransferLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("taskId", l.getTaskId());
        m.put("taskName", l.getTaskName());
        m.put("batchId", l.getBatchId());
        m.put("sourceFile", l.getSourceFile());
        m.put("targetFile", l.getTargetFile());
        m.put("fileSize", l.getFileSize());
        m.put("fileSizeText", humanSize(l.getFileSize() == null ? 0L : l.getFileSize()));
        m.put("md5", l.getMd5());
        m.put("status", l.getStatus());
        m.put("costMs", l.getCostMs());
        m.put("triggerType", l.getTriggerType());
        m.put("retryCount", l.getRetryCount());
        m.put("errorMsg", l.getErrorMsg());
        m.put("startTime", l.getStartTime());
        m.put("endTime", l.getEndTime());
        return m;
    }

    /** 文件视图（列表/详情共用，含下载地址） */
    public Map<String, Object> toView(FlFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("fileName", f.getFileName());
        m.put("fileSize", f.getFileSize());
        m.put("fileSizeText", humanSize(f.getFileSize() == null ? 0L : f.getFileSize()));
        m.put("contentType", f.getContentType());
        m.put("extName", f.getExtName());
        m.put("md5", f.getMd5());
        m.put("uploader", f.getUploader());
        m.put("uploaderId", f.getUploaderId());
        m.put("deptId", f.getDeptId());
        m.put("status", f.getStatus());
        m.put("secretLevel", f.getSecretLevel());
        m.put("bizType", f.getBizType());
        m.put("storageType", f.getStorageType());
        m.put("objectKey", f.getObjectKey());
        m.put("remark", f.getRemark());
        m.put("createTime", f.getCreateTime());
        m.put("downloadUrl", "/api/file/v1/files/" + f.getId() + "/download");
        return m;
    }

    /** 磁盘文件是否存在（视图辅助，供种子数据/巡检使用） */
    public boolean existsOnDisk(FlFile f) {
        return Files.exists(storage.resolve(f.getFilePath()));
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
