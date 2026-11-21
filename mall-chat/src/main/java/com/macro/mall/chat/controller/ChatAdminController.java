package com.macro.mall.chat.controller;

import com.macro.mall.chat.index.KnowledgeIndexService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * 【模块】管理 / 调试接口 —— 给开发和运维用的「后台按钮」
 * ============================================================================
 *
 * 【一句话职责】
 * 提供两个不给普通顾客用、但排查问题必不可少的接口：
 *   ① 重建知识库（商品改了之后让 AI 重新学一遍）
 *   ② 单独测试「检索」这一步（不经过大模型，直接看翻到了哪几页资料）
 *
 * 【为什么要把「检索」单独暴露出来】
 * RAG 答错时，最难的是分清「资料没查到」还是「查到了但模型说错了」。
 * /chat/search 只看检索结果（命中哪条、距离多少），相当于给整条链路装了个检查口：
 *   - 这里就没命中 → 去查灌库 / 向量模型 / 阈值
 *   - 这里命中了但回答不对 → 去调提示词 / 生成模型
 * 这个「分段可观测」的设计，是调 RAG 效果时最实用的工具。
 *
 * 【对外提供的两个地址】
 *   POST /chat/admin/reindex        → 全量重建知识库，返回 {products, documents, costMs}
 *   GET  /chat/search?q=...&type=...&topK=... → 只看检索结果
 *
 * 【输入 / 输出】
 *   reindex：无参数；输出统计 Map
 *   search：
 *     输入 q（问题文本）、type（可选，按 doc_type 过滤，如 product / policy）、topK（返回条数，默认 5）
 *     输出 List&lt;Map&gt;，每条含 id / title / docType / categoryId / score(距离) / content(正文)
 *
 * 【执行流程（search）】
 *   1) 构造 SearchRequest：查询文本 + 返回条数
 *   2) 若传了 type，用 FilterExpressionBuilder 加一个 metadata 过滤条件
 *      （doc_type 存在文档的 metadata 里，Milvus 会先按条件筛、再算相似度）
 *   3) vectorStore.similaritySearch() 执行检索
 *   4) 把 Document 拆成前端好读的 Map 列表返回
 *
 * 【注意事项】
 *   - reindex 会「先删后建」，执行期间问答可能短暂查不到资料，别在高峰期点。
 *   - 这两个接口目前没有权限校验，正式上线前必须加后台鉴权，否则任何人都能清空知识库。
 *   - score 字段实际是「距离」：数值越小越相关（不是越大越好，别看反了）。
 */
@RestController
@RequestMapping("/chat")
public class ChatAdminController {

    private final KnowledgeIndexService indexService;   // 负责重建知识库
    private final VectorStore vectorStore;              // 负责相似度检索

    public ChatAdminController(KnowledgeIndexService indexService, VectorStore vectorStore) {
        this.indexService = indexService;
        this.vectorStore = vectorStore;
    }

    /** 全量重建商品知识索引：把旧库删掉、按数据库最新商品重新灌一遍 */
    @PostMapping("/admin/reindex")
    public Map<String, Object> reindex() throws Exception {
        return indexService.reindex();
    }

    /**
     * 检索冒烟接口：只验证「翻资料」这一步，不调用大模型，响应很快。
     * @param q     查询文本
     * @param type  可选，按 doc_type 过滤（product/policy...）
     * @param topK  返回条数，默认 5
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(defaultValue = "5") int topK) {
        SearchRequest.Builder builder = SearchRequest.builder().query(q).topK(topK);
        if (type != null && !type.isBlank()) {
            // metadata 过滤：例如只看商品文档，把以后的售后政策文档排除掉
            builder.filterExpression(new FilterExpressionBuilder().eq("doc_type", type).build());
        }
        List<Document> hits = vectorStore.similaritySearch(builder.build());
        List<Map<String, Object>> result = new ArrayList<>();
        if (hits != null) {
            for (Document d : hits) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", d.getId());
                item.put("title", d.getMetadata().get("title"));
                item.put("docType", d.getMetadata().get("doc_type"));
                item.put("categoryId", d.getMetadata().get("category_id"));
                item.put("score", d.getMetadata().get("distance"));   // 距离，越小越相关
                item.put("content", d.getFormattedContent());
                result.add(item);
            }
        }
        return result;
    }
}
