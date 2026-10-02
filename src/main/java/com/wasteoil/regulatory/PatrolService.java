package com.wasteoil.regulatory;

import com.wasteoil.common.AuthContext;
import com.wasteoil.common.BizException;
import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.merchant.Merchant;
import com.wasteoil.merchant.MerchantRepository;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管巡查服务：
 * <ol>
 *   <li>周期扫描各门店回收情况，长期无回收的商户标记疑似违规并自动生成现场检查任务；</li>
 *   <li>监管人员现场核查后回填检查照片、约谈结果、整改期限；</li>
 *   <li>商户恢复正常回收后，风险状态经连续巡查确认逐步下降；仍无记录则进入重点巡查名单。</li>
 * </ol>
 */
@Slf4j
@Service
public class PatrolService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter NO_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZONE);
    private static final List<String> OPEN_TASK_STATUSES =
            List.of(SiteInspectionStatus.PENDING.name(), SiteInspectionStatus.RECTIFYING.name());

    /** 疑似违规分值 */
    private static final int WATCH_SCORE = 50;
    /** 重点巡查分值 */
    private static final int KEY_SCORE = 80;
    /** 现场确认违规的分值 */
    private static final int VIOLATION_SCORE = 80;
    /** 每次"已恢复回收"巡查确认下调的分值 */
    private static final int RECOVER_STEP = 40;

    private final StoreRepository storeRepository;
    private final MerchantRepository merchantRepository;
    private final StoreRiskProfileRepository profileRepository;
    private final SiteInspectionTaskRepository taskRepository;
    private final ChainTraceService chainTraceService;
    private final EventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final WasteOilProperties properties;

    public PatrolService(StoreRepository storeRepository,
                         MerchantRepository merchantRepository,
                         StoreRiskProfileRepository profileRepository,
                         SiteInspectionTaskRepository taskRepository,
                         ChainTraceService chainTraceService,
                         EventPublisher eventPublisher,
                         JdbcTemplate jdbcTemplate,
                         WasteOilProperties properties) {
        this.storeRepository = storeRepository;
        this.merchantRepository = merchantRepository;
        this.profileRepository = profileRepository;
        this.taskRepository = taskRepository;
        this.chainTraceService = chainTraceService;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 全量巡查扫描：更新所有在用门店的动态风险画像，
     * 对长期无回收且无未结现场检查任务的商户自动生成检查任务。
     *
     * @return 本次新生成的现场检查任务数
     */
    @Transactional
    public int scanAll() {
        int created = 0;
        for (Store store : storeRepository.findAll()) {
            if ("ACTIVE".equals(store.getStatus())) {
                created += scanStore(store, "SCAN");
            }
        }
        return created;
    }

    /**
     * 扫描单个门店，返回新生成的任务数（纯状态演进逻辑，便于测试与定时任务共用）。
     */
    @Transactional
    public int scanStore(Store store, String trigger) {
        Instant now = Instant.now();
        StoreRiskProfile profile = profileRepository.findById(store.getId())
                .orElseGet(StoreRiskProfile::new);
        if (profile.getStoreId() == null) {
            profile.setStoreId(store.getId());
        }
        String previousLevel = profile.getRiskLevel();
        Integer previousScore = profile.getRiskScore();

        Instant lastRecycling = chainTraceService.latestRecyclingAt(store.getId());
        LocalDate today = LocalDate.now(ZONE);
        LocalDate baseline = lastRecycling == null
                ? LocalDate.ofInstant(store.getCreatedAt(), ZONE)
                : LocalDate.ofInstant(lastRecycling, ZONE);
        int daysNoRecycling = (int) java.time.Duration.between(
                baseline.atStartOfDay(ZONE).toInstant(),
                today.atStartOfDay(ZONE).toInstant()).toDays();
        if (daysNoRecycling < 0) {
            daysNoRecycling = 0;
        }

        int created = 0;
        boolean noRecycling = daysNoRecycling >= properties.getPatrolWatchDays();
        if (noRecycling) {
            created = escalate(store, profile, daysNoRecycling, lastRecycling, trigger, now);
        } else {
            decline(profile, lastRecycling, now);
        }

        profile.setLastRecyclingAt(lastRecycling);
        profile.setLastScannedAt(now);
        profile.setUpdatedAt(now);
        profileRepository.save(profile);

        if (!profile.getRiskLevel().equals(previousLevel)
                || !profile.getRiskScore().equals(previousScore)) {
            publishRiskChanged(store, profile, daysNoRecycling, trigger);
        }
        return created;
    }

    /**
     * 无回收：风险升级。连续巡查仍无回收或无回收时长达到重点阈值，列入重点巡查名单。
     */
    private int escalate(Store store, StoreRiskProfile profile, int daysNoRecycling,
                         Instant lastRecycling, String trigger, Instant now) {
        profile.setConsecutiveNoRecyclingScans(profile.getConsecutiveNoRecyclingScans() + 1);
        profile.setNormalRecyclingStreak(0);

        boolean toKey = daysNoRecycling >= properties.getPatrolKeyDays()
                || profile.getConsecutiveNoRecyclingScans() >= 2;
        if (toKey) {
            profile.setRiskLevel(StoreRiskProfile.KEY);
            profile.setRiskScore(KEY_SCORE);
            profile.setKeyPatrol(true);
        } else {
            profile.setRiskLevel(StoreRiskProfile.WATCH);
            profile.setRiskScore(WATCH_SCORE);
        }

        int created = 0;
        List<SiteInspectionTask> open = taskRepository.findByStoreIdAndStatusIn(
                store.getId(), OPEN_TASK_STATUSES);
        if (open.isEmpty()) {
            createTask(store, "RISK_SCAN",
                    String.format("连续 %d 天无合规回收记录，最近一次回收 %s，疑似废油违规外流，请现场核查",
                            daysNoRecycling,
                            lastRecycling == null ? "无" : LocalDate.ofInstant(lastRecycling, ZONE)),
                    daysNoRecycling, lastRecycling, now);
            created = 1;
        }
        log.info("巡查扫描: store={} 无回收 {} 天 level={} 新任务={}",
                store.getId(), daysNoRecycling, profile.getRiskLevel(), created);
        return created;
    }

    /**
     * 恢复回收：风险分逐步下降，连续多轮巡查确认恢复后解除关注与重点巡查。
     */
    private void decline(StoreRiskProfile profile, Instant lastRecycling, Instant now) {
        if (StoreRiskProfile.NORMAL.equals(profile.getRiskLevel()) && profile.getRiskScore() == 0) {
            profile.setConsecutiveNoRecyclingScans(0);
            profile.setNormalRecyclingStreak(0);
            return;
        }
        profile.setNormalRecyclingStreak(profile.getNormalRecyclingStreak() + 1);
        int score = Math.max(0, profile.getRiskScore() - RECOVER_STEP);
        boolean recovered = score == 0
                || profile.getNormalRecyclingStreak() >= properties.getPatrolRecoverScans();
        if (recovered) {
            // 连续巡查确认恢复正常回收：风险清零，退出重点巡查名单
            profile.setRiskScore(0);
            profile.setRiskLevel(StoreRiskProfile.NORMAL);
            profile.setKeyPatrol(false);
            profile.setConsecutiveNoRecyclingScans(0);
            profile.setNormalRecyclingStreak(0);
        } else {
            applyScore(profile, score);
        }
        log.info("巡查扫描: store={} 已恢复回收，连续确认 {} 次，风险分降至 {}",
                profile.getStoreId(), profile.getNormalRecyclingStreak(), profile.getRiskScore());
    }

    private void applyScore(StoreRiskProfile profile, int score) {
        profile.setRiskScore(score);
        if (score >= 60) {
            profile.setRiskLevel(StoreRiskProfile.KEY);
            profile.setKeyPatrol(true);
        } else if (score > 0) {
            profile.setRiskLevel(StoreRiskProfile.WATCH);
            profile.setKeyPatrol(false);
        } else {
            profile.setRiskLevel(StoreRiskProfile.NORMAL);
            profile.setKeyPatrol(false);
        }
    }

    /** 监管人员手工发起现场检查任务 */
    @Transactional
    public SiteInspectionTask createManualTask(Long storeId, String reason, String inspector) {
        if (reason == null || reason.isBlank()) {
            throw BizException.badRequest("发起现场检查必须填写事由");
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> BizException.notFound("门店"));
        SiteInspectionTask task = createTask(store, "MANUAL", reason, null,
                chainTraceService.latestRecyclingAt(storeId), Instant.now());
        if (inspector != null && !inspector.isBlank()) {
            task.setInspector(inspector);
            taskRepository.save(task);
        }
        return task;
    }

    private SiteInspectionTask createTask(Store store, String source, String reason,
                                          Integer daysNoRecycling, Instant lastRecycling,
                                          Instant now) {
        Merchant merchant = merchantRepository.findById(store.getMerchantId())
                .orElseThrow(() -> BizException.notFound("商户"));
        SiteInspectionTask task = new SiteInspectionTask();
        task.setTaskNo(nextTaskNo());
        task.setStoreId(store.getId());
        task.setMerchantId(merchant.getId());
        task.setSource(source);
        task.setReason(reason);
        task.setStatus(SiteInspectionStatus.PENDING.name());
        task.setLastRecyclingAt(lastRecycling);
        task.setDaysNoRecycling(daysNoRecycling);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        SiteInspectionTask saved = taskRepository.save(task);

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", saved.getId());
        payload.put("taskNo", saved.getTaskNo());
        payload.put("storeId", saved.getStoreId());
        payload.put("merchantId", saved.getMerchantId());
        payload.put("source", source);
        payload.put("reason", reason);
        payload.put("daysNoRecycling", daysNoRecycling);
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "store-" + store.getId(), "SiteInspectionTaskGenerated", payload);
        return saved;
    }

    /**
     * 现场检查回填：检查照片、约谈结果与整改期限。
     * COMPLIANT 现场核实合规，任务直接完成；NON_COMPLIANT 确认违规，
     * 须填写整改期限，任务进入责令整改并将门店升级为重点巡查。
     */
    @Transactional
    public SiteInspectionTask completeInspection(Long taskId, String checkResult,
                                                 List<String> photoUrls, String interviewResult,
                                                 LocalDate rectifyDeadline) {
        SiteInspectionTask task = getTask(taskId);
        if (SiteInspectionStatus.COMPLETED.name().equals(task.getStatus())
                || SiteInspectionStatus.VERIFIED_CLOSED.name().equals(task.getStatus())) {
            throw BizException.conflict("现场检查任务已完结，不能重复回填");
        }
        boolean compliant = "COMPLIANT".equals(checkResult);
        boolean nonCompliant = "NON_COMPLIANT".equals(checkResult);
        if (!compliant && !nonCompliant) {
            throw BizException.badRequest("检查结果只能是 COMPLIANT 或 NON_COMPLIANT");
        }
        if (photoUrls == null || photoUrls.isEmpty()
                || photoUrls.stream().anyMatch(u -> u == null || u.isBlank())) {
            throw BizException.badRequest("现场检查必须回填至少一张检查照片");
        }
        if (nonCompliant) {
            if (interviewResult == null || interviewResult.isBlank()) {
                throw BizException.badRequest("认定违规必须回填约谈结果");
            }
            if (rectifyDeadline == null) {
                throw BizException.badRequest("认定违规必须填写整改期限");
            }
            if (rectifyDeadline.isBefore(LocalDate.now(ZONE))) {
                throw BizException.badRequest("整改期限不能早于今天");
            }
        }

        Instant now = Instant.now();
        String operator = AuthContext.accountId();
        task.setPhotoUrls(String.join(",", photoUrls));
        task.setInterviewResult(interviewResult);
        task.setRectifyDeadline(rectifyDeadline);
        task.setInspectedBy(operator);
        task.setInspectedAt(now);

        if (compliant) {
            task.setCheckResult("COMPLIANT");
            task.setStatus(SiteInspectionStatus.COMPLETED.name());
            task.setCloseNote("现场核实合规，检查完成");
            task.setClosedBy(operator);
            task.setClosedAt(now);
        } else {
            task.setCheckResult("NON_COMPLIANT");
            task.setStatus(SiteInspectionStatus.RECTIFYING.name());
            // 现场确认违规：升级重点巡查
            StoreRiskProfile profile = profileRepository.findById(task.getStoreId())
                    .orElseGet(() -> {
                        StoreRiskProfile p = new StoreRiskProfile();
                        p.setStoreId(task.getStoreId());
                        return p;
                    });
            profile.setRiskLevel(StoreRiskProfile.KEY);
            profile.setRiskScore(Math.max(profile.getRiskScore(), VIOLATION_SCORE));
            profile.setKeyPatrol(true);
            profile.setUpdatedAt(now);
            profileRepository.save(profile);
            publishRiskChangedById(task.getStoreId(), profile, "INSPECTION");
        }
        task.setUpdatedAt(now);
        SiteInspectionTask saved = taskRepository.save(task);
        publishInspection(saved, "SiteInspectionFilled");
        return saved;
    }

    /**
     * 整改期满复核。通过则任务关闭；不通过则维持责令整改并记录复核意见。
     */
    @Transactional
    public SiteInspectionTask verifyRectification(Long taskId, boolean passed, String closeNote) {
        SiteInspectionTask task = getTask(taskId);
        if (!SiteInspectionStatus.RECTIFYING.name().equals(task.getStatus())) {
            throw BizException.conflict("仅责令整改中的任务可以复核（当前状态 " + task.getStatus() + "）");
        }
        if (closeNote == null || closeNote.isBlank()) {
            throw BizException.badRequest("复核必须填写复核意见");
        }
        Instant now = Instant.now();
        String operator = AuthContext.accountId();
        if (passed) {
            task.setStatus(SiteInspectionStatus.VERIFIED_CLOSED.name());
            task.setCloseNote(closeNote);
            task.setClosedBy(operator);
            task.setClosedAt(now);
        } else {
            task.setInterviewResult((task.getInterviewResult() == null ? "" : task.getInterviewResult()
                    + " | 复核不合格: ") + closeNote);
        }
        task.setUpdatedAt(now);
        SiteInspectionTask saved = taskRepository.save(task);
        publishInspection(saved, passed ? "SiteInspectionClosed" : "RectificationRejected");
        return saved;
    }

    @Transactional(readOnly = true)
    public SiteInspectionTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("现场检查任务"));
    }

    @Transactional(readOnly = true)
    public List<SiteInspectionTask> listTasks(String status, Long storeId) {
        if (storeId != null) {
            List<SiteInspectionTask> list = taskRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
            if (status != null && !status.isBlank()) {
                list = list.stream().filter(t -> status.equals(t.getStatus())).toList();
            }
            return list;
        }
        if (status != null && !status.isBlank()) {
            return taskRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        return taskRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<StoreRiskProfile> listProfiles(String level) {
        if (level != null && !level.isBlank()) {
            return profileRepository.findByRiskLevelOrderByRiskScoreDesc(level);
        }
        return profileRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<StoreRiskProfile> keyPatrolList() {
        return profileRepository.findByKeyPatrolTrueOrderByRiskScoreDesc();
    }

    @Transactional(readOnly = true)
    public StoreRiskProfile getProfile(Long storeId) {
        return profileRepository.findById(storeId)
                .orElseThrow(() -> BizException.notFound("门店风险画像"));
    }

    private String nextTaskNo() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('site_inspection_no_seq')", Long.class);
        return "SI" + NO_FMT.format(Instant.now()) + "-" + String.format("%04d", seq);
    }

    private void publishRiskChanged(Store store, StoreRiskProfile profile,
                                    int daysNoRecycling, String trigger) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("storeId", store.getId());
        payload.put("riskLevel", profile.getRiskLevel());
        payload.put("riskScore", profile.getRiskScore());
        payload.put("keyPatrol", profile.getKeyPatrol());
        payload.put("daysNoRecycling", daysNoRecycling);
        payload.put("trigger", trigger);
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "store-" + store.getId(), "StoreRiskLevelChanged", payload);
    }

    private void publishRiskChangedById(Long storeId, StoreRiskProfile profile, String trigger) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("storeId", storeId);
        payload.put("riskLevel", profile.getRiskLevel());
        payload.put("riskScore", profile.getRiskScore());
        payload.put("keyPatrol", profile.getKeyPatrol());
        payload.put("trigger", trigger);
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "store-" + storeId, "StoreRiskLevelChanged", payload);
    }

    private void publishInspection(SiteInspectionTask task, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("taskNo", task.getTaskNo());
        payload.put("storeId", task.getStoreId());
        payload.put("status", task.getStatus());
        payload.put("checkResult", task.getCheckResult());
        payload.put("hasPhotos", task.getPhotoUrls() != null && !task.getPhotoUrls().isBlank());
        payload.put("rectifyDeadline", task.getRectifyDeadline() == null
                ? null : task.getRectifyDeadline().atStartOfDay().toInstant(ZoneOffset.UTC));
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "store-" + task.getStoreId(), type, payload);
    }
}
