import { defineStore } from 'pinia'
import { ref } from 'vue'
import { uploadDocument } from '../api'

export const useDocumentStore = defineStore('document', () => {
  const files = ref([])
  const uploading = ref(false)

  const addFiles = (newFiles) => {
    newFiles.forEach(f => {
      files.value.push({
        id: Date.now() + Math.random(),
        name: f.name,
        size: f.size,
        status: 'pending',
        file: f
      })
    })
  }

  const uploadFiles = async (chunkOptions = {}) => {
    const pendingFiles = files.value.filter(f => f.status === 'pending')
    if (pendingFiles.length === 0 || uploading.value) return

    uploading.value = true

    for (const fileItem of pendingFiles) {
      fileItem.status = 'uploading'

      const formData = new FormData()
      formData.append('file', fileItem.file)
      formData.append('kbId', chunkOptions.kbId || 'default')
      formData.append('chunkStrategy', chunkOptions.chunkStrategy || 'fixed')

      // Add chunk strategy params
      if (chunkOptions.chunkSize) {
        formData.append('chunkSize', chunkOptions.chunkSize)
      }
      if (chunkOptions.chunkOverlap) {
        formData.append('chunkOverlap', chunkOptions.chunkOverlap)
      }
      if (chunkOptions.minParagraphLength) {
        formData.append('minParagraphLength', chunkOptions.minParagraphLength)
      }
      if (chunkOptions.maxParagraphLength) {
        formData.append('maxParagraphLength', chunkOptions.maxParagraphLength)
      }
      if (chunkOptions.maxTokensPerChunk) {
        formData.append('maxTokensPerChunk', chunkOptions.maxTokensPerChunk)
      }
      if (chunkOptions.similarityThreshold) {
        formData.append('similarityThreshold', chunkOptions.similarityThreshold)
      }

      try {
        await uploadDocument(formData)
        fileItem.status = 'success'
      } catch (err) {
        fileItem.status = 'error'
        console.error('Upload failed:', err)
      }
    }

    uploading.value = false
  }

  const removeFile = (id) => {
    const index = files.value.findIndex(f => f.id === id)
    if (index > -1) {
      files.value.splice(index, 1)
    }
  }

  const clearFiles = () => {
    files.value = files.value.filter(f => f.status !== 'success')
  }

  return { files, uploading, addFiles, uploadFiles, removeFile, clearFiles }
})
