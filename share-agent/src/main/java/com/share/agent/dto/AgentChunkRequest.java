package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

/** 分片上传请求（FR-09 分片断点续传在节点侧的上传通道）。 */
@Getter
@Setter
public class AgentChunkRequest {

    /** 上传会话 ID（首次上传可不传，由服务端生成） */
    private String uploadId;

    /** 节点编码 */
    private String nodeId;

    /** 节点令牌 */
    private String nodeToken;

    /** 文件名 */
    private String fileName;

    /** 分片序号（从 1 开始） */
    private Integer chunkNo;

    /** 分片总数 */
    private Integer totalChunks;

    /** 分片数据（Base64） */
    private String data;

    /** 分片 MD5（十六进制） */
    private String chunkMd5;

    /** 整包 MD5（可选，最后一片校验） */
    private String fileMd5;

    /** 分片字节数 */
    private Long size;
}
