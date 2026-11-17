package com.macro.mall.portal.service;

import com.macro.mall.portal.domain.SeckillResult;

/**
 * 秒杀活动管理 Service
 */
public interface SeckillService {

    /**
     * 获取当前进行中的秒杀活动及全部场次商品
     */
    SeckillResult getCurrentPromotionSeckill();
}
