<template>
  <div class="users-page">
    <article class="panel">
      <header class="panel__head">
        <h2>账号管理</h2>
        <span class="panel__tag">UI 骨架 · 接口待建</span>
      </header>

      <p class="panel__hint">
        契约的 22 个操作里没有账号管理接口（现有 only 登录 / 登出 / 当前用户），
        因此本页先固定表头与交互骨架；后端补上
        <code>GET /api/users</code>、<code>PATCH /api/users/{{ '{' }}id{{ '}' }}</code>
        之后只需替换数据源。
      </p>

      <el-table :data="rows" class="table" empty-text="暂无账号数据（接口未接入）">
        <el-table-column prop="username" label="用户名" min-width="160" />
        <el-table-column prop="roles" label="角色" min-width="160" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="lastLoginAt" label="最近登录" width="200" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default>
            <el-button text type="primary" disabled>编辑</el-button>
            <el-button text type="danger" disabled>禁用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="panel__foot">
        角色来自 <code>MenuCatalog</code> 的 ADMIN / RESEARCHER / DOCTOR / PATIENT 四类；
        账号与档案绑定的权限管理同样在后续接口里补。
      </footer>
    </article>
  </div>
</template>

<script setup>
/**
 * 管理员「账号管理」占位页。
 * 不编造假数据：表格数据源固定为空数组，等后端提供用户管理接口后再接。
 */
const rows = []
</script>

<style scoped>
.users-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel {
  padding: 20px 22px;
  border: 1px solid rgba(120, 190, 235, 0.18);
  border-radius: var(--r-lg);
  background: linear-gradient(160deg, rgba(22, 54, 80, 0.72), rgba(12, 32, 50, 0.72));
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel__head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #dcecfb;
}

.panel__tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  color: #7fa6c4;
  border: 1px solid rgba(120, 190, 235, 0.24);
}

.panel__hint,
.panel__foot {
  margin: 0 0 14px;
  font-size: 12px;
  line-height: 1.8;
  color: #7fa6c4;
}

.panel__foot {
  margin: 14px 0 0;
  color: #5d86a4;
}

.panel__hint code,
.panel__foot code {
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(79, 195, 247, 0.12);
  color: #9fd4f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.table {
  width: 100%;
}

.table :deep(.el-table),
.table :deep(.el-table tr),
.table :deep(.el-table th.el-table__cell),
.table :deep(.el-table td.el-table__cell) {
  background: transparent;
  color: #dcecfb;
  border-bottom-color: rgba(120, 190, 235, 0.12);
}

.table :deep(.el-table th.el-table__cell) {
  color: #7fa6c4;
  font-weight: 600;
}

.table :deep(.el-table__empty-text) {
  color: #5d86a4;
}
</style>
