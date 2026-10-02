package com.wasteoil.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RiskEntryRepository extends JpaRepository<RiskEntry, Long> {

    List<RiskEntry> findByStoreId(Long storeId);

    List<RiskEntry> findByStatus(String status);

    List<RiskEntry> findByRiskLevelAndStatus(RiskLevel riskLevel, String status);

    List<RiskEntry> findByRiskLevelAndStatusIn(RiskLevel riskLevel, Collection<String> statuses);

    List<RiskEntry> findByStoreIdAndIndicatorAndStatusIn(Long storeId, String indicator,
                                                         Collection<String> statuses);

    /**
     * 门店某指标仍在进行中（OPEN/CONFIRMED）的最新风险条目，用于升级与恢复降级。
     */
    Optional<RiskEntry> findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
            Long storeId, String indicator, Collection<String> statuses);

    Optional<RiskEntry> findByStoreIdAndIndicatorAndWindowEndAndStatus(
            Long storeId, String indicator, LocalDate windowEnd, String status);
}
