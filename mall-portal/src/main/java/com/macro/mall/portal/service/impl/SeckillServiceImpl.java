package com.macro.mall.portal.service.impl;

import com.macro.mall.mapper.SmsFlashPromotionMapper;
import com.macro.mall.mapper.SmsFlashPromotionSessionMapper;
import com.macro.mall.model.SmsFlashPromotion;
import com.macro.mall.model.SmsFlashPromotionExample;
import com.macro.mall.model.SmsFlashPromotionSession;
import com.macro.mall.model.SmsFlashPromotionSessionExample;
import com.macro.mall.portal.dao.HomeDao;
import com.macro.mall.portal.domain.FlashPromotionProduct;
import com.macro.mall.portal.domain.SeckillResult;
import com.macro.mall.portal.domain.SeckillSessionDetail;
import com.macro.mall.portal.service.SeckillService;
import com.macro.mall.portal.util.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 秒杀活动 Service 实现：聚合进行中活动的全部场次与商品，
 * 供 C 端秒杀专区页一次性拉取，避免按场次多次请求
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private SmsFlashPromotionMapper flashPromotionMapper;
    @Autowired
    private SmsFlashPromotionSessionMapper flashPromotionSessionMapper;
    @Autowired
    private HomeDao homeDao;

    @Override
    public SeckillResult getCurrentPromotionSeckill() {
        Date now = new Date();
        SeckillResult result = new SeckillResult();
        SmsFlashPromotion promotion = getActivePromotion(now);
        if (promotion == null) {
            return result;
        }
        result.setPromotionId(promotion.getId());
        result.setPromotionTitle(promotion.getTitle());
        result.setSessions(new ArrayList<>());
        List<SmsFlashPromotionSession> sessionList = getSessionList();
        for (SmsFlashPromotionSession session : sessionList) {
            SeckillSessionDetail detail = new SeckillSessionDetail();
            detail.setId(session.getId());
            detail.setName(session.getName());
            detail.setStartTime(session.getStartTime());
            detail.setEndTime(session.getEndTime());
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            detail.setStartTimeStr(fmt.format(session.getStartTime()));
            detail.setEndTimeStr(fmt.format(session.getEndTime()));
            detail.setStatus(session.getStatus());
            List<FlashPromotionProduct> products = homeDao.getFlashProductList(promotion.getId(), session.getId());
            detail.setProductList(products);
            result.getSessions().add(detail);
        }
        return result;
    }

    private SmsFlashPromotion getActivePromotion(Date date) {
        Date currDate = DateUtil.getDate(date);
        SmsFlashPromotionExample example = new SmsFlashPromotionExample();
        example.createCriteria()
                .andStatusEqualTo(1)
                .andStartDateLessThanOrEqualTo(currDate)
                .andEndDateGreaterThanOrEqualTo(currDate);
        List<SmsFlashPromotion> promotionList = flashPromotionMapper.selectByExample(example);
        return CollectionUtils.isEmpty(promotionList) ? null : promotionList.get(0);
    }

    private List<SmsFlashPromotionSession> getSessionList() {
        SmsFlashPromotionSessionExample example = new SmsFlashPromotionSessionExample();
        example.setOrderByClause("start_time asc");
        return flashPromotionSessionMapper.selectByExample(example);
    }
}
