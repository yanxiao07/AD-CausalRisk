import { nextTick, onMounted, watch } from 'vue'

/**
 * 表格行的键盘可达性：
 *  - 给每一行加 tabindex，Tab 能聚焦；
 *  - Enter 时用行在 tbody 中的下标映射回当前数据，调用与鼠标点击相同的处理函数。
 *
 * Element Plus 的 el-table 没有行级键盘事件，所以这里在渲染后给 .el-table__row 打标记，
 * 并把 keydown 监听放在表格上（事件冒泡）。
 */
export function useTableKeyboard(rowsRef, open) {
  function decorateRows() {
    nextTick(() => {
      document.querySelectorAll('.el-table__body .el-table__row').forEach((row) => {
        row.setAttribute('tabindex', '0')
        row.setAttribute('role', 'button')
      })
    })
  }

  watch(rowsRef, decorateRows, { flush: 'post' })
  onMounted(decorateRows)

  function onRowKeydown(event) {
    if (event.key !== 'Enter') return
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
