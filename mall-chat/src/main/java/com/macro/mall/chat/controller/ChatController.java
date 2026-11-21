package com.macro.mall.chat.controller;

import com.macro.mall.chat.service.RagChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ============================================================================
 * 【模块】C 端问答接口 —— 网页和 AI 之间的「传话筒」
 * ============================================================================
 *
 * 【一句话职责】
 * 把浏览器的提问转交给 RagChatService，再把模型生成的一小段一小段文字，
 * 用 SSE 协议持续推回浏览器，做出「像人打字一样一句句冒出来」的效果。
 *
 * 【为什么用 SSE 而不是普通的一次性返回】
 * 本地 4B 模型生成一段回答可能要几十秒。如果等全部写完才返回，
 * 用户会盯着空白页面以为卡死了。SSE 让服务器想到一点就发一点，
 * 用户立刻能看到字在动，体验差别很大。而且 SSE 基于普通 HTTP、单向推送，
 * 对聊天这种「只由服务器往外发」的场景比 WebSocket 更轻。
 *
 * 【对外提供的两个地址】
 *   GET /chat/ask?q=问题        → SSE 流式（正式给前端用）
 *      返回格式是一连串事件，每个事件形如：
 *          id: 1
 *          data: 您好
 *      ……最后一条 data: [DONE] 表示说完了
 *   GET /chat/ask-sync?q=问题   → 整段文本一次性返回（开发时命令行测试用）
 *
 * 【输入 / 输出】
 *   输入：URL 参数 q（顾客问题文本）
 *   输出：Flux&lt;ServerSentEvent&lt;String&gt;&gt;（事件流）/ String（同步版）
 *
 * 【执行流程】
 *   1) 浏览器带 q 请求 /chat/ask
 *   2) 调 ragChatService.askStream(q) 拿到「文字流」（此时可能还没开始生成）
 *   3) .map() 把每一小段文字包装成一个带自增 id 的 SSE 事件
 *   4) .concatWith() 在流末尾补一个 [DONE] 事件，前端收到就知道可以停止转圈了
 *   5) Spring 负责把事件按 SSE 协议写回浏览器，直到流结束
 *
 * 【关键变量 / 类型】
 *   - AtomicInteger seq：事件序号计数器。用 Atomic 而不是普通 int，
 *     因为流式处理可能在不同线程上回调，需要线程安全的自增。
 *   - Flux：一根会陆续流出数据的「管子」，不是一次性装好的列表。
 *   - ServerSentEvent：SSE 协议里的一条消息，含 id / event / data 等字段，这里只用 id 和 data。
 *   - MediaType.TEXT_EVENT_STREAM_VALUE：告诉浏览器「这是事件流，请保持连接别断开」。
 *
 * 【注意事项】
 *   - 这里用 GET 是为了方便前端 EventSource 直接连（EventSource 原生只支持 GET）。
 *   - 目前接口没有登录校验，任何人都能问；正式上线前要按方案补限流和鉴权。
 *   - 前端商城（mall-portal-web）暂时还没接这个接口，接法见技术报告「后续扩展建议」。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final RagChatService ragChatService;

    /** 构造方法注入：Spring 自动把问答核心服务传进来 */
    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * SSE 流式问答。
     * @param q 顾客的问题，例如「小米12屏幕多大」
     * @return 一串事件；每个 data 是一小段生成文字，末尾 [DONE] 表示结束
     */
    @GetMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ask(@RequestParam String q) {
        AtomicInteger seq = new AtomicInteger();
        return ragChatService.askStream(q)
                // 把「一小段文字」→「一条带编号的 SSE 事件」
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .data(chunk)
                        .build())
                // 流结束后追加结束标记：前端收到 [DONE] 就关闭加载动画
                .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
    }

    /** 同步问答（冒烟验证用）：等模型整段写完，一次返回纯文本 */
    @GetMapping("/ask-sync")
    public String askSync(@RequestParam String q) {
        return ragChatService.askSync(q);
    }
}
