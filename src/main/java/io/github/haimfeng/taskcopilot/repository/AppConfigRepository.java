package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AppConfigRepository extends JpaRepository<AppConfig, String> {

    /**
     * 原子 upsert：key 已存在则更新 value，否则插入。
     * 避免并发/先查后插导致的唯一键冲突（DataIntegrityViolationException）。
     * 语法为 H2 专有：MERGE INTO ... KEY(pk) VALUES(...)
     */
    @Modifying
    @Query("MERGE INTO app_config (cfg_key, cfg_value) KEY (cfg_key) VALUES (:k, :v)")
    void upsert(@Param("k") String k, @Param("v") String v);
}
