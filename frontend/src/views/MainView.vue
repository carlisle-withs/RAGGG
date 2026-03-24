<template>
  <!-- 后台管理界面 - 全屏显示 -->
  <AdminView v-if="currentView === 'admin'" @back="currentView = 'chat'" />

  <!-- 主对话界面 -->
  <div v-else class="main-layout">
      <!-- 左侧菜单 -->
      <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
        <div class="sidebar-header">
          <button class="menu-btn" @click="sidebarCollapsed = !sidebarCollapsed">
            ☰
          </button>
          <span v-if="!sidebarCollapsed" class="logo-text">RAG</span>
        </div>

        <nav class="sidebar-nav">
          <button class="nav-item" :class="{ active: currentView === 'chat' }" @click="currentView = 'chat'">
            <span class="nav-icon">💬</span>
            <span v-if="!sidebarCollapsed">智能问答</span>
          </button>
          <button class="nav-item" :class="{ active: currentView === 'retrieve' }" @click="currentView = 'retrieve'">
            <span class="nav-icon">🔍</span>
            <span v-if="!sidebarCollapsed">内容检索</span>
          </button>
        </nav>

        <div class="sidebar-footer">
          <button class="nav-item" @click="currentView = 'admin'">
            <span class="nav-icon">⚙️</span>
            <span v-if="!sidebarCollapsed">后台管理</span>
          </button>
        </div>
      </aside>

      <!-- 主内容区 -->
      <main class="main-content">
        <!-- 顶部栏 -->
        <header class="top-bar">
          <div class="current-mode">
            {{ currentModeText }}
          </div>
          <button class="new-chat-btn" @click="startNewChat">+ 新对话</button>
        </header>

        <!-- 聊天/检索区域 -->
        <div class="chat-container" ref="chatContainer">
          <!-- 欢迎页 -->
          <div v-if="messages.length === 0 && currentView !== 'retrieve'" class="welcome">
            <div class="welcome-icon">🤖</div>
            <h1>你好，我是 RAG 助手</h1>
            <p>我可以回答你的问题，或者帮你检索文档内容</p>
            <div class="quick-actions">
              <button class="quick-btn" @click="sendQuickMessage('请介绍一下你自己')">请介绍一下你自己</button>
              <button class="quick-btn" @click="sendQuickMessage('有什么我可以帮你的吗')">有什么我可以帮你的吗</button>
            </div>
          </div>

          <!-- 检索结果页 -->
          <div v-if="currentView === 'retrieve' && messages.length === 0" class="welcome">
            <div class="welcome-icon">🔍</div>
            <h1>内容检索</h1>
            <p>输入关键词搜索相关文档内容</p>
          </div>

          <!-- 消息列表 -->
          <div class="messages">
            <div v-for="(msg, index) in messages" :key="index" class="message" :class="msg.role">
              <div class="message-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
              <div class="message-content">
                <div class="message-text" v-html="formatMessage(msg.content)"></div>
                <div v-if="msg.sources && msg.sources.length > 0" class="message-sources">
                  <div class="sources-title">参考文档：</div>
                  <div v-for="source in msg.sources" :key="source.chunkId" class="source-item">
                    <span class="source-content">{{ source.content.substring(0, 100) }}...</span>
                    <span class="source-score">相似度: {{ (source.score * 100).toFixed(1) }}%</span>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="loading" class="message assistant">
              <div class="message-avatar">🤖</div>
              <div class="message-content">
                <div class="typing-indicator">
                  <span></span><span></span><span></span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 检索模式输入区 -->
        <div v-if="currentView === 'retrieve'" class="input-area retrieve-mode">
          <input type="text" v-model="retrieveQuery" class="search-input"
                 placeholder="输入关键词搜索..."
                 @keyup.enter="doRetrieve">
          <button class="send-btn" @click="doRetrieve" :disabled="!retrieveQuery.trim() || loading">
            🔍
          </button>
        </div>

        <!-- 聊天模式输入区 -->
        <div v-if="currentView === 'chat'" class="input-area chat-mode">
          <div class="input-wrapper">
            <textarea v-model="inputMessage" class="message-input"
                      placeholder="输入消息..."
                      @keydown.enter.exact.prevent="sendMessage"
                      @input="autoResize"
                      rows="1"></textarea>
            <button class="send-btn" @click="sendMessage" :disabled="!inputMessage.trim() || loading">
              ➤
            </button>
          </div>
        </div>

        <!-- 底部工具栏 -->
        <div class="bottom-toolbar">
          <label class="upload-btn">
            <span>📎</span>
            <span>上传文档</span>
            <input type="file" @change="handleFileUpload" accept=".pdf,.txt,.doc,.docx" hidden>
          </label>
          <div class="upload-status" v-if="uploadStatus">
            {{ uploadStatus }}
          </div>
        </div>
      </main>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { chat, retrieve, uploadDocument } from '../api'
import { useChatStore } from '../stores/chat'
import { storeToRefs } from 'pinia'
import AdminView from './AdminView.vue'

const chatStore = useChatStore()
const { messages } = storeToRefs(chatStore)

// View state
const currentView = ref('chat')
const sidebarCollapsed = ref(false)
const chatContainer = ref(null)
const loading = ref(false)

// Chat state
const inputMessage = ref('')
const retrieveQuery = ref('')

// Upload state
const uploadStatus = ref('')

const currentModeText = computed(() => {
  switch (currentView.value) {
    case 'chat': return '智能问答'
    case 'retrieve': return '内容检索'
    case 'admin': return '后台管理'
    default: return 'RAG 助手'
  }
})

const startNewChat = () => {
  chatStore.clearMessages()
  retrieveQuery.value = ''
}

const sendQuickMessage = (msg) => {
  currentView.value = 'chat'
  inputMessage.value = msg
  sendMessage()
}

const sendMessage = async () => {
  if (!inputMessage.value.trim() || loading.value) return

  const userMessage = inputMessage.value.trim()
  inputMessage.value = ''
  chatStore.addMessage('user', userMessage)
  loading.value = true

  try {
    const response = await chat({ message: userMessage })
    chatStore.addMessage('assistant', response.message, response.sources)
  } catch (err) {
    chatStore.addMessage('assistant', '抱歉，发生了错误：' + err.message)
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

const doRetrieve = async () => {
  if (!retrieveQuery.value.trim() || loading.value) return

  const query = retrieveQuery.value.trim()
  retrieveQuery.value = ''
  chatStore.addMessage('user', '搜索: ' + query)
  loading.value = true

  try {
    const response = await retrieve({ query })
    chatStore.addMessage('assistant', formatRetrieveResults(response.results))
  } catch (err) {
    chatStore.addMessage('assistant', '抱歉，发生了错误：' + err.message)
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

const formatRetrieveResults = (results) => {
  if (!results || results.length === 0) return '没有找到相关内容'
  return results.map(r =>
    `📄 ${r.content.substring(0, 200)}...\n相似度: ${(r.score * 100).toFixed(1)}%`
  ).join('\n\n')
}

const handleFileUpload = async (e) => {
  const file = e.target.files[0]
  if (!file) return

  uploadStatus.value = '上传中...'

  // 从 localStorage 获取保存的分块配置
  const savedChunkConfig = JSON.parse(localStorage.getItem('chunkConfig') || '{}')

  const formData = new FormData()
  formData.append('file', file)
  formData.append('kbId', 'default')
  formData.append('chunkStrategy', savedChunkConfig.strategy || 'fixed')
  formData.append('chunkSize', savedChunkConfig.chunkSize || 512)
  formData.append('chunkOverlap', savedChunkConfig.chunkOverlap || 50)
  formData.append('minParagraphLength', savedChunkConfig.minParagraphLength || 50)
  formData.append('maxParagraphLength', savedChunkConfig.maxParagraphLength || 2000)
  formData.append('maxTokensPerChunk', savedChunkConfig.maxTokensPerChunk || 512)
  formData.append('similarityThreshold', savedChunkConfig.similarityThreshold || 0.7)

  try {
    await uploadDocument(formData)
    uploadStatus.value = '上传成功'
    setTimeout(() => { uploadStatus.value = '' }, 2000)
  } catch (err) {
    uploadStatus.value = '上传失败: ' + err.message
  }

  e.target.value = ''
}

const formatMessage = (content) => {
  return content
    .replace(/\n/g, '<br>')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.*?)`/g, '<code>$1</code>')
}

const autoResize = (e) => {
  e.target.style.height = 'auto'
  e.target.style.height = Math.min(e.target.scrollHeight, 150) + 'px'
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}
</script>

<style scoped>
.main-layout {
  display: flex;
  height: 100vh;
  background: #f5f5f5;
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  background: #1a1a1a;
  color: #fff;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
}

.sidebar.collapsed {
  width: 60px;
}

.sidebar-header {
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #333;
}

.menu-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 20px;
  cursor: pointer;
}

.logo-text {
  font-size: 18px;
  font-weight: bold;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 8px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 16px;
  background: none;
  border: none;
  color: #a0a0a0;
  cursor: pointer;
  border-radius: 8px;
  margin-bottom: 4px;
  transition: all 0.2s;
}

.nav-item:hover, .nav-item.active {
  background: #333;
  color: #fff;
}

.nav-icon {
  font-size: 18px;
}

.sidebar-footer {
  padding: 12px 8px;
  border-top: 1px solid #333;
}

/* 主内容区 */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.top-bar {
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.current-mode {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.new-chat-btn {
  padding: 8px 16px;
  background: #1a1a1a;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.chat-container {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.welcome {
  text-align: center;
  padding: 60px 20px;
}

.welcome-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.welcome h1 {
  font-size: 28px;
  margin-bottom: 12px;
  color: #333;
}

.welcome p {
  color: #666;
  margin-bottom: 30px;
}

.quick-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.quick-btn {
  padding: 10px 20px;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-btn:hover {
  background: #f0f0f0;
  border-color: #aaa;
}

/* 消息 */
.messages {
  max-width: 800px;
  margin: 0 auto;
}

.message {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}

.message.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #e0e0e0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.message.assistant .message-avatar {
  background: #4a90e2;
  color: #fff;
}

.message-content {
  max-width: 70%;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.message.user .message-text {
  background: #4a90e2;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message.assistant .message-text {
  background: #fff;
  color: #333;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.message-sources {
  margin-top: 12px;
  padding: 12px;
  background: #f8f8f8;
  border-radius: 8px;
  font-size: 13px;
}

.sources-title {
  font-weight: 500;
  margin-bottom: 8px;
  color: #666;
}

.source-item {
  padding: 8px 0;
  border-bottom: 1px solid #eee;
}

.source-item:last-child {
  border-bottom: none;
}

.source-content {
  display: block;
  color: #333;
  margin-bottom: 4px;
}

.source-score {
  color: #999;
  font-size: 12px;
}

.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  background: #999;
  border-radius: 50%;
  animation: typing 1.4s infinite;
}

.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-8px); }
}

/* 输入区 */
.input-area {
  padding: 16px 24px;
  background: #fff;
  border-top: 1px solid #e0e0e0;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  max-width: 800px;
  margin: 0 auto;
}

.message-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-size: 14px;
  font-family: inherit;
  max-height: 150px;
}

.message-input:focus {
  outline: none;
  border-color: #4a90e2;
}

.search-input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 24px;
  font-size: 14px;
}

.search-input:focus {
  outline: none;
  border-color: #4a90e2;
}

.send-btn {
  width: 48px;
  height: 48px;
  background: #4a90e2;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 20px;
}

.send-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 底部工具栏 */
.bottom-toolbar {
  padding: 8px 24px;
  background: #fafafa;
  border-top: 1px solid #e0e0e0;
  display: flex;
  align-items: center;
  gap: 16px;
}

.upload-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.upload-btn:hover {
  background: #f5f5f5;
}

.upload-status {
  color: #666;
  font-size: 13px;
}
</style>
