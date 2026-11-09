package com.share;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档信息（/swagger-ui.html 展示）。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shareExchangeOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("共享交换系统 API")
                .version("1.0.0")
                .description("""
                        共享交换系统（FR-01~FR-14）对外开放与内部接口集合。
                        - 认证：POST /api/auth/v1/login 获取 JWT，后续请求携带 Authorization: Bearer <token>
                        - 统一响应：{ code, message, traceId, data, page }，code=0 表示成功
                        - 分页：pageNum / pageSize，分页元信息在 page 字段
                        """)
                .contact(new Contact().name("共享交换系统项目组"))
                .license(new License().name("内部使用")));
    }
}
