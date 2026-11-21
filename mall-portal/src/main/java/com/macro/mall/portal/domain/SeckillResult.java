package com.macro.mall.portal.domain;

import java.util.List;

/**
 * 进行中秒杀活动的完整信息：活动标题 + 全部场次及商品。
 * <p>
 * 用大白话说这个类是干什么的：它本身不干活，只是一个「装数据的盒子」。
 * 后端把查到的活动信息填进去，转成 JSON 一次性返回给前端，前端照着字段把秒杀页画出来。
 * 为什么要单独做个盒子：秒杀页的数据是「一个活动 → 多个场次 → 每场多个商品」三层结构，
 * 用这么一个类把三层打包，接口只返回一个对象，前端处理起来最简单。
 * <p>
 * 字段说明：promotionId=活动编号；promotionTitle=活动标题（页面顶部那行大字）；
 * sessions=场次列表，列表里每一项（SeckillSessionDetail）代表一个时间段及其商品。
 * 如果当前没有进行中的活动，后端会返回一个各字段都为空的盒子，前端据此显示「暂无秒杀」。
 */
public class SeckillResult {
    private Long promotionId;
    private String promotionTitle;
    private List<SeckillSessionDetail> sessions;

    public Long getPromotionId() {
        return promotionId;
    }

    public void setPromotionId(Long promotionId) {
        this.promotionId = promotionId;
    }

    public String getPromotionTitle() {
        return promotionTitle;
    }

    public void setPromotionTitle(String promotionTitle) {
        this.promotionTitle = promotionTitle;
    }

    public List<SeckillSessionDetail> getSessions() {
        return sessions;
    }

    public void setSessions(List<SeckillSessionDetail> sessions) {
        this.sessions = sessions;
    }
}
