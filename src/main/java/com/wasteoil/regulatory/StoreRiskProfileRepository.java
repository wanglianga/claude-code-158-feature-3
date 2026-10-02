package com.wasteoil.regulatory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRiskProfileRepository extends JpaRepository<StoreRiskProfile, Long> {

    List<StoreRiskProfile> findByRiskLevelOrderByRiskScoreDesc(String riskLevel);

    List<StoreRiskProfile> findByKeyPatrolTrueOrderByRiskScoreDesc();
}
