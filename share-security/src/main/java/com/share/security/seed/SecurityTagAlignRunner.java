package com.share.security.seed;

import com.share.security.entity.SecClassifyTag;
import com.share.security.repository.SecClassifyTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分级标签目录 id 对齐（FR-10）：种子标签先按 catalog 种子顺序写入 target_id=1~4，
 * 待 share-catalog 种子（@Order(20)）执行后，按 cat_catalog.resource_name 反查真实 id 并修正。
 * <p>跨域只读 cat_catalog（契约允许）；表未就绪或名称不一致时保持原值，不阻断启动。</p>
 */
@Slf4j
@Component
@Order(25)
@RequiredArgsConstructor
public class SecurityTagAlignRunner implements CommandLineRunner {

    private final SecClassifyTagRepository tagRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${share.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        List<SecClassifyTag> tags = tagRepository.findByDeletedFalseOrderByIdAsc();
        if (tags.isEmpty()) {
            return;
        }
        int aligned = 0;
        for (SecClassifyTag tag : tags) {
            String resourceName = resolveResourceName(tag);
            if (resourceName == null) {
                continue;
            }
            try {
                List<Long> ids = jdbcTemplate.queryForList(
                        "select id from cat_catalog where resource_name = ? order by id", Long.class, resourceName);
                if (ids.isEmpty()) {
                    // catalog 尚未就绪或名称与种子不一致，保留顺序 id
                    continue;
                }
                Long catalogId = ids.get(0);
                if (!catalogId.equals(tag.getTargetId())) {
                    tag.setTargetId(catalogId);
                    tagRepository.save(tag);
                    aligned++;
                }
            } catch (Exception e) {
                log.debug("cat_catalog 暂不可读，跳过分级标签 id 对齐: {}", e.getMessage());
                return;
            }
        }
        if (aligned > 0) {
            log.info("分级标签 target_id 已按 cat_catalog.resource_name 对齐 {} 条", aligned);
        }
    }

    /** 目录级标签用 targetName；字段级标签的 targetName 形如「目录名-字段中文名」，取目录名前缀。 */
    private String resolveResourceName(SecClassifyTag tag) {
        String name = tag.getTargetName();
        if (name == null || name.isBlank()) {
            return null;
        }
        int split = name.indexOf('-');
        return split > 0 ? name.substring(0, split) : name;
    }
}
