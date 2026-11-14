# 下单压测脚本

配合 `高并发改造方案.md` 第四章「场景 4：JMeter 压测对比」使用。

## 前置条件

- mall-portal 已启动（默认 8085），且 **关闭限流**：`--mall.rate-limit.enabled=false`
- MySQL/Redis 可用，测试会员密码统一为 `123456`
- JMeter 5.6.3（本机 `D:\software\soft\apache-jmeter-5.6.3`）

## 步骤

### 1. 准备数据

```sql
-- 库存压到 100 件，清掉历史测试购物车
update mall.pms_sku_stock set stock=100, lock_stock=0 where id=99;
delete from mall.oms_cart_item where product_sku_id=99;
-- 9 个会员各 40 条购物车（递归 CTE 生成）
insert into mall.oms_cart_item(member_id,product_id,product_sku_id,quantity,price,product_name,product_pic,product_attr,delete_status,create_date)
with recursive seq as (select 1 n union all select n+1 from seq where n<40)
select m.id, 27, 99, 1, 2999.00, 'Huawei Mate 20', '/p.jpg', '6GB+64GB', 0, now()
from mall.ums_member m join seq where m.id in (1,3,4,5,6,7,8,9,10);
```

### 2. 导出待下单清单 players.tsv

```sql
select c.member_id, a.addr, c.id, m.username
from mall.oms_cart_item c
join (select member_id, max(id) addr from mall.ums_member_receive_address
      where member_id in (1,3,4,5,6,7,8,9,10) group by member_id) a on a.member_id=c.member_id
join mall.ums_member m on m.id=c.member_id
where c.product_sku_id=99 and c.delete_status=0 order by c.id;
```

### 3. 预生成令牌

```bash
python prep_tokens.py players.tsv bench.csv
```

输出 `bench.csv`（列：jwt, order_token, address_id, cart_id）。登录是 BCrypt 校验、单次约 100ms，必须放在压测之外做，否则 CPU 打满导致 TPS 失真。

### 4. 执行压测

```bash
jmeter.bat -n -t order-bench.jmx -l result.jtl -j jmeter.log
```

参数：40 线程、ramp-up 5s、每线程 9 次 = 360 请求。CSV 读完即停（`stopThread=true`），不会重复使用已被下单消耗的购物车。

### 5. 判定

| 口径 | 命令 |
|---|---|
| 成功数 / P95 | 解析 `result.jtl`（`success` 列 + `elapsed` 列） |
| 是否超卖 | `SKU99 成功订单数 <= 初始库存` 且 `lock_stock == 成功订单数` |
| 事务是否回滚 | 失败请求对应的购物车条目仍然存在（`delete_status=0`） |

## 两种模式的启动参数

```bash
# A 基线（改造前，会超卖）
java -jar mall-portal.jar --mall.rate-limit.enabled=false \
  --mall.order.stock-lock-mode=legacy --mall.order.idempotent-enabled=false

# B 改造后（推荐，防超卖 + 幂等）
java -jar mall-portal.jar --mall.rate-limit.enabled=false \
  --mall.order.stock-lock-mode=atomic --mall.order.idempotent-enabled=true
```

## 实测结论（2026-11-14）

| 指标 | A 基线 | B 改造后 |
|---|---|---|
| 成功下单 | 359（库存 100） | 100 |
| 超卖 | 259 件 | 0 |
| lock_stock | 47 | 100 |
| 吞吐 | 66.7 req/s | 73.5 req/s |
| P95 | 202 ms | 178 ms |
