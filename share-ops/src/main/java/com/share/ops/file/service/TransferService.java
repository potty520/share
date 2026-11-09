package com.share.ops.file.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.common.util.IdGen;
import com.share.ops.file.entity.FlTransferLog;
import com.share.ops.file.entity.FlTransferTask;
import com.share.ops.file.repository.FlTransferLogRepository;
import com.share.ops.file.repository.FlTransferTaskRepository;
import com.share.ops.support.OpsConstants;
import com.share.ops.support.PageSupport;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件收发任务服务（FR-09）：任务 CRUD、立即执行（真实复制文件 + 传输日志）、cron 调度与目录监听扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final FlTransferTaskRepository taskRepository;
    private final FlTransferLogRepository logRepository;
    private final FileStorageService storage;

    // ================= 任务 CRUD =================

    @Transactional(readOnly = true)
    public Map<String, Object> page(String keyword, Boolean enabled, String sourceType,
                                    Integer pageNum, Integer pageSize) {
        int pn = PageSupport.pageNum(pageNum);
        int ps = PageSupport.pageSize(pageSize);
        Specification<FlTransferTask> spec = (root, query, cb) -> {
            List<Predicate> ps2 = new ArrayList<>();
            ps2.add(cb.isFalse(root.get("deleted")));
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps2.add(cb.or(cb.like(cb.lower(root.get("taskName")), kw),
                        cb.like(cb.lower(root.get("sourcePath")), kw),
                        cb.like(cb.lower(root.get("targetPath")), kw)));
            }
            if (enabled != null) {
                ps2.add(cb.equal(root.get("enabled"), enabled));
            }
            if (StringUtils.hasText(sourceType)) {
                ps2.add(cb.equal(root.get("sourceType"), sourceType));
            }
            return cb.and(ps2.toArray(new Predicate[0]));
        };
        Page<FlTransferTask> page = taskRepository.findAll(spec,
                PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "createTime")));
        return PageSupport.of(page, this::toView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        FlTransferTask task = find(id);
        Map<String, Object> view = toView(task);
        view.put("recentLogs", PageSupport.mapList(
                logRepository.findTop50ByTaskIdAndDeletedFalseOrderByStartTimeDesc(id), this::toLogView));
        return view;
    }

    @Transactional(readOnly = true)
    public FlTransferTask find(Long id) {
        return taskRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文件收发任务不存在: " + id));
    }

    @Transactional
    public Map<String, Object> create(FlTransferTask req) {
        validate(req, null);
        req.setId(null);
        req.setDeleted(false);
        if (req.getEnabled() == null) {
            req.setEnabled(false);
        }
        if (req.getDeptId() == null) {
            req.setDeptId(UserContext.deptId());
        }
        if (req.getTransferMode() == null) {
            req.setTransferMode(OpsConstants.TRANSFER_MODE_FILE);
        }
        if (!StringUtils.hasText(req.getSourceType())) {
            req.setSourceType("LOCAL");
        }
        if (!StringUtils.hasText(req.getTargetType())) {
            req.setTargetType("LOCAL");
        }
        FlTransferTask saved = taskRepository.save(req);
        return toView(saved);
    }

    @Transactional
    public Map<String, Object> update(Long id, FlTransferTask req) {
        FlTransferTask task = find(id);
        validate(req, task.getId());
        if (StringUtils.hasText(req.getTaskName())) {
            task.setTaskName(req.getTaskName());
        }
        if (req.getSourceType() != null) {
            task.setSourceType(req.getSourceType());
        }
        if (req.getSourcePath() != null) {
            task.setSourcePath(req.getSourcePath());
        }
        if (req.getTargetType() != null) {
            task.setTargetType(req.getTargetType());
        }
        if (req.getTargetPath() != null) {
            task.setTargetPath(req.getTargetPath());
        }
        if (req.getScheduleCron() != null) {
            task.setScheduleCron(req.getScheduleCron());
        }
        if (req.getWatchPath() != null) {
            task.setWatchPath(req.getWatchPath());
        }
        if (req.getEnabled() != null) {
            task.setEnabled(req.getEnabled());
        }
        if (req.getTransferMode() != null) {
            task.setTransferMode(req.getTransferMode());
        }
        if (req.getRemark() != null) {
            task.setRemark(req.getRemark());
        }
        if (req.getDeptId() != null) {
            task.setDeptId(req.getDeptId());
        }
        FlTransferTask saved = taskRepository.save(task);
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        FlTransferTask task = find(id);
        task.setDeleted(true);
        taskRepository.save(task);
    }

    // ================= 调度与执行 =================

    /** cron 到点触发：按 lastRunTime 计算下一次触发时间，落在本分钟内的任务执行一次 */
    @Transactional
    public List<Long> tickScheduled() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> triggered = new ArrayList<>();
        for (FlTransferTask task : taskRepository.findAllByEnabledTrueAndDeletedFalse()) {
            if (!StringUtils.hasText(task.getScheduleCron())) {
                continue;
            }
            try {
                CronExpression cron = CronExpression.parse(task.getScheduleCron().trim());
                LocalDateTime base = task.getLastRunTime() == null ? now.minusMinutes(1) : task.getLastRunTime();
                LocalDateTime next = cron.next(base);
                if (next != null && !next.isAfter(now)) {
                    execute(task, OpsConstants.TRIGGER_CRON, null, null);
                    triggered.add(task.getId());
                }
            } catch (IllegalArgumentException e) {
                log.warn("文件任务 {} 的 cron 表达式非法: {}", task.getId(), task.getScheduleCron());
            }
        }
        return triggered;
    }

    /** 目录监听：watchPath 下出现新文件（修改时间晚于上次扫描）时触发一次传输 */
    @Transactional
    public List<Long> tickWatch() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> triggered = new ArrayList<>();
        for (FlTransferTask task : taskRepository.findAllByEnabledTrueAndDeletedFalse()) {
            if (!StringUtils.hasText(task.getWatchPath())) {
                continue;
            }
            Path dir = Paths.get(task.getWatchPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            LocalDateTime from = task.getLastScanTime() == null ? now.minusMinutes(10) : task.getLastScanTime();
            List<Path> newFiles = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant()
                                .isAfter(from.atZone(java.time.ZoneId.systemDefault()).toInstant())) {
                            newFiles.add(p);
                        }
                    } catch (IOException ignored) {
                        // 单个文件读取失败跳过
                    }
                });
            } catch (IOException e) {
                log.warn("扫描监听目录失败 {}: {}", dir, e.getMessage());
            }
            newFiles.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });
            if (!newFiles.isEmpty()) {
                // 每次扫描只取最新的一个文件，避免同一批文件重复传输
                execute(task, OpsConstants.TRIGGER_WATCH, newFiles.get(0).toString(), null);
                triggered.add(task.getId());
            }
            task.setLastScanTime(now);
            taskRepository.save(task);
        }
        return triggered;
    }

    /** 立即执行 */
    @Transactional
    public Map<String, Object> run(Long id) {
        FlTransferTask task = find(id);
        FlTransferLog transferLog = execute(task, OpsConstants.TRIGGER_MANUAL, null, null);
        return toLogView(transferLog);
    }

    /**
     * 真实复制文件并写传输日志。
     *
     * @param task           任务
     * @param triggerType    MANUAL / CRON / WATCH
     * @param overrideSource 目录监听触发时的具体源文件
     * @param retryCount     已重试次数
     */
    @Transactional
    public FlTransferLog execute(FlTransferTask task, String triggerType, String overrideSource, Integer retryCount) {
        LocalDateTime start = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        FlTransferLog transferLog = new FlTransferLog();
        transferLog.setTaskId(task.getId());
        transferLog.setTaskName(task.getTaskName());
        transferLog.setBatchId(IdGen.batchId());
        transferLog.setTriggerType(triggerType);
        transferLog.setRetryCount(retryCount == null ? 0 : retryCount);
        transferLog.setStartTime(start);
        String source = StringUtils.hasText(overrideSource) ? overrideSource : task.getSourcePath();
        transferLog.setSourceFile(source);
        try {
            if (!StringUtils.hasText(source)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "任务未配置源路径");
            }
            Path src = Paths.get(source).toAbsolutePath().normalize();
            if (!Files.exists(src)) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND, "源路径不存在: " + source);
            }
            Path targetRoot = requireTarget(task);
            CopyResult result = Files.isDirectory(src)
                    ? copyDirectory(src, targetRoot)
                    : copySingleFile(src, targetRoot);
            transferLog.setTargetFile(result.target().toString());
            transferLog.setFileSize(result.bytes());
            transferLog.setMd5(result.md5());
            transferLog.setStatus(OpsConstants.TRANSFER_SUCCESS);
            transferLog.setEndTime(LocalDateTime.now());
            transferLog.setCostMs(System.currentTimeMillis() - t0);
            task.setLastStatus(OpsConstants.TRANSFER_SUCCESS);
            task.setLastMessage("传输成功：" + result.description());
        } catch (Exception e) {
            transferLog.setStatus(OpsConstants.TRANSFER_FAIL);
            transferLog.setErrorMsg(truncate(e.getMessage(), 900));
            transferLog.setEndTime(LocalDateTime.now());
            transferLog.setCostMs(System.currentTimeMillis() - t0);
            task.setLastStatus(OpsConstants.TRANSFER_FAIL);
            task.setLastMessage(truncate("传输失败：" + e.getMessage(), 480));
            log.warn("文件传输失败 task={} source={} err={}", task.getId(), source, e.getMessage());
        }
        FlTransferLog saved = logRepository.save(transferLog);
        task.setLastRunTime(start);
        taskRepository.save(task);
        return saved;
    }

    // ================= 复制实现 =================

    private Path requireTarget(FlTransferTask task) {
        if (!StringUtils.hasText(task.getTargetPath())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务未配置目标路径");
        }
        return Paths.get(task.getTargetPath()).toAbsolutePath().normalize();
    }

    /** 单文件复制：目标为目录时保留源文件名，复制后比对 MD5 保证不丢不错 */
    private CopyResult copySingleFile(Path src, Path targetPath) throws IOException {
        Path target = Files.isDirectory(targetPath) || taskTargetLooksLikeDir(targetPath)
                ? targetPath.resolve(src.getFileName().toString())
                : targetPath;
        if (target.getParent() != null) {
            storage.ensureDir(target.getParent());
        }
        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        long bytes = Files.size(target);
        String srcMd5 = storage.md5(src);
        String targetMd5 = storage.md5(target);
        if (!srcMd5.equalsIgnoreCase(targetMd5)) {
            throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                    "目标文件 MD5 与源文件不一致（源 " + srcMd5 + " / 目标 " + targetMd5 + "）");
        }
        return new CopyResult(target, bytes, srcMd5,
                "文件 " + src.getFileName() + " → " + target.getFileName() + "（" + bytes + " 字节，MD5 一致）");
    }

    /** 目录批量复制：保留相对目录结构，逐文件校验 MD5 */
    private CopyResult copyDirectory(Path srcDir, Path targetDir) throws IOException {
        storage.ensureDir(targetDir);
        long[] total = {0L};
        int[] fileCount = {0};
        String[] firstMd5 = {null};
        try (var walk = Files.walk(srcDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path rel = srcDir.relativize(file);
                Path target = targetDir.resolve(rel);
                if (target.getParent() != null) {
                    storage.ensureDir(target.getParent());
                }
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                String srcMd5 = storage.md5(file);
                String targetMd5 = storage.md5(target);
                if (!srcMd5.equalsIgnoreCase(targetMd5)) {
                    throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                            "目录复制后 MD5 不一致: " + rel);
                }
                if (firstMd5[0] == null) {
                    firstMd5[0] = srcMd5;
                }
                total[0] += Files.size(target);
                fileCount[0]++;
            }
        }
        return new CopyResult(targetDir, total[0], firstMd5[0],
                "目录 " + srcDir.getFileName() + " 共 " + fileCount[0] + " 个文件 → " + targetDir + "（" + total[0] + " 字节，MD5 全部一致）");
    }

    private boolean taskTargetLooksLikeDir(Path targetPath) {
        String raw = targetPath.toString();
        return raw.endsWith("/") || raw.endsWith("\\");
    }

    // ================= 视图 =================

    public Map<String, Object> toView(FlTransferTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("taskName", t.getTaskName());
        m.put("sourceType", t.getSourceType());
        m.put("sourcePath", t.getSourcePath());
        m.put("targetType", t.getTargetType());
        m.put("targetPath", t.getTargetPath());
        m.put("scheduleCron", t.getScheduleCron());
        m.put("watchPath", t.getWatchPath());
        m.put("enabled", t.getEnabled());
        m.put("transferMode", t.getTransferMode());
        m.put("deptId", t.getDeptId());
        m.put("lastRunTime", t.getLastRunTime());
        m.put("lastStatus", t.getLastStatus());
        m.put("lastMessage", t.getLastMessage());
        m.put("lastScanTime", t.getLastScanTime());
        m.put("remark", t.getRemark());
        m.put("createTime", t.getCreateTime());
        m.put("updateTime", t.getUpdateTime());
        m.put("nextRunTime", nextRunTime(t));
        m.put("sourceExists", t.getSourcePath() != null && Files.exists(Paths.get(t.getSourcePath())));
        m.put("watchDirExists", t.getWatchPath() != null && Files.isDirectory(Paths.get(t.getWatchPath())));
        return m;
    }

    public Map<String, Object> toLogView(FlTransferLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("taskId", l.getTaskId());
        m.put("taskName", l.getTaskName());
        m.put("batchId", l.getBatchId());
        m.put("sourceFile", l.getSourceFile());
        m.put("targetFile", l.getTargetFile());
        m.put("fileSize", l.getFileSize());
        m.put("fileSizeText", FileService.humanSize(l.getFileSize() == null ? 0L : l.getFileSize()));
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

    // ================= 内部工具 =================

    private String nextRunTime(FlTransferTask task) {
        if (!StringUtils.hasText(task.getScheduleCron())) {
            return null;
        }
        try {
            CronExpression cron = CronExpression.parse(task.getScheduleCron().trim());
            LocalDateTime base = task.getLastRunTime() == null ? LocalDateTime.now() : task.getLastRunTime();
            LocalDateTime next = cron.next(base);
            return next == null ? null : next.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validate(FlTransferTask req, Long selfId) {
        if (!StringUtils.hasText(req.getTaskName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务名称不能为空");
        }
        if (!StringUtils.hasText(req.getSourcePath())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "源路径不能为空");
        }
        if (!StringUtils.hasText(req.getTargetPath())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标路径不能为空");
        }
        if (StringUtils.hasText(req.getScheduleCron())) {
            try {
                CronExpression.parse(req.getScheduleCron().trim());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "cron 表达式不合法: " + req.getScheduleCron());
            }
        }
        if (selfId == null && taskRepository.existsByTaskNameAndDeletedFalse(req.getTaskName())) {
            throw new BizException(ErrorCode.CONFLICT, "任务名称已存在: " + req.getTaskName());
        }
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max) + "...";
    }

    /** 复制结果 */
    private record CopyResult(Path target, long bytes, String md5, String description) {
    }
}
