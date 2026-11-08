import { onBeforeUnmount, onMounted, unref } from 'vue'

/**
 * 抽屉的 Esc 关闭。
 *
 * Element Plus 的 close-on-press-escape 依赖事件能到达它的 document 监听；当焦点停在
 * 表格行、按钮或 iframe 里时并不可靠。这里在 window 上再挂一层，保证"按 Esc 一定关"。
 *
 * 两层窗口（档案抽屉里再开「新建随访」对话框）时，Esc 只能关最上层：
 *  - 调用方可以传 shouldIgnore() 显式告诉这里"上层有窗口，先别关我"；
 *  - 同时兜底检查可见的 el-dialog / el-message-box，避免忘记传 guard 时连底层一起关掉。
 */
export function useEscapeToClose(openRef, close, shouldIgnore) {
  function hasVisibleUpperLayer() {
    return Array.from(document.querySelectorAll('.el-dialog, .el-message-box')).some((element) => {
      if (!(element instanceof HTMLElement)) return false
      const style = getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden' && element.offsetParent !== null
    })
  }

  function onKeydown(event) {
    if (event.key !== 'Escape') return
    if (!unref(openRef)) return
    if (shouldIgnore?.()) return
    if (hasVisibleUpperLayer()) return
    close()
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
