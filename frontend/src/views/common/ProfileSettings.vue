<template>
  <div class="profile-page">
    <PageHeader
      back
      title="个人设置"
      subtitle="维护你在报告、患者端和研究队列中展示的联系信息，并管理登录密码。"
    >
      <template #extra>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <div class="profile-grid">
      <DataCard title="个人资料" subtitle="显示名、科室和联系方式会出现在报告与患者可见页面。">
        <el-form :model="profileForm" label-width="96px" class="profile-form">
          <el-form-item label="用户名">
            <el-input :model-value="profile.username" disabled />
          </el-form-item>
          <el-form-item label="角色">
            <div class="role-tags">
              <el-tag v-for="role in userStore.roles" :key="role" size="small" effect="plain">
                {{ ROLE_LABELS[role] || role }}
              </el-tag>
            </div>
          </el-form-item>
          <el-form-item label="显示名">
            <el-input
              v-model="profileForm.displayName"
              maxlength="100"
              show-word-limit
              placeholder="例如：张医生 / 李研究员"
            />
          </el-form-item>
          <el-form-item label="科室">
            <el-input v-model="profileForm.department" maxlength="100" placeholder="例如：记忆门诊 / 神经内科" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="profileForm.phone" maxlength="50" placeholder="患者端会展示该联系电话" />
          </el-form-item>
          <el-form-item v-if="isDoctor" label="门诊时间">
            <el-input
              v-model="profileForm.officeHours"
              maxlength="200"
              placeholder="例如：周一/周三 上午"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button>
            <span class="form-hint">留空的联系方式不会展示给患者。</span>
          </el-form-item>
        </el-form>
      </DataCard>

      <DataCard title="修改密码" subtitle="修改成功后，其他设备上的登录会话会失效。">
        <el-form :model="passwordForm" label-width="96px" class="profile-form">
          <el-form-item label="当前密码">
            <el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 6 位" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="changing" @click="changePassword">更新密码</el-button>
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="安全提示"
          description="演示账号请勿在公共环境修改密码；修改后请使用新密码重新登录其他设备。"
        />
      </DataCard>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import { useUserStore } from '@/stores/user.js'
import { changeMyPassword, getMyProfile, updateMyProfile } from '@/api/auth.js'

const ROLE_LABELS = { ADMIN: '管理员', RESEARCHER: '研究员', DOCTOR: '医生', PATIENT: '患者' }

const userStore = useUserStore()
const isDoctor = computed(() => userStore.hasRole('DOCTOR'))
const loading = ref(false)
const saving = ref(false)
const changing = ref(false)
const profile = reactive({
  id: null,
  username: '',
  displayName: '',
  department: '',
  phone: '',
  officeHours: ''
})
const profileForm = reactive({ displayName: '', department: '', phone: '', officeHours: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })

function fillProfile(data) {
  profile.id = data?.id ?? null
  profile.username = data?.username ?? ''
  profile.displayName = data?.displayName ?? ''
  profile.department = data?.department ?? ''
  profile.phone = data?.phone ?? ''
  profile.officeHours = data?.officeHours ?? ''
  profileForm.displayName = profile.displayName
  profileForm.department = profile.department
  profileForm.phone = profile.phone
  profileForm.officeHours = profile.officeHours
}

async function load() {
  loading.value = true
  try {
    fillProfile(await getMyProfile())
  } catch (problem) {
    ElMessage.error(problem?.message || '个人资料加载失败')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  if (profileForm.displayName.length > 100) {
    ElMessage.warning('显示名不能超过 100 个字符')
    return
  }
  saving.value = true
  try {
    const payload = {
      displayName: profileForm.displayName,
      department: profileForm.department,
      phone: profileForm.phone
    }
    if (isDoctor.value) payload.officeHours = profileForm.officeHours
    fillProfile(await updateMyProfile(payload))
    ElMessage.success('个人资料已保存')
  } catch (problem) {
    ElMessage.error(problem?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  if (!passwordForm.currentPassword) {
    ElMessage.warning('请填写当前密码')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changing.value = true
  try {
    await changeMyPassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword
    })
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    ElMessage.success('密码已更新；其他设备需要重新登录')
  } catch (problem) {
    ElMessage.error(problem?.message || '密码修改失败')
  } finally {
    changing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.8fr);
  gap: 16px;
  align-items: start;
}

.profile-form {
  max-width: 560px;
}

.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--muted);
}

@media (max-width: 1000px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
