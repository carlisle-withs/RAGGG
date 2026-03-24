import { createRouter, createWebHistory } from 'vue-router'
import ChatView from './views/ChatView.vue'
import UploadView from './views/UploadView.vue'
import RetrieveView from './views/RetrieveView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/upload', name: 'upload', component: UploadView },
  { path: '/retrieve', name: 'retrieve', component: RetrieveView }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
