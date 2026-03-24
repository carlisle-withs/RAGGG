import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chat } from '../api'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const conversationId = ref(null)
  const sending = ref(false)

  const addMessage = (role, content, sources = null) => {
    messages.value.push({
      role,
      content,
      sources,
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })
  }

  const sendMessage = async (content) => {
    if (!content.trim() || sending.value) return null

    sending.value = true

    // Add user message
    addMessage('user', content)

    // Add assistant placeholder
    const placeholderIdx = messages.value.length
    addMessage('assistant', '思考中...')

    try {
      const res = await chat({
        message: content,
        conversationId: conversationId.value,
        userId: 'default-user',
        kbIds: ['default']
      })

      const data = res.data
      conversationId.value = data.conversationId

      messages.value[placeholderIdx].content = data.message || '抱歉，没有得到回复'
      messages.value[placeholderIdx].sources = data.sources || []

      return data
    } catch (err) {
      messages.value[placeholderIdx].content = '抱歉，发生了错误：' + (err.message || '未知错误')
      return null
    } finally {
      sending.value = false
    }
  }

  const clearMessages = () => {
    messages.value = []
    conversationId.value = null
  }

  return { messages, conversationId, sending, addMessage, sendMessage, clearMessages }
})
