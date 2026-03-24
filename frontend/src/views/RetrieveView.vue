<template>
  <div class="search-wrapper">
    <div class="search-box">
      <input type="text" class="search-input"
             v-model="searchQuery"
             @keyup.enter="doSearch"
             placeholder="输入搜索关键词..."
             :disabled="searching">
      <button class="search-btn" @click="doSearch" :disabled="searching || !searchQuery.trim()">
        <span v-if="searching" class="loading-spinner"></span>
        <span v-else>搜索</span>
      </button>
    </div>

    <div v-if="searching" class="empty-state">
      搜索中...
    </div>

    <div v-else-if="searched && results.length === 0" class="no-results">
      <div class="no-results-icon">🔍</div>
      <div>没有找到相关结果</div>
    </div>

    <div v-else-if="results.length > 0" class="results-list">
      <div v-for="(result, index) in results" :key="index" class="result-item">
        <div class="result-header">
          <span class="result-rank">{{ index + 1 }}</span>
          <span class="result-score">相似度 {{ (result.score * 100).toFixed(1) }}%</span>
        </div>
        <div class="result-content">{{ result.content }}</div>
        <div class="result-footer">来源: {{ result.chunkId || '未知' }}</div>
      </div>
    </div>

    <div v-else class="empty-state">
      输入关键词开始搜索
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { retrieve } from '../api'

const searchQuery = ref('')
const searching = ref(false)
const searched = ref(false)
const results = ref([])

const doSearch = async () => {
  const query = searchQuery.value.trim()
  if (!query || searching.value) return

  searching.value = true
  searched.value = true

  try {
    const res = await retrieve({
      query: query,
      kbIds: [],
      topK: 10,
      rerank: true
    })
    results.value = res.data.results || []
  } catch (err) {
    results.value = []
    console.error('Search failed:', err)
  }

  searching.value = false
}
</script>
