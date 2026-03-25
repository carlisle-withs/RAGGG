import { createRouter, createWebHistory } from 'vue-router'
import MainView from './views/MainView.vue'
import KnowledgeBaseView from './views/KnowledgeBaseView.vue'
import AdminView from './views/AdminView.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import { useAuthStore } from './stores/auth'

const routes = [
  { path: '/', name: 'home', component: MainView },
  { path: '/chat', name: 'chat', component: MainView },
  { path: '/kb', name: 'kb', component: KnowledgeBaseView },
  { path: '/login', name: 'login', component: LoginView },
  { path: '/register', name: 'register', component: RegisterView },
  { path: '/admin', name: 'admin', component: AdminView, meta: { requiresAdmin: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  const publicRoutes = ['/login', '/register']
  const isPublicRoute = publicRoutes.includes(to.path)

  if (!isPublicRoute && !authStore.isAuthenticated) {
    next('/login')
  } else if (isPublicRoute && authStore.isAuthenticated) {
    next('/')
  } else if (to.meta.requiresAdmin && !authStore.isAdmin) {
    // Non-admin users trying to access admin routes get redirected to home
    next('/')
  } else {
    next()
  }
})

export default router
