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
 * 供 C 端秒杀专区页一次性拉取，避免按场次多次请求。
 * <p>
 * 【这个类是干什么的】秒杀专区页面要显示「当前这场活动分哪几个时间段、每个时间段卖哪些商品」，
 * 这个实现类就是去数据库把这些数据查出来、拼成一个整包（SeckillResult）交给前端的。
 * <p>
 * 【为什么一次性聚合全部场次，而不是点一场查一场】
 * 1) 秒杀页一打开就要把 10 点场、12 点场、14 点场……全铺出来让用户挑，
 *    如果点一次查一场，来回请求好几趟，页面又慢又卡；
 * 2) 一次查完能保证页面看到的是「同一时刻」的数据，不会出现「刚看到 10 点场有货、
 *    再点 12 点场时活动已经变了」这种前后对不上的情况；
 * 3) 秒杀是「读多写少」的场景，一次多查几场对数据库压力很小，换来的是前端更简单、更稳。
 * <p>
 * 【数据怎么来的】先找「当前正在进行」的活动 → 取所有场次 → 逐个场次查它下面的秒杀商品 → 组装返回。
 * 用到的 Mapper（可理解为「帮你执行 SQL 的现成工具」）都来自 mall-mbg 模块，本类只负责编排。
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private SmsFlashPromotionMapper flashPromotionMapper;
    @Autowired
    private SmsFlashPromotionSessionMapper flashPromotionSessionMapper;
    @Autowired
    private HomeDao homeDao;

    /**
     * 取出「当前正在进行的秒杀活动」整包数据。流程：
     * 1) 先确认现在有没有正在进行的活动，没有就返回一个空盒子（前端显示「暂无活动」）；
     * 2) 有活动就查出全部场次，一个场次一个场次地补齐信息和它下面的秒杀商品；
     * 3) 全部装进 SeckillResult 一次返回。
     */
    @Override
    public SeckillResult getCurrentPromotionSeckill() {
        Date now = new Date();
        SeckillResult result = new SeckillResult();
        SmsFlashPromotion promotion = getActivePromotion(now);
        if (promotion == null) {
            // 当前没有进行中的活动：返回空结果（不是报错），前端据此显示空状态即可
            return result;
        }
        result.setPromotionId(promotion.getId());
        result.setPromotionTitle(promotion.getTitle());
        result.setSessions(new ArrayList<>());
        // 一次性取出全部场次（已按开始时间排好序），下面用循环逐个补齐，不按场次分次查
        List<SmsFlashPromotionSession> sessionList = getSessionList();
        for (SmsFlashPromotionSession session : sessionList) {
            SeckillSessionDetail detail = new SeckillSessionDetail();
            detail.setId(session.getId());
            detail.setName(session.getName());
            detail.setStartTime(session.getStartTime());
            detail.setEndTime(session.getEndTime());
            // 数据库里场次时间是「java.sql.Time」类型，它自带一个 1970-01-01 的基准日期，
            // 直接转成 JSON 给前端，容易因时区换算显示成「08:00」而不是库里的「10:00」。
            // 所以这里额外格式化成 "HH:mm:ss" 的纯字符串（如 "10:00:00"）存进 startTimeStr/endTimeStr，
            // 前端直接拿这个字符串显示，不用自己换算，也就避开了时区歧义。
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            detail.setStartTimeStr(fmt.format(session.getStartTime()));
            detail.setEndTimeStr(fmt.format(session.getEndTime()));
            detail.setStatus(session.getStatus());
            // 按「活动 id + 场次 id」查这一场挂了哪些秒杀商品（秒杀价、限量等）
            List<FlashPromotionProduct> products = homeDao.getFlashProductList(promotion.getId(), session.getId());
            detail.setProductList(products);
            result.getSessions().add(detail);
        }
        return result;
    }

    /**
     * 判断「现在有没有正在进行的秒杀活动」，有就返回那一个活动，没有返回 null。
     * <p>
     * 判断逻辑是三个条件同时满足（可以理解成「活动开关打开 + 今天在活动日期范围内」）：
     * 1) status = 1：活动是「启用」状态，被停用的不算；
     * 2) 开始日期 <= 今天：活动已经开始（或今天刚开始）；
     * 3) 结束日期 >= 今天：活动还没结束。
     * 注意这里用 DateUtil.getDate 把时间归一到「当天」再比较，避免时分秒影响日期判断。
     * 万一查出多个（正常配置下不该有），只取第一个作为当前活动。
     */
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

    /**
     * 取出系统里配置的所有秒杀场次，并按开始时间从早到晚排序（start_time asc），
     * 这样前端拿到的列表天然就是「10 点场、12 点场、14 点场」的顺序，不用自己再排。
     */
    private List<SmsFlashPromotionSession> getSessionList() {
        SmsFlashPromotionSessionExample example = new SmsFlashPromotionSessionExample();
        example.setOrderByClause("start_time asc");
        return flashPromotionSessionMapper.selectByExample(example);
    }
}
