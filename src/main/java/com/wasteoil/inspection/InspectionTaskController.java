package com.wasteoil.inspection;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 监管现场检查任务。长期无回收被标记疑似违规后自动生成，
 * 监管人员回填检查照片、约谈结果与整改期限。
 */
@RestController
@RequestMapping("/api/inspection-tasks")
public class InspectionTaskController {

    private final InspectionTaskService taskService;

    public InspectionTaskController(InspectionTaskService taskService) {
        this.taskService = taskService;
    }

    /** 监管人工发起现场检查任务 */
    @PostMapping
    public ApiResult<InspectionTask> create(@Valid @RequestBody CreateRequest req) {
        AuthContext.require(ActorRole.REGULATOR);
        String inspector = AuthContext.accountId() == null ? null : AuthContext.accountId();
        return ApiResult.ok(taskService.createManual(req.storeId(), inspector, req.dueDate(), req.note()));
    }

    /** 任务列表（可按状态过滤） */
    @GetMapping
    public ApiResult<List<InspectionTask>> list(@RequestParam(required = false) String status) {
        AuthContext.require(ActorRole.REGULATOR);
        if (status != null && !status.isBlank()) {
            try {
                return ApiResult.ok(taskService.list(TaskStatus.valueOf(status.toUpperCase()).name()));
            } catch (IllegalArgumentException e) {
                throw new com.wasteoil.common.BizException(
                        "非法任务状态: " + status + "，可选 OPEN/IN_PROGRESS/COMPLETED/OVERDUE/CANCELLED");
            }
        }
        return ApiResult.ok(taskService.list(null));
    }

    /** 某门店的检查任务 */
    @GetMapping("/by-store/{storeId}")
    public ApiResult<List<InspectionTask>> listByStore(@PathVariable Long storeId) {
        AuthContext.require(ActorRole.REGULATOR, ActorRole.RECYCLER);
        return ApiResult.ok(taskService.listByStore(storeId));
    }

    @GetMapping("/{id}")
    public ApiResult<InspectionTask> detail(@PathVariable Long id) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(taskService.get(id));
    }

    /** 认领任务 */
    @PostMapping("/{id}/claim")
    public ApiResult<InspectionTask> claim(@PathVariable Long id) {
        AuthContext.require(ActorRole.REGULATOR);
        String inspector = AuthContext.accountId() == null ? "regulator" : AuthContext.accountId();
        return ApiResult.ok(taskService.claim(id, inspector));
    }

    /** 回填现场检查照片、约谈结果、整改期限、检查结论并结案 */
    @PostMapping("/{id}/complete")
    public ApiResult<InspectionTask> complete(@PathVariable Long id,
                                              @Valid @RequestBody CompleteBody body) {
        AuthContext.require(ActorRole.REGULATOR);
        String inspector = AuthContext.accountId() == null ? body.inspector() : AuthContext.accountId();
        return ApiResult.ok(taskService.complete(id, new InspectionTaskService.CompleteRequest(
                body.inspectionPhotoUrls(), body.interviewResult(), body.rectificationDeadline(),
                body.inspectionResult(), inspector, body.note())));
    }

    public record CreateRequest(
            @NotNull(message = "门店不能为空") Long storeId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            String note) {
    }

    public record CompleteBody(
            String inspectionPhotoUrls,
            String interviewResult,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rectificationDeadline,
            @NotNull(message = "检查结论不能为空")
            @Pattern(regexp = "QUALIFIED|UNQUALIFIED", message = "检查结论只能是 QUALIFIED 或 UNQUALIFIED")
            String inspectionResult,
            String inspector,
            String note) {
    }
}
