<template>
  <div class="kb-wrapper">
    <div class="kb-header">
      <h2 class="kb-title">知识库管理</h2>
      <button class="create-btn" @click="showCreateForm = true">新建知识库</button>
    </div>

    <!-- Knowledge Base List -->
    <div class="kb-list" v-if="kbStore.knowledgeBases.length > 0">
      <div v-for="kb in kbStore.knowledgeBases" :key="kb.id" class="kb-card">
        <div class="kb-info">
          <div class="kb-name">{{ kb.name }}</div>
          <div class="kb-desc" v-if="kb.description">{{ kb.description }}</div>
          <div class="kb-meta">
            <span>文档数: {{ kb.documentCount }}</span>
            <span>创建时间: {{ formatDate(kb.createdAt) }}</span>
          </div>
        </div>
        <div class="kb-actions">
          <button class="action-btn delete" @click="handleDelete(kb.id)">删除</button>
        </div>
      </div>
    </div>
    <div class="empty-state" v-else>
      <p>暂无知识库，点击"新建知识库"创建</p>
    </div>

    <!-- Create Form Modal -->
    <div class="modal" v-if="showCreateForm" @click.self="showCreateForm = false">
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
        <div class="form-actions">
          <button @click="showCreateForm = false">取消</button>
          <button class="primary" @click="handleCreate">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useKnowledgeBaseStore } from '../stores/knowledgeBase'

const kbStore = useKnowledgeBaseStore()

const showCreateForm = ref(false)
const newKB = ref({
  name: '',
  description: ''
})

onMounted(() => {
  kbStore.fetchKBs()
})

const handleCreate = async () => {
  if (!newKB.value.name.trim()) return
  await kbStore.createKB({
    name: newKB.value.name,
    description: newKB.value.description
  })
  newKB.value = { name: '', description: '' }
  showCreateForm.value = false
}

const handleDelete = async (id) => {
  if (confirm('确定要删除这个知识库吗？')) {
    await kbStore.deleteKB(id)
  }
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.kb-wrapper {
  padding: 20px;
}

.kb-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.kb-title {
  margin: 0;
  font-size: 24px;
  color: #333;
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
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.kb-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: #f5f5f5;
  border-radius: 8px;
}

.kb-info {
  flex: 1;
}

.kb-name {
  font-size: 18px;
  font-weight: 500;
  color: #333;
  margin-bottom: 4px;
}

.kb-desc {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.kb-meta {
  font-size: 12px;
  color: #999;
  display: flex;
  gap: 16px;
}

.kb-actions {
  display: flex;
  gap: 8px;
}

.action-btn {
  padding: 6px 12px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.action-btn.delete {
  background: #ff4d4f;
  color: white;
}

.action-btn.delete:hover {
  background: #ff7875;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #999;
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

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 4px;
  font-size: 14px;
  color: #333;
}

.form-group input,
.form-group textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group textarea {
  min-height: 80px;
  resize: vertical;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}

.form-actions button {
  padding: 8px 16px;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: white;
  cursor: pointer;
  font-size: 14px;
}

.form-actions button.primary {
  background: #1890ff;
  color: white;
  border-color: #1890ff;
}

.form-actions button.primary:hover {
  background: #40a9ff;
}
</style>
