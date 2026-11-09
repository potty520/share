package com.share.task.engine;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 引擎运行参数（读取 share.engine.* 配置）。 */
@Getter
@Component
public class EngineSettings {

    /** 每批读写行数 */
    @Value("${share.engine.batch-size:500}")
    private int batchSize;

    /** 单次执行最大读取行数，防止误配置拉爆内存 */
    @Value("${share.engine.max-rows-per-run:200000}")
    private int maxRowsPerRun;

    /** 失败重试次数（指数退避） */
    @Value("${share.engine.retry-times:3}")
    private int retryTimes;

    /** 重试基础间隔（秒），第 n 次重试等待 interval * 2^(n-1) */
    @Value("${share.engine.retry-interval-seconds:10}")
    private int retryIntervalSeconds;
}
