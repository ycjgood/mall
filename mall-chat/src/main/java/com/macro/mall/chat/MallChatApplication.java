package com.macro.mall.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mall 智能问答服务（RAG + Agent）
 * 独立于交易主链路：AI 不可用时降级，不影响下单
 */
@SpringBootApplication
public class MallChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(MallChatApplication.class, args);
    }
}
