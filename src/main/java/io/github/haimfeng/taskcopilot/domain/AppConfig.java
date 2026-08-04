package io.github.haimfeng.taskcopilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 应用级键值配置（如仪表盘显示名）。
 */
@Entity
@Table(name = "app_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppConfig {

    @Id
    @Column(name = "cfg_key", length = 64)
    private String key;

    @Column(name = "cfg_value", length = 512)
    private String value;
}
