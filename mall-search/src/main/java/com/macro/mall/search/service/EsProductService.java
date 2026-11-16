package com.macro.mall.search.service;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 搜索商品管理Service
 * Created by macro on 2018/6/19.
 */
public interface EsProductService {
    /**
     * 从数据库中导入所有商品到ES
     */
    int importAll();

    /**
     * 根据id删除商品
     */
    void delete(Long id);

    /**
     * 根据id创建商品
     */
    EsProduct create(Long id);

    /**
     * 批量删除商品
     */
    void delete(List<Long> ids);

    /**
     * 同步单个商品的索引：商品在库且已上架则写入/覆盖文档，否则从索引中移除。
     * <p>
     * 与 {@link #create(Long)} 的区别：create 在商品查不到时什么都不做，
     * 会让已下架/已删除的商品一直留在索引里被搜出来。同步场景必须处理「查不到」这一情况。
     *
     * @return true 表示索引已写入，false 表示文档已被移除或未变更
     */
    boolean sync(Long id);

    /**
     * 批量同步商品索引，单条失败不影响其余商品
     *
     * @return 实际写入索引的条数
     */
    int sync(List<Long> ids);

    /**
     * 根据关键字通过名称或副标题查询商品
     */
    Page<EsProduct> search(String keyword, Integer pageNum, Integer pageSize);

    /**
     * 根据关键字通过名称或副标题复合查询商品
     */
    Page<EsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize,Integer sort);

    /**
     * 根据商品id推荐相关商品
     */
    Page<EsProduct> recommend(Long id, Integer pageNum, Integer pageSize);

    /**
     * 搜索关键字相关品牌、分类、属性
     */
    EsProductRelatedInfo searchRelatedInfo(String keyword);
}
