<template>
  <div class="users-page">
    <PageHeader
      back
      title="账号管理"
      subtitle="维护工作人员账号与授权。请在「患者账号申请」核对并发放患者账号。"
    >
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="tab" class="users-tabs">
      <el-tab-pane label="患者账号申请" name="patientRequests">
        <el-button :icon="Refresh" @click="loadPatientRequests">刷新申请</el-button>
        <el-table :data="patientRequests" empty-text="暂无账号申请">
          <el-table-column prop="subjectCode" label="档案编号" />
          <el-table-column prop="alias" label="档案别名" />
          <el-table-column prop="doctor" label="责任医生" />
          <el-table-column label="当前资料">
            <template #default="{row}">{{ onboardingQualityLabel(row.qualityStatus) }}</template>
          </el-table-column>
          <el-table-column label="状态">
            <template #default="{row}">
              {{ row.status === 'ISSUED' ? '已发放' : row.canIssue ? '待核对发放' : '待医生核对并重新提交' }}
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="{row}">
              <el-button v-if="row.canIssue" type="primary" link @click="prepareIssue(row)">核对并绑定</el-button>
              <el-button link @click="prepareHandoff(row)">交接责任医生</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <!-- 用户账号 -->
      <el-tab-pane label="用户账号" name="users">
        <DataCard padding="none">
          <div class="filter">
            <el-input
              v-model="keyword"
              class="filter__input"
              size="small"
              clearable
              placeholder="按用户名搜索"
              @keyup.enter="reload"
            />
            <el-select
              v-model="status"
              class="filter__select"
              size="small"
              clearable
              placeholder="账号状态"
              @change="reload"
            >
              <el-option label="启用" value="ACTIVE" />
              <el-option label="停用" value="DISABLED" />
            </el-select>
            <el-button size="small" :icon="Search" @click="reload">查询</el-button>
            <span class="filter__count">共 {{ total }} 个账号</span>
          </div>

          <el-skeleton v-if="loading && items.length === 0" :rows="4" animated />

          <EmptyState
            v-else-if="loadError"
            :icon="WarningFilled"
            title="账号列表加载失败"
            :description="loadError"
            action-text="重试"
            @action="reload"
          />

          <EmptyState
            v-else-if="items.length === 0"
            :icon="User"
            title="没有账号"
            description="当前筛选条件下没有账号，可以调整条件或新增一个。"
          />

          <el-table
            v-else
            :data="items"
            class="table"
            row-key="id"
            @keydown.enter="onRowKeydown"
          >
            <el-table-column prop="username" label="用户名" min-width="140" />
            <el-table-column label="显示名" min-width="120">
              <template #default="{ row }">{{ row.displayName || '未设置' }}</template>
            </el-table-column>
            <el-table-column label="角色" min-width="200">
              <template #default="{ row }">
                <el-tag
                  v-for="role in row.roles || []"
                  :key="role"
                  size="small"
                  class="role-tag"
                  type="info"
                >
                  {{ roleLabel(role) }}
                </el-tag>
                <span v-if="!(row.roles || []).length" class="is-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusTag :type="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="数据范围" width="110">
              <template #default="{ row }">
                {{ row.roles?.includes('PATIENT') ? '仅本人摘要' : row.dataScope === 'ALL' ? '全部' : '授权范围' }}
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click.stop="openEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="total > pageSize" class="pager">
            <el-pagination
              v-model:current-page="page"
              :page-size="pageSize"
              :total="total"
              layout="total, prev, pager, next"
              background
              @current-change="loadUsers"
            />
          </div>
        </DataCard>
      </el-tab-pane>

      <!-- 档案授权 -->
      <el-tab-pane label="档案授权" name="grants">        <DataCard padding="none">
          <div class="filter">
            <el-select
              v-model="grantSubjectId"
              class="filter__select--wide"
              size="small"
              filterable
              clearable
              placeholder="选择档案（最多列出前 100 个）"
              @change="loadGrants"
            >
              <el-option
                v-for="subject in subjects"
                :key="subject.id"
                :label="`${subject.alias || '—'}（${subject.subjectCode}）`"
                :value="subject.id"
              />
            </el-select>
            <span class="filter__count">
              {{ grantSubjectId ? `共 ${grants.length} 条授权` : '选择档案后查看授权' }}
            </span>
          </div>

          <EmptyState
            v-if="!grantSubjectId"
            :icon="Lock"
            title="请选择档案"
            description="授权决定医生/研究员能看到哪些档案；患者只能授予“摘要读取”，且只能绑定一个档案。"
          />

          <template v-else>
            <el-skeleton v-if="grantsLoading" :rows="3" animated />
            <el-empty v-else-if="grants.length === 0" description="该档案还没有任何授权" />
            <el-table v-else :data="grants" class="table" row-key="id">
              <el-table-column prop="username" label="账号" min-width="160" />
              <el-table-column label="权限" width="160">
                <template #default="{ row }">
                  <el-tag size="small">{{ permissionLabel(row.permissionType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button text type="danger" @click="revokeGrant(row)">撤销</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="grant-form">
              <span class="grant-form__label">新增授权</span>
              <el-select
                v-model="grantUserId"
                class="filter__select--wide"
                size="small"
                filterable
                placeholder="选择账号"
              >
                <el-option
                  v-for="user in grantUserOptions"
                  :key="user.id"
                  :label="`${user.username}${user.displayName ? `（${user.displayName}）` : ''}`"
                  :value="user.id"
                />
              </el-select>
              <el-select v-model="grantType" class="filter__select" size="small">
                <el-option label="查看（VIEW）" value="VIEW" />
                <el-option label="管理（MANAGE）" value="MANAGE" />
                <el-option label="摘要读取（SUMMARY_READ）" value="SUMMARY_READ" />
              </el-select>
              <el-button type="primary" size="small" :loading="granting" @click="grant">
                授权
              </el-button>
            </div>
          </template>
        </DataCard>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="handoffOpen" title="交接责任医生" width="min(480px, 94vw)">
      <p>请核对档案 {{ handoffRow?.subjectCode }}，并选择具备该档案管理权限的接手医生。</p>
      <el-select v-model="handoffDoctorId" filterable aria-label="接手医生" placeholder="选择接手医生" style="width: 100%">
        <el-option v-for="doctor in handoffOptions" :key="doctor.id" :label="doctorLabel(doctor)" :value="doctor.id" />
      </el-select>
      <p v-if="handoffOptions.length === 0">暂无可选医生。可先在「档案授权」为接手医生添加管理权限。</p>
      <template #footer>
        <el-button @click="handoffOpen = false">取消</el-button>
        <el-button type="primary" :loading="handoffBusy" :disabled="!handoffDoctorId" @click="saveHandoff">确认交接</el-button>
      </template>
    </el-dialog>

    <!-- 编辑账号 -->
    <el-dialog v-model="dialogOpen" title="编辑账号" width="560px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <el-form-item label="重置密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="留空表示不修改"
          />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roles" multiple class="form__wide" placeholder="至少选择一个角色">
            <el-option label="管理员（ADMIN）" value="ADMIN" />
            <el-option label="医生（DOCTOR）" value="DOCTOR" />
            <el-option label="研究员（RESEARCHER）" value="RESEARCHER" />
            <el-option label="患者（PATIENT）" value="PATIENT" />
          </el-select>
          <p class="form__hint">患者账号必须独立使用 PATIENT 角色，不能与其它角色组合。</p>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio-button value="ACTIVE">启用</el-radio-button>
            <el-radio-button value="DISABLED">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" placeholder="用于报告与患者端展示" />
        </el-form-item>
        <el-form-item label="科室">
          <el-input v-model="form.department" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="门诊时间">
          <el-input v-model="form.officeHours" placeholder="例如 周一/周三 上午" />
        </el-form-item>
        <el-form-item label="数据范围">
          <el-radio-group v-model="form.dataScope">
            <el-radio-button value="ASSIGNED">授权范围</el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增账号：角色化向导 -->
    <el-dialog
      v-model="createOpen"
      title="新增账号"
      width="760px"
      destroy-on-close
      @closed="resetCreate"
    >
      <el-steps :active="createStep" finish-status="success" align-center class="wizard-steps">
        <el-step title="基本信息" description="账号与角色" />
        <el-step title="权限与绑定" description="范围与关联" />
        <el-step title="确认提交" description="核对后创建" />
      </el-steps>

      <div class="wizard-body">
        <template v-if="createStep === 0">
          <el-form label-width="96px">
            <el-form-item label="用户名" required>
              <el-input
                v-model="createForm.username"
                placeholder="2–50 位字母、数字、下划线或短横线"
              />
            </el-form-item>
            <el-form-item label="密码" required>
              <el-input
                v-model="createForm.password"
                type="password"
                show-password
                placeholder="至少 6 位"
              />
            </el-form-item>
            <el-form-item label="主角色" required>
              <el-select
                v-model="createForm.primaryRole"
                class="form__wide"
                placeholder="选择主角色"
                @change="onPrimaryRoleChange"
              >
                <el-option label="管理员（ADMIN）" value="ADMIN" />
                <el-option label="医生（DOCTOR）" value="DOCTOR" />
                <el-option label="研究员（RESEARCHER）" value="RESEARCHER" />

              </el-select>
            </el-form-item>
            <el-form-item label="附加角色">
              <el-select
                v-model="createForm.extraRoles"
                multiple
                class="form__wide"
                placeholder="可选附加角色"
              >
                <el-option label="管理员（ADMIN）" value="ADMIN" />
                <el-option label="医生（DOCTOR）" value="DOCTOR" />
                <el-option label="研究员（RESEARCHER）" value="RESEARCHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="显示名">
              <el-input
                v-model="createForm.displayName"
                placeholder="用于报告与患者端展示"
              />
            </el-form-item>
              <template v-if="createForm.primaryRole === 'DOCTOR'">
                <el-form-item label="科室">
                  <el-input v-model="createForm.department" />
                </el-form-item>
                <el-form-item label="联系电话">
                  <el-input v-model="createForm.phone" placeholder="患者端会展示该号码" />
                </el-form-item>
                <el-form-item label="门诊时间">
                  <el-input v-model="createForm.officeHours" placeholder="例如 周一/周三 上午" />
                </el-form-item>
              </template>
              <p v-else class="form__hint form__hint--block">
                管理员与研究员账号不需要填写医生联系方式；如需展示给患者，请使用医生角色。
              </p>
            <el-form-item label="状态">
              <el-radio-group v-model="createForm.status">
                <el-radio-button value="ACTIVE">启用</el-radio-button>
                <el-radio-button value="DISABLED">停用</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </template>

        <template v-else-if="createStep === 1">
            <el-form label-width="110px">
              <el-form-item label="数据范围">
                <el-radio-group v-model="createForm.dataScope">
                  <el-radio-button value="ASSIGNED">授权范围</el-radio-button>
                  <el-radio-button value="ALL">全部</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </el-form>
            <p class="form__hint">创建后可在「档案授权」页继续为该账号分配档案。</p>
        </template>

        <template v-else>
          <el-descriptions :column="2" border class="wizard-summary">
            <el-descriptions-item label="用户名">{{ createForm.username }}</el-descriptions-item>
            <el-descriptions-item label="显示名">{{ createForm.displayName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ createRoleNames }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              {{ createForm.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-descriptions-item>
              <el-descriptions-item label="数据范围">
                {{ createForm.dataScope === 'ALL' ? '全部' : '授权范围' }}
              </el-descriptions-item>
              <el-descriptions-item v-if="createForm.primaryRole === 'DOCTOR'" label="科室">
                {{ createForm.department || '—' }}
              </el-descriptions-item>
          </el-descriptions>

        </template>
      </div>

      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button v-if="createStep > 0" @click="createStep -= 1">上一步</el-button>
        <el-button v-if="createStep < 2" type="primary" @click="nextCreateStep">下一步</el-button>
        <el-button v-else type="primary" :loading="creating" @click="submitCreate">
          创建账号
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, Plus, Refresh, Search, User, WarningFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { listSubjects } from '@/api/subjects.js'
import {
  createUser,
  grantSubjectPermission,
  listSubjectPermissions,
  listUsers,
  revokeSubjectPermission,
  updateUser
} from '@/api/users.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'
import { listPatientAccountRequests, issuePatientAccount, handoffPatientDoctor, onboardingQualityLabel } from '@/api/patientAccountRequests.js'

const ROLE_LABELS = {
  ADMIN: '管理员',
  DOCTOR: '医生',
  RESEARCHER: '研究员',
  PATIENT: '患者'
}

const route = useRoute()
const tab = ref('users')
const patientRequests = ref([])
const handoffOpen = ref(false)
const handoffRow = ref(null)
const handoffDoctorId = ref(null)
const handoffOptions = ref([])
const handoffBusy = ref(false)
async function prepareHandoff(row) {
  const [permissions] = await Promise.all([listSubjectPermissions(row.subjectId), loadDoctorOptions()])
  const permitted = new Set(permissions.filter(grant => grant.permissionType === 'MANAGE').map(grant => grant.userId))
  handoffOptions.value = doctorOptions.value.filter(doctor => permitted.has(doctor.id))
  handoffDoctorId.value = null
  handoffRow.value = row
  handoffOpen.value = true
}
async function saveHandoff() {
  handoffBusy.value = true
  try {
    await handoffPatientDoctor(handoffRow.value.id, handoffDoctorId.value)
    handoffOpen.value = false
    await loadPatientRequests()
    ElMessage.success('责任医生已更新')
  } finally {
    handoffBusy.value = false
  }
}
async function loadPatientRequests() {
  patientRequests.value = await listPatientAccountRequests()
}
watch(() => route.query.tab, value => {
  if (value === 'patientRequests') tab.value = 'patientRequests'
}, { immediate: true })
watch(tab, value => {
  if (value === 'patientRequests') loadPatientRequests().catch(() => {})
}, { immediate: true })
async function prepareIssue(row) {
  try {
    await ElMessageBox.confirm(
      `请核对本人身份及档案 ${row.subjectCode}（${row.alias || '未填别名'}），并确认责任医生为 ${row.doctor}。`,
      '确认患者、档案和责任医生',
      { confirmButtonText: '已核对，继续', cancelButtonText: '取消', type: 'info' }
    )
    const { value: username } = await ElMessageBox.prompt(`为 ${row.subjectCode} 输入用户名`, '发放患者账号', {
      inputPattern: /^[A-Za-z0-9_-]{2,50}$/, inputErrorMessage: '请输入 2–50 个字母、数字、下划线或连字符'
    })
    const { value: password } = await ElMessageBox.prompt('设置初始密码', '发放并绑定', {
      inputType: 'password', inputPattern: /^.{6,100}$/, inputErrorMessage: '请输入 6–100 个字符'
    })
    await issuePatientAccount(row.id, { username, password, identityVerified: true })
    await loadPatientRequests()
    await reload()
    ElMessage.success('账号已发放并绑定。')
  } catch {
    // Cancelled dialogs require no action. The client displays API errors.
  }
}

// ---- 用户列表 ----
const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const status = ref('')
const loading = ref(false)
const loadError = ref('')

// ---- 新增/编辑 ----
const dialogOpen = ref(false)
const editingId = ref(null)
const saving = ref(false)
const form = ref(emptyForm())

// ---- 新增账号向导 ----
const createOpen = ref(false)
const createStep = ref(0)
const creating = ref(false)
const createForm = ref(emptyCreateForm())
const doctorOptions = ref([])

const createRoles = computed(() => {
  const primary = createForm.value.primaryRole
  if (!primary) return []
  return [...new Set([primary, ...createForm.value.extraRoles])]
})
const createRoleNames = computed(() =>
  createRoles.value.map((role) => roleLabel(role)).join('、') || '—'
)
// ---- 档案授权 ----
const subjects = ref([])
/** 授权下拉的账号选项：独立加载前 100 个，避免只能选到当前页的 20 个 */
const grantUserOptions = ref([])
const grantSubjectId = ref(null)
const grants = ref([])
const grantsLoading = ref(false)
const grantUserId = ref(null)
const grantType = ref('VIEW')
const granting = ref(false)

function emptyForm() {
  return {
    username: '',
    password: '',
    roles: [],
    status: 'ACTIVE',
    displayName: '',
    department: '',
    phone: '',
    officeHours: '',
    dataScope: 'ASSIGNED'
  }
}

function emptyCreateForm() {
  return {
    username: '',
    password: '',
    primaryRole: '',
    extraRoles: [],
    status: 'ACTIVE',
    displayName: '',
    department: '',
    phone: '',
    officeHours: '',
    dataScope: 'ASSIGNED'
  }
}

function roleLabel(role) {
  return ROLE_LABELS[role] || role
}

function permissionLabel(type) {
  return { VIEW: '查看', MANAGE: '管理', SUMMARY_READ: '摘要读取' }[type] || type
}

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit'
})

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormatter.format(date)
}

const { onRowKeydown } = useTableKeyboard(items, (row) => openEdit(row))

async function loadUsers(target = page.value) {
  loading.value = true
  loadError.value = ''
  try {
    const data = await listUsers({
      page: target,
      pageSize: pageSize.value,
      status: status.value,
      keyword: keyword.value.trim()
    })
    items.value = Array.isArray(data?.items) ? data.items : []
    total.value = Number(data?.total ?? items.value.length)
    page.value = Number(data?.page ?? target)
    if (items.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value -= 1
      await loadUsers(page.value)
    }
  } catch (problem) {
    items.value = []
    total.value = 0
    loadError.value =
      problem?.code === 403 ? '仅管理员可管理账号' : problem?.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

function reload() {
  if (tab.value === 'users') loadUsers(1)
  else if (grantSubjectId.value) loadGrants()
  else loadSubjects()
}

async function loadSubjects() {
  try {
    const data = await listSubjects({ page: 1, pageSize: 100 })
    subjects.value = Array.isArray(data?.items) ? data.items : []
  } catch {
    subjects.value = []
  }
}

async function loadGrantUserOptions() {
  try {
    const data = await listUsers({ page: 1, pageSize: 100 })
    grantUserOptions.value = Array.isArray(data?.items) ? data.items : []
  } catch {
    grantUserOptions.value = []
  }
}

function openCreate() {
  resetCreate()
  createOpen.value = true
}

function resetCreate() {
  createStep.value = 0
  creating.value = false
  createForm.value = emptyCreateForm()
}

function doctorLabel(doctor) {
  const department = doctor.department ? `（${doctor.department}）` : ''
  const phone = doctor.phone ? ` · ${doctor.phone}` : ''
  return `${doctor.displayName || doctor.username}${department}${phone}`
}

async function loadDoctorOptions() {
  try {
    const data = await listUsers({ page: 1, pageSize: 100, status: 'ACTIVE' })
    const items = Array.isArray(data?.items) ? data.items : []
    doctorOptions.value = items.filter(
      (user) => Array.isArray(user.roles) && user.roles.includes('DOCTOR')
    )
  } catch {
    doctorOptions.value = []
  }
}

function onPrimaryRoleChange() {
  if (createForm.value.primaryRole !== 'DOCTOR') {
    createForm.value.department = ''
    createForm.value.phone = ''
    createForm.value.officeHours = ''
  }
}

function nextCreateStep() {
  if (createStep.value === 0) {
    const username = createForm.value.username.trim()
    if (!/^[A-Za-z0-9_-]{2,50}$/.test(username)) {
      ElMessage.warning('用户名需为 2–50 位字母、数字、下划线或短横线')
      return
    }
    if (createForm.value.password.length < 6) {
      ElMessage.warning('密码至少 6 位')
      return
    }
    if (!createForm.value.primaryRole) {
      ElMessage.warning('请选择主角色')
      return
    }
  }
  createStep.value += 1
}

async function submitCreate() {
  if (creating.value) return
  creating.value = true
  try {
    const form = createForm.value
    await createUser({
      username: form.username.trim(), password: form.password, status: form.status,
      roles: createRoles.value, displayName: form.displayName.trim(), department: form.department.trim(),
      phone: form.phone.trim(), officeHours: form.officeHours.trim(), dataScope: form.dataScope
    })
    createOpen.value = false
    await loadUsers(1)
    await loadGrantUserOptions()
  } finally {
    creating.value = false
  }
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    username: row.username || '',
    password: '',
    roles: Array.isArray(row.roles) ? [...row.roles] : [],
    status: row.status || 'ACTIVE',
    displayName: row.displayName || '',
    department: row.department || '',
    phone: row.phone || '',
    officeHours: row.officeHours || '',
    dataScope: row.dataScope || 'ASSIGNED'
  }
  dialogOpen.value = true
}

async function save() {
  if (saving.value) return
  const payload = {
    status: form.value.status,
    roles: form.value.roles,
    displayName: form.value.displayName.trim(),
    department: form.value.department.trim(),
    phone: form.value.phone.trim(),
    officeHours: form.value.officeHours.trim(),
    dataScope: form.value.dataScope
  }

  if (!editingId.value) {
    if (!/^[A-Za-z0-9_-]{2,50}$/.test(form.value.username.trim())) {
      ElMessage.warning('用户名需为 2–50 位字母、数字、下划线或短横线')
      return
    }
    payload.username = form.value.username.trim()
    if (form.value.password.length < 6) {
      ElMessage.warning('密码至少 6 位')
      return
    }
    payload.password = form.value.password
  } else if (form.value.password) {
    if (form.value.password.length < 6) {
      ElMessage.warning('密码至少 6 位')
      return
    }
    payload.password = form.value.password
  }

  if (!payload.roles.length) {
    ElMessage.warning('至少选择一个角色')
    return
  }
  if (payload.roles.includes('PATIENT') && payload.roles.length > 1) {
    ElMessage.warning('患者账号必须独立使用 PATIENT 角色')
    return
  }

  saving.value = true
  try {
    if (editingId.value) await updateUser(editingId.value, payload)
    else await createUser(payload)
    ElMessage.success(editingId.value ? '账号已更新' : '账号已创建')
    dialogOpen.value = false
    await loadUsers(editingId.value ? page.value : 1)
    // 账号集合变化后，授权下拉也要跟着更新
    await loadGrantUserOptions()
  } catch {
    // request.js 已给出具体错误（用户名重复、角色约束等）
  } finally {
    saving.value = false
  }
}

async function loadGrants() {
  if (!grantSubjectId.value) {
    grants.value = []
    return
  }
  grantsLoading.value = true
  try {
    const data = await listSubjectPermissions(grantSubjectId.value)
    grants.value = Array.isArray(data) ? data : []
  } catch {
    grants.value = []
  } finally {
    grantsLoading.value = false
  }
}

async function grant() {
  if (!grantSubjectId.value) {
    ElMessage.warning('请先选择档案')
    return
  }
  if (!grantUserId.value) {
    ElMessage.warning('请选择要授权的账号')
    return
  }
  granting.value = true
  try {
    await grantSubjectPermission(grantSubjectId.value, {
      userId: grantUserId.value,
      permissionType: grantType.value
    })
    ElMessage.success('授权已保存')
    await loadGrants()
  } catch {
    // request.js 已提示（患者只能绑定一个档案、患者只能摘要读取等）
  } finally {
    granting.value = false
  }
}

async function revokeGrant(row) {
  try {
    await ElMessageBox.confirm(`确定撤销「${row.username}」的这条授权吗？`, '撤销授权', {
      confirmButtonText: '撤销',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await revokeSubjectPermission(grantSubjectId.value, row.id)
    ElMessage.success('授权已撤销')
    await loadGrants()
  } catch {
    // request.js 已提示
  }
}

onMounted(async () => {
  await loadUsers(1)
  await loadSubjects()
  await loadGrantUserOptions()
  await loadDoctorOptions()
})
</script>

<style scoped>
.users-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.users-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.filter__input {
  width: 200px;
}

.filter__select {
  width: 140px;
}

.filter__select--wide {
  width: 300px;
}

.filter__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--muted);
}

.table {
  width: 100%;
}

.role-tag {
  margin-right: 6px;
}

.is-muted {
  color: var(--soft);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.grant-form {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 16px 18px;
  border-top: 1px dashed var(--line);
}

.grant-form__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}

.form__wide {
  width: 100%;
}

.form__hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--soft);
}

.form__hint--warning {
  color: var(--warning, #b26a00);
}

.form__hint--block {
  margin: 0 0 18px 96px;
}

.wizard-steps {
  margin: 4px 0 22px;
}

.wizard-body {
  min-height: 300px;
}

.wizard-summary {
  margin-bottom: 14px;
}

.wizard-block {
  margin-top: 12px;
}

.doctor-preview {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 18px;
  width: 100%;
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-muted, #f7fafd);
}

.doctor-preview div {
  display: flex;
  gap: 8px;
  font-size: 12px;
  line-height: 1.8;
}

.doctor-preview span {
  color: var(--soft);
}

.doctor-preview strong {
  font-weight: 600;
  color: var(--ink-700);
}
</style>
