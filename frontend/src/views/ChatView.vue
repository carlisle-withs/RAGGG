<template>
  <div class="chat-wrapper">
    <div class="chat-messages" ref="chatMessagesRef">
      <div v-if="messages.length === 0" class="welcome-tip">
        <div class="welcome-icon">🤖</div>
        <div>开始对话吧！基于已上传的文档智能回答问题</div>
      </div>

      <div v-for="(msg, index) in messages" :key="index"
           class="message" :class="msg.role">
        <div v-html="msg.content"></div>
        <div class="message-time">{{ msg.time }}</div>
      </div>
    </div>

    <div class="chat-input-area">
      <input type="text" class="chat-input"
             v-model="inputMessage"
             @keyup.enter="handleSend"
             placeholder="输入问题，按 Enter 发送..."
             :disabled="sending">
      <button class="send-btn" @click="handleSend" :disabled="sending || !inputMessage.trim()">
        <span v-if="sending" class="loading-spinner"></span>
        <span v-else>发送</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { useChatStore } from '../stores/chat'
import { storeToRefs } from 'pinia'

const chatStore = useChatStore()
const { messages, sending } = storeToRefs(chatStore)

const inputMessage = ref('')
const chatMessagesRef = ref(null)

const handleSend = async () => {
  const content = inputMessage.value.trim()
  if (!content) return

  inputMessage.value = ''
  await chatStore.sendMessage(content)
  scrollToBottom()
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatMessagesRef.value) {
      chatMessagesRef.value.scrollTop = chatMessagesRef.value.scrollHeight
    }
  })
}
</script>
