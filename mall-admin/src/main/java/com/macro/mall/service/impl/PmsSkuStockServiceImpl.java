package com.macro.mall.service.impl;

import cn.hutool.core.util.StrUtil;
import com.macro.mall.common.stock.RedisStockSupport;
import com.macro.mall.dao.PmsSkuStockDao;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import com.macro.mall.service.PmsSkuStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品SKU库存管理Service实现类
 * Created by macro on 2018/4/27.
 */
@Service
public class PmsSkuStockServiceImpl implements PmsSkuStockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PmsSkuStockServiceImpl.class);

    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private PmsSkuStockDao skuStockDao;
    @Autowired
    private RedisStockSupport redisStockSupport;

    @Override
    public List<PmsSkuStock> getList(Long pid, String keyword) {
        PmsSkuStockExample example = new PmsSkuStockExample();
        PmsSkuStockExample.Criteria criteria = example.createCriteria().andProductIdEqualTo(pid);
        if (!StrUtil.isEmpty(keyword)) {
            criteria.andSkuCodeLike("%" + keyword + "%");
        }
        return skuStockMapper.selectByExample(example);
    }

    @Override
    public int update(Long pid, List<PmsSkuStock> skuStockList) {
        List<PmsSkuStock> filterSkuList = skuStockList.stream()
                .filter(item -> pid.equals(item.getProductId()))
                .collect(Collectors.toList());
        int count = skuStockDao.replaceList(filterSkuList);
        if (count > 0) {
            refreshRedisForProduct(pid);
        }
        return count;
    }

    @Override
    public int preheatToRedis(boolean overwrite) {
        // 全量扫描而不是按商品维度：秒杀前预热通常是一次性动作，数据量在千级完全可接受
        List<PmsSkuStock> skuStockList = skuStockMapper.selectByExample(new PmsSkuStockExample());
        for (PmsSkuStock skuStock : skuStockList) {
            redisStockSupport.preheat(skuStock.getId(), availableOf(skuStock), overwrite);
        }
        LOGGER.info("[库存预热] 完成，共 {} 个 SKU，overwrite={}", skuStockList.size(), overwrite);
        return skuStockList.size();
    }

    /**
     * 后台修改库存后刷新对应商品的 Redis 计数（覆盖写）
     * <p>
     * 不刷新会出现「后台页面显示 100 件、Redis 仍是 0 件」的错位，
     * 用户下单永远提示库存不足。改库存属于低频操作，重新查一次库的代价可以接受。
     */
    private void refreshRedisForProduct(Long pid) {
        PmsSkuStockExample example = new PmsSkuStockExample();
        example.createCriteria().andProductIdEqualTo(pid);
        for (PmsSkuStock skuStock : skuStockMapper.selectByExample(example)) {
            redisStockSupport.preheat(skuStock.getId(), availableOf(skuStock), true);
        }
    }

    /**
     * 可售数量：库存为 null 视为 0，且不允许为负（历史超卖遗留的脏数据不能放大成负库存）
     */
    private int availableOf(PmsSkuStock skuStock) {
        int stock = skuStock.getStock() == null ? 0 : skuStock.getStock();
        int lockStock = skuStock.getLockStock() == null ? 0 : skuStock.getLockStock();
        return Math.max(0, stock - lockStock);
    }
}
