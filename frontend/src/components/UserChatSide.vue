<script setup lang="ts">
/**
 * 用户视角右侧信息栏：群公告（上）+ 群成员（下），各占一半内部滚动。
 * 纯静态装饰假数据：公告内容与非机器人成员的头像/名字模糊化，机器人名字可见。
 */
const members = ['运维·张三', '后端·李四', '前端·陈晨', '值班·赵六']
</script>

<template>
  <aside class="chat-side">
    <div class="side-sec">
      <div class="side-title">📌 群公告</div>
      <div class="blurred notice">
        本群用于接收告警自动推送，点击告警消息内「分析」可发起自动诊断，分析报告完成后将回推至本群，请相关同学及时关注处理结果。
      </div>
    </div>
    <div class="side-sec">
      <div class="side-title">群成员（12）</div>
      <div class="member">
        <div class="member-avatar">🤖</div>
        <div class="member-name bot">告警机器人</div>
      </div>
      <div v-for="name in members" :key="name" class="member">
        <div class="member-avatar blurred">▢</div>
        <div class="member-name blurred">{{ name }}</div>
      </div>
      <div class="side-more">…（共 12 人）</div>
    </div>
  </aside>
</template>

<style scoped>
.chat-side {
  width: 240px;
  flex-shrink: 0;
  background: var(--el-bg-color);
  border-left: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
}

/* 上下各占一半，内容超出时内部滚动 */
.side-sec {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  padding: 14px;
}

/* 群公告与群成员之间的分隔线 */
.side-sec + .side-sec {
  border-top: 1px solid var(--color-border-light);
}

.side-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 10px;
  color: var(--color-text-primary);
}

.notice {
  font-size: 13px;
  color: var(--color-text-regular);
  line-height: 1.8;
}

/* 模糊化装饰：公告内容与非机器人成员，不可选中不可交互 */
.blurred {
  filter: blur(4px);
  user-select: none;
  pointer-events: none;
}

.member {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.member-avatar {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  background: var(--color-primary-light-9);
  border: 1px solid var(--color-border-light);
}

.member-name {
  font-size: 13px;
  color: var(--color-text-regular);
}

.member-name.bot {
  color: var(--color-text-primary);
  font-weight: 600;
}

.side-more {
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
