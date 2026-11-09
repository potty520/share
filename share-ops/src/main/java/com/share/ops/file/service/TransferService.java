package com.share.ops.file.service;

import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import com.share.common.security.UserContext;
import com.share.common.util.CryptoUtil;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import javax.crypto.spec.SecretKeySpec;

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
    private final RemoteFileService remoteFileService;

    @Value("${share.crypto.key:share-exchange-local-crypto-key-change-me}")
    private String cryptoKey;

    private volatile SecretKeySpec secretKey;

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
        req.setSourcePassword(encryptPassword(req.getSourcePassword()));
        req.setTargetPassword(encryptPassword(req.getTargetPassword()));
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
        if (req.getSourceHost() != null) {
            task.setSourceHost(req.getSourceHost());
        }
        if (req.getSourcePort() != null) {
            task.setSourcePort(req.getSourcePort());
        }
        if (req.getSourceUsername() != null) {
            task.setSourceUsername(req.getSourceUsername());
        }
        if (StringUtils.hasText(req.getSourcePassword())) {
            task.setSourcePassword(encryptPassword(req.getSourcePassword()));
        }
        if (req.getTargetHost() != null) {
            task.setTargetHost(req.getTargetHost());
        }
        if (req.getTargetPort() != null) {
            task.setTargetPort(req.getTargetPort());
        }
        if (req.getTargetUsername() != null) {
            task.setTargetUsername(req.getTargetUsername());
        }
        if (StringUtils.hasText(req.getTargetPassword())) {
            task.setTargetPassword(encryptPassword(req.getTargetPassword()));
        }
        if (req.getCompress() != null) {
            task.setCompress(req.getCompress().toUpperCase(Locale.ROOT));
        }
        if (req.getEncryptFlag() != null) {
            task.setEncryptFlag(req.getEncryptFlag());
        }
        if (req.getRetryTimes() != null) {
            task.setRetryTimes(req.getRetryTimes());
        }
        if (req.getRetryIntervalSeconds() != null) {
            task.setRetryIntervalSeconds(req.getRetryIntervalSeconds());
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
        int retryTimes = task.getRetryTimes() == null ? 0 : Math.max(0, Math.min(task.getRetryTimes(), 5));
        int intervalSeconds = task.getRetryIntervalSeconds() == null ? 0
                : Math.max(0, Math.min(task.getRetryIntervalSeconds(), 30));
        String lastError = null;
        for (int attempt = 0; attempt <= retryTimes; attempt++) {
            transferLog.setRetryCount((retryCount == null ? 0 : retryCount) + attempt);
            try {
                if (!StringUtils.hasText(source)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "任务未配置源路径");
                }
                TransferOutcome outcome = performTransfer(task, source);
                transferLog.setTargetFile(outcome.target());
                transferLog.setFileSize(outcome.bytes());
                transferLog.setMd5(outcome.md5());
                transferLog.setStatus(OpsConstants.TRANSFER_SUCCESS);
                transferLog.setErrorMsg(null);
                task.setLastStatus(OpsConstants.TRANSFER_SUCCESS);
                task.setLastMessage("传输成功：" + outcome.description());
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("文件传输失败 task={} source={} attempt={}/{} err={}", task.getId(), source,
                        attempt + 1, retryTimes + 1, e.getMessage());
                if (attempt < retryTimes) {
                    if (intervalSeconds > 0) {
                        try {
                            Thread.sleep(intervalSeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
        transferLog.setEndTime(LocalDateTime.now());
        transferLog.setCostMs(System.currentTimeMillis() - t0);
        if (lastError != null) {
            transferLog.setStatus(OpsConstants.TRANSFER_FAIL);
            transferLog.setErrorMsg(truncate(lastError, 900));
            task.setLastStatus(OpsConstants.TRANSFER_FAIL);
            task.setLastMessage(truncate("传输失败：" + lastError, 480));
        }
        FlTransferLog saved = logRepository.save(transferLog);
        task.setLastRunTime(start);
        taskRepository.save(task);
        return saved;
    }

    /** 按源/目标类型分派：LOCAL↔LOCAL、LOCAL↔SFTP/FTP/FTPS、远端↔远端。 */
    private TransferOutcome performTransfer(FlTransferTask task, String source) throws Exception {
        String sourceType = normalizeType(task.getSourceType());
        String targetType = normalizeType(task.getTargetType());
        boolean sourceRemote = remoteFileService.isRemote(sourceType);
        boolean targetRemote = remoteFileService.isRemote(targetType);
        if (!sourceRemote && !"LOCAL".equals(sourceType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的源类型: " + sourceType);
        }
        if (!targetRemote && !"LOCAL".equals(targetType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的目标类型: " + targetType);
        }
        if (!sourceRemote && !targetRemote) {
            return localToLocal(task, source);
        }
        if (sourceRemote) {
            String remoteName = remoteFileName(source);
            Path pullDir = storage.tmpDir().resolve("pull-" + UUID.randomUUID().toString().replace("-", ""));
            storage.ensureDir(pullDir);
            Path pulled = pullDir.resolve(remoteName);
            try {
                remoteFileService.download(remoteConfig(task, true), source, pulled);
                if (!targetRemote) {
                    return moveToLocal(task, pulled, remoteName, sourceType);
                }
                Path prepared = applyTransform(pulled, task);
                try {
                    return pushToRemote(task, prepared, sourceType + " → " + targetType);
                } finally {
                    cleanupTemp(prepared, pulled);
                }
            } finally {
                try {
                    Files.deleteIfExists(pulled);
                    Files.deleteIfExists(pullDir);
                } catch (IOException ignore) {
                    // 临时文件清理失败不影响传输结果
                }
            }
        }
        Path src = Paths.get(source).toAbsolutePath().normalize();
        if (!Files.exists(src)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "源路径不存在: " + source);
        }
        if (Files.isDirectory(src)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "远端传输仅支持单文件（FILE）模式");
        }
        Path prepared = applyTransform(src, task);
        try {
            return pushToRemote(task, prepared, "LOCAL → " + targetType);
        } finally {
            cleanupTemp(prepared, src);
        }
    }

    private TransferOutcome localToLocal(FlTransferTask task, String source) throws IOException {
        Path src = Paths.get(source).toAbsolutePath().normalize();
        if (!Files.exists(src)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "源路径不存在: " + source);
        }
        Path targetRoot = requireTarget(task);
        if (Files.isDirectory(src)) {
            if (Boolean.TRUE.equals(task.getEncryptFlag()) || isGzip(task)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "目录模式不支持压缩/加密，请使用 FILE 模式");
            }
            CopyResult result = copyDirectory(src, targetRoot);
            return new TransferOutcome(result.target().toString(), result.bytes(), result.md5(),
                    result.description());
        }
        Path prepared = applyTransform(src, task);
        try {
            Path target = resolveLocalTarget(task.getTargetPath(), prepared.getFileName().toString());
            if (target.getParent() != null) {
                storage.ensureDir(target.getParent());
            }
            Files.copy(prepared, target, StandardCopyOption.REPLACE_EXISTING);
            long bytes = Files.size(target);
            String md5 = storage.md5(prepared);
            if (!md5.equalsIgnoreCase(storage.md5(target))) {
                throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH, "目标文件 MD5 与源文件不一致");
            }
            String extra = prepared.equals(src) ? "MD5 一致" : "压缩/加密后 MD5 一致";
            return new TransferOutcome(target.toString(), bytes, md5,
                    "文件 " + src.getFileName() + " → " + target.getFileName() + "（" + bytes + " 字节，" + extra + "）");
        } finally {
            cleanupTemp(prepared, src);
        }
    }

    private TransferOutcome pushToRemote(FlTransferTask task, Path prepared, String route) throws IOException {
        String remotePath = resolveRemotePath(task.getTargetPath(), prepared.getFileName().toString());
        RemoteFileService.RemoteConfig config = remoteConfig(task, false);
        remoteFileService.upload(config, prepared, remotePath);
        long localSize = Files.size(prepared);
        long remoteSize = remoteFileService.size(config, remotePath);
        if (remoteSize >= 0 && remoteSize != localSize) {
            throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH,
                    "远端文件大小校验失败（本地 " + localSize + " / 远端 " + remoteSize + "）");
        }
        String md5 = storage.md5(prepared);
        return new TransferOutcome(remotePath, localSize, md5,
                route + " 上传 " + prepared.getFileName() + " → " + remotePath + "（" + localSize + " 字节，MD5 " + md5 + "）");
    }

    private TransferOutcome moveToLocal(FlTransferTask task, Path pulled, String remoteName,
                                        String sourceType) throws IOException {
        Path target = resolveLocalTarget(task.getTargetPath(), remoteName);
        if (target.getParent() != null) {
            storage.ensureDir(target.getParent());
        }
        Files.copy(pulled, target, StandardCopyOption.REPLACE_EXISTING);
        long bytes = Files.size(target);
        String md5 = storage.md5(pulled);
        if (!md5.equalsIgnoreCase(storage.md5(target))) {
            throw new BizException(ErrorCode.FILE_CHECKSUM_MISMATCH, "下载后 MD5 校验不一致");
        }
        return new TransferOutcome(target.toString(), bytes, md5,
                sourceType + " → LOCAL 下载 " + remoteName + " → " + target + "（" + bytes + " 字节，MD5 一致）");
    }

    /** 按任务配置做 GZIP 压缩 / AES-256-GCM 加密，返回处理后的临时文件（无处理则原样返回）。 */
    private Path applyTransform(Path src, FlTransferTask task) throws IOException {
        boolean gzip = isGzip(task);
        boolean encrypt = Boolean.TRUE.equals(task.getEncryptFlag());
        if (!gzip && !encrypt) {
            return src;
        }
        String name = src.getFileName().toString() + (gzip ? ".gz" : "") + (encrypt ? ".enc" : "");
        Path txDir = storage.tmpDir().resolve("tx-" + UUID.randomUUID().toString().replace("-", ""));
        storage.ensureDir(txDir);
        Path out = txDir.resolve(safeName(name));
        try (OutputStream raw = Files.newOutputStream(out)) {
            OutputStream encoded = encrypt ? CryptoUtil.encryptingStream(raw, key()) : raw;
            if (gzip) {
                try (GZIPOutputStream gz = new GZIPOutputStream(encoded)) {
                    Files.copy(src, gz);
                }
            } else {
                Files.copy(src, encoded);
                encoded.close();
            }
        }
        return out;
    }

    private void cleanupTemp(Path prepared, Path original) {
        if (prepared == null || prepared.equals(original)) {
            return;
        }
        try {
            Files.deleteIfExists(prepared);
            Path parent = prepared.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("tx-")) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ignore) {
            // 临时文件清理失败不影响传输结果
        }
    }

    private boolean isGzip(FlTransferTask task) {
        return "GZIP".equalsIgnoreCase(task.getCompress());
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "LOCAL" : type.trim().toUpperCase(Locale.ROOT);
    }

    private Path resolveLocalTarget(String rawPath, String fileName) {
        Path targetPath = Paths.get(rawPath).toAbsolutePath().normalize();
        boolean dir = rawPath.endsWith("/") || rawPath.endsWith("\\") || Files.isDirectory(targetPath);
        return dir ? targetPath.resolve(fileName) : targetPath;
    }

    private String resolveRemotePath(String rawPath, String fileName) {
        String path = rawPath.replace('\\', '/');
        return path.endsWith("/") ? path + fileName : path;
    }

    private String remoteFileName(String remotePath) {
        String path = remotePath.replace('\\', '/');
        int idx = path.lastIndexOf('/');
        String name = idx >= 0 ? path.substring(idx + 1) : path;
        return safeName(name.isBlank() ? "remote-file" : name);
    }

    private RemoteFileService.RemoteConfig remoteConfig(FlTransferTask task, boolean source) {
        String type = normalizeType(source ? task.getSourceType() : task.getTargetType());
        String host = source ? task.getSourceHost() : task.getTargetHost();
        Integer port = source ? task.getSourcePort() : task.getTargetPort();
        String username = source ? task.getSourceUsername() : task.getTargetUsername();
        String password = decryptPassword(source ? task.getSourcePassword() : task.getTargetPassword());
        int defaultPort = "SFTP".equals(type) ? 22 : 21;
        return new RemoteFileService.RemoteConfig(type, host, port == null ? defaultPort : port, username, password);
    }

    private SecretKeySpec key() {
        if (secretKey == null) {
            secretKey = CryptoUtil.deriveKey(cryptoKey);
        }
        return secretKey;
    }

    private String encryptPassword(String plain) {
        return StringUtils.hasText(plain) ? CryptoUtil.encrypt(plain, key()) : null;
    }

    private String decryptPassword(String cipher) {
        if (!StringUtils.hasText(cipher)) {
            return null;
        }
        try {
            return CryptoUtil.decrypt(cipher, key());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS, "远端口令解密失败，请重新填写口令");
        }
    }

    private String safeName(String name) {
        String value = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return value.length() > 100 ? value.substring(value.length() - 100) : value;
    }

    private record TransferOutcome(String target, long bytes, String md5, String description) {
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
        m.put("sourceHost", t.getSourceHost());
        m.put("sourcePort", t.getSourcePort());
        m.put("sourceUsername", t.getSourceUsername());
        m.put("sourcePasswordSet", StringUtils.hasText(t.getSourcePassword()));
        m.put("targetHost", t.getTargetHost());
        m.put("targetPort", t.getTargetPort());
        m.put("targetUsername", t.getTargetUsername());
        m.put("targetPasswordSet", StringUtils.hasText(t.getTargetPassword()));
        m.put("compress", t.getCompress());
        m.put("encryptFlag", Boolean.TRUE.equals(t.getEncryptFlag()));
        m.put("retryTimes", t.getRetryTimes());
        m.put("retryIntervalSeconds", t.getRetryIntervalSeconds());
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
