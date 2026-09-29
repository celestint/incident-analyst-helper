import type { Ref } from 'vue'
import type { SseEvent } from '@/types'

export function useSse(
  eventSource: EventSource,
  onData: (event: SseEvent) => void,
  onError?: (error: Event) => void,
  onClose?: () => void
) {
  const events: SseEvent[] = []

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data) as SseEvent
      events.push(data)
      onData(data)
    } catch (err) {
      console.error('Failed to parse SSE data:', err)
    }
  }

  eventSource.onerror = (error) => {
    if (onError) {
      onError(error)
    }
    eventSource.close()
  }

  eventSource.addEventListener('close', () => {
    if (onClose) {
      onClose()
    }
  })

  return {
    events,
    clear: () => {
      events.length = 0
    },
    close: () => {
      eventSource.close()
    },
  }
}
