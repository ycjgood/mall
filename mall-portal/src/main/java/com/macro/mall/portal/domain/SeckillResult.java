package com.macro.mall.portal.domain;

import java.util.List;

/**
 * 进行中秒杀活动的完整信息：活动标题 + 全部场次及商品
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
