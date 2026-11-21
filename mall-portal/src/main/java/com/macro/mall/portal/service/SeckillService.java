package com.macro.mall.portal.service;

import com.macro.mall.portal.domain.SeckillResult;

/**
 * 秒杀活动管理 Service（接口）。
 * <p>
 * 用大白话说这个类是干什么的：它是一张「能力清单 / 约定」，只写「要提供什么功能」，不写具体怎么做。
 * 真正干活的是带 Impl 后缀的实现类（SeckillServiceImpl）。为什么要分成清单和实现两份？
 * 这样调用方（Controller）只认这张清单，以后内部怎么改、甚至换一套实现，调用方都不用动。
 * <p>
 * 这里只约定一个能力：把「当前正在进行的秒杀活动 + 它下面全部场次 + 每场商品」一次性取出来，
 * 交给秒杀专区页面去显示。
 */
public interface SeckillService {

    /**
     * 获取当前进行中的秒杀活动及全部场次商品。
     * <p>
     * 为什么是「一次性取全部」而不是按场次分次取：秒杀页一打开就要同时展示好几个时间段
     * （例如 10 点场、12 点场）的商品，分次请求会让页面拼接很麻烦、还容易出现前后不一致；
     * 一次返回整包数据（SeckillResult），前端拿来直接渲染即可。
     */
    SeckillResult getCurrentPromotionSeckill();
}
