package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.SeckillResult;
import com.macro.mall.portal.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀活动管理 Controller
 */
@RestController
@Tag(name = "SeckillController", description = "秒杀活动管理")
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @Operation(summary = "获取进行中的秒杀活动及全部场次商品")
    @RequestMapping(value = "/sessions", method = RequestMethod.GET)
    public CommonResult<SeckillResult> sessions() {
        return CommonResult.success(seckillService.getCurrentPromotionSeckill());
    }
}
