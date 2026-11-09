package com.share;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 共享交换系统中心平台启动类。
 * <p>物理上是一个可执行单元（课程/演示环境按文档第 4 章建议合并部署），
 * 代码按域分包，后续可按域拆分为独立微服务。</p>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class ShareApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShareApplication.class, args);
    }
}
