package com.share.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交换引擎线程资源装配：
 * <ul>
 *   <li>shareEngineExecutor：真实搬运数据的工作线程池（异步执行，避免阻塞接口）；</li>
 *   <li>shareTaskScheduler：cron 扫描与定时触发使用的 ThreadPoolTaskScheduler。</li>
 * </ul>
 */
@Configuration
public class TaskEngineConfig {

    /** 引擎执行线程池：核心 4、最大 12、队列 200，任务相互隔离。 */
    @Bean(name = "shareEngineExecutor", destroyMethod = "shutdown")
    public ExecutorService shareEngineExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "share-engine-worker-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(4, 12, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 调度线程池：用于每分钟扫描启用任务，以及 cron 触发。 */
    @Bean(name = "shareTaskScheduler")
    public ThreadPoolTaskScheduler shareTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("share-task-scheduler-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
