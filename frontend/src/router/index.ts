import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Alert } from '@/types'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/AlertList.vue'),
  },
  {
    path: '/incidents/:id',
    name: 'IncidentDetail',
    component: () => import('@/views/IncidentDetail.vue'),
    props: true,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
