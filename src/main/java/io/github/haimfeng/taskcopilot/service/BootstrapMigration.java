package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TriggerMode;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时数据迁移：将旧版本中以"调度方式"（DAILY）作为任务类型的数据，
 * 迁移为新的"功能类别"语义（RUN_COMMAND）。旧任务的命令、工作目录、执行时间
 * 配置均与运行指令类型兼容，无需额外转换。迁移完成后重新装载调度器。
 */
@Component
public class BootstrapMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapMigration.class);

    private final TaskRepository taskRepository;
    private final TaskTypeRegistry taskTypeRegistry;
    private final TaskScheduler taskScheduler;

    public BootstrapMigration(TaskRepository taskRepository,
                              TaskTypeRegistry taskTypeRegistry,
                              TaskScheduler taskScheduler) {
        this.taskRepository = taskRepository;
        this.taskTypeRegistry = taskTypeRegistry;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void run(String... args) {
        List<String> known = taskTypeRegistry.all().stream()
                .map(h -> h.code())
                .toList();
        long migrated = 0;
        for (Task task : taskRepository.findAll()) {
            if (task.getTypeCode() == null || !known.contains(task.getTypeCode())) {
                String target = "RUN_COMMAND";
                log.info("迁移任务 [{}] 类型 {} -> {}", task.getName(), task.getTypeCode(), target);
                task.setTypeCode(target);
                taskRepository.save(task);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("共迁移 {} 个任务至 RUN_COMMAND", migrated);
        }
        migrateTriggerMode();
        // 迁移后重新装载调度，使新类型生效
        taskScheduler.reloadAll();
    }

    /**
     * 向上兼容：旧版本的任务表没有 trigger_mode 列，升级后该列为 null。
     * 首次运行新版本时统一回填为 SCHEDULED（定时运行），使旧任务行为完全不变。
     */
    private void migrateTriggerMode() {
        long pending = taskRepository.countByTriggerModeIsNull();
        if (pending == 0) {
            return;
        }
        int updated = taskRepository.backfillTriggerMode(TriggerMode.SCHEDULED);
        log.info("共有 {} 个旧任务缺少运行方式，已回填为定时运行（实际更新 {} 条）", pending, updated);
    }
}
