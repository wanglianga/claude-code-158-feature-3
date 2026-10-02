package com.wasteoil.inspection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Long> {

    List<InspectionTask> findByStoreIdOrderByIdDesc(Long storeId);

    List<InspectionTask> findByStatusOrderByIdDesc(TaskStatus status);

    List<InspectionTask> findByStatusInOrderByIdDesc(List<TaskStatus> statuses);

    /**
     * 门店某触发来源下仍未结案的任务，用于防止重复生成现场检查任务。
     */
    Optional<InspectionTask> findFirstByStoreIdAndTriggerReasonAndStatusInOrderByIdDesc(
            Long storeId, InspectionTrigger triggerReason, List<TaskStatus> statuses);
}
