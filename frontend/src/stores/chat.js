import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chat } from '../api'

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const conversationId = ref(null)
  const sending = ref(false)

  const sendMessage = async (content) => {
    if (!content.trim() || sending.value) return null

    sending.value = true

    // Add user message
    messages.value.push({
      role: 'user',
      content,
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })

    // Add assistant placeholder
    const placeholderIdx = messages.value.length
    messages.value.push({
      role: 'assistant',
      content: '思考中...',
      time: ''
    })

    try {
      const res = await chat({
        message: content,
        conversationId: conversationId.value,
        userId: 'default-user',
        kbIds: ['default']
      })

      const data = res.data
      conversationId.value = data.conversationId

      // Build response HTML
      let responseHtml = data.message || '抱歉，没有得到回复'

      if (data.sources && data.sources.length > 0) {
        responseHtml += '<div class="sources"><div class="source-title">参考文档：</div>'
        data.sources.forEach(s => {
          const snippet = (s.content || '').substring(0, 200)
          responseHtml += `<div class="source-item">[${(s.score || 0).toFixed(2)}] ${snippet}...</div>`
        })
        responseHtml += '</div>'
      }

      messages.value[placeholderIdx].content = responseHtml
      messages.value[placeholderIdx].time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

      return data
    } catch (err) {
      messages.value[placeholderIdx].content = '抱歉，发生了错误：' + (err.message || '未知错误')
      messages.value[placeholderIdx].time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      return null
    } finally {
      sending.value = false
    }
  }

  const clearMessages = () => {
    messages.value = []
    conversationId.value = null
  }

  return { messages, conversationId, sending, sendMessage, clearMessages }
})
