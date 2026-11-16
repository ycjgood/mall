package com.macro.mall.service;

import com.macro.mall.model.PmsSkuStock;

import java.util.List;

/**
 * 商品SKU库存管理Service
 * Created by macro on 2018/4/27.
 */
public interface PmsSkuStockService {
    /**
     * 根据产品id和skuCode关键字模糊搜索
     */
    List<PmsSkuStock> getList(Long pid, String keyword);

    /**
     * 批量更新商品库存信息
     */
    int update(Long pid, List<PmsSkuStock> skuStockList);

    /**
     * 把数据库的可售库存（stock - lock_stock）预热到 Redis，供下单预扣使用
     *
     * @param overwrite true=强制覆盖（运营重置库存后使用）；false=只补不存在的 key（服务重启后恢复用）
     * @return 处理成功的 SKU 数量
     */
    int preheatToRedis(boolean overwrite);
}
