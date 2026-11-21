package com.macro.mall.portal.domain;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 秒杀场次详情：包含场次信息与其下的秒杀商品列表。
 * <p>
 * 用大白话说：「场次」就是秒杀里的一个时间段（比如「10:00 场」「14:00 场」），
 * 这个类同样是一个「装数据的盒子」，代表其中某一个场次：几点开始、几点结束、是否启用，
 * 以及这个时间段里能抢的商品清单（productList）。
 * 页面上通常一个场次对应一个这样的对象，多个对象组成 SeckillResult 里的 sessions 列表。
 * <p>
 * 为什么时间存了两份（startTime/endTime 和 startTimeStr/endTimeStr）：
 * 前者是数据库查出来的时间对象，后者是格式化成 "HH:mm:ss" 的纯文本字符串（如 "10:00:00"）。
 * 数据库里这个时间类型是 java.sql.Time，自带 1970-01-01 基准日期，直接传给前端换算时区时容易显示错位；
 * 所以额外备一份字符串让前端直接显示，谁都不用做时区换算，最省事也最不容易错。
 */
public class SeckillSessionDetail {
    private Long id;
    private String name;
    private Date startTime;
    private Date endTime;
    private Integer status;
    /**
     * 场次开始时间（HH:mm:ss 字符串）：java.sql.Time 以 1970 为基准，前端直接用字符串避免时区歧义
     */
    private String startTimeStr;
    /**
     * 场次结束时间（HH:mm:ss 字符串），作用同 startTimeStr，供前端直接显示「几点结束」
     */
    private String endTimeStr;
    /**
     * 本场次挂的秒杀商品清单：每件含秒杀价、限量等，供页面逐个渲染
     */
    private List<FlashPromotionProduct> productList;

    public String getStartTimeStr() {
        return startTimeStr;
    }

    public void setStartTimeStr(String startTimeStr) {
        this.startTimeStr = startTimeStr;
    }

    public String getEndTimeStr() {
        return endTimeStr;
    }

    public void setEndTimeStr(String endTimeStr) {
        this.endTimeStr = endTimeStr;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<FlashPromotionProduct> getProductList() {
        return productList;
    }

    public void setProductList(List<FlashPromotionProduct> productList) {
        this.productList = productList;
    }
}
