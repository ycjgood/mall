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
 * P0 验证与管理接口（后续演进为正式问答接口）
 */
@RestController
@RequestMapping("/chat")
public class ChatAdminController {

    private final KnowledgeIndexService indexService;
    private final VectorStore vectorStore;

    public ChatAdminController(KnowledgeIndexService indexService, VectorStore vectorStore) {
        this.indexService = indexService;
        this.vectorStore = vectorStore;
    }

    /** 全量重建商品知识索引 */
    @PostMapping("/admin/reindex")
    public Map<String, Object> reindex() throws Exception {
        return indexService.reindex();
    }

    /**
     * 检索冒烟接口
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
                item.put("score", d.getMetadata().get("distance"));
                item.put("content", d.getFormattedContent());
                result.add(item);
            }
        }
        return result;
    }
}
