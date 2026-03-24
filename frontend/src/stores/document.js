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

  const uploadFiles = async () => {
    const pendingFiles = files.value.filter(f => f.status === 'pending')
    if (pendingFiles.length === 0 || uploading.value) return

    uploading.value = true

    for (const fileItem of pendingFiles) {
      fileItem.status = 'uploading'

      const formData = new FormData()
      formData.append('file', fileItem.file)
      formData.append('kbId', 'default')

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
