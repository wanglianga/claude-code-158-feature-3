package com.wasteoil.inspection;

import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.risk.RiskEntry;
import com.wasteoil.risk.RiskEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 现场检查任务单元测试：自动生成去重、回填结案、不合格联动风险确认、
 * 逾期扫描、风险解除时取消未结任务。
 */
@ExtendWith(MockitoExtension.class)
class InspectionTaskServiceTest {

    @Mock
    InspectionTaskRepository taskRepository;
    @Mock
    RiskEntryRepository riskEntryRepository;
    @Mock
    JdbcTemplate jdbcTemplate;
    @Mock
    EventPublisher eventPublisher;

    InspectionTaskService service;

    @BeforeEach
    void setUp() {
        service = new InspectionTaskService(taskRepository, riskEntryRepository,
                jdbcTemplate, eventPublisher);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        lenient().when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private InspectionTask task(Long id, TaskStatus status, InspectionTrigger trigger) {
        InspectionTask t = new InspectionTask();
        t.setId(id);
        t.setTaskNo("INS-TEST-" + id);
        t.setStoreId(3L);
        t.setStatus(status);
        t.setTriggerReason(trigger);
        return t;
    }

    @Test
    void 自动生成任务_无未结任务时新建并发布事件() {
        when(taskRepository.findFirstByStoreIdAndTriggerReasonAndStatusInOrderByIdDesc(
                eq(3L), eq(InspectionTrigger.NO_RECYCLING), anyList())).thenReturn(Optional.empty());

        InspectionTask created = service.autoCreate(3L, 10L, InspectionTrigger.NO_RECYCLING,
                LocalDate.now().plusDays(7), "长期无回收");

        assertThat(created.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(created.getTriggerReason()).isEqualTo(InspectionTrigger.NO_RECYCLING);
        assertThat(created.getRiskEntryId()).isEqualTo(10L);
        verify(taskRepository).save(any(InspectionTask.class));
        verify(eventPublisher).publish(anyString(), anyString(), eq("InspectionTaskCreated"), anyMap());
    }

    @Test
    void 自动生成任务_已有未结任务则不重复生成() {
        when(taskRepository.findFirstByStoreIdAndTriggerReasonAndStatusInOrderByIdDesc(
                eq(3L), eq(InspectionTrigger.NO_RECYCLING), anyList()))
                .thenReturn(Optional.of(task(5L, TaskStatus.IN_PROGRESS, InspectionTrigger.NO_RECYCLING)));

        InspectionTask result = service.autoCreate(3L, 10L, InspectionTrigger.NO_RECYCLING,
                LocalDate.now().plusDays(7), "长期无回收");

        assertThat(result.getId()).isEqualTo(5L);
        verify(taskRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void 回填检查结果_不合格联动风险确认() {
        InspectionTask open = task(7L, TaskStatus.IN_PROGRESS, InspectionTrigger.NO_RECYCLING);
        open.setRiskEntryId(99L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(open));
        RiskEntry risk = new RiskEntry();
        risk.setId(99L);
        risk.setStatus("OPEN");
        when(riskEntryRepository.findById(99L)).thenReturn(Optional.of(risk));

        var req = new InspectionTaskService.CompleteRequest(
                "https://img.example.com/site1.jpg,https://img.example.com/site2.jpg",
                "约谈负责人，承认将废油交无资质人员",
                LocalDate.now().plusDays(15), "UNQUALIFIED", "regulator-1", "现场查实");
        InspectionTask done = service.complete(7L, req);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.getInspectionPhotoUrls()).contains("site1.jpg");
        assertThat(done.getInterviewResult()).contains("无资质");
        assertThat(done.getRectificationDeadline()).isEqualTo(LocalDate.now().plusDays(15));
        assertThat(risk.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void 已完成任务不能重复回填() {
        when(taskRepository.findById(7L)).thenReturn(
                Optional.of(task(7L, TaskStatus.COMPLETED, InspectionTrigger.MANUAL)));

        var req = new InspectionTaskService.CompleteRequest(
                "u", "r", LocalDate.now().plusDays(1), "QUALIFIED", "regulator-1", null);
        assertThatThrownBy(() -> service.complete(7L, req)).hasMessageContaining("已完成");
    }

    @Test
    void 逾期任务扫描_超过期限未办结置为OVERDUE() {
        InspectionTask overdue = task(8L, TaskStatus.OPEN, InspectionTrigger.KEY_PATROL);
        overdue.setDueDate(LocalDate.now().minusDays(1));
        InspectionTask normal = task(9L, TaskStatus.OPEN, InspectionTrigger.NO_RECYCLING);
        normal.setDueDate(LocalDate.now().plusDays(3));
        when(taskRepository.findByStatusInOrderByIdDesc(anyList()))
                .thenReturn(List.of(overdue, normal));

        int count = service.scanOverdue();

        assertThat(count).isEqualTo(1);
        assertThat(overdue.getStatus()).isEqualTo(TaskStatus.OVERDUE);
        assertThat(normal.getStatus()).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void 风险解除_取消门店全部未结任务() {
        InspectionTask open = task(11L, TaskStatus.OPEN, InspectionTrigger.NO_RECYCLING);
        InspectionTask progress = task(12L, TaskStatus.IN_PROGRESS, InspectionTrigger.KEY_PATROL);
        InspectionTask done = task(13L, TaskStatus.COMPLETED, InspectionTrigger.MANUAL);
        when(taskRepository.findByStoreIdOrderByIdDesc(3L))
                .thenReturn(List.of(done, progress, open));

        service.cancelOpenTasksByStore(3L, "风险解除");

        assertThat(open.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(progress.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(eventPublisher, times(2))
                .publish(anyString(), anyString(), eq("InspectionTaskCancelled"), anyMap());
    }
}
