package com.wasteoil.supervision;

import com.wasteoil.common.ApiResult;
import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 监管抽查追踪：按商户、日期、回收批次生成废油去向完整链路
 * （预约、称重、司机、车辆、到厂联单、结算）。
 */
@RestController
@RequestMapping("/api/supervision")
public class SupervisionChainController {

    private final SupervisionChainService chainService;

    public SupervisionChainController(SupervisionChainService chainService) {
        this.chainService = chainService;
    }

    /**
     * 抽查某条街：按商圈/地址/名称片段匹配街道上的餐饮商户，
     * 返回指定日期各商户的回收批次链路（无批次即当日无回收记录）。
     */
    @GetMapping("/streets/{street}/chains")
    public ApiResult<List<ChainDtos.StreetChain>> streetChains(
            @PathVariable String street,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(chainService.chainByStreet(street, date));
    }

    /**
     * 抽查某商户（门店）某天或某日期区间的完整回收链路。
     */
    @GetMapping("/stores/{storeId}/chains")
    public ApiResult<ChainDtos.StreetChain> storeChains(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthContext.require(ActorRole.REGULATOR);
        return ApiResult.ok(chainService.chainByStore(storeId, date, from, to));
    }

    /** 抽查单个回收批次（电子联单）的完整链路 */
    @GetMapping("/manifests/{manifestId}/chain")
    public ApiResult<ChainDtos.PickupBatch> manifestChain(@PathVariable Long manifestId) {
        AuthContext.require(ActorRole.REGULATOR, ActorRole.RECYCLER);
        return ApiResult.ok(chainService.batchByManifest(manifestId));
    }
}
