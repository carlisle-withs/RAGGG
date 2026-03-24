<template>
  <div class="upload-wrapper">
    <h2 class="upload-title">文档上传</h2>

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
            :disabled="files.length === 0 || uploading || !hasPendingFiles">
      <span v-if="uploading" class="loading-spinner btn-spinner"></span>
      <span v-else>上传到知识库</span>
    </button>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useDocumentStore } from '../stores/document'
import { storeToRefs } from 'pinia'

const documentStore = useDocumentStore()
const { files, uploading } = storeToRefs(documentStore)

const isDragover = ref(false)
const fileInputRef = ref(null)
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
  documentStore.uploadFiles()
}

const formatFileSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>
