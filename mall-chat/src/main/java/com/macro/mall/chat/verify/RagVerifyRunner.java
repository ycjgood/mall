package com.macro.mall.chat.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * 【模块】技术验证脚本 —— AI 方案立项前的「可行性体检」（默认关闭）
 * ============================================================================
 *
 * 【一句话职责】
 * 在项目刚起步、还没写业务代码时，先用 5 条假资料跑一遍：
 * 「向量库能不能建表？能不能按意思排序？能不能按标签过滤？」
 * 三项全过，才敢确定用 Spring AI + Milvus 这套技术栈，避免后期推倒重来。
 *
 * 【为什么现在还留着它】
 * 它是一份可复跑的「体检报告」：以后升级 Spring AI / Milvus 版本，
 * 或换了一台机器，把它打开跑一次就能确认底层兼容，不用靠猜。
 *
 * 【默认不运行 —— 重要】
 * 类上的 @ConditionalOnProperty(name = "rag.verify-enabled", havingValue = "true")
 * 表示：只有 application.yml 里把 rag.verify-enabled 改成 true，这个类才会被 Spring 创建。
 * 现在配置是 false，所以它完全不参与日常运行，也不会往知识库里塞假数据。
 *
 * 【验证矩阵（三项）】
 *  V1 纯向量检索            —— 问「手机电池容量多大」，含电池语义的商品应该排最前
 *  V2 doc_type 单字段过滤   —— 只要「商品」文档，售后政策文档必须被排除
 *  V3 组合过滤              —— 同时限定 doc_type=product 且 category_id=3（手机类），冰箱要被排除
 *
 * 【输入 / 输出】
 *   输入：无（seed() 里写死了 5 条测试文档）
 *   输出：控制台日志 + 断言结果；任何一项不符就抛异常让启动失败（fail fast）
 *
 * 【执行流程】
 *   服务启动 → Spring 发现开关为 true → 自动调用 run()
 *   → seed() 写 5 条假文档 → 等 1.5 秒让向量索引可见
 *   → V1/V2/V3 依次检索并断言 → 全部通过打印 ALL PASSED
 *
 * 【注意事项】
 *   - Thread.sleep(1500) 是必要的：Milvus 刚写入的向量要过一小会儿才能被检索到，
 *     不等就会误判为「检索失败」。生产环境由 flush / compaction 策略保证，不用手动 sleep。
 *   - V1 最初用自造的「假向量」（按字面重叠算相似度）跑通，后来已换成真实 bge-m3；
 *     因此断言只检查「含电池字样的文档排第一」，换成真模型后语义更准，断言可放宽。
 *   - 类里写的 5 条文档是测试数据，不要误当成真实商品资料。
 *
 * 【名词扫盲】
 *   metadata 过滤：向量库除了存向量，还能存一串标签（类型、分类 ID），
 *   检索时可以先用标签缩小范围，再算相似度，类似「先按分类书架找，再翻内容」。
 */
@ConditionalOnProperty(name = "rag.verify-enabled", havingValue = "true")
@Component
public class RagVerifyRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagVerifyRunner.class);
    private final VectorStore vectorStore;

    public RagVerifyRunner(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 启动完成后自动执行：按 写数据 → V1 → V2 → V3 的顺序体检 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed();
        v1_plainSearch();
        v2_docTypeFilter();
        v3_combinedFilter();
        log.info("===== P0-2 ALL PASSED: Spring AI MilvusVectorStore schema/过滤验证通过 =====");
    }

    /** 极简断言工具：条件不成立就抛异常，让启动直接失败，避免「看起来跑起来了其实是错的」 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("P0-2 验证失败: " + message);
        }
    }

    /**
     * 写 5 条测试文档：3 条商品（其中 2 条手机、1 条冰箱）+ 2 条售后政策。
     * 每条都带 metadata 标签，V2/V3 就是靠这些标签来验过滤是否生效。
     * ★ 这些数据只在开关打开时写入，默认配置下不会污染正式知识库。
     */
    private void seed() {
        Map<String, Object> prod26 = Map.of("doc_type", "product", "biz_id", "26", "category_id", 3);
        Map<String, Object> prod28 = Map.of("doc_type", "product", "biz_id", "28", "category_id", 3);
        Map<String, Object> prod30 = Map.of("doc_type", "product", "biz_id", "30", "category_id", 8);
        Map<String, Object> policy1 = Map.of("doc_type", "policy", "biz_id", "refund_001");
        Map<String, Object> policy2 = Map.of("doc_type", "policy", "biz_id", "warranty_001");

        vectorStore.add(List.of(
                new Document("prod_26",
                        "iPhone 14 6.1英寸OLED屏幕 A15仿生芯片 3279mAh电池容量 支持20W快充",
                        prod26),
                new Document("prod_28",
                        "小米12 骁龙8处理器 6.28英寸屏幕 4500mAh电池 67W快充",
                        prod28),
                new Document("prod_30",
                        "海尔冰箱501升双开门 风冷无霜 一级能效",
                        prod30),
                new Document("policy_refund",
                        "商品自签收之日起7天内支持无理由退货 需保持商品包装完好",
                        policy1),
                new Document("policy_warranty",
                        "iPad整机保修一年 电池保修六个月 人为损坏不在保修范围",
                        policy2)
        ));
        log.info("[seed] 5 条知识文档已写入");
    }

    /** V1: 纯向量检索，无过滤 —— 含"电池"语义的文档应排在最前 */
    private void v1_plainSearch() throws InterruptedException {
        Thread.sleep(1500); // 等索引可见
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query("手机电池容量多大").topK(5).build());
        log.info("[V1] 纯向量检索 top5:");
        hits.forEach(d -> log.info("     id={}, meta={}", d.getId(), d.getMetadata()));
        assertThatHits(hits, 5, null, null);
        check(hits.get(0).getId().equals("prod_26") || hits.get(0).getId().equals("prod_28"),
                "V1 期望含'电池'语义的商品文档排第一，实际=" + hits.get(0).getId());
        log.info("[V1] PASS —— 排序语义正确");
    }

    /** V2: doc_type 单字段过滤 —— 只返回商品文档 */
    private void v2_docTypeFilter() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("电池保修多久")
                .topK(5)
                .filterExpression(b.eq("doc_type", "product").build())
                .build());
        log.info("[V2] doc_type=product 过滤结果 top{}:", hits.size());
        hits.forEach(d -> log.info("     id={}, meta={}", d.getId(), d.getMetadata()));
        assertThatHits(hits, -1, "product", null);
        log.info("[V2] PASS —— 单字段 JSON 过滤生效");
    }

    /** V3: 组合过滤 —— doc_type=product 且 category_id=3（手机类） */
    private void v3_combinedFilter() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("电器商品介绍")
                .topK(5)
                .filterExpression(b.and(
                        b.eq("doc_type", "product"),
                        b.eq("category_id", 3)).build())
                .build());
        log.info("[V3] doc_type=product AND category_id=3 过滤结果 top{}:", hits.size());
        hits.forEach(d -> log.info("     id={}, meta={}", d.getId(), d.getMetadata()));
        assertThatHits(hits, -1, "product", 3);
        log.info("[V3] PASS —— 组合过滤生效（冰箱类商品被正确排除）");
    }

    /**
     * 统一结果断言：
     * @param minSize 期望最小条数（-1 表示不校验条数，但要求非空）
     * @param docType 期望的 doc_type（null 表示不校验）
     * @param categoryId 期望的 category_id（null 表示不校验）
     */
    private void assertThatHits(List<Document> hits, int minSize, String docType, Integer categoryId) {
        check(!hits.isEmpty(), "检索结果为空");
        if (minSize > 0) {
            check(hits.size() >= minSize, "期望至少 " + minSize + " 条，实际 " + hits.size());
        }
        for (Document d : hits) {
            if (docType != null) {
                check(docType.equals(d.getMetadata().get("doc_type")),
                        "过滤失效: id=" + d.getId() + " doc_type=" + d.getMetadata().get("doc_type"));
            }
            if (categoryId != null) {
                Object v = d.getMetadata().get("category_id");
                check(v instanceof Number && ((Number) v).intValue() == categoryId,
                        "过滤失效: id=" + d.getId() + " category_id=" + v);
            }
        }
    }
}
