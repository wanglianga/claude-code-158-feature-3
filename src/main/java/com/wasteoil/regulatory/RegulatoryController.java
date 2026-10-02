package com.wasteoil.regulatory;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 监管抽查追踪接口：
 * 链路追踪（商户/日期/回收批次）、巡查扫描、现场检查任务生成与回填、
 * 风险画像与重点巡查名单。
 */
@Validated
@RestController
@RequestMapping("/api/regulatory")
public class RegulatoryController {

    private final ChainTraceService chainTraceService;
    private final PatrolService patrolService;

    public RegulatoryController(ChainTraceService chainTraceService, PatrolService patrolService) {
        this.chainTraceService = chainTraceService;
        this.patrolService = patrolService;
    }

    // ---------- 链路追踪 ----------

    /** 按商户、日期（与回收批次）组装完整链路：预约/称重/司机/车辆/到厂联单/结算 */
    @GetMapping("/chains")
    public ApiResult<ChainTraceService.ChainView> chains(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthContext.require(ActorRole.REGULATOR);
        if (storeId == null && merchantId == null && (district == null || district.isBlank())) {
            throw new com.wasteoil.common.BizException(
                    "必须指定 storeId / merchantId / district 中的至少一个查询范围");
        }
        return ApiResult.ok(chainTraceService.trace(storeId, merchantId, district, date));
    }

    // ---------- 巡查扫描 ----------

    /** 手动触发巡查扫描：长期无回收 → 疑似违规并生成现场检查任务，持续无回收 → 重点巡查 */
    @PostMapping("/patrol/scan")
    public ApiResult<ScanResult> scan() {
        AuthContext.require(ActorRole.REGULATOR);
        int created = patrolService.scanAll();
        return ApiResult.ok(new ScanResult(created));
    }

    public record ScanResult(int tasksCreated) {
    }

    // ---------- 现场检查任务 ----------

    /** 现场检查任务列表（可按状态/门店过滤） */
    @GetMapping("/tasks")
    public ApiResult<List<SiteInspectionTask>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long storeId) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.listTasks(status, storeId));
    }

    @GetMapping("/tasks/{id}")
    public ApiResult<SiteInspectionTask> task(@PathVariable Long id) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.getTask(id));
    }

    /** 监管手工发起现场检查任务 */
    @PostMapping("/tasks")
    public ApiResult<SiteInspectionTask> createTask(@Valid @RequestBody CreateTaskRequest req) {
        AuthContext.require(ActorRole.REGULATOR);
        String inspector = AuthContext.accountId() == null ? req.inspector() : AuthContext.accountId();
        return ApiResult.ok(patrolService.createManualTask(req.storeId(), req.reason(), inspector));
    }

    /** 回填现场检查结果：照片、约谈结果、整改期限 */
    @PostMapping("/tasks/{id}/fill")
    public ApiResult<SiteInspectionTask> fill(@PathVariable Long id,
                                              @Valid @RequestBody FillInspectionRequest req) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.completeInspection(id, req.checkResult(),
                req.photoUrls(), req.interviewResult(), req.rectifyDeadline()));
    }

    /** 整改期满复核 */
    @PostMapping("/tasks/{id}/verify")
    public ApiResult<SiteInspectionTask> verify(@PathVariable Long id,
                                                @Valid @RequestBody VerifyRectifyRequest req) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.verifyRectification(id, req.passed(), req.closeNote()));
    }

    // ---------- 风险画像与重点巡查名单 ----------

    /** 门店动态风险画像（可按等级 NORMAL/WATCH/KEY 过滤） */
    @GetMapping("/risk-profiles")
    public ApiResult<List<StoreRiskProfile>> profiles(
            @RequestParam(required = false) @Pattern(
                    regexp = "NORMAL|WATCH|KEY", message = "风险等级只能是 NORMAL/WATCH/KEY")
            String level) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.listProfiles(level));
    }

    /** 重点巡查名单 */
    @GetMapping("/risk-profiles/key-patrol")
    public ApiResult<List<StoreRiskProfile>> keyPatrol() {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.keyPatrolList());
    }

    @GetMapping("/risk-profiles/{storeId}")
    public ApiResult<StoreRiskProfile> profile(@PathVariable Long storeId) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(patrolService.getProfile(storeId));
    }

    // ---------- 请求体 ----------

    public record CreateTaskRequest(
            @NotNull(message = "门店不能为空") Long storeId,
            @NotBlank(message = "检查事由不能为空") String reason,
            String inspector) {
    }

    public record FillInspectionRequest(
            @NotBlank(message = "检查结果不能为空")
            @Pattern(regexp = "COMPLIANT|NON_COMPLIANT",
                    message = "检查结果只能是 COMPLIANT 或 NON_COMPLIANT") String checkResult,
            @NotNull(message = "检查照片不能为空") List<@NotBlank String> photoUrls,
            String interviewResult,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rectifyDeadline) {
    }

    public record VerifyRectifyRequest(
            @NotNull(message = "复核结论不能为空") Boolean passed,
            @NotBlank(message = "复核意见不能为空") String closeNote) {
    }
}
