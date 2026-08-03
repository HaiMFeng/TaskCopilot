package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findAllByOrderBySortOrderAscIdAsc();

    List<Schedule> findByActiveTrue();

    Optional<Schedule> findFirstByActiveTrue();
}
