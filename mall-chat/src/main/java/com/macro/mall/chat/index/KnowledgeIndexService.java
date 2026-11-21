package com.macro.mall.chat.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.DropCollectionParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品知识离线灌库服务（P0 第三步）
 *
 * 文本组装遵循方案 §3.1 铁律：价格、库存、促销价严禁进入语料。
 * chunk 结构：名称 + 分类 + 品牌 + 卖点(副标题/关键词) + 属性参数 + SKU 可选规格
 */
@Service
public class KnowledgeIndexService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final Pattern SP_KEY_VALUE = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    /** 独立管理连接，仅用于灌库前的 collection 重建（不依赖 auto-config 内部 bean 命名） */
    private final MilvusServiceClient dropClient;

    public KnowledgeIndexService(VectorStore vectorStore,
                                 JdbcTemplate jdbcTemplate,
                                 @Value("${spring.ai.vectorstore.milvus.client.host}") String host,
                                 @Value("${spring.ai.vectorstore.milvus.client.port}") int port) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.dropClient = new MilvusServiceClient(
                ConnectParam.newBuilder().withHost(host).withPort(port).build());
    }

    /**
     * 启动时不做任何清理——幂等性由 {@link #reindex()} 内部的 drop 保证。
     * （教训：启动即 drop 会导致服务重启后 collection 空缺、检索 500）
     */
    @Override
    public void afterPropertiesSet() {
        // no-op
    }

    @Override
    public void destroy() {
        if (dropClient != null) dropClient.close();
    }

    /**
     * 全量重建商品知识索引。
     * 流程：drop collection -> 重建 schema -> 查库组装文档 -> 分批向量化写入
     */
    public synchronized Map<String, Object> reindex() throws Exception {
        long t0 = System.currentTimeMillis();
        dropCollection();
        // 让 VectorStore 重新建表（afterPropertiesSet 幂等：存在跳过，不存在新建）
        ((InitializingBean) vectorStore).afterPropertiesSet();
        log.info("[reindex] collection 已重建");

        List<Document> docs = loadProductDocuments();
        int total = 0;
        int batch = 32;
        for (int i = 0; i < docs.size(); i += batch) {
            vectorStore.add(docs.subList(i, Math.min(i + batch, docs.size())));
            total += Math.min(batch, docs.size() - i);
            log.info("[reindex] progress {}/{}", total, docs.size());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("products", docs.size());
        result.put("documents", total);
        result.put("costMs", System.currentTimeMillis() - t0);
        log.info("[reindex] 完成: {} 条文档, {}ms", total, result.get("costMs"));
        return result;
    }

    private void dropCollection() {
        try {
            dropClient.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName("mall_knowledge").build());
            log.info("[reindex] dropped old collection mall_knowledge");
        } catch (Exception e) {
            log.info("[reindex] collection mall_knowledge 不存在，跳过 drop");
        }
    }

    /**
     * 从 mall 库组装商品知识文档（一个商品一条结构化文本，方案 §3.3 商品分块策略）
     */
    List<Document> loadProductDocuments() {
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                "SELECT id, name, sub_title, keywords, brand_name, product_category_name, " +
                "       product_category_id, brand_id, detail_title " +
                "FROM pms_product WHERE delete_status = 0 AND publish_status = 1");

        // 属性参数（规格+参数）：product_id -> "颜色:金色,银色；屏幕尺寸:5.0"
        Map<Long, String> attrs = new HashMap<>();
        jdbcTemplate.query(
                "SELECT pav.product_id, pa.name, pav.value FROM pms_product_attribute_value pav " +
                "JOIN pms_product_attribute pa ON pav.product_attribute_id = pa.id",
                rs -> {
                    attrs.merge(rs.getLong(1), rs.getString(2) + ":" + rs.getString(3),
                            (a, b) -> a + "；" + b);
                });

        // SKU 可选规格（distinct key/value，不含价格库存）
        Map<Long, Map<String, LinkedHashSet<String>>> skuSpecs = new HashMap<>();
        jdbcTemplate.query(
                "SELECT product_id, sp_data FROM pms_sku_stock",
                rs -> {
                    long pid = rs.getLong(1);
                    Matcher m = SP_KEY_VALUE.matcher(rs.getString(2) == null ? "" : rs.getString(2));
                    Map<String, LinkedHashSet<String>> spec = skuSpecs.computeIfAbsent(pid, k -> new HashMap<>());
                    while (m.find()) {
                        spec.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
                    }
                });

        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> p : products) {
            long id = ((Number) p.get("id")).longValue();
            StringBuilder sb = new StringBuilder();
            sb.append("【商品】").append(p.get("name")).append('\n');
            if (p.get("product_category_name") != null) sb.append("【分类】").append(p.get("product_category_name")).append('\n');
            if (p.get("brand_name") != null) sb.append("【品牌】").append(p.get("brand_name")).append('\n');
            if (p.get("sub_title") != null && !((String) p.get("sub_title")).isBlank())
                sb.append("【卖点】").append(p.get("sub_title")).append('\n');
            if (p.get("keywords") != null && !((String) p.get("keywords")).isBlank())
                sb.append("【关键词】").append(p.get("keywords")).append('\n');
            if (p.get("detail_title") != null && !((String) p.get("detail_title")).isBlank())
                sb.append("【详情】").append(p.get("detail_title")).append('\n');
            String attr = attrs.get(id);
            if (attr != null) sb.append("【参数】").append(attr).append('\n');
            Map<String, LinkedHashSet<String>> spec = skuSpecs.get(id);
            if (spec != null && !spec.isEmpty()) {
                StringBuilder specLine = new StringBuilder("【可选规格】");
                spec.forEach((k, vs) -> specLine.append(k).append(": ").append(String.join("/", vs)).append("；"));
                sb.append(specLine);
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("doc_type", "product");
            metadata.put("biz_id", String.valueOf(id));
            metadata.put("product_id", id);
            if (p.get("product_category_id") != null)
                metadata.put("category_id", ((Number) p.get("product_category_id")).intValue());
            if (p.get("brand_id") != null)
                metadata.put("brand_id", ((Number) p.get("brand_id")).longValue());
            metadata.put("title", String.valueOf(p.get("name")));

            docs.add(new Document("product_" + id, sb.toString(), metadata));
        }
        return docs;
    }
}
