import {
  ChatLineSquare,
  Checked,
  Cpu,
  Document,
  Postcard,
  Setting,
  Share,
  Tickets,
  Timer,
  UploadFilled,
  User,
  UserFilled
} from '@element-plus/icons-vue'

/**
 * 菜单词表 —— 与后端 security/MenuCatalog.java 一一对应：
 *   ADMIN      : audit, system, users
 *   RESEARCHER : graph, imports, quality, reports, subjects, tasks
 *   DOCTOR     : followups, graph, reports, reviews, subjects
 *   PATIENT    : followups, summary
 *
 * 前端只负责"这个 key 长什么样、点去哪里"；"你有没有这个 key"由后端 menus 决定。
 * ready=false 表示页面尚未交付：仍然展示（让用户知道功能存在），但处于禁用态。
 */
export const MENU_REGISTRY = {
  subjects: { label: '受试者档案', path: '/subjects', icon: User, ready: true },
  imports: { label: '数据导入', path: '/imports', icon: UploadFilled, ready: true },
  quality: { label: '数据质控', path: '/quality', icon: Checked, ready: true },
  tasks: { label: '评估任务', path: '/tasks', icon: Cpu, ready: true },
  reviews: { label: '业务复核', path: '/reviews', icon: ChatLineSquare, ready: true },
  followups: { label: '随访计划', path: '/followups', icon: Timer, ready: true },
  graph: { label: '因果图谱', path: '/graph', icon: Share, ready: true },
  reports: { label: '评估报告', path: '/reports', icon: Document, ready: true },
  audit: { label: '审计日志', path: '/admin/audit', icon: Tickets, ready: true },
  users: { label: '账号管理', path: '/admin/users', icon: UserFilled, ready: true },
  system: { label: '系统设置', path: '/admin/system', icon: Setting, ready: true },
  summary: { label: '我的摘要', path: '/summary', icon: Postcard, ready: false }
}

/**
 * 把后端下发的 menus 变成可渲染的菜单项。
 * 后端没给 key 就不渲染；后端给了但前端没登记（版本错位）时降级成一条禁用项，
 * 避免整块菜单凭空消失，也方便排查契约不一致。
 */
export function buildMenuItems(menus = []) {
  return menus.map((key) => {
    const entry = MENU_REGISTRY[key]
    if (!entry) {
      return { key, label: key, path: null, icon: Document, ready: false, unknown: true }
    }
    return { key, ...entry }
  })
}

/** 当前路径命中的菜单 key（用于高亮）。 */
export function activeKeyOf(items, path) {
  const hit = items.find((item) => item.path && (path === item.path || path.startsWith(item.path + '/')))
  return hit?.key ?? ''
}
