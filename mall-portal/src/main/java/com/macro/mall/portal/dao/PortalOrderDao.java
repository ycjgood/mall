package com.macro.mall.portal.dao;

import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao
 * Created by macro on 2018/9/4.
 */
public interface PortalOrderDao {
    /**
     * 获取订单及下单商品详情
     */
    OmsOrderDetail getDetail(@Param("orderId") Long orderId);

    /**
     * 修改 pms_sku_stock表的锁定库存及真实库存
     */
    int updateSkuStock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 获取超时订单
     * @param minute 超时时间（分）
     */
    List<OmsOrderDetail> getTimeOutOrders(@Param("minute") Integer minute);

    /**
     * 批量修改订单状态
     */
    int updateOrderStatus(@Param("ids") List<Long> ids,@Param("status") Integer status);

    /**
     * 解除取消订单的库存锁定
     */
    int releaseSkuStockLock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 原子锁定单个 SKU 的库存
     * <p>
     * SQL 语义：{@code UPDATE pms_sku_stock SET lock_stock = lock_stock + #{quantity}
     * WHERE id = #{skuId} AND stock - lock_stock >= #{quantity}}
     * <p>
     * 把「判断可售库存是否足够」和「累加锁定库存」压进一条 UPDATE，
     * 由 InnoDB 行锁保证并发下的原子性：抢不到库存的请求影响行数为 0，
     * 调用方据此抛异常回滚整个下单事务，从根上避免超卖。
     *
     * @return 影响行数，0 表示库存不足
     */
    int lockSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

}
