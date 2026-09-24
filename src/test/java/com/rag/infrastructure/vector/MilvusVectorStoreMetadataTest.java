package com.rag.infrastructure.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milvus metadata 序列化往返测试（"写入字段 = 读出字段"守卫）。
 *
 * SWA 层级检索依赖 chunk metadata（chunkLevel/parentChunkId/siblingCount/windowContent）
 * 在 Milvus 写入与检索之间无损往返（known-gaps #SWA 的修复组成部分）。
 */
class MilvusVectorStoreMetadataTest {

    @Test
    @DisplayName("metadata JSON 序列化往返无损（含中文/引号/反斜杠）")
    void metadataRoundTripIsLossless() {
        Map<String, String> meta = new HashMap<>();
        meta.put("chunkLevel", "leaf");
        meta.put("parentChunkId", "p-uuid-9");
        meta.put("siblingCount", "7");
        meta.put("windowContent", "窗口内容示例，含中文与 \"引号\" 及 \\ 反斜杠\n换行");

        String json = MilvusVectorStore.serializeMetadata(meta);
        Map<String, String> parsed = MilvusVectorStore.deserializeMetadata(json);

        assertEquals(meta, parsed, "metadata 经 JSON 序列化写入 Milvus 后必须能无损读回");
    }

    @Test
    @DisplayName("空与异常输入的防御性处理")
    void handlesNullAndInvalidInput() {
        assertEquals("{}", MilvusVectorStore.serializeMetadata(null));
        assertEquals("{}", MilvusVectorStore.serializeMetadata(new HashMap<>()));
        assertTrue(MilvusVectorStore.deserializeMetadata("not-json").isEmpty());
        assertTrue(MilvusVectorStore.deserializeMetadata(null).isEmpty());
    }
}
