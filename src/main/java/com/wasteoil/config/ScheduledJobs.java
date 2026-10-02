package com.wasteoil.config;

import com.wasteoil.dispatch.DispatchService;
import com.wasteoil.regulatory.PatrolService;
import com.wasteoil.risk.RiskAnalyzer;
import com.wasteoil.track.TrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：司机迟到扫描、运输轨迹缺失扫描、风险名单周期分析、监管巡查扫描。
 */
@Slf4j
@Component
public class ScheduledJobs {

    private final DispatchService dispatchService;
    private final TrackService trackService;
    private final RiskAnalyzer riskAnalyzer;
    private final PatrolService patrolService;

    public ScheduledJobs(DispatchService dispatchService,
                         TrackService trackService,
                         RiskAnalyzer riskAnalyzer,
                         PatrolService patrolService) {
        this.dispatchService = dispatchService;
        this.trackService = trackService;
        this.riskAnalyzer = riskAnalyzer;
        this.patrolService = patrolService;
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

    /** 每日凌晨执行长期趋势风险分析 */
    @Scheduled(cron = "0 0 2 * * *")
    public void analyzeRisks() {
        int created = riskAnalyzer.scanAll();
        log.info("风险分析完成，新增 {} 条风险条目", created);
    }

    /** 每日凌晨 02:30 执行监管巡查：无回收疑似违规生成现场检查任务，持续无回收列入重点巡查 */
    @Scheduled(cron = "0 30 2 * * *")
    public void patrolScan() {
        int tasks = patrolService.scanAll();
        log.info("监管巡查扫描完成，新增 {} 个现场检查任务", tasks);
    }
}
