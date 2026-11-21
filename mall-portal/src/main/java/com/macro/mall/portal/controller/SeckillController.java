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
 * 秒杀专区的「前台接待窗口」（Controller）：整个秒杀页只靠这里唯一的一个接口供数据。
 * <p>
 * 这个类是干什么的：商城前端的「秒杀专区」页面一打开，就会调用这里的接口，
 * 一次拿到「当前正在进行的秒杀活动 + 它下面所有场次 + 每场有哪些商品」。
 * 为什么需要它：秒杀页要同时显示多个时间段（比如 10 点场、14 点场）的商品，
 * 若按场次一个个请求，页面加载慢、且多次请求之间活动/库存可能变化导致前后对不上；
 * 所以这里只提供「一次全给」的接口。
 * <p>
 * 名词大白话：Controller（控制器）就是「接待网页请求的窗口」，只负责收请求、把结果装进
 * 统一的 CommonResult 盒子返回，真正查数据库、拼数据的活儿交给后面的 SeckillService。
 * 本类属于 mall-portal（买家端服务，端口 8085），不直接连数据库，也不碰消息队列。
 */
@RestController
@Tag(name = "SeckillController", description = "秒杀活动管理")
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 唯一对外的接口：GET /seckill/sessions，前端打开秒杀页时调一次即可拿到整页数据。
     * <p>
     * 用大白话说：这个方法自己不算数据，只是「转交」——把活儿交给 seckillService，
     * 拿到结果后用 CommonResult.success(...) 装进统一盒子返回给前端。
     * 前端拿到的是一个 SeckillResult（活动 + 全部场次 + 每场商品），可以直接把整页渲染出来。
     */
    @Operation(summary = "获取进行中的秒杀活动及全部场次商品")
    @RequestMapping(value = "/sessions", method = RequestMethod.GET)
    public CommonResult<SeckillResult> sessions() {
        return CommonResult.success(seckillService.getCurrentPromotionSeckill());
    }
}
