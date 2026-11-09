package com.share.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（FR-11）。标注在 Controller 方法上，由 {@link AuditAspect} 采集后发布事件，
 * 由 share-ops 持久化到审计库。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditLog {

    /** 操作动作，如 DATASOURCE_CREATE、CATALOG_PUBLISH、TASK_TRIGGER。 */
    String action();

    /** 操作对象类型，如 DATASOURCE、CATALOG、TASK。 */
    String targetType() default "";

    /** 是否记录请求参数（涉敏接口可关闭）。 */
    boolean recordArgs() default true;
}
