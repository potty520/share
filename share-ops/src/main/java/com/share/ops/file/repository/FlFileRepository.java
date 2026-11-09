package com.share.ops.file.repository;

import com.share.ops.file.entity.FlFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 文件记录仓储。 */
public interface FlFileRepository extends JpaRepository<FlFile, Long>, JpaSpecificationExecutor<FlFile> {

    Optional<FlFile> findByIdAndDeletedFalse(Long id);

    boolean existsByMd5AndDeletedFalse(String md5);

    long countByDeletedFalse();

    /** 指定时间之后上传的文件数（今日上传量） */
    long countByDeletedFalseAndCreateTimeAfter(java.time.LocalDateTime time);

    /** 已落盘文件总字节数 */
    @Query("select coalesce(sum(f.fileSize), 0) from FlFile f where f.deleted = false")
    Long sumFileSize();

    /** 指定业务类型的文件数量 */
    long countByBizTypeAndDeletedFalse(String bizType);

    /** 按上传人统计（用于文件交换排行） */
    @Query("select f.uploader, count(f), coalesce(sum(f.fileSize), 0) from FlFile f "
            + "where f.deleted = false and f.uploader is not null group by f.uploader order by count(f) desc")
    java.util.List<Object[]> groupByUploader();

    /** 按关键词匹配文件名 */
    @Query("select f from FlFile f where f.deleted = false and lower(f.fileName) like :kw "
            + "order by f.createTime desc")
    java.util.List<FlFile> searchByName(@Param("kw") String keyword);
}
