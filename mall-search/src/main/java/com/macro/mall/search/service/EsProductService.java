package com.macro.mall.search.service;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 搜索商品管理 Service（接口）：约定「商品索引」能提供哪些能力。
 * <p>
 * 【这个接口是干什么的】它是一张能力清单，只写方法名不写实现，真正干活的是 EsProductServiceImpl。
 * 这里的「索引」可以类比成图书的「目录卡片」：MySQL 存商品正数据，Elasticsearch（ES）
 * 另存一份专门用来「查得快」的目录，用户搜索时查的是这份目录，而不是翻数据库。
 * <p>
 * 【提供哪些能力】全量导入(importAll)、按 id 新建/删除文档、搜索、相关推荐、以及本轮新增的
 * 单条/批量同步 sync(...)——后台改了商品后，靠它把某一件商品在 ES 里的目录卡片更新到最新。
 * <p>
 * 【依赖什么、数据怎么来】本接口属于 mall-search 搜索服务（端口 8081），
 * 目录数据来自 MySQL（通过 mall-mbg 的实体与本模块 DAO 查询），由 RabbitMQ 的
 * mall.product.sync 消息触发增量同步；本接口不写 MySQL，只负责维护 ES 索引和对外搜索。
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
     * <p>
     * 【用大白话再说一遍为什么「查得到就写、查不到就删」】
     * 把 ES 索引想成超市门口的「商品导购牌」，MySQL 才是库房里的真实存货。
     * 后台每次改商品都会发消息说「这件商品动了」，本方法就照这个 id 回库房查一次：
     * - 查得到（还在卖、还上架）：把导购牌上这件的内容刷成最新（名字/价格/图片等），这叫「写索引」；
     * - 查不到（已下架、已删除）：说明库房里已经没这货了，就必须把导购牌上这张旧卡片撕掉，
     *   否则顾客按牌子去找，会搜到一件其实已经不卖的商品（点进去才发现没了）。
     * 原版只有 create，商品下架时查不到就「什么都不做」，等于旧卡片一直留在牌子上——
     * 这就是「下架不删文档」的缺陷，本方法用「查不到就删除文档」把它补上了。
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
