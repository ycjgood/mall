package com.macro.mall.portal.domain;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 秒杀场次详情：包含场次信息与其下的秒杀商品列表
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
    private String endTimeStr;
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
