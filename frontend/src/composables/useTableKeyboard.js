import { getCurrentInstance, nextTick, onMounted, watch } from 'vue'

/**
 * 表格行的键盘可达性：
 *  - 给每一行加 tabindex，Tab 能聚焦；
 *  - Enter 时用行在 tbody 中的下标映射回当前数据，调用与鼠标点击相同的处理函数。
 *
 * Element Plus 的 el-table 没有行级键盘事件，所以这里在渲染后给 .el-table__row 打标记，
 * 并把 keydown 监听放在表格上（事件冒泡）。
 *
 * 两个边界：
 *  - 只标记【当前组件根】内的行。抽屉/弹窗会 teleport 到 body，若用 document 全量查询，
 *    抽屉里的随访表也会被标记，且可能被当成页面表格的下标去映射。
 *  - 行内按钮/链接自己处理 Enter，不要再触发一次「打开详情」，避免双请求。
 */
export function useTableKeyboard(rowsRef, open) {
  const instance = getCurrentInstance()

  function scopeRoot() {
    const el = instance?.proxy?.$el
    return el instanceof HTMLElement ? el : document
  }

  function decorateRows() {
    nextTick(() => {
      scopeRoot()
        .querySelectorAll('.el-table__body .el-table__row')
        .forEach((row) => {
          row.setAttribute('tabindex', '0')
          row.setAttribute('role', 'button')
        })
    })
  }

  watch(rowsRef, decorateRows, { flush: 'post' })
  onMounted(decorateRows)

  function onRowKeydown(event) {
    if (event.key !== 'Enter') return
    if (event.target?.closest?.('button, a, input, select, textarea')) return
    const row = event.target?.closest?.('.el-table__row')
    const body = row?.parentElement
    if (!row || !body) return
    const index = Array.from(body.children).indexOf(row)
    const item = rowsRef.value?.[index]
    if (!item) return
    event.preventDefault()
    open(item)
  }

  return { onRowKeydown, decorateRows }
}
