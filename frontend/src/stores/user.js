import { defineStore } from 'pinia'

/**
 * 登录用户。字段与 `POST /api/auth/login` 返回的 data 对齐：
 * { id, username, roles, menus }
 *
 * roles 用于路由/按钮鉴权，menus 用于侧边栏渲染（后端已按角色下发），
 * 两者必须分开存 —— 把 menus 塞进 permissions 会让权限判断失真。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    id: null,
    username: '',
    roles: [],
    menus: [],
    permissions: []
  }),

  getters: {
    isLoggedIn: (state) => !!state.username,
    /** 主角色：用于布局与首页跳转，ADMIN > RESEARCHER > DOCTOR > PATIENT。 */
    primaryRole: (state) => {
      for (const role of ['ADMIN', 'RESEARCHER', 'DOCTOR', 'PATIENT']) {
        if (state.roles.includes(role)) return role
      }
      return ''
    },
    /** 角色对应的落地页。 */
    homePath: (state) => {
      const map = { ADMIN: '/admin', RESEARCHER: '/researcher', DOCTOR: '/doctor', PATIENT: '/patient' }
      const role = ['ADMIN', 'RESEARCHER', 'DOCTOR', 'PATIENT'].find(r => state.roles.includes(r))
      return role ? map[role] : '/login'
    },
    hasRole: (state) => (role) => {
      if (!role) return true
      const need = Array.isArray(role) ? role : [role]
      return need.some(r => state.roles.includes(r))
    },
    hasPermission: (state) => (perm) => {
      if (!perm) return true
      const need = Array.isArray(perm) ? perm : [perm]
      return need.some(p => p === '*' || state.permissions.includes(p))
    },
    hasMenu: (state) => (key) => !key || state.menus.includes(key)
  },

  actions: {
    setUser(payload = {}) {
      this.id = payload.id ?? null
      this.username = payload.username ?? ''
      this.roles = Array.isArray(payload.roles) ? payload.roles : []
      this.menus = Array.isArray(payload.menus) ? payload.menus : []
      this.permissions = Array.isArray(payload.permissions) ? payload.permissions : []
    },

    /**
     * 清空登录态。
     *
     * 旧的批量评估轮询已随旧规则通道一起退役，这里不再需要中止后台定时器。
     */
    clear() {
      this.id = null
      this.username = ''
      this.roles = []
      this.menus = []
      this.permissions = []
    },

    /** 语义化别名：布局层退出登录时调用，等价于 clear()。 */
    logout() {
      this.clear()
    }
  },

  persist: {
    key: 'ad-causalrisk-user',
    pick: ['id', 'username', 'roles', 'menus', 'permissions']
  }
})
