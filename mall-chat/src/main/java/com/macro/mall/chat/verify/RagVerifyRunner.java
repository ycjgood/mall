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
 * P0 第二步验证：Spring AI MilvusVectorStore 固定 schema + metadata 过滤适配性
 *
 * 验证矩阵：
 *  V1 纯向量检索           —— 相似度排序是否符合预期
 *  V2 doc_type 单字段过滤  —— JSON metadata 过滤是否生效
 *  V3 组合过滤             —— doc_type + category_id 组合表达式
 *
 * 全部通过 = 方案的风险项 9.1-② 排除，mall-chat 可正式采用 Spring AI + Milvus。
 */
@ConditionalOnProperty(name = "rag.verify-enabled", havingValue = "true")
@Component
public class RagVerifyRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagVerifyRunner.class);
    private final VectorStore vectorStore;

    public RagVerifyRunner(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed();
        v1_plainSearch();
        v2_docTypeFilter();
        v3_combinedFilter();
        log.info("===== P0-2 ALL PASSED: Spring AI MilvusVectorStore schema/过滤验证通过 =====");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("P0-2 验证失败: " + message);
        }
    }

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
