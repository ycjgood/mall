package com.macro.mall.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 问答生成链路：
 *   用户问题 -> 向量检索 topK -> 组装受限上下文 -> qwen3.5 流式生成
 *
 * 设计要点（方案 §4/§6）：
 *  - 上下文只喂知识库命中文档，模型无从编造
 *  - 价格/库存类问题由系统提示词拦截（实时数据走 Agent 工具，P2 接入）
 *  - 检索无命中或全部不相关（distance 超阈值）时直接拒答，不进模型
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    /** 系统提示词：约束回答边界（方案 §6.2 提示词工程） */
    private static final String SYSTEM_PROMPT = """
            你是 mall 商城的智能客服助手，名叫"小智"。请严格遵守以下规则：
            1. 只依据【参考资料】回答问题；资料中没有的信息，直接回复"抱歉，暂时没有查到相关资料"，绝对不要编造。
            2. 商品价格、库存、秒杀价格属于实时数据，禁止根据资料或记忆给出具体数字，请引导用户到商城对应商品页查看最新信息。
            3. 回答面向顾客，简洁友好，使用中文，控制在 300 字以内。
            4. 如果参考资料与问题无关，按没有资料处理。
            """;

    /** 用户消息模板：参考资料 + 问题 */
    private static final String USER_TEMPLATE = """
            【参考资料】
            %s
            【用户问题】
            %s
            请根据参考资料回答。
            """;

    /** 无命中时的拒答文案 */
    private static final String REJECT_ANSWER = "抱歉，暂时没有查到相关资料。您可以换个说法描述问题，或到商品详情页查看更多信息～";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("${rag.chat.top-k:4}")
    private int topK;

    @Value("${rag.chat.max-context-chars:3000}")
    private int maxContextChars;

    @Value("${rag.chat.reject-distance:0.55}")
    private double rejectDistance;

    public RagChatService(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 流式问答：检索 -> 组装上下文 -> SSE 流式返回
     * 无命中时直接返回拒答文案，不消耗模型资源。
     */
    public Flux<String> askStream(String question) {
        List<Document> hits = retrieve(question);
        if (isIrrelevant(hits)) {
            log.info("[chat] reject (best distance too far) for: {}", question);
            return Flux.just(REJECT_ANSWER);
        }
        String context = buildContext(hits);
        String userMsg = String.format(USER_TEMPLATE, context, question);
        log.info("[chat] question='{}', hits={}, contextChars={}", question, hits.size(), context.length());
        return chatClient.prompt()
                .user(userMsg)
                .stream()
                .content();
    }

    /** 同步问答（curl 冒烟用） */
    public String askSync(String question) {
        List<Document> hits = retrieve(question);
        if (isIrrelevant(hits)) {
            return REJECT_ANSWER;
        }
        String context = buildContext(hits);
        String userMsg = String.format(USER_TEMPLATE, context, question);
        return chatClient.prompt().user(userMsg).call().content();
    }

    /**
     * 相关性判断：只看最佳命中（第一名）的距离。
     * 实测分布：商品类问题最佳命中 0.29~0.43，无关问题 0.58+；
     * 逐条过滤会误杀 2~4 名的补充上下文，故只卡第一名。
     */
    private boolean isIrrelevant(List<Document> hits) {
        if (hits.isEmpty()) {
            return true;
        }
        Object dist = hits.get(0).getMetadata().get("distance");
        return dist != null && ((Number) dist).doubleValue() > rejectDistance;
    }

    /** 向量检索：返回 topK 全部命中，相关性由 isIrrelevant 统一判断 */
    private List<Document> retrieve(String question) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());
        return hits == null ? List.of() : hits;
    }

    /** 组装受限上下文：来源标注 + 总长度截断 */
    private String buildContext(List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size() && sb.length() < maxContextChars; i++) {
            Document d = hits.get(i);
            String title = String.valueOf(d.getMetadata().getOrDefault("title", "未知"));
            sb.append("[").append(i + 1).append("] ").append(title).append("\n");
            String content = d.getFormattedContent();
            if (sb.length() + content.length() > maxContextChars) {
                sb.append(content, 0, Math.max(0, maxContextChars - sb.length())).append("...\n");
            } else {
                sb.append(content).append("\n");
            }
        }
        return sb.toString();
    }
}
