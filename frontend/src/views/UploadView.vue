<template>
  <div class="upload-wrapper">
    <h2 class="upload-title">文档上传</h2>

    <div class="chunk-settings">
      <div class="setting-row">
        <label class="setting-label">知识库</label>
        <select v-model="selectedKbId" class="setting-select">
          <option v-for="kb in kbStore.knowledgeBases" :key="kb.id" :value="kb.id">
            {{ kb.name }}
          </option>
        </select>
      </div>

      <div class="setting-row">
        <label class="setting-label">分块策略</label>
        <select v-model="chunkStrategy" class="setting-select">
          <option value="fixed">固定分块</option>
          <option value="structural">结构分块</option>
          <option value="semantic">语义分块</option>
          <option value="hybrid">混合分块</option>
          <option value="intelligent">智能分块</option>
        </select>
      </div>

      <!-- Fixed chunk params -->
      <div v-if="chunkStrategy === 'fixed'" class="params-section">
        <div class="setting-row">
          <label class="setting-label">块大小</label>
          <input type="number" v-model="chunkSize" class="setting-input" min="100" max="2000" />
          <span class="setting-unit">字符</span>
        </div>
        <div class="setting-row">
          <label class="setting-label">块重叠</label>
          <input type="number" v-model="chunkOverlap" class="setting-input" min="0" max="500" />
          <span class="setting-unit">字符</span>
        </div>
      </div>

      <!-- Structural chunk params -->
      <div v-if="chunkStrategy === 'structural'" class="params-section">
        <div class="setting-row">
          <label class="setting-label">最小段落长度</label>
          <input type="number" v-model="minParagraphLength" class="setting-input" min="10" max="500" />
          <span class="setting-unit">字符</span>
        </div>
        <div class="setting-row">
          <label class="setting-label">最大段落长度</label>
          <input type="number" v-model="maxParagraphLength" class="setting-input" min="100" max="10000" />
          <span class="setting-unit">字符</span>
        </div>
      </div>

      <!-- Semantic chunk params -->
      <div v-if="chunkStrategy === 'semantic'" class="params-section">
        <div class="setting-row">
          <label class="setting-label">最大Token数</label>
          <input type="number" v-model="maxTokensPerChunk" class="setting-input" min="100" max="2000" />
          <span class="setting-unit">Token</span>
        </div>
        <div class="setting-row">
          <label class="setting-label">相似度阈值</label>
          <input type="number" v-model="similarityThreshold" class="setting-input" min="0.1" max="1.0" step="0.1" />
        </div>
      </div>

      <!-- Hybrid chunk params -->
      <div v-if="chunkStrategy === 'hybrid'" class="params-section">
        <div class="setting-row">
          <label class="setting-label">最大Token数</label>
          <input type="number" v-model="maxTokensPerChunk" class="setting-input" min="100" max="2000" />
          <span class="setting-unit">Token</span>
        </div>
        <div class="setting-row">
          <label class="setting-label">最小段落长度</label>
          <input type="number" v-model="minParagraphLength" class="setting-input" min="10" max="500" />
          <span class="setting-unit">字符</span>
        </div>
      </div>

      <!-- Intelligent chunk params -->
      <div v-if="chunkStrategy === 'intelligent'" class="params-section">
        <div class="setting-row">
          <span class="setting-hint">智能分块：根据文档类型自动选择最优策略</span>
        </div>
      </div>
    </div>

    <div class="drop-zone"
         :class="{ dragover: isDragover }"
         @click="triggerFileInput"
         @dragover.prevent="isDragover = true"
         @dragleave.prevent="isDragover = false"
         @drop.prevent="handleDrop">
      <div class="drop-zone-icon">📁</div>
      <div class="drop-zone-text">拖拽文件到此处或点击选择</div>
      <div class="drop-zone-hint">支持 PDF, TXT, DOC, DOCX 等格式</div>
      <input type="file" class="file-input"
             ref="fileInputRef"
             @change="handleFileSelect"
             multiple accept=".pdf,.txt,.doc,.docx">
    </div>

    <div class="file-list" v-if="files.length > 0">
      <div v-for="fileItem in files" :key="fileItem.id" class="file-item">
        <div class="file-info">
          <span class="file-icon">📄</span>
          <div class="file-details">
            <div class="file-name">{{ fileItem.name }}</div>
            <div class="file-size">{{ formatFileSize(fileItem.size) }}</div>
          </div>
        </div>
        <div class="file-actions">
          <span class="file-status" :class="'status-' + fileItem.status">
            {{ statusText[fileItem.status] }}
          </span>
          <button v-if="fileItem.status !== 'uploading'" class="remove-btn" @click="removeFile(fileItem.id)">×</button>
        </div>
      </div>
    </div>

    <button class="upload-btn"
            @click="uploadFiles"
            :disabled="files.length === 0 || uploading || !hasPendingFiles || !selectedKbId">
      <span v-if="uploading" class="loading-spinner btn-spinner"></span>
      <span v-else>上传到知识库</span>
    </button>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useDocumentStore } from '../stores/document'
import { useKnowledgeBaseStore } from '../stores/knowledgeBase'
import { storeToRefs } from 'pinia'

const documentStore = useDocumentStore()
const kbStore = useKnowledgeBaseStore()
const { files, uploading } = storeToRefs(documentStore)

const selectedKbId = ref('')
const kbLoaded = ref(false)

onMounted(async () => {
  await kbStore.fetchKBs()
  kbLoaded.value = true
  if (kbStore.knowledgeBases.length > 0) {
    selectedKbId.value = kbStore.knowledgeBases[0].id
    applyKBChunkStrategy(kbStore.knowledgeBases[0])
  }
})

// Watch for selected KB changes and apply its default chunk strategy
watch(selectedKbId, (newId) => {
  const kb = kbStore.knowledgeBases.find(k => k.id === newId)
  if (kb) {
    applyKBChunkStrategy(kb)
  }
})

const applyKBChunkStrategy = (kb) => {
  chunkStrategy.value = kb.chunkStrategy || 'intelligent'
  chunkSize.value = kb.chunkSize || 512
  chunkOverlap.value = kb.chunkOverlap || 50
  minParagraphLength.value = kb.minParagraphLength || 50
  maxParagraphLength.value = kb.maxParagraphLength || 2000
  maxTokensPerChunk.value = kb.maxTokensPerChunk || 512
  similarityThreshold.value = kb.similarityThreshold || 0.7
}

const isDragover = ref(false)
const fileInputRef = ref(null)

// Chunk strategy settings
const chunkStrategy = ref('intelligent')
const chunkSize = ref(512)
const chunkOverlap = ref(50)
const minParagraphLength = ref(50)
const maxParagraphLength = ref(2000)
const maxTokensPerChunk = ref(512)
const similarityThreshold = ref(0.7)

const statusText = {
  pending: '待上传',
  uploading: '上传中',
  success: '已上传',
  error: '上传失败'
}

const hasPendingFiles = computed(() => files.value.some(f => f.status === 'pending'))

const triggerFileInput = () => fileInputRef.value.click()

const handleFileSelect = (e) => {
  addFiles(Array.from(e.target.files))
  e.target.value = ''
}

const handleDrop = (e) => {
  isDragover.value = false
  addFiles(Array.from(e.dataTransfer.files))
}

const addFiles = (newFiles) => {
  documentStore.addFiles(newFiles)
}

const removeFile = (id) => {
  documentStore.removeFile(id)
}

const uploadFiles = () => {
  documentStore.uploadFiles({
    kbId: selectedKbId.value || 'default',
    chunkStrategy: chunkStrategy.value,
    chunkSize: chunkSize.value,
    chunkOverlap: chunkOverlap.value,
    minParagraphLength: minParagraphLength.value,
    maxParagraphLength: maxParagraphLength.value,
    maxTokensPerChunk: maxTokensPerChunk.value,
    similarityThreshold: similarityThreshold.value
  })
}

const formatFileSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<style scoped>
.chunk-settings {
  background: #f5f5f5;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.setting-row {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.setting-row:last-child {
  margin-bottom: 0;
}

.setting-label {
  width: 120px;
  font-weight: 500;
  color: #333;
}

.setting-select,
.setting-input {
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}

.setting-select {
  width: 150px;
}

.setting-input {
  width: 100px;
}

.setting-unit {
  margin-left: 8px;
  color: #666;
  font-size: 14px;
}

.params-section {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #ddd;
}

.setting-hint {
  color: #666;
  font-size: 14px;
  font-style: italic;
}
</style>
