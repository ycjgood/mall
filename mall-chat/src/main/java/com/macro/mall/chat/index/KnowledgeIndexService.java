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
 * ============================================================================
 * 【模块】商品知识「灌库」服务 —— 给 AI 编一本参考书
 * ============================================================================
 *
 * 【一句话职责】
 * 把数据库里冷冰冰的商品表，改写成一段段「人能读懂、AI 也好查」的资料，
 * 再把这些资料转换成向量，存进 Milvus 向量库。这一步叫「灌库」或「建索引」。
 * 没有这一步，AI 就无资料可查，任何问题都只能回答「抱歉没查到」。
 *
 * 【用生活类比理解】
 * 数据库里的商品表像仓库的货架清单（编号、字段、外键）；
 * 这个类做的事，是把清单改写成一本《商品说明书》：
 *   「【商品】Apple iPhone 14 【分类】手机 【参数】电池容量:3000毫安 ...」
 * 然后把说明书每一页都贴上一个「语义标签」（向量），以后顾客问「电池多大」，
 * 系统就能凭意思翻到这一页，而不需要顾客精确说出商品型号。
 *
 * 【最重要的一条铁律：价格、库存绝不写进这本参考书】
 * 价格会随秒杀、活动、调价实时变化，库存每下一单都在变。
 * 一旦把它们写进知识库，AI 就会把「某一刻的旧价格」当成事实告诉顾客，
 * 这是电商 AI 客服最常见、也最致命的事故（顾客按报错的价格来投诉）。
 * 所以本类组装文本时，SQL 里根本没有查 price / stock 字段。
 * 顾客真问价格时，由提示词引导他去商品页看实时数字；将来 P2 阶段会接工具实时查。
 *
 * 【输入 / 输出】
 *   输入：
 *     - 数据库（只读）pms_product 商品表、pms_product_attribute_value 属性表、pms_sku_stock 规格表
 *     - 调用方触发：POST /chat/admin/reindex
 *   输出：
 *     - Milvus 的 mall_knowledge 集合里，每个在售商品一条 Document（文档）
 *     - 返回统计 Map：{products: 商品数, documents: 写入文档数, costMs: 耗时毫秒}
 *
 * 【执行流程（reindex 一次全量重建）】
 *   1) dropCollection()：删掉旧的知识库集合（相当于把旧参考书扔掉）
 *   2) 让 Spring AI 的 VectorStore 重新建表（表结构：id / embedding / metadata / content）
 *   3) loadProductDocuments()：查 MySQL，把每个商品拼成一段结构化文本 + 一串「标签信息」
 *   4) 分批（每批 32 条）交给 VectorStore.add()：
 *      内部会自动调用 bge-m3 把文本变成 1024 维向量，再写进 Milvus
 *   5) 返回统计结果
 *
 * 【关键变量 / 字段说明】
 *   - vectorStore：Spring AI 提供的「向量库操作门面」，我们只管调 add/similaritySearch，
 *     底层 Milvus 的连接、建表、向量化细节它都包了。
 *   - jdbcTemplate：执行原生 SQL 的轻量工具，本项目只用它读商品数据。
 *   - dropClient：单独建的 Milvus 连接，专门用来删旧集合。
 *     为什么不复用 vectorStore 的连接？因为自动配置创建的对象名字是框架内部决定的，
 *     直接按名字注入容易因版本升级失效，自己建一条最稳。
 *   - SP_KEY_VALUE：正则表达式，用来从 SKU 的 JSON 串里抠出 "key":"颜色","value":"金色" 这类规格。
 *   - metadata（文档元数据）：相当于给每页说明书贴的便利贴，记录
 *     doc_type=product（资料类型）、product_id、category_id（分类）、brand_id、title（标题）。
 *     以后可以只查「手机类商品」，或把商品文档和售后政策文档分开。
 *
 * 【注意事项 / 踩过的坑】
 *   - ★ 启动时绝对不能删集合！早期版本在 afterPropertiesSet() 里做了 drop，
 *     结果每次重启服务，知识库被清空但没重新灌，问答接口直接 500。
 *     现在启动什么都不做（no-op），「先删后建」只发生在人工调用 reindex 时。
 *   - 目前是全量重建：商品少（20 个在售）时 3.4 秒搞定；
 *     商品上万后要改成增量（只更新变化的那几个商品），方案里计划复用已有的
 *     mall.product.sync 消息队列来做，见技术报告「后续扩展建议」。
 *   - 队列/SKU 规格里可能存脏数据（空值、格式不规范），代码里都做了 null 或空串保护。
 *
 * 【名词扫盲】
 *   - Document = 一条知识 = 正文文本 + 元数据标签 + 唯一 id。
 *   - Embedding / 向量化 = 把一段文字变成一串数字（本项目 1024 个小数），
 *     意思相近的文字，数字串也相近，于是「电池容量」能匹配到「3000毫安」。
 *   - Collection（集合）= Milvus 里放向量的「表」，本模块固定叫 mall_knowledge。
 */
@Service
public class KnowledgeIndexService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    /** SKU 规格 JSON 的解析规则：从 {"key":"颜色","value":"金色"} 里分别抠出键和值 */
    private static final Pattern SP_KEY_VALUE = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");

    /** 向量库门面：负责「文本 → 向量 → 存入 Milvus」以及后面的相似度检索 */
    private final VectorStore vectorStore;
    /** 只读查 MySQL 的工具 */
    private final JdbcTemplate jdbcTemplate;
    /** 独立管理连接，仅用于灌库前的 collection 重建（不依赖 auto-config 内部 bean 命名） */
    private final MilvusServiceClient dropClient;

    /**
     * 构造方法：Spring 会自动把需要的对象传进来（依赖注入）。
     * host / port 来自 application.yml 的 spring.ai.vectorstore.milvus.client.*，
     * 这样换 Milvus 地址只改配置、不改代码。
     */
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
     * Spring 在对象创建完成后会自动调用这里。
     * ★ 故意留空：启动时不做任何清理。
     * 教训：以前在这里删集合，导致「服务一重启 → 知识库空了 → 检索 500」。
     * 幂等性（反复执行不出错）由 reindex() 自己保证，不需要启动阶段插手。
     */
    @Override
    public void afterPropertiesSet() {
        // no-op：什么都不做，保留已有知识
    }

    /** 服务关闭时释放 Milvus 连接，避免连接泄漏 */
    @Override
    public void destroy() {
        if (dropClient != null) dropClient.close();
    }

    /**
     * 全量重建知识库（对外由 POST /chat/admin/reindex 触发）。
     *
     * 流程：删旧集合 → 重建表 → 查库拼文档 → 分批向量化写入 → 返回统计。
     * synchronized：防止两个人同时点重建，导致一边删一边写、数据错乱。
     *
     * @return 统计结果 Map，含商品数、文档数、耗时
     */
    public synchronized Map<String, Object> reindex() throws Exception {
        long t0 = System.currentTimeMillis();   // 计时起点，用来统计本次重建花了多久
        dropCollection();
        // 删完后让 VectorStore 重新建表：它的 afterPropertiesSet 是幂等的
        //（集合已存在就跳过，不存在才新建），所以这里可以放心调用。
        ((InitializingBean) vectorStore).afterPropertiesSet();
        log.info("[reindex] collection 已重建");

        List<Document> docs = loadProductDocuments();
        int total = 0;
        int batch = 32;   // 每批 32 条：一次性全塞给模型会内存吃紧，分批更稳
        for (int i = 0; i < docs.size(); i += batch) {
            // add() 内部会调用 bge-m3 把每条文本变成 1024 维向量再写入 Milvus
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

    /** 删除旧集合。集合本来就不存在时会抛异常，这里吞掉并记日志即可（属于正常情况）。 */
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
     * 从 mall 库组装「商品知识文档」——本类最核心的方法。
     *
     * 输入：无显式参数（直接查数据库）
     * 输出：List&lt;Document&gt;，一个在售商品一条文档
     *
     * 拼出来的文本长这样（注意：完全没有价格、库存）：
     *   【商品】Apple iPhone 14 (A2884)
     *   【分类】手机通讯
     *   【品牌】苹果
     *   【卖点】……【关键词】……【详情】……
     *   【参数】电池容量:3000毫安；屏幕尺寸:6.1英寸；……
     *   【可选规格】颜色: 金色/银色；版本: 128G/256G；
     *
     * 流程：
     *   1) 查 pms_product，只取「未删除 + 已上架」的商品（下架商品不该被顾客问到）
     *   2) 查属性值表，按商品 ID 汇总成「参数」一行
     *   3) 查 SKU 表，用正则从 JSON 里抠出可选规格，去重后汇总（LinkedHashSet 保证顺序又不重复）
     *   4) 逐条拼文本 + 组装 metadata 标签
     *
     * 为什么一个商品只做一条文档（而不是按参数拆成很多条）？
     * 因为 demo 商品资料很短，整段一起给大模型反而上下文完整；
     * 将来商品详情很长时，才需要按「参数 / 售后 / 使用方法」分块（chunking）。
     */
    List<Document> loadProductDocuments() {
        // ① 只查在售商品：delete_status=0 未删除，publish_status=1 已上架。
        //    注意 SELECT 里刻意没有 price、stock —— 这是全项目最重要的约定，别手滑加上。
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                "SELECT id, name, sub_title, keywords, brand_name, product_category_name, " +
                "       product_category_id, brand_id, detail_title " +
                "FROM pms_product WHERE delete_status = 0 AND publish_status = 1");

        // ② 商品属性参数：把同一个商品的多个「属性名:值」用中文分号拼起来，存进 attrs。
        //    merge 的第三个参数是「撞车时怎么合并」，这里就是追加拼接。
        //    例：26 号商品 → "电池容量:3000毫安；屏幕尺寸:6.1英寸"
        Map<Long, String> attrs = new HashMap<>();
        jdbcTemplate.query(
                "SELECT pav.product_id, pa.name, pav.value FROM pms_product_attribute_value pav " +
                "JOIN pms_product_attribute pa ON pav.product_attribute_id = pa.id",
                rs -> {
                    attrs.merge(rs.getLong(1), rs.getString(2) + ":" + rs.getString(3),
                            (a, b) -> a + "；" + b);
                });

        // ③ SKU 可选规格：sp_data 是一串 JSON，形如
        //    [{"key":"颜色","value":"金色"},{"key":"版本","value":"128G"}]
        //    用正则把 key/value 抠出来，按商品汇总、按规格名分组、值去重。
        //    用 LinkedHashSet 而不是 HashSet，是为了保持数据库里的原始顺序，输出更稳定。
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

        // ④ 逐个商品拼文本 + 贴标签
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

            // metadata = 检索时能带回来的「标签」，也是将来做分类过滤的依据。
            // 例如 doc_type 让我们能把「商品资料」和以后的「退货政策」区分开。
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("doc_type", "product");
            metadata.put("biz_id", String.valueOf(id));
            metadata.put("product_id", id);
            if (p.get("product_category_id") != null)
                metadata.put("category_id", ((Number) p.get("product_category_id")).intValue());
            if (p.get("brand_id") != null)
                metadata.put("brand_id", ((Number) p.get("brand_id")).longValue());
            metadata.put("title", String.valueOf(p.get("name")));

            // 文档 id 用 "product_37" 这种可读格式，排查问题时一眼能看出对应哪个商品
            docs.add(new Document("product_" + id, sb.toString(), metadata));
        }
        return docs;
    }
}
