package com.wasteoil.regulatory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SiteInspectionTaskRepository extends JpaRepository<SiteInspectionTask, Long> {

    List<SiteInspectionTask> findAllByOrderByCreatedAtDesc();

    List<SiteInspectionTask> findByStatusOrderByCreatedAtDesc(String status);

    List<SiteInspectionTask> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<SiteInspectionTask> findByStoreIdAndStatusIn(Long storeId, List<String> statuses);
}
