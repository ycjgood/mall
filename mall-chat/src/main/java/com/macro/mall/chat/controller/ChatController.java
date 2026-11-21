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
 * C 端问答接口：
 *  GET /chat/ask      —— SSE 流式（前端 EventSource / fetch-stream）
 *  GET /chat/ask-sync —— 同步整段返回（curl 冒烟用）
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /** SSE 流式问答：data 事件逐段推送生成文本，[DONE] 结束 */
    @GetMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ask(@RequestParam String q) {
        AtomicInteger seq = new AtomicInteger();
        return ragChatService.askStream(q)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .data(chunk)
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
    }

    /** 同步问答（冒烟验证用） */
    @GetMapping("/ask-sync")
    public String askSync(@RequestParam String q) {
        return ragChatService.askSync(q);
    }
}
