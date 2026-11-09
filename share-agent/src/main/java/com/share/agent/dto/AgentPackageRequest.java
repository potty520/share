package com.share.agent.dto;

import lombok.Getter;
import lombok.Setter;

/** 离线摆渡包导入请求（FR-02/09）：校验签名与清单后落盘并记录导入结果。 */
@Getter
@Setter
public class AgentPackageRequest {

    /** 摆渡包编号 */
    private String packageId;

    /** 包名 */
    private String packageName;

    /** 来源节点编码 */
    private String nodeId;

    /** 包校验值（MD5） */
    private String checksum;

    /** 包签名（SHA-256(packageName|checksum|manifestJson|secret)） */
    private String signature;

    /** 清单 JSON 数组：[{fileName,size,md5,rows}] */
    private String manifestJson;
}
