package com.macro.mall.portal.dao;

import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao（数据访问层）。
 * <p>
 * 【这个类是干什么的】它属于 mall-portal（买家端服务）的「数据库读写窗口」：
 * 订单相关的复杂 SQL（查订单连明细、按条件关单、锁定/释放库存等）都在这里声明方法，
 * 真正的 SQL 语句写在对应的 XML 映射文件里，由 MyBatis 按方法名对上并执行。
 * 上层的 OmsPortalOrderServiceImpl 只管调这些方法，不用自己写 SQL。
 * <p>
 * 【依赖什么】只连 MySQL（通过 mall-mbg 的实体和本模块的自定义映射），
 * 不直接碰 Redis / RabbitMQ / Elasticsearch；但方法设计上配合上层的幂等与库存预扣逻辑：
 * 例如 closeUnpaidOrder、lockSkuStock 都用「带条件的一条 UPDATE」，
 * 由数据库保证同一笔订单不会被重复关、库存不会被超卖（各方法下方注释有详细解释）。
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
     * 关闭未支付订单（条件更新，幂等）
     * <p>
     * SQL 语义：{@code UPDATE oms_order SET status = 4 WHERE id = #{orderId} AND status = 0}
     * <p>
     * 把「订单当前仍是待付款」的判断和状态修改压进一条 UPDATE，由 InnoDB 行锁保证原子性。
     * 延迟消息可能重复投递，定时扫描任务也可能与消息消费并发取消同一订单，
     * 只有真正把状态从 0 改成 4 的那一次调用返回 1，后续重复调用返回 0，
     * 调用方据此跳过库存回滚/优惠券返还等副作用，避免重复释放。
     *
     * @return 影响行数，0 表示订单不存在或已被取消/支付
     */
    int closeUnpaidOrder(@Param("orderId") Long orderId);

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
