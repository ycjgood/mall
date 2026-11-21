package com.macro.mall.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================================
 * 【模块】mall-chat —— 商城 AI 智能问答服务（整个项目的「AI 大脑」入口）
 * ============================================================================
 *
 * 【一句话职责】
 * 启动一个独立的 Java 服务（端口 8086），让顾客能用大白话问商品问题，
 * 例如「iPhone 14 电池多大」，它会先去知识库里翻资料，再让本地大模型
 * 组织成一句人话回答出来。
 *
 * 【为什么要单独做一个服务，而不是塞进 mall-portal】
 * 下单、支付是商城的命脉，必须稳；而 AI 大模型可能慢、可能出错、可能要重启。
 * 把 AI 拆成独立服务后：AI 挂了，顾客只是问不了问题，购物车和下单完全不受影响。
 * 这在架构上叫「故障隔离」，是 AI 落地企业系统时最重要的一条原则。
 *
 * 【本模块内部的分工（按一次问答的先后顺序）】
 *   1) index/KnowledgeIndexService  —— 后台把商品资料整理成「知识」，存进向量库（相当于先编好一本参考书）
 *   2) service/RagChatService       —— 收到问题后翻这本参考书，把相关段落抄给大模型，让它照着答
 *   3) controller/ChatController   —— 对外提供网页能调用的接口，把回答一个字一个字地推给浏览器
 *   4) controller/ChatAdminController —— 给开发/运维用的管理接口（重建知识库、单独测试检索效果）
 *   5) verify/RagVerifyRunner       —— 早期验证「向量库能不能用」的测试代码，默认关闭
 *
 * 【依赖哪些外部东西（少一个都起不来）】
 *   - MySQL（虚拟机 192.168.150.101:3306 的 mall 库）：只读商品、参数、规格数据
 *   - Ollama（本机 localhost:11434）：本地大模型运行器，提供两个模型
 *       · bge-m3    —— 负责把文字变成数字向量（Embedding，1024 维）
 *       · qwen3.5:4b —— 负责读资料、组织语言、生成回答（对话模型）
 *   - Milvus（虚拟机 192.168.150.101:19530）：向量数据库，专门按「意思相近」来查资料
 *   以上全部本地/内网，不花钱、不传数据到外网。
 *
 * 【输入 / 输出】
 *   输入：浏览器发来的一个问题文本，例如 GET /chat/ask?q=小米12屏幕多大
 *   输出：SSE 流式文本（一段一段往外吐字），最后发一个 [DONE] 表示说完
 *
 * 【注意事项】
 *   - 这是 Spring Boot 应用，@SpringBootApplication 会自动扫描本包及子包下的所有组件并装配。
 *   - 配置文件 application.yml 里写死了上述地址；换机器时只需要改那一个文件。
 *   - 首次启动后知识库是空的，必须先调用 POST /chat/admin/reindex 灌一次知识，否则问答会一直拒答。
 *
 * 【名词扫盲】
 *   RAG = Retrieval-Augmented Generation（检索增强生成）。
 *   大白话：不让大模型凭记忆瞎猜，而是先帮它查资料，再让它「照着资料回答」。
 *   这也是本模块能避免报出错误价格的关键设计。
 */
@SpringBootApplication
public class MallChatApplication {
    /**
     * 程序入口：Java 程序从这里开始跑。
     * 这一行做的事 = 启动 Spring 容器 → 自动连上 MySQL/Ollama/Milvus → 监听 8086 端口等请求。
     * args 是启动参数，一般为空；需要临时改端口时可以传 --server.port=xxxx。
     */
    public static void main(String[] args) {
        SpringApplication.run(MallChatApplication.class, args);
    }
}
