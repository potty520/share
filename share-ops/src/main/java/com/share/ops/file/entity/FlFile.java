package com.share.ops.file.entity;

import com.share.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件交换记录（FR-09）：落盘文件元数据 + 完整性校验值。
 * <p>物理文件存放于 {@code share.file.root} 下，filePath 为相对该根目录（或绝对路径）的定位串。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "fl_file", indexes = {
        @Index(name = "idx_fl_file_uploader", columnList = "uploader"),
        @Index(name = "idx_fl_file_create_time", columnList = "create_time"),
        @Index(name = "idx_fl_file_md5", columnList = "md5")
})
public class FlFile extends BaseEntity {

    /** 原始文件名 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 落盘文件名（UUID + 扩展名，避免重名覆盖） */
    @Column(name = "stored_name", length = 255)
    private String storedName;

    /** 文件定位串：相对根目录路径或绝对路径 */
    @Column(name = "file_path", length = 600)
    private String filePath;

    /** 文件字节数 */
    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "ext_name", length = 32)
    private String extName;

    /** MD5 校验值（32 位小写十六进制） */
    @Column(name = "md5", length = 64)
    private String md5;

    /** SHA-256 校验值（64 位小写十六进制） */
    @Column(name = "sha256", length = 128)
    private String sha256;

    @Column(name = "uploader", length = 64)
    private String uploader;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "dept_id")
    private Long deptId;

    /** 状态：SUCCESS（已落盘）/ VERIFIED（校验通过）/ CORRUPTED（校验失败） */
    @Column(name = "status", length = 32)
    private String status;

    /** 密级 1~4 */
    @Column(name = "secret_level")
    private Integer secretLevel;

    /** 分片数（分片上传时为实际分片数量，直传为 1） */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 业务类型：UPLOAD / CHUNK / TRANSFER / DEMO */
    @Column(name = "biz_type", length = 32)
    private String bizType;

    @Column(name = "remark", length = 500)
    private String remark;

    /** 存储后端：LOCAL / MINIO */
    @Column(name = "storage_type", length = 20)
    private String storageType = "LOCAL";

    /** 对象存储 key（MinIO 启用时写入） */
    @Column(name = "object_key", length = 600)
    private String objectKey;
}
