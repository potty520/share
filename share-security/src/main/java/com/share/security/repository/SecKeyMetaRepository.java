package com.share.security.repository;

import com.share.security.entity.SecKeyMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 密钥元数据仓储。 */
public interface SecKeyMetaRepository extends JpaRepository<SecKeyMeta, Long> {

    List<SecKeyMeta> findByDeletedFalseOrderByIdAsc();

    Optional<SecKeyMeta> findByIdAndDeletedFalse(Long id);

    Optional<SecKeyMeta> findByKeyAliasAndDeletedFalse(String keyAlias);

    boolean existsByKeyAlias(String keyAlias);

    long countByDeletedFalse();

    long countByDeletedFalseAndKeyStatus(String keyStatus);
}
