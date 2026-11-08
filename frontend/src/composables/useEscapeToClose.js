import { onBeforeUnmount, onMounted, unref } from 'vue'

/**
 * 抽屉的 Esc 关闭。
 *
 * Element Plus 的 close-on-press-escape 依赖事件能到达它的 document 监听；当焦点停在
 * 表格行、按钮或 iframe 里时并不可靠。这里在 window 上再挂一层，保证"按 Esc 一定关"。
 * 与组件自带的 Esc 行为不冲突（都只是把 open 置 false）。
 */
export function useEscapeToClose(openRef, close) {
  function onKeydown(event) {
    if (event.key !== 'Escape') return
    if (!unref(openRef)) return
    close()
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
