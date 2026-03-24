<template>
  <div class="admin-layout">
    <!-- 左侧导航 -->
    <aside class="admin-sidebar">
      <div class="admin-logo">
        <span>⚙️</span>
        <span>后台管理</span>
      </div>
      <nav class="admin-nav">
        <button class="admin-nav-item" :class="{ active: activeTab === 'documents' }" @click="activeTab = 'documents'">
          📄 文档管理
        </button>
        <button class="admin-nav-item" :class="{ active: activeTab === 'chunking' }" @click="activeTab = 'chunking'">
          ✂️ 分块配置
        </button>
        <button class="admin-nav-item" :class="{ active: activeTab === 'system' }" @click="activeTab = 'system'">
          🔧 系统配置
        </button>
      </nav>
      <div class="admin-footer">
        <button class="back-btn" @click="$emit('back')">← 返回对话</button>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="admin-main">
      <!-- 文档管理 -->
      <div v-if="activeTab === 'documents'" class="admin-section">
        <h2>文档管理</h2>
        <div class="section-header">
          <input type="text" v-model="searchQuery" placeholder="搜索文档..." class="search-input">
          <button class="refresh-btn" @click="loadDocuments">🔄 刷新</button>
        </div>

        <div class="doc-table-wrapper">
          <table class="doc-table">
            <thead>
              <tr>
                <th>文档名称</th>
                <th>知识库</th>
                <th>分块策略</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="doc in filteredDocuments" :key="doc.id">
                <td>{{ doc.fileName }}</td>
                <td>{{ doc.kbId }}</td>
                <td>{{ doc.chunkStrategy || 'default' }}</td>
                <td><span class="status-badge" :class="'status-' + doc.status">{{ doc.status }}</span></td>
                <td>{{ formatDate(doc.createdAt) }}</td>
                <td>
                  <button class="action-btn delete" @click="deleteDocument(doc.id)">删除</button>
                </td>
              </tr>
              <tr v-if="documents.length === 0">
                <td colspan="6" class="empty-cell">暂无文档</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 分块配置 -->
      <div v-if="activeTab === 'chunking'" class="admin-section">
        <h2>分块策略配置</h2>

        <div class="config-form">
          <div class="form-group">
            <label>默认分块策略</label>
            <select v-model="chunkConfig.strategy">
              <option value="fixed">固定分块</option>
              <option value="structural">结构分块</option>
              <option value="semantic">语义分块</option>
            </select>
          </div>

          <!-- 固定分块参数 -->
          <div v-if="chunkConfig.strategy === 'fixed'" class="params-panel">
            <h3>固定分块参数</h3>
            <div class="form-group">
              <label>块大小 (字符数)</label>
              <input type="number" v-model="chunkConfig.chunkSize" min="100" max="5000">
              <span class="hint">每个文本块的字符数，建议 300-1000</span>
            </div>
            <div class="form-group">
              <label>块重叠 (字符数)</label>
              <input type="number" v-model="chunkConfig.chunkOverlap" min="0" max="500">
              <span class="hint">相邻块之间的重叠字符数，用于保持上下文</span>
            </div>
          </div>

          <!-- 结构分块参数 -->
          <div v-if="chunkConfig.strategy === 'structural'" class="params-panel">
            <h3>结构分块参数</h3>
            <div class="form-group">
              <label>最小段落长度</label>
              <input type="number" v-model="chunkConfig.minParagraphLength" min="10" max="1000">
              <span class="hint">最小段落字符数，小于此值会合并</span>
            </div>
            <div class="form-group">
              <label>最大段落长度</label>
              <input type="number" v-model="chunkConfig.maxParagraphLength" min="100" max="10000">
              <span class="hint">最大段落字符数，超过会拆分</span>
            </div>
          </div>

          <!-- 语义分块参数 -->
          <div v-if="chunkConfig.strategy === 'semantic'" class="params-panel">
            <h3>语义分块参数</h3>
            <div class="form-group">
              <label>最大 Token 数</label>
              <input type="number" v-model="chunkConfig.maxTokensPerChunk" min="50" max="2000">
              <span class="hint">每个块的 最大Token数</span>
            </div>
            <div class="form-group">
              <label>相似度阈值</label>
              <input type="number" v-model="chunkConfig.similarityThreshold" min="0.1" max="1.0" step="0.1">
              <span class="hint">语义相似度阈值，低于此值会断开</span>
            </div>
          </div>

          <div class="form-actions">
            <button class="save-btn" @click="saveChunkConfig">保存配置</button>
            <button class="reset-btn" @click="resetChunkConfig">重置</button>
          </div>
        </div>
      </div>

      <!-- 系统配置 -->
      <div v-if="activeTab === 'system'" class="admin-section">
        <h2>系统配置</h2>

        <div class="config-form">
          <div class="form-group">
            <label>Embedding 模型</label>
            <input type="text" v-model="systemConfig.embeddingModel" disabled>
            <span class="hint">当前使用: BAAI/bge-m3</span>
          </div>
          <div class="form-group">
            <label>向量维度</label>
            <input type="number" v-model="systemConfig.embeddingDimension" disabled>
            <span class="hint">当前: 1024</span>
          </div>
          <div class="form-group">
            <label>LLM 提供商</label>
            <select v-model="systemConfig.llmProvider" disabled>
              <option value="openai">OpenAI</option>
              <option value="siliconflow">SiliconFlow</option>
            </select>
          </div>
          <div class="form-group">
            <label>LLM 模型</label>
            <input type="text" v-model="systemConfig.llmModel" disabled>
            <span class="hint">当前: gpt-4o-mini</span>
          </div>

          <div class="system-stats">
            <h3>系统状态</h3>
            <div class="stat-grid">
              <div class="stat-item">
                <span class="stat-value">{{ systemStats.totalDocuments }}</span>
                <span class="stat-label">文档总数</span>
              </div>
              <div class="stat-item">
                <span class="stat-value">{{ systemStats.totalChunks }}</span>
                <span class="stat-label">分块总数</span>
              </div>
              <div class="stat-item">
                <span class="stat-value">{{ systemStats.milvusStatus }}</span>
                <span class="stat-label">Milvus 状态</span>
              </div>
              <div class="stat-item">
                <span class="stat-value">{{ systemStats.esStatus }}</span>
                <span class="stat-label">ES 状态</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'

defineEmits(['back'])

const activeTab = ref('documents')
const searchQuery = ref('')
const documents = ref([])
const chunkConfig = ref({
  strategy: 'fixed',
  chunkSize: 512,
  chunkOverlap: 50,
  minParagraphLength: 50,
  maxParagraphLength: 2000,
  maxTokensPerChunk: 512,
  similarityThreshold: 0.7
})
const systemConfig = ref({
  embeddingModel: 'BAAI/bge-m3',
  embeddingDimension: 1024,
  llmProvider: 'siliconflow',
  llmModel: 'gpt-4o-mini'
})
const systemStats = ref({
  totalDocuments: 0,
  totalChunks: 0,
  milvusStatus: '运行中',
  esStatus: '运行中'
})

const filteredDocuments = computed(() => {
  if (!searchQuery.value) return documents.value
  const query = searchQuery.value.toLowerCase()
  return documents.value.filter(d =>
    d.fileName.toLowerCase().includes(query) ||
    d.kbId.toLowerCase().includes(query)
  )
})

const loadDocuments = () => {
  // TODO: 从后端 API 加载文档列表
  // 模拟数据
  documents.value = [
    { id: '1', fileName: '示例文档.pdf', kbId: 'default', chunkStrategy: 'fixed', status: 'INDEXED', createdAt: new Date().toISOString() }
  ]
  systemStats.value.totalDocuments = documents.value.length
}

const deleteDocument = async (id) => {
  if (!confirm('确定要删除这个文档吗？')) return
  // TODO: 调用后端 API 删除文档
  documents.value = documents.value.filter(d => d.id !== id)
  systemStats.value.totalDocuments = documents.value.length
}

const saveChunkConfig = () => {
  localStorage.setItem('chunkConfig', JSON.stringify(chunkConfig.value))
  alert('配置已保存')
}

const resetChunkConfig = () => {
  chunkConfig.value = {
    strategy: 'fixed',
    chunkSize: 512,
    chunkOverlap: 50,
    minParagraphLength: 50,
    maxParagraphLength: 2000,
    maxTokensPerChunk: 512,
    similarityThreshold: 0.7
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN') + ' ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

onMounted(() => {
  loadDocuments()
  // 从 localStorage 加载保存的配置
  const saved = localStorage.getItem('chunkConfig')
  if (saved) {
    chunkConfig.value = JSON.parse(saved)
  }
})
</script>

<style scoped>
.admin-layout {
  display: flex;
  height: 100vh;
  background: #f5f5f5;
}

.admin-sidebar {
  width: 240px;
  background: #1a1a1a;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.admin-logo {
  padding: 24px 20px;
  font-size: 18px;
  font-weight: bold;
  border-bottom: 1px solid #333;
  display: flex;
  align-items: center;
  gap: 10px;
}

.admin-nav {
  flex: 1;
  padding: 16px 12px;
}

.admin-nav-item {
  display: block;
  width: 100%;
  padding: 14px 16px;
  background: none;
  border: none;
  color: #a0a0a0;
  cursor: pointer;
  border-radius: 8px;
  text-align: left;
  margin-bottom: 4px;
  transition: all 0.2s;
}

.admin-nav-item:hover, .admin-nav-item.active {
  background: #333;
  color: #fff;
}

.admin-footer {
  padding: 16px 12px;
  border-top: 1px solid #333;
}

.back-btn {
  width: 100%;
  padding: 12px;
  background: #333;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.admin-main {
  flex: 1;
  overflow-y: auto;
  padding: 32px;
}

.admin-section h2 {
  font-size: 24px;
  margin-bottom: 24px;
  color: #333;
}

.section-header {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.search-input {
  flex: 1;
  padding: 10px 16px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}

.refresh-btn, .action-btn {
  padding: 10px 16px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.refresh-btn {
  background: #e0e0e0;
  color: #333;
}

.doc-table-wrapper {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
}

.doc-table th, .doc-table td {
  padding: 14px 16px;
  text-align: left;
  border-bottom: 1px solid #eee;
}

.doc-table th {
  background: #f5f5f5;
  font-weight: 500;
  color: #666;
}

.doc-table tbody tr:hover {
  background: #fafafa;
}

.status-badge {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
}

.status-INDEXED { background: #e8f5e9; color: #2e7d32; }
.status-PROCESSING { background: #fff3e0; color: #f57c00; }
.status-PENDING { background: #e3f2fd; color: #1976d2; }
.status-FAILED { background: #ffebee; color: #c62828; }

.action-btn.delete {
  background: #ff4444;
  color: #fff;
  padding: 6px 12px;
  font-size: 12px;
}

.empty-cell {
  text-align: center;
  color: #999;
  padding: 40px !important;
}

/* 分块配置 */
.config-form {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  font-weight: 500;
  margin-bottom: 8px;
  color: #333;
}

.form-group input, .form-group select {
  width: 100%;
  max-width: 400px;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}

.form-group input:disabled, .form-group select:disabled {
  background: #f5f5f5;
  color: #999;
}

.hint {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}

.params-panel {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #eee;
}

.params-panel h3 {
  font-size: 16px;
  margin-bottom: 16px;
  color: #666;
}

.form-actions {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #eee;
  display: flex;
  gap: 12px;
}

.save-btn {
  padding: 12px 32px;
  background: #4a90e2;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.reset-btn {
  padding: 12px 32px;
  background: #e0e0e0;
  color: #333;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.system-stats {
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid #eee;
}

.system-stats h3 {
  font-size: 16px;
  margin-bottom: 16px;
  color: #666;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-item {
  background: #f5f5f5;
  padding: 20px;
  border-radius: 8px;
  text-align: center;
}

.stat-value {
  display: block;
  font-size: 28px;
  font-weight: bold;
  color: #333;
  margin-bottom: 8px;
}

.stat-label {
  font-size: 13px;
  color: #666;
}
</style>
