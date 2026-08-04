package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppConfigRepository extends JpaRepository<AppConfig, String> {
}
