package com.wasteoil.inspection;

import com.wasteoil.common.BizException;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.risk.RiskEntry;
import com.wasteoil.risk.RiskEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 现场检查任务服务。
 *
 * <p>长期无回收被标记疑似违规时由风险引擎自动生成任务；监管人员可：
 * <ol>
 *   <li>查询/认领任务；</li>
 *   <li>回填现场检查照片、约谈结果、整改期限、检查结论；</li>
 *   <li>人工发起检查任务。</li>
 * </ol>
 * 检查结论不合格会把对应风险条目复核为 CONFIRMED；合格不直接解除风险，
 * 风险是否下降仍以"是否恢复正常回收"为准（见 RiskAnalyzer）。
 */
@Slf4j
@Service
public class InspectionTaskService {

    private static final DateTimeFormatter NO_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai"));
    private static final List<TaskStatus> OPEN_STATUSES =
            List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS, TaskStatus.OVERDUE);

    private final InspectionTaskRepository taskRepository;
    private final RiskEntryRepository riskEntryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EventPublisher eventPublisher;

    public InspectionTaskService(InspectionTaskRepository taskRepository,
                                 RiskEntryRepository riskEntryRepository,
                                 JdbcTemplate jdbcTemplate,
                                 EventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.riskEntryRepository = riskEntryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 风险引擎调用：长期无回收/重点巡查时自动生成检查任务（同一门店同一来源有未结任务则不重复生成）。
     */
    @Transactional
    public InspectionTask autoCreate(Long storeId, Long riskEntryId, InspectionTrigger trigger,
                                     LocalDate dueDate, String reason) {
        var existing = taskRepository
                .findFirstByStoreIdAndTriggerReasonAndStatusInOrderByIdDesc(storeId, trigger, OPEN_STATUSES);
        if (existing.isPresent()) {
            return existing.get();
        }
        InspectionTask task = new InspectionTask();
        task.setTaskNo(nextTaskNo());
        task.setStoreId(storeId);
        task.setRiskEntryId(riskEntryId);
        task.setTriggerReason(trigger);
        task.setStatus(TaskStatus.OPEN);
        task.setDueDate(dueDate);
        task.setNote(reason);
        InspectionTask saved = taskRepository.save(task);
        log.info("现场检查任务自动生成: taskNo={} store={} trigger={}", saved.getTaskNo(), storeId, trigger);
        publish(saved, "InspectionTaskCreated");
        return saved;
    }

    /** 监管人工发起检查任务 */
    @Transactional
    public InspectionTask createManual(Long storeId, String inspector, LocalDate dueDate, String note) {
        InspectionTask task = new InspectionTask();
        task.setTaskNo(nextTaskNo());
        task.setStoreId(storeId);
        task.setTriggerReason(InspectionTrigger.MANUAL);
        task.setStatus(TaskStatus.OPEN);
        task.setInspector(inspector);
        task.setDueDate(dueDate);
        task.setNote(note);
        InspectionTask saved = taskRepository.save(task);
        publish(saved, "InspectionTaskCreated");
        return saved;
    }

    /** 认领任务（进入检查中） */
    @Transactional
    public InspectionTask claim(Long taskId, String inspector) {
        InspectionTask task = get(taskId);
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw BizException.conflict("任务已结案，不能认领");
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setInspector(inspector);
        task.setUpdatedAt(Instant.now());
        InspectionTask saved = taskRepository.save(task);
        publish(saved, "InspectionTaskClaimed");
        return saved;
    }

    /**
     * 回填现场检查结果：照片、约谈结果、整改期限、检查结论。回填后任务结案。
     */
    @Transactional
    public InspectionTask complete(Long taskId, CompleteRequest req) {
        InspectionTask task = get(taskId);
        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw BizException.conflict("任务已完成，不能重复回填");
        }
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw BizException.conflict("任务已取消，不能回填");
        }
        task.setInspectionPhotoUrls(req.inspectionPhotoUrls());
        task.setInterviewResult(req.interviewResult());
        task.setRectificationDeadline(req.rectificationDeadline());
        task.setInspectionResult(req.inspectionResult());
        if (req.note() != null) {
            task.setNote(req.note());
        }
        if (req.inspector() != null) {
            task.setInspector(req.inspector());
        }
        task.setStatus(TaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        InspectionTask saved = taskRepository.save(task);

        // 检查不合格：将触发任务的风险条目复核为 CONFIRMED，保持重点监管
        if ("UNQUALIFIED".equals(req.inspectionResult()) && task.getRiskEntryId() != null) {
            riskEntryRepository.findById(task.getRiskEntryId()).ifPresent(risk -> {
                if ("OPEN".equals(risk.getStatus())) {
                    risk.setStatus("CONFIRMED");
                    risk.setLastEvaluatedAt(Instant.now());
                    riskEntryRepository.save(risk);
                }
            });
        }
        publish(saved, "InspectionTaskCompleted");
        return saved;
    }

    /** 风险解除（商户恢复正常回收）时系统自动取消未结任务 */
    @Transactional
    public void cancelOpenTasksByStore(Long storeId, String reason) {
        for (InspectionTask task : taskRepository.findByStoreIdOrderByIdDesc(storeId)) {
            if (OPEN_STATUSES.contains(task.getStatus())) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setNote((task.getNote() == null ? "" : task.getNote() + " | ")
                        + "商户恢复正常回收，任务自动取消: " + reason);
                task.setUpdatedAt(Instant.now());
                InspectionTask saved = taskRepository.save(task);
                publish(saved, "InspectionTaskCancelled");
            }
        }
    }

    @Transactional(readOnly = true)
    public InspectionTask get(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> BizException.notFound("现场检查任务"));
    }

    @Transactional(readOnly = true)
    public List<InspectionTask> list(String status) {
        if (status == null || status.isBlank()) {
            return taskRepository.findAll();
        }
        return taskRepository.findByStatusOrderByIdDesc(TaskStatus.valueOf(status.toUpperCase()));
    }

    @Transactional(readOnly = true)
    public List<InspectionTask> listByStore(Long storeId) {
        return taskRepository.findByStoreIdOrderByIdDesc(storeId);
    }

    /**
     * 定时扫描：超过办理期限仍未结案的任务置为 OVERDUE。
     * @return 新增逾期数
     */
    @Transactional
    public int scanOverdue() {
        LocalDate today = LocalDate.now();
        int count = 0;
        for (InspectionTask task : taskRepository.findByStatusInOrderByIdDesc(
                List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS))) {
            if (task.getDueDate() != null && task.getDueDate().isBefore(today)) {
                task.setStatus(TaskStatus.OVERDUE);
                task.setUpdatedAt(Instant.now());
                taskRepository.save(task);
                publish(task, "InspectionTaskOverdue");
                count++;
            }
        }
        return count;
    }

    private String nextTaskNo() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('inspection_task_no_seq')", Long.class);
        return "INS" + NO_DATE_FMT.format(Instant.now()) + "-" + String.format("%04d", seq);
    }

    private void publish(InspectionTask task, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getId());
        payload.put("taskNo", task.getTaskNo());
        payload.put("storeId", task.getStoreId());
        payload.put("riskEntryId", task.getRiskEntryId());
        payload.put("triggerReason", task.getTriggerReason().name());
        payload.put("status", task.getStatus().name());
        payload.put("inspectionResult", task.getInspectionResult());
        eventPublisher.publish(KafkaTopics.SUPERVISION_EVENTS,
                "inspection-task-" + task.getId(), type, payload);
    }

    /** 回填请求 */
    public record CompleteRequest(
            String inspectionPhotoUrls,
            String interviewResult,
            LocalDate rectificationDeadline,
            String inspectionResult,   // QUALIFIED / UNQUALIFIED
            String inspector,
            String note) {
    }
}
