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
        <button class="admin-nav-item" :class="{ active: activeTab === 'knowledgeBase' }" @click="activeTab = 'knowledgeBase'">
          🗃️ 知识库管理
        </button>
        <button class="admin-nav-item" :class="{ active: activeTab === 'system' }" @click="activeTab = 'system'">
          🔧 系统配置
        </button>
      </nav>
      <div class="admin-footer">
        <button class="back-btn" @click="goBack">← 返回对话</button>
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

        <!-- 上传区域 -->
        <div class="upload-area">
          <span class="upload-label">上传到:</span>
          <select v-model="selectedUploadKbId" class="search-input" style="max-width: 200px;">
            <option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
          </select>
          <label class="upload-btn primary" v-if="knowledgeBases.length > 0">
            <span>📤</span>
            <span>批量上传文档</span>
            <input type="file" @change="handleFileUpload" accept=".pdf,.txt,.doc,.docx" multiple hidden>
          </label>
          <span class="upload-hint" v-else>请先创建知识库</span>
          <span class="upload-hint">支持 PDF、TXT、DOC、DOCX，可选择多个文件</span>
          <span class="upload-status" :class="uploadStatusClass" v-if="uploadStatus">{{ uploadStatus }}</span>
        </div>

        <!-- 上传进度显示 -->
        <div v-if="uploadingFiles.length > 0" class="upload-progress">
          <div v-for="(file, idx) in uploadingFiles" :key="idx" class="upload-file-item">
            <span class="file-name">{{ file.name }}</span>
            <span class="file-status" :class="file.status">{{ file.status === 'uploading' ? '上传中...' : file.status }}</span>
          </div>
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
                <td>{{ getKBName(doc.kbId) }}</td>
                <td>{{ doc.chunkStrategy || 'default' }}</td>
                <td>
                  <span class="status-badge" :class="'status-' + doc.status">{{ formatStatus(doc.status) }}</span>
                </td>
                <td>{{ formatDate(doc.createdAt) }}</td>
                <td>
                  <button class="action-btn delete" @click="removeDocument(doc.id)">删除</button>
                </td>
              </tr>
              <tr v-if="filteredDocuments.length === 0">
                <td colspan="6" class="empty-cell">该知识库暂无文档，请上传文档</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 知识库管理 -->
      <div v-if="activeTab === 'knowledgeBase'" class="admin-section">
        <h2>知识库管理</h2>
        <div class="section-header">
          <button class="create-btn" @click="showCreateKB = true">新建知识库</button>
        </div>

        <div class="kb-list" v-if="knowledgeBases.length > 0">
          <table class="doc-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>描述</th>
                <th>分块策略</th>
                <th>文档数</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="kb in knowledgeBases" :key="kb.id">
                <td>{{ kb.name }}</td>
                <td>{{ kb.description || '-' }}</td>
                <td>{{ kb.chunkStrategy || 'intelligent' }}</td>
                <td>{{ kb.documentCount }}</td>
                <td>{{ formatDate(kb.createdAt) }}</td>
                <td>
                  <button class="action-btn edit" @click="editKB(kb)">编辑</button>
                  <button class="action-btn delete" @click="removeKB(kb.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty-state">
          <p>暂无知识库，点击"新建知识库"创建</p>
        </div>

        <!-- Create KB Modal -->
        <div class="modal" v-if="showCreateKB" @click.self="showCreateKB = false">
          <div class="modal-content">
            <h3>新建知识库</h3>
            <div class="form-group">
              <label>名称</label>
              <input v-model="newKB.name" type="text" placeholder="知识库名称" />
            </div>
            <div class="form-group">
              <label>描述</label>
              <textarea v-model="newKB.description" placeholder="知识库描述（可选）"></textarea>
            </div>
            <div class="form-group">
              <label>默认分块策略</label>
              <select v-model="newKB.chunkStrategy">
                <option value="fixed">固定分块</option>
                <option value="structural">结构分块</option>
                <option value="semantic">语义分块</option>
                <option value="hybrid">混合分块</option>
                <option value="intelligent">智能分块</option>
              </select>
            </div>
            <!-- Fixed chunk params -->
            <div v-if="newKB.chunkStrategy === 'fixed'" class="form-group">
              <label>块大小</label>
              <input v-model.number="newKB.chunkSize" type="number" min="100" max="5000" />
            </div>
            <div v-if="newKB.chunkStrategy === 'fixed'" class="form-group">
              <label>块重叠</label>
              <input v-model.number="newKB.chunkOverlap" type="number" min="0" max="500" />
            </div>
            <!-- Structural chunk params -->
            <div v-if="newKB.chunkStrategy === 'structural'" class="form-group">
              <label>最小段落长度</label>
              <input v-model.number="newKB.minParagraphLength" type="number" min="10" max="1000" />
            </div>
            <div v-if="newKB.chunkStrategy === 'structural'" class="form-group">
              <label>最大段落长度</label>
              <input v-model.number="newKB.maxParagraphLength" type="number" min="100" max="10000" />
            </div>
            <!-- Semantic chunk params -->
            <div v-if="newKB.chunkStrategy === 'semantic'" class="form-group">
              <label>最大Token数</label>
              <input v-model.number="newKB.maxTokensPerChunk" type="number" min="50" max="2000" />
            </div>
            <div v-if="newKB.chunkStrategy === 'semantic'" class="form-group">
              <label>相似度阈值</label>
              <input v-model.number="newKB.similarityThreshold" type="number" min="0.1" max="1.0" step="0.1" />
            </div>
            <div class="form-actions">
              <button @click="showCreateKB = false">取消</button>
              <button class="save-btn" @click="createKB">创建</button>
            </div>
          </div>
        </div>

        <!-- Edit KB Modal -->
        <div class="modal" v-if="showEditKB" @click.self="showEditKB = false">
          <div class="modal-content">
            <h3>编辑知识库</h3>
            <div class="form-group">
              <label>名称</label>
              <input v-model="editKBData.name" type="text" placeholder="知识库名称" />
            </div>
            <div class="form-group">
              <label>描述</label>
              <textarea v-model="editKBData.description" placeholder="知识库描述（可选）"></textarea>
            </div>
            <div class="form-group">
              <label>分块策略</label>
              <select v-model="editKBData.chunkStrategy">
                <option value="fixed">固定分块</option>
                <option value="structural">结构分块</option>
                <option value="semantic">语义分块</option>
                <option value="hybrid">混合分块</option>
                <option value="intelligent">智能分块</option>
              </select>
            </div>
            <!-- Fixed chunk params -->
            <div v-if="editKBData.chunkStrategy === 'fixed'" class="form-group">
              <label>块大小</label>
              <input v-model.number="editKBData.chunkSize" type="number" min="100" max="5000" />
            </div>
            <div v-if="editKBData.chunkStrategy === 'fixed'" class="form-group">
              <label>块重叠</label>
              <input v-model.number="editKBData.chunkOverlap" type="number" min="0" max="500" />
            </div>
            <!-- Structural chunk params -->
            <div v-if="editKBData.chunkStrategy === 'structural'" class="form-group">
              <label>最小段落长度</label>
              <input v-model.number="editKBData.minParagraphLength" type="number" min="10" max="1000" />
            </div>
            <div v-if="editKBData.chunkStrategy === 'structural'" class="form-group">
              <label>最大段落长度</label>
              <input v-model.number="editKBData.maxParagraphLength" type="number" min="100" max="10000" />
            </div>
            <!-- Semantic chunk params -->
            <div v-if="editKBData.chunkStrategy === 'semantic'" class="form-group">
              <label>最大Token数</label>
              <input v-model.number="editKBData.maxTokensPerChunk" type="number" min="50" max="2000" />
            </div>
            <div v-if="editKBData.chunkStrategy === 'semantic'" class="form-group">
              <label>相似度阈值</label>
              <input v-model.number="editKBData.similarityThreshold" type="number" min="0.1" max="1.0" step="0.1" />
            </div>
            <div class="form-actions">
              <button @click="showEditKB = false">取消</button>
              <button class="save-btn" @click="saveKB">保存</button>
            </div>
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { uploadDocument, getDocuments, deleteDocument, getKnowledgeBases, createKnowledgeBase, deleteKnowledgeBase } from '../api'

const router = useRouter()

const goBack = () => {
  router.push('/')
}

const activeTab = ref('documents')
const searchQuery = ref('')
const documents = ref([])
const uploadStatus = ref('')
const uploadStatusClass = ref('')
const uploadingFiles = ref([])
const knowledgeBases = ref([])
const showCreateKB = ref(false)
const showEditKB = ref(false)
const selectedUploadKbId = ref('')
const newKB = ref({
  name: '',
  description: '',
  chunkStrategy: 'intelligent',
  chunkSize: 512,
  chunkOverlap: 50,
  minParagraphLength: 50,
  maxParagraphLength: 2000,
  maxTokensPerChunk: 512,
  similarityThreshold: 0.7
})
const editKBData = ref({
  id: '',
  name: '',
  description: '',
  chunkStrategy: 'intelligent',
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
  let docs = documents.value
  if (selectedUploadKbId.value) {
    docs = docs.filter(d => d.kbId === selectedUploadKbId.value)
  }
  if (!searchQuery.value) return docs
  const query = searchQuery.value.toLowerCase()
  return docs.filter(d =>
    d.fileName.toLowerCase().includes(query) ||
    (d.kbId && d.kbId.toLowerCase().includes(query))
  )
})

const getKBName = (kbId) => {
  const kb = knowledgeBases.value.find(k => k.id === kbId)
  return kb ? kb.name : kbId || '-'
}

const loadDocuments = async () => {
  try {
    const response = await getDocuments()
    documents.value = response.data
    systemStats.value.totalDocuments = documents.value.length
  } catch (err) {
    console.error('Failed to load documents:', err)
    documents.value = []
    systemStats.value.totalDocuments = 0
  }
}

const loadKBs = async () => {
  try {
    const response = await getKnowledgeBases()
    knowledgeBases.value = response.data
    // Always set to first KB if available
    if (response.data.length > 0) {
      selectedUploadKbId.value = response.data[0].id
    }
  } catch (err) {
    console.error('Failed to load knowledge bases:', err)
    knowledgeBases.value = []
  }
}

const createKB = async () => {
  if (!newKB.value.name.trim()) return
  try {
    await createKnowledgeBase({
      name: newKB.value.name,
      description: newKB.value.description,
      chunkStrategy: newKB.value.chunkStrategy,
      chunkSize: newKB.value.chunkSize,
      chunkOverlap: newKB.value.chunkOverlap,
      minParagraphLength: newKB.value.minParagraphLength,
      maxParagraphLength: newKB.value.maxParagraphLength,
      maxTokensPerChunk: newKB.value.maxTokensPerChunk,
      similarityThreshold: newKB.value.similarityThreshold
    })
    newKB.value = {
      name: '',
      description: '',
      chunkStrategy: 'intelligent',
      chunkSize: 512,
      chunkOverlap: 50,
      minParagraphLength: 50,
      maxParagraphLength: 2000,
      maxTokensPerChunk: 512,
      similarityThreshold: 0.7
    }
    showCreateKB.value = false
    loadKBs()
  } catch (err) {
    alert('创建失败: ' + err.message)
  }
}

const removeKB = async (id) => {
  if (!confirm('确定要删除这个知识库吗？')) return
  try {
    await deleteKnowledgeBase(id)
    loadKBs()
  } catch (err) {
    alert('删除失败: ' + err.message)
  }
}

const editKB = (kb) => {
  editKBData.value = {
    id: kb.id,
    name: kb.name,
    description: kb.description || '',
    chunkStrategy: kb.chunkStrategy || 'intelligent',
    chunkSize: kb.chunkSize || 512,
    chunkOverlap: kb.chunkOverlap || 50,
    minParagraphLength: kb.minParagraphLength || 50,
    maxParagraphLength: kb.maxParagraphLength || 2000,
    maxTokensPerChunk: kb.maxTokensPerChunk || 512,
    similarityThreshold: kb.similarityThreshold || 0.7
  }
  showEditKB.value = true
}

const saveKB = async () => {
  if (!editKBData.value.name.trim()) return
  try {
    await updateKnowledgeBase(editKBData.value.id, {
      name: editKBData.value.name,
      description: editKBData.value.description,
      chunkStrategy: editKBData.value.chunkStrategy,
      chunkSize: editKBData.value.chunkSize,
      chunkOverlap: editKBData.value.chunkOverlap,
      minParagraphLength: editKBData.value.minParagraphLength,
      maxParagraphLength: editKBData.value.maxParagraphLength,
      maxTokensPerChunk: editKBData.value.maxTokensPerChunk,
      similarityThreshold: editKBData.value.similarityThreshold
    })
    showEditKB.value = false
    loadKBs()
  } catch (err) {
    alert('保存失败: ' + err.message)
  }
}

const removeDocument = async (id) => {
  if (!confirm('确定要删除这个文档吗？')) return
  try {
    await deleteDocument(id)
    documents.value = documents.value.filter(d => d.id !== id)
    systemStats.value.totalDocuments = documents.value.length
  } catch (err) {
    alert('删除失败: ' + err.message)
  }
}

const handleFileUpload = async (e) => {
  const files = Array.from(e.target.files)
  if (files.length === 0) return

  // 获取选中知识库的分块配置
  const selectedKB = knowledgeBases.value.find(k => k.id === selectedUploadKbId.value)
  const chunkStrategy = selectedKB?.chunkStrategy || 'intelligent'
  const chunkSize = selectedKB?.chunkSize
  const chunkOverlap = selectedKB?.chunkOverlap
  const minParagraphLength = selectedKB?.minParagraphLength
  const maxParagraphLength = selectedKB?.maxParagraphLength
  const maxTokensPerChunk = selectedKB?.maxTokensPerChunk
  const similarityThreshold = selectedKB?.similarityThreshold

  // 显示上传状态
  uploadingFiles.value = files.map(f => ({ name: f.name, status: '等待上传' }))
  uploadStatus.value = `准备上传 ${files.length} 个文件...`
  uploadStatusClass.value = 'info'

  let successCount = 0
  let failCount = 0

  // 逐个上传文件，避免内存溢出
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    uploadingFiles.value[i].status = '上传中...'

    const formData = new FormData()
    formData.append('file', file)
    formData.append('kbId', selectedUploadKbId.value || 'default')
    formData.append('chunkStrategy', chunkStrategy)
    if (chunkSize) formData.append('chunkSize', chunkSize)
    if (chunkOverlap) formData.append('chunkOverlap', chunkOverlap)
    if (minParagraphLength) formData.append('minParagraphLength', minParagraphLength)
    if (maxParagraphLength) formData.append('maxParagraphLength', maxParagraphLength)
    if (maxTokensPerChunk) formData.append('maxTokensPerChunk', maxTokensPerChunk)
    if (similarityThreshold) formData.append('similarityThreshold', similarityThreshold)

    uploadStatus.value = `正在上传 ${i + 1}/${files.length} 个文件...`

    try {
      await uploadDocument(formData)  // 单文件上传
      uploadingFiles.value[i].status = '已完成'
      successCount++
    } catch (err) {
      uploadingFiles.value[i].status = `失败: ${err.message}`
      failCount++
    }
  }

  uploadStatus.value = `上传完成: 成功 ${successCount} 个, 失败 ${failCount} 个`
  uploadStatusClass.value = failCount > 0 ? 'error' : 'success'

  loadDocuments()

  setTimeout(() => {
    uploadingFiles.value = []
    uploadStatus.value = ''
  }, 3000)

  e.target.value = ''
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleDateString('zh-CN') + ' ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const formatStatus = (status) => {
  const statusMap = {
    'PENDING': '等待中',
    'UPLOADED': '已上传',
    'PARSING': '解析中',
    'PARSED': '已解析',
    'CHUNKING': '分块中',
    'CHUNKED': '已分块',
    'INDEXING': '索引中',
    'INDEXED': '已完成',
    'FAILED': '失败'
  }
  return statusMap[status] || status
}

let refreshTimer = null

onMounted(() => {
  loadDocuments()
  loadKBs()
  // 定时刷新文档列表，每 3 秒更新一次状态
  refreshTimer = setInterval(loadDocuments, 3000)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
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

.upload-area {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.upload-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: #4a90e2;
  color: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.upload-btn.primary {
  background: #4a90e2;
}

.upload-btn:hover {
  background: #3a7bc8;
}

.upload-btn.disabled {
  background: #ccc;
  cursor: not-allowed;
}

.upload-hint {
  color: #999;
  font-size: 13px;
}

.upload-label {
  font-weight: 500;
  color: #333;
}

.upload-status {
  font-size: 14px;
  padding: 4px 12px;
  border-radius: 4px;
}

.upload-status.success {
  color: #2e7d32;
  background: #e8f5e9;
}

.upload-status.error {
  color: #c62828;
  background: #ffebee;
}

.upload-status.info {
  color: #1976d2;
  background: #e3f2fd;
}

.upload-progress {
  background: #f5f5f5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 20px;
}

.upload-file-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #eee;
}

.upload-file-item:last-child {
  border-bottom: none;
}

.file-name {
  color: #333;
}

.file-status {
  font-size: 13px;
}

.file-status.uploading {
  color: #1976d2;
}

.file-status.已完成 {
  color: #2e7d32;
}

.file-status.失败, .file-status.失败\: {
  color: #c62828;
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
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid #eee;
}

.doc-table td:first-child {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.status-PENDING { background: #e3f2fd; color: #1976d2; }
.status-UPLOADED { background: #e3f2fd; color: #1976d2; }
.status-PARSING { background: #fff3e0; color: #f57c00; }
.status-PARSED { background: #e8f5e9; color: #2e7d32; }
.status-CHUNKING { background: #fff3e0; color: #f57c00; }
.status-CHUNKED { background: #e8f5e9; color: #2e7d32; }
.status-INDEXING { background: #fff3e0; color: #f57c00; }
.status-INDEXED { background: #c8e6c9; color: #1b5e20; }
.status-FAILED { background: #ffebee; color: #c62828; }
.status-PROCESSING { background: #fff3e0; color: #f57c00; }

.action-btn.delete {
  background: #ff4444;
  color: #fff;
  padding: 6px 12px;
  font-size: 12px;
}

.action-btn.edit {
  background: #4a90e2;
  color: #fff;
  padding: 6px 12px;
  font-size: 12px;
  margin-right: 8px;
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

.create-btn {
  padding: 10px 20px;
  background: #1890ff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.create-btn:hover {
  background: #40a9ff;
}

.kb-list {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #999;
  background: #fff;
  border-radius: 8px;
}

.modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  padding: 24px;
  border-radius: 8px;
  width: 400px;
  max-width: 90%;
}

.modal-content h3 {
  margin: 0 0 20px 0;
  font-size: 18px;
}

.modal-content .form-group {
  margin-bottom: 16px;
}

.modal-content .form-group label {
  display: block;
  margin-bottom: 4px;
  font-size: 14px;
  color: #333;
}

.modal-content .form-group input,
.modal-content .form-group textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
}

.modal-content .form-group textarea {
  min-height: 80px;
  resize: vertical;
}

.modal-content .form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}

.modal-content .form-actions button {
  padding: 8px 16px;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: white;
  cursor: pointer;
  font-size: 14px;
}

.modal-content .form-actions .save-btn {
  background: #1890ff;
  color: white;
  border-color: #1890ff;
}

.modal-content .form-actions .save-btn:hover {
  background: #40a9ff;
}
</style>
