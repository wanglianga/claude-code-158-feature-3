package com.wasteoil.config;

import com.wasteoil.dispatch.DispatchService;
import com.wasteoil.inspection.InspectionTaskService;
import com.wasteoil.risk.RiskAnalyzer;
import com.wasteoil.track.TrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：司机迟到扫描、运输轨迹缺失扫描、风险名单周期分析、现场检查任务逾期扫描。
 */
@Slf4j
@Component
public class ScheduledJobs {

    private final DispatchService dispatchService;
    private final TrackService trackService;
    private final RiskAnalyzer riskAnalyzer;
    private final InspectionTaskService inspectionTaskService;

    public ScheduledJobs(DispatchService dispatchService,
                         TrackService trackService,
                         RiskAnalyzer riskAnalyzer,
                         InspectionTaskService inspectionTaskService) {
        this.dispatchService = dispatchService;
        this.trackService = trackService;
        this.riskAnalyzer = riskAnalyzer;
        this.inspectionTaskService = inspectionTaskService;
    }

    /** 每分钟扫描迟到派单 */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    public void scanLateDispatches() {
        int raised = dispatchService.scanLateDispatches();
        if (raised > 0) {
            log.info("迟到扫描新增 {} 条异常", raised);
        }
    }

    /** 每分钟扫描运输轨迹缺失 */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    public void scanMissingTracks() {
        int raised = trackService.scanMissingTracks();
        if (raised > 0) {
            log.info("轨迹缺失扫描新增 {} 条异常", raised);
        }
    }

    /** 每 10 分钟扫描超期未办结的现场检查任务 */
    @Scheduled(fixedDelayString = "600000", initialDelayString = "600000")
    public void scanOverdueInspectionTasks() {
        int overdue = inspectionTaskService.scanOverdue();
        if (overdue > 0) {
            log.info("现场检查任务逾期扫描新增 {} 条 OVERDUE", overdue);
        }
    }

    /** 每日凌晨执行长期趋势风险分析（含无回收升级/恢复降级、现场检查任务生成） */
    @Scheduled(cron = "0 0 2 * * *")
    public void analyzeRisks() {
        int created = riskAnalyzer.scanAll();
        log.info("风险分析完成，新增 {} 条风险条目", created);
    }
}
