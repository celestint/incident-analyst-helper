import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/AlertList.vue'),
  },
  {
    path: '/user-view',
    name: 'UserView',
    component: () => import('@/views/UserView.vue'),
  },
  {
    path: '/evaluation',
    name: 'Evaluation',
    component: () => import('@/views/EvaluationView.vue'),
  },
  {
    // 旧详情链接兼容：重定向到工作台并选中对应告警
    path: '/incidents/:id',
    redirect: to => ({ path: '/', query: { incidentId: to.params.id } }),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
