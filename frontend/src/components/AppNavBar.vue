<script setup lang="ts">
import { useRoute } from 'vue-router'

/**
 * 顶部导航栏：品牌名 + 视角切换器。
 * 新页面在 tabs 中补上 label 与 to 路由即可接入。
 */
const route = useRoute()

interface NavTab {
  label: string
  to: string
}

const tabs: NavTab[] = [
  { label: '用户视角', to: '/user-view' },
  { label: '告警分析', to: '/' },
  { label: '评测', to: '/evaluation' },
]

const isActive = (tab: NavTab) => tab.to != null && route.path === tab.to
</script>

<template>
  <header class="app-bar">
    <span class="brand">故障分析助手</span>
    <nav class="view-switcher">
      <RouterLink
        v-for="tab in tabs" :key="tab.label" :to="tab.to" class="view-tab"
        :class="{ active: isActive(tab) }"
      >
        {{ tab.label }}
      </RouterLink>
    </nav>
    <!-- 右侧装饰标签：呼应苔原低刺激配色的护眼定位 -->
    <span class="eco-tag" title="苔原主题 · 低刺激配色">
      <svg
        viewBox="0 0 24 24" fill="none" stroke="currentColor"
        stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
      >
        <path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z" />
        <path d="M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12" />
      </svg>
      护眼模式
    </span>
  </header>
</template>

<style scoped>
.app-bar {
  height: 52px;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 20px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--color-border-light);
}

.brand {
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-primary);
}

.view-switcher {
  display: flex;
  gap: 4px;
}

.view-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 5px 14px;
  border-radius: 999px;
  color: var(--color-text-secondary);
  text-decoration: none;
  cursor: pointer;
}

.view-tab:hover {
  background: var(--color-primary-light-9);
  color: var(--color-primary-dark-2);
}

.view-tab.active {
  background: var(--color-primary-light-8);
  color: var(--color-primary-dark-2);
  font-weight: 600;
}

.eco-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 999px;
  background: var(--color-primary-light-9);
  color: var(--color-primary-dark-2);
  cursor: default;
}

.eco-tag svg {
  width: 14px;
  height: 14px;
}

@media (max-width: 768px) {
  .app-bar {
    padding: 0 12px;
    gap: 12px;
  }

  .view-tab {
    padding: 4px 10px;
  }

  .eco-tag {
    display: none;
  }
}
</style>
