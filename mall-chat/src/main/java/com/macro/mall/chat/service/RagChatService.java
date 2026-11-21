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
 * ============================================================================
 * 【模块】RAG 问答核心 —— 顾客提问后，真正「动脑子」的地方
 * ============================================================================
 *
 * 【一句话职责】
 * 收到顾客的一句大白话问题 → 去知识库里翻出最相关的几段商品资料 →
 * 把资料连同问题一起交给本地大模型 → 让它照着资料生成回答 → 逐字往外吐。
 *
 * 【用生活类比理解 RAG】
 * 直接问大模型，像让一个记性不好又爱编的学生闭卷考试；
 * RAG 则是「开卷考试」：我们先帮他翻到参考书对应的那几页，
 * 再规定「只准照着这几页答，书上没有就说不知道」。
 * 这样既不用重新训练模型（省钱），又能让它回答自家商品的具体参数（准确）。
 *
 * 【输入 / 输出】
 *   输入：question —— 顾客输入的自然语言问题，例如「iPhone 14 电池容量多大」
 *   输出：
 *     askStream() → Flux&lt;String&gt;：一段一段的文本流（供网页边生成边显示）
 *     askSync()   → String：整段回答一次性返回（主要给命令行测试用）
 *
 * 【一次问答的完整执行流程（以「电池容量多大」为例）】
 *   1) retrieve()：把问题用 bge-m3 变成 1024 维向量，去 Milvus 里找意思最接近的 4 条资料。
 *      Spring AI 会自动完成「问题转向量」这一步，我们只传字符串。
 *      实测命中：「iPhone 14」距离 0.294（很近）、「iPhone 8 Plus」0.434、「小米笔记本」0.531 …
 *      距离（distance）越小 = 意思越接近；这里用的是余弦距离 COSINE。
 *   2) isIrrelevant()：看排第一的那条资料够不够近。
 *      实测规律：问商品的问题，第一名距离约 0.29~0.43；问「今天天气」这种无关问题，
 *      第一名也要 0.58 以上。于是定阈值 0.55：超过就判定「知识库里没这事」，
 *      直接返回固定拒答文案，连大模型都不调用（省钱又避免胡说）。
 *      ★ 只卡第一名，不逐条过滤：第 2~4 名虽然远一点，但常含补充信息，
 *        全砍掉反而让回答变单薄。这个取舍是实测调出来的。
 *   3) buildContext()：把命中的资料拼成一段「参考资料」，标上 [1][2] 来源，
 *      总长度截断到 3000 字符，防止把大模型的输入撑爆。
 *   4) 用 USER_TEMPLATE 拼成最终提问：「【参考资料】…【用户问题】…请根据参考资料回答」
 *   5) ChatClient 把 SYSTEM_PROMPT（规矩）+ 上面这段提问一起发给本地 qwen3.5:4b，
 *      .stream() 表示要流式输出（模型每想出几个字就立刻推给前端，不用等整段写完）。
 *
 * 【四条提示词规矩为什么这么定（这是 AI 落地的业务约束，不是技术炫技）】
 *   ① 只依据资料回答 + 资料没有就说不知道 → 防止「幻觉」（一本正经地编造参数）
 *   ② 价格、库存、秒杀价禁止报数，引导去商品页 → 因为这些数字实时在变，
 *      知识库和模型记忆里的都是旧值，报出来就是客诉。方案里计划 P2 接「工具」
 *      让 AI 实时查库，那时才允许回答数字，而且必须由服务端查、模型不能自己编。
 *   ③ 中文、300 字以内 → 客服场景要短、要友好
 *   ④ 资料无关按没有资料处理 → 双保险，配合第 2 步的距离阈值
 *
 * 【关键变量说明】
 *   - vectorStore：向量库门面，similaritySearch() 就是「按意思找资料」
 *   - chatClient：Spring AI 封装的大模型调用器；.defaultSystem() 给它定死规矩，
 *     之后每次提问都会自动带上这段系统提示词，不用重复写
 *   - topK = 4：翻几页参考书。太少容易漏，太多会掺进无关内容还费 token
 *   - maxContextChars = 3000：参考资料总长度上限
 *   - rejectDistance = 0.55：拒答阈值（可在 application.yml 的 rag.chat.* 里调）
 *   - Document.getMetadata().get("distance")：本次检索算出来的距离，判断相关性用
 *
 * 【注意事项】
 *   - 这些阈值是拿当前 20 个 demo 商品实测出来的；换成真实商品库、或接入更多
 *     「售后政策 / 活动规则」类文档后，必须重新采样调阈值，否则会误拒或误答。
 *   - 目前是纯向量检索，对「A2890 有货吗」这种精确型号/编码类问题会失灵，
 *     方案 P1 计划加 ES 关键词检索做混合召回（RRF 融合）。
 *   - qwen3.5:4b 在纯 CPU 上生成较慢（首字可能要几十秒），属算力限制；
 *     换 GPU 或更大模型时本类代码不用改，只改 application.yml 里的模型名。
 *
 * 【名词扫盲】
 *   - Flux：响应式「流」类型，可以理解成一根会陆续流出文字的管子，前端边收边显示。
 *   - SSE：Server-Sent Events，服务器单向往浏览器推消息的技术，打字机效果就靠它。
 *   - System Prompt（系统提示词）：每次对话前先给模型立的规矩，优先级高于用户说的话。
 *   - ChatModel / ChatClient：前者是「模型本身」，后者是带规矩、带模板的「好用版调用器」。
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    /**
     * 系统提示词：每次对话都会先悄悄发给模型的「规矩」。
     * 用三引号文本块（Java 15+）写多行，改文案时不用拼一堆 \n。
     * ★ 这里第 2 条是电商 AI 的安全底线：宁可让用户去看商品页，也不能报一个可能过期的价格。
     */
    private static final String SYSTEM_PROMPT = """
            你是 mall 商城的智能客服助手，名叫"小智"。请严格遵守以下规则：
            1. 只依据【参考资料】回答问题；资料中没有的信息，直接回复"抱歉，暂时没有查到相关资料"，绝对不要编造。
            2. 商品价格、库存、秒杀价格属于实时数据，禁止根据资料或记忆给出具体数字，请引导用户到商城对应商品页查看最新信息。
            3. 回答面向顾客，简洁友好，使用中文，控制在 300 字以内。
            4. 如果参考资料与问题无关，按没有资料处理。
            """;

    /**
     * 用户消息模板：把「查到的资料」和「顾客的问题」包在一起发给模型。
     * %s 是两个占位符，运行时用 String.format 依次填入 context 和 question。
     * 把资料放在前面、问题放在最后，是提示词工程的常用做法（模型更注意结尾的指令）。
     */
    private static final String USER_TEMPLATE = """
            【参考资料】
            %s
            【用户问题】
            %s
            请根据参考资料回答。
            """;

    /** 判定「查不到」时直接返回的固定话术，不进模型、不消耗算力 */
    private static final String REJECT_ANSWER = "抱歉，暂时没有查到相关资料。您可以换个说法描述问题，或到商品详情页查看更多信息～";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    /** 检索返回几条资料，来自 application.yml 的 rag.chat.top-k，默认 4 */
    @Value("${rag.chat.top-k:4}")
    private int topK;

    /** 参考资料总长度上限，默认 3000 字符 */
    @Value("${rag.chat.max-context-chars:3000}")
    private int maxContextChars;

    /** 拒答距离阈值，默认 0.55；数值越大越宽松（越容易硬答） */
    @Value("${rag.chat.reject-distance:0.55}")
    private double rejectDistance;

    /**
     * 构造方法：Spring 自动注入向量库和本地大模型。
     * ChatClient.builder(chatModel).defaultSystem(SYSTEM_PROMPT).build()
     * 相当于给模型配了一本「员工手册」，之后每次提问都自动附上，业务代码不必重复传。
     */
    public RagChatService(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 流式问答（网页真正用的入口）。
     *
     * 输入：question 顾客问题
     * 输出：Flux&lt;String&gt; 文本流；若判定无关，流里只有一句拒答话术
     *
     * 流程：检索 → 相关性判断 → 拼上下文 → 拼提示词 → 让模型流式生成
     * 为什么拒答也返回 Flux？为了让调用方（Controller）永远只需处理一种类型，
     * 不用为「拒答」写一套特殊分支。
     */
    public Flux<String> askStream(String question) {
        List<Document> hits = retrieve(question);
        if (isIrrelevant(hits)) {
            log.info("[chat] reject (best distance too far) for: {}", question);
            return Flux.just(REJECT_ANSWER);   // 只发一条就结束，完全不碰模型
        }
        String context = buildContext(hits);
        String userMsg = String.format(USER_TEMPLATE, context, question);
        log.info("[chat] question='{}', hits={}, contextChars={}", question, hits.size(), context.length());
        // .stream().content() 让模型边生成边返回，前端就能做出「一个字一个字蹦出来」的效果
        return chatClient.prompt()
                .user(userMsg)
                .stream()
                .content();
    }

    /**
     * 同步问答：逻辑与 askStream 完全一样，只是 .call() 等模型把整段写完再一次性返回。
     * 用途：命令行 curl 冒烟测试、后续做批量评测跑分。
     */
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
     * 相关性判断：只看「排第一」的那条资料够不够近。
     *
     * 实测分布（20 个 demo 商品，COSINE 距离）：
     *   商品类问题（如电池容量）第一名 ≈ 0.29~0.43
     *   无关问题（如今天天气）  第一名 ≈ 0.58 以上
     *   → 取 0.55 作为分界线。
     *
     * 为什么只卡第一名？因为第 2~4 名常常是「同品牌其他型号」之类的补充资料，
     * 距离虽然偏大但有用；如果逐条按阈值过滤，会把它们全砍掉，回答反而变差。
     *
     * @return true 表示「跟知识库没关系，应该拒答」
     */
    private boolean isIrrelevant(List<Document> hits) {
        if (hits.isEmpty()) {
            return true;   // 一条都没查到
        }
        Object dist = hits.get(0).getMetadata().get("distance");
        return dist != null && ((Number) dist).doubleValue() > rejectDistance;
    }

    /**
     * 向量检索：把问题变成向量，去 Milvus 找 topK 条最相近的资料。
     * 这一行代码背后 Spring AI 帮我们做了三件事：
     *   ① 调 bge-m3 把问题文本转成 1024 维向量
     *   ② 在 Milvus 里做余弦相似度计算并排序
     *   ③ 把命中的资料（正文 + metadata + 距离）还原成 Document 列表
     * 所以业务代码里看不到任何「向量」「维度」的细节，这是用框架的好处。
     */
    private List<Document> retrieve(String question) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(topK).build());
        return hits == null ? List.of() : hits;
    }

    /**
     * 把命中的若干条资料拼成一段「参考资料」文本。
     * 加 [1][2] 编号和标题，是让模型知道资料来自不同商品，便于它自己取舍。
     * 到达 maxContextChars 就截断并加 "..."，避免超出模型输入长度限制或拖慢生成。
     */
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
