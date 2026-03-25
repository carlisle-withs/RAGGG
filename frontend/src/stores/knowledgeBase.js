import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from '../api'

export const useKnowledgeBaseStore = defineStore('kb', () => {
  const knowledgeBases = ref([])
  const loading = ref(false)

  const fetchKBs = async () => {
    loading.value = true
    try {
      const { data } = await getKnowledgeBases()
      knowledgeBases.value = data
    } catch (err) {
      console.error('Failed to fetch knowledge bases:', err)
    } finally {
      loading.value = false
    }
  }

  const createKB = async (kbData) => {
    const { data } = await createKnowledgeBase(kbData)
    knowledgeBases.value.push(data)
    return data
  }

  const updateKB = async (id, kbData) => {
    const { data } = await updateKnowledgeBase(id, kbData)
    const index = knowledgeBases.value.findIndex(kb => kb.id === id)
    if (index !== -1) {
      knowledgeBases.value[index] = data
    }
    return data
  }

  const deleteKB = async (id) => {
    await deleteKnowledgeBase(id)
    knowledgeBases.value = knowledgeBases.value.filter(kb => kb.id !== id)
  }

  return { knowledgeBases, loading, fetchKBs, createKB, updateKB, deleteKB }
})
