package com.macro.mall.common.domain;

import java.io.Serializable;
import java.util.List;

/**
 * 商品变更消息：mall-admin 写入商品后投递，mall-search 消费并同步 ES 索引。
 * <p>
 * 消息只带商品 ID，不带商品快照。这样消费端永远按数据库最新状态构建文档，
 * 天然规避「先发的旧消息后到」把新数据覆盖回旧值的问题（顺序无关）。
 * <p>
 * 使用 JDK 序列化（项目未配置 JSON MessageConverter），因此必须实现 Serializable。
 */
public class ProductSyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 发生变更的商品 ID 列表
     */
    private List<Long> productIds;

    /**
     * 变更原因，仅用于日志追踪
     */
    private String reason;

    public ProductSyncMessage() {
    }

    public ProductSyncMessage(List<Long> productIds, String reason) {
        this.productIds = productIds;
        this.reason = reason;
    }

    public List<Long> getProductIds() {
        return productIds;
    }

    public void setProductIds(List<Long> productIds) {
        this.productIds = productIds;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "ProductSyncMessage{productIds=" + productIds + ", reason='" + reason + "'}";
    }
}
