import { onUnmounted, ref } from 'vue'

/**
 * "仅滚动时显示滚动条"：滚动时置 is-scrolling 为真，停止滚动 idleMs 后复位。
 * 配合组件内的滚动条 CSS（默认透明 thumb，.is-scrolling 时显示）使用。
 */
export function useScrollFlash(idleMs = 800) {
  const scrolling = ref(false)
  let timer: number | null = null

  const onScroll = () => {
    scrolling.value = true
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      scrolling.value = false
    }, idleMs)
  }

  onUnmounted(() => {
    if (timer) window.clearTimeout(timer)
  })

  return { scrolling, onScroll }
}
