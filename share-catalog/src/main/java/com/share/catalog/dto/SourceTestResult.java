package com.share.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 连通性测试结果。 */
@Getter
@Setter
public class SourceTestResult {

    /** 是否连通 */
    private boolean success;
    /** 探测耗时（毫秒） */
    private long latencyMs;
    /** 结论说明 */
    private String message;
    /** 探测方式：TCP / LOCAL_PATH / URL_FORMAT */
    private String probe;
    /** 探测目标，如 10.20.31.15:3306 */
    private String target;
    private String typeCode;
    private String typeName;
    /** 最近一次测试时间 */
    private LocalDateTime testTime;

    public static SourceTestResult ok(String probe, String target, long latencyMs, String message) {
        SourceTestResult r = new SourceTestResult();
        r.success = true;
        r.probe = probe;
        r.target = target;
        r.latencyMs = latencyMs;
        r.message = message;
        r.testTime = LocalDateTime.now();
        return r;
    }

    public static SourceTestResult fail(String probe, String target, long latencyMs, String message) {
        SourceTestResult r = ok(probe, target, latencyMs, message);
        r.success = false;
        return r;
    }
}
