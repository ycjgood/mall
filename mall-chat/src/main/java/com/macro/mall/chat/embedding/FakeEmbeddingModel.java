package com.macro.mall.chat.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P0 验证用确定性 Embedding（1024 维，与 BGE-M3 同维度）
 *
 * 算法：中文 2-gram + 英文词袋 hash 到 1024 维，L2 归一化。
 * 语义上"同词重叠越多向量越近"，足以验证向量检索与 metadata 过滤逻辑，
 * 后续接入真实 Embedding（BGE-M3 API / 本地部署）只需替换此 Bean，不动任何其他代码。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIM = 1024;

    @Override
    public float[] embed(Document document) {
        return embed(document.getFormattedContent());
    }

    @Override
    public float[] embed(String text) {
        float[] vec = new float[DIM];
        for (String token : tokenize(text)) {
            int h = Math.floorMod(token.hashCode(), DIM);
            vec[h] += 1.0f;
            vec[Math.floorMod(h * 31 + 7, DIM)] += 0.5f;
        }
        // L2 归一化，使余弦检索等价于内积
        double sum = 0;
        for (float v : vec) sum += (double) v * v;
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) for (int i = 0; i < DIM; i++) vec[i] /= norm;
        return vec;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> data = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            data.add(new Embedding(embed(instructions.get(i)), i));
        }
        return new EmbeddingResponse(data);
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    /**
     * 分词：连续 ASCII 词按空格/符号切；连续中文按 2-gram（滑动窗口）
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) return tokens;
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) { // CJK
                if (ascii.length() > 0) { seen.add(ascii.toString().toLowerCase()); ascii.setLength(0); }
                cjk.append(c);
                if (cjk.length() == 2) { seen.add(cjk.toString()); cjk.deleteCharAt(0); }
            } else {
                if (cjk.length() > 0) { seen.add(cjk.toString()); cjk.setLength(0); }
                if (Character.isLetterOrDigit(c)) ascii.append(c);
                else if (ascii.length() > 0) { seen.add(ascii.toString().toLowerCase()); ascii.setLength(0); }
            }
        }
        if (ascii.length() > 0) seen.add(ascii.toString().toLowerCase());
        if (cjk.length() > 0) seen.add(cjk.toString());
        tokens.addAll(seen);
        return tokens;
    }
}
