package com.share.ops.file.controller;

import com.share.common.api.Result;
import com.share.common.audit.AuditLog;
import com.share.common.constant.PermissionConstants;
import com.share.common.security.RequirePermission;
import com.share.ops.file.entity.FlFile;
import com.share.ops.file.service.ChunkUploadService;
import com.share.ops.file.service.FileService;
import com.share.ops.support.ResultSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件交换接口（FR-09）：上传、列表、详情、下载、删除、完整性校验、分片断点续传、统计。
 */
@Slf4j
@RestController
@RequestMapping("/api/file/v1")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final ChunkUploadService chunkUploadService;

    // ================= 文件 =================

    /** 上传文件（multipart）：落盘 + MD5/SHA-256 + 记录入库 */
    @PostMapping("/files")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_UPLOAD", targetType = "FILE")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) Integer secretLevel,
                                              @RequestParam(required = false) String remark) {
        FlFile saved = fileService.upload(file, deptId, secretLevel, remark);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", saved.getId());
        body.put("fileName", saved.getFileName());
        body.put("fileSize", saved.getFileSize());
        body.put("fileSizeText", FileService.humanSize(saved.getFileSize() == null ? 0L : saved.getFileSize()));
        body.put("md5", saved.getMd5());
        body.put("sha256", saved.getSha256());
        body.put("status", saved.getStatus());
        body.put("uploader", saved.getUploader());
        body.put("createTime", saved.getCreateTime());
        body.put("downloadUrl", "/api/file/v1/files/" + saved.getId() + "/download");
        return Result.ok(body);
    }

    /** 文件分页列表（keyword 匹配文件名/上传人/MD5） */
    @GetMapping("/files")
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String uploader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return ResultSupport.paged(fileService.page(keyword, deptId, uploader, status, bizType,
                from, to, pageNum, pageSize));
    }

    /** 文件详情 */
    @GetMapping("/files/{id}")
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(fileService.detail(id));
    }

    /** 文件下载（流式，Content-Disposition 带原始文件名） */
    @GetMapping("/files/{id}/download")
    @RequirePermission(PermissionConstants.FILE_READ)
    @AuditLog(action = "FILE_DOWNLOAD", targetType = "FILE", recordArgs = false)
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
        FlFile file = fileService.findForDownload(id);
        Path path = fileService.pathOf(file);
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = Files.newInputStream(path)) {
                in.transferTo(outputStream);
            }
        };
        String encoded = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
        long length = file.getFileSize() == null ? 0L : file.getFileSize();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .body(body);
    }

    /** 删除文件（逻辑删除 + 清理磁盘文件） */
    @DeleteMapping("/files/{id}")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_DELETE", targetType = "FILE")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.ok();
    }

    /** 完整性校验：重算 MD5/SHA-256 与登记值比对 */
    @PostMapping("/files/{id}/verify")
    @RequirePermission(PermissionConstants.FILE_READ)
    @AuditLog(action = "FILE_VERIFY", targetType = "FILE")
    public Result<Map<String, Object>> verify(@PathVariable Long id) {
        return Result.ok(fileService.verify(id));
    }

    // ================= 分片断点续传 =================

    /** 上传单个分片（index 从 0 开始，md5 可选：分片 MD5） */
    @PostMapping("/uploads/chunk")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    public Result<Map<String, Object>> uploadChunk(@RequestParam("uploadId") String uploadId,
                                                   @RequestParam("index") Integer index,
                                                   @RequestParam("total") Integer total,
                                                   @RequestParam(required = false) String md5,
                                                   @RequestParam("file") MultipartFile file) {
        return Result.ok(chunkUploadService.uploadChunk(uploadId, index, total, md5, file));
    }

    /** 查询已完成分片清单（断点续传：客户端据此补传缺失分片） */
    @GetMapping("/uploads/{uploadId}")
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<Map<String, Object>> chunkStatus(@PathVariable String uploadId) {
        return Result.ok(chunkUploadService.status(uploadId));
    }

    /** 合并分片：整体 MD5 校验通过后落 fl_file */
    @PostMapping("/uploads/complete")
    @RequirePermission(PermissionConstants.FILE_WRITE)
    @AuditLog(action = "FILE_CHUNK_COMPLETE", targetType = "FILE")
    public Result<Map<String, Object>> complete(@RequestBody(required = false) Map<String, Object> body,
                                                @RequestParam(required = false) String uploadId) {
        String uid = uploadId;
        String fileName = null;
        Long deptId = null;
        Integer secretLevel = null;
        String remark = null;
        if (body != null) {
            if (uid == null && body.get("uploadId") != null) {
                uid = String.valueOf(body.get("uploadId"));
            }
            if (body.get("fileName") != null) {
                fileName = String.valueOf(body.get("fileName"));
            }
            if (body.get("deptId") != null && !String.valueOf(body.get("deptId")).isBlank()) {
                deptId = Long.valueOf(String.valueOf(body.get("deptId")));
            }
            if (body.get("secretLevel") != null && !String.valueOf(body.get("secretLevel")).isBlank()) {
                secretLevel = Integer.valueOf(String.valueOf(body.get("secretLevel")));
            }
            if (body.get("remark") != null) {
                remark = String.valueOf(body.get("remark"));
            }
        }
        return Result.ok(chunkUploadService.complete(uid, fileName, deptId, secretLevel, remark));
    }

    // ================= 统计 =================

    /** 文件交换统计：文件数/总字节/成功失败传输数/今日上传/传输成功率 */
    @GetMapping("/stats")
    @RequirePermission(PermissionConstants.FILE_READ)
    public Result<Map<String, Object>> stats() {
        return Result.ok(fileService.stats());
    }
}
