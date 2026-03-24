<template>
  <div class="app-container">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-icon">⚡</div>
        <span>RAG 系统</span>
      </div>
      <nav class="nav-list">
        <router-link to="/chat" class="nav-item" :class="{ active: $route.path === '/chat' }">
          <span class="nav-icon">💬</span>
          <span>智能问答</span>
        </router-link>
        <router-link to="/upload" class="nav-item" :class="{ active: $route.path === '/upload' }">
          <span class="nav-icon">📄</span>
          <span>文档上传</span>
        </router-link>
        <router-link to="/retrieve" class="nav-item" :class="{ active: $route.path === '/retrieve' }">
          <span class="nav-icon">🔍</span>
          <span>内容检索</span>
        </router-link>
      </nav>
    </aside>

    <main class="main">
      <header class="header">
        <h1 class="header-title">{{ pageTitle }}</h1>
        <div class="header-status">
          <div class="status-dot"></div>
          <span>已连接</span>
        </div>
      </header>
      <div class="content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const pageTitle = computed(() => {
  const titles = {
    '/chat': '智能问答',
    '/upload': '文档上传',
    '/retrieve': '内容检索'
  }
  return titles[route.path] || 'RAG 系统'
})
</script>
