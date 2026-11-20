<template>
  <div class="patient-list">
    <PageHeader back
      :title="doctorView ? (showLegacy ? '全部授权档案' : '我的患者') : '受试者档案'"
      subtitle="点击档案查看资料与评估记录。"
      :terms="doctorView ? ['quality', 'coreScales', 'archiveBinding'] : ['controlled', 'constructed', 'dataVersion', 'source']"
    >
      <template #extra>
        <ResearchScopeSelect v-if="!doctorView" :model-value="scope" @update:model-value="setScope" />
        <el-select
          v-model="statusFilter"
          class="status-filter"
          size="default"
          clearable
          placeholder="档案状态"
          @change="search"
        >
          <el-option label="在册" value="ACTIVE" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-input
          v-model="keyword"
          class="search"
          placeholder="按档案编号搜索（服务端）"
          clearable
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button v-if="canCreateArchive && (doctorView || scope === CONSTRUCTED_VALIDATION)" type="primary" :icon="DocumentAdd" @click="openCreateSubject">
          {{ doctorView ? '新建患者档案' : '新建测试档案' }}
        </el-button>
        <el-button v-if="doctorView" @click="router.push({ query: showLegacy ? {} : { tab: 'legacy' } })">
          {{ showLegacy ? '返回我的患者' : '查看全部档案' }}
        </el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <DataCard padding="none">
      <!-- 首次加载才用骨架屏；后续刷新保留表格，只叠一层遮罩，避免表格反复卸载重建 -->
      <div v-if="firstLoading" class="skeleton" aria-busy="true" aria-live="polite">
        <div v-for="row in 6" :key="row" class="skeleton__row">
          <el-skeleton-item variant="text" style="width: 14%" />
          <el-skeleton-item variant="text" style="width: 20%" />
          <el-skeleton-item variant="text" style="width: 10%" />
          <el-skeleton-item variant="text" style="width: 16%" />
          <el-skeleton-item variant="text" style="width: 12%" />
        </div>
      </div>

      <!-- 失败态：不是"空数据"，必须分开表达 -->
      <EmptyState
        v-else-if="error"
        :icon="WarningFilled"
        title="档案加载失败"
        :description="error.message || '请稍后重试；如果持续失败，请联系管理员确认授权。'"
        action-text="重新加载"
        @action="load"
      />

      <!-- 空态 -->
      <EmptyState
        v-else-if="items.length === 0"
        :icon="User"
        title="授权范围内还没有档案"
        :description="doctorView ? (showLegacy ? '可更改搜索条件或返回我的患者。' : '您负责的普通患者档案会出现在这里。') : scope === CONTROLLED_RESEARCH ? '已登记且授权的受控档案会出现在这里。' : '可新建测试档案，或查阅已有测试样例。'"
      />

      <template v-else>
        <div class="table-wrap">
          <el-table
            :data="items"
            class="table"
            row-key="id"
            highlight-current-row
            @row-click="openDrawer"
            @keydown.enter="onRowKeydown"
          >
            <el-table-column prop="subjectCode" label="档案编号" min-width="160" show-overflow-tooltip />
            <el-table-column prop="alias" label="别名" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="doctorView && showLegacy" label="资料来源" width="120">
              <template #default="{ row }">{{ subjectResearchScope(row) === CONSTRUCTED_VALIDATION ? '本机测试' : '研究资料' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <StatusTag :type="row.status" />
              </template>
            </el-table-column>
            <el-table-column v-if="!doctorView" prop="dataVersion" label="数据版本" min-width="140" show-overflow-tooltip />
            <el-table-column label="建档时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click.stop="openDrawer(row)">查看档案</el-button>
              </template>
            </el-table-column>

            <template #empty>
              <div class="table-empty">
                当前页没有匹配的档案（关键词只过滤本页，清空后可看整页）
              </div>
            </template>
          </el-table>

          <!-- 刷新遮罩：不卸载表格，滚动位置与选中行都不会丢 -->
          <div v-if="loading" class="table-mask" aria-live="polite">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>正在加载…</span>
          </div>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            background
            @current-change="load"
          />
        </div>
      </template>
    </DataCard>

    <!-- 抽屉：档案概览 + 风险评估（含因果图）+ 随访计划 + 历史访视 -->
    <el-drawer
      v-model="drawerOpen"
      :title="selected ? (doctorView ? '患者档案' : '受试者档案') : '档案概览'"
      direction="rtl"
      :size="drawerSize"
      destroy-on-close
      class="patient-drawer"
      :close-on-press-escape="true"
      @keydown.esc="drawerOpen = false"
    >
      <template v-if="selected">
        <div class="drawer__head">
          <StatusTag :type="selected.status" />
          <span class="drawer__code">{{ selected.subjectCode }}</span>
        </div>

        <el-tabs v-model="drawerTab" class="drawer-tabs">
          <el-tab-pane label="概览与操作" name="overview">
            <el-descriptions :column="2" border class="drawer__desc">
          <el-descriptions-item label="别名">{{ selected.alias }}</el-descriptions-item>
          <el-descriptions-item label="档案编号">{{ selected.subjectCode }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag :type="selected.status" />
          </el-descriptions-item>
          <el-descriptions-item v-if="!doctorView" label="数据版本">{{ selected.dataVersion }}</el-descriptions-item>
          <el-descriptions-item label="建档时间" :span="2">
            {{ formatTime(selected.createdAt) }}
          </el-descriptions-item>
        </el-descriptions>

            <section v-if="userStore.hasRole('DOCTOR') && canEditSubject" class="drawer__section onboarding">
              <h3>档案资料</h3>
              <p v-if="!timelineLoading">已保存 {{ visits.length }} 条访视。质控读取档案里的访视和特征。</p>
              <el-button type="primary" size="small" @click="openVisitRecord()">录入访视量表</el-button>
              <el-button size="small" @click="drawerTab = 'records'">查看已保存访视</el-button>
              <el-button size="small" @click="openFeatureRecord()">补录检验和影像结果</el-button>
            </section>

            <DoctorAssessmentRequest v-if="doctorView && canEditSubject" :key="selected.id"
              :subject-id="selected.id" :revision="assessmentRequestRevision" :can-check-quality="canQualityCheck"
              @visit="openVisitRecord()" @quality="qualityCheckOpen = true" @snapshot="openTestIntake"
              @scope="assessmentScope = $event" @results="drawerTab = 'research'" @saved-visit="drawerTab = 'records'" />
            <section v-else-if="doctorView && isCareArchive && subjectCapabilities" class="drawer__section">
              <h3>预测评估</h3>
              <p>提交评估申请和补录资料需要该档案的管理权限。</p>
            </section>

            <section v-if="userStore.hasRole('DOCTOR') && canEditSubject" class="drawer__section onboarding">
              <h3>患者账号开通</h3>
              <p v-if="accountStatusLoading" role="status">正在读取本档案的质控与开通状态…</p>
              <p v-else-if="accountStatusError" role="alert">{{ accountStatusError }}</p>
              <template v-else-if="accountStatus">
                <p>{{ onboardingMessage }}</p>
                <el-button v-if="accountStatus.canApply" type="primary" size="small"
                  :loading="accountRequestLoading" @click="applyForPatientAccount">
                  {{ accountStatus.resubmissionRequired ? '更新质控并重新提交申请' : '申请患者账号' }}
                </el-button>
                <el-button v-else-if="accountStatus.accountStatus !== 'ISSUED'" size="small" @click="loadAccountStatus">
                  刷新开通状态
                </el-button>
                <el-button v-if="accountStatus.qualityStatus !== 'ARCHIVED' && canQualityCheck" size="small"
                  @click="qualityCheckOpen = true">
                  {{ accountStatus.qualityStatus === 'NO_CHECK' ? '发起质控检查' : '重新质控检查' }}
                </el-button>
              </template>
              <el-button v-if="accountStatusError" size="small" @click="loadAccountStatus">重试</el-button>
            </section>

            <div class="drawer__section drawer__actions">
              <el-button
                v-if="canEditSubject"
                size="small"
                :icon="EditPen"
                @click="openEditSubject"
              >
                编辑档案
              </el-button>
              <el-button
                v-if="showLegacy && !doctorView"
                size="small"
                :icon="Share"
                :disabled="reportLibrary.length < 2"
                @click="graphDiffOpen = true"
              >
                图谱对比
              </el-button>
              <el-button
                v-if="canQualityCheck && !(userStore.hasRole('DOCTOR') && canEditSubject && accountStatus)"
                size="small"
                :icon="FirstAidKit"
                @click="qualityCheckOpen = true"
              >
                发起质控检查
              </el-button>
              <span v-if="showLegacy && !doctorView && reportLibrary.length < 2" class="drawer__actions-hint">
                至少两次评估才能对比图谱
              </span>
            </div>
            <div v-if="!doctorView" class="drawer__section">
              <DataProvenancePanel :subject-id="selected.id" />
            </div>

            <div v-if="!doctorView" class="drawer__section">
              <AssessmentPanel
                :subject-id="selected.id"
                :data-scope="reportScope"
                @submitted="onAssessmentSubmitted"
              />
            </div>

            <div class="drawer__section">
              <section class="followup followup--compact">
                <header class="followup__head">
                  <h3 class="followup__title">随访</h3>
                  <el-button size="small" type="primary" @click="followUpDialogOpen = true">
                    新建随访
                  </el-button>
                </header>
                <p class="followup__hint">{{ followUpSummaryText }}</p>
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="doctorView ? '评估结果' : '模型验证结果'" name="research">
            <div v-if="canOpenTestIntake" class="drawer__section">
              <el-button size="small" @click="openTestIntake('history')">查看测试输入记录</el-button>
            </div>
            <div class="research-head">
              <div>
                <strong>评估报告</strong>
                <p>评估完成后，可在这里查看报告和结果解读。</p>
              </div>
              <div class="research-head__actions">
                <el-button size="small" @click="openLatestGraph">查看评估贡献图</el-button>
                <el-button size="small" :icon="Refresh" :loading="researchLoading" @click="loadResearchArchive">刷新</el-button>
              </div>
            </div>

            <el-alert
              v-if="researchError"
              class="research-error"
              type="warning"
              :closable="false"
              show-icon
              :title="researchError"
            />

            <el-table
              v-loading="researchLoading"
              :data="researchArchive"
              size="small"
              empty-text="还没有评估结果"
            >
              <el-table-column prop="taskId" label="报告编号" width="88" />
              <el-table-column v-if="!doctorView" prop="snapshotId" label="快照" width="64" />
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <StatusTag :type="row.status" />
                </template>
              </el-table-column>
              <el-table-column label="阿尔茨海默病 24 个月估计" min-width="165">
                <template #default="{ row }">{{ researchCif(row.result?.prediction?.adCif24m) }}</template>
              </el-table-column>
              <el-table-column label="其他痴呆 24 个月估计" min-width="150">
                <template #default="{ row }">
                  {{ researchCif(row.result?.prediction?.otherDementiaCif24m) }}
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="140">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button
                    text
                    type="primary"
                    :disabled="!row.taskId"
                    @click="router.push(`/reports/research/${row.taskId}`)"
                  >
                    查看报告
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- Tab 2：全量报告库（所有历史评估报告集中管理） -->
          <el-tab-pane v-if="showLegacy" label="历史报告" name="reports">
            <div class="reports-head">
              <span class="reports-count">共 {{ reportLibrary.length }} 份报告</span>
              <el-radio-group v-model="reportSort" size="small">
                <el-radio-button value="timeDesc">最新优先</el-radio-button>
                <el-radio-button value="timeAsc">最早优先</el-radio-button>
                <el-radio-button value="riskDesc">风险高→低</el-radio-button>
                <el-radio-button value="riskAsc">风险低→高</el-radio-button>
              </el-radio-group>
              <el-button text size="small" :loading="timelineLoading" @click="loadTimeline">
                刷新
              </el-button>
            </div>

            <el-skeleton v-if="timelineLoading && reportLibrary.length === 0" :rows="4" animated />

            <EmptyState
              v-else-if="reportLibrary.length === 0"
              :icon="Document"
              title="还没有评估报告"
              description="在「概览与操作」里发起评估，完成后报告会自动汇总到这里。"
            />

            <div v-else class="report-cards">
              <article
                v-for="item in sortedReports"
                :key="item.assessmentId"
                class="report-card"
              >
                <div class="report-card__main">
                  <p class="report-card__time">{{ formatTime(item.createdAt) }}</p>
                  <p class="report-card__window">
                    预测窗口 {{ item.predictionWindowMonths ?? '—' }} 个月
                  </p>
                  <p class="report-card__score">{{ formatScore(item) }}</p>
                  <div class="report-card__badges">
                    <StatusTag :type="item.riskLevel || 'UNAVAILABLE'" />
                    <StatusTag
                      :type="item.reviewStatus"
                      :label="reviewStatusLabel(item.reviewStatus)"
                    />
                  </div>
                </div>
                <div class="report-card__actions">
                  <el-button size="small" @click="openPreview(item)">快速预览</el-button>
                  <el-button size="small" type="primary" @click="openReport(item)">
                    查看完整报告
                  </el-button>
                </div>
              </article>
            </div>
          </el-tab-pane>

          <!-- Tab 3：生命周期 -->
          <el-tab-pane label="档案记录" name="lifecycle">
            <div class="drawer__section timeline-section">
          <header class="timeline__head">
            <h3 class="timeline__title">患者 360° 总体评估记录</h3>
            <span class="timeline__count">
              共 {{ timeline.length }} 条 · 最新在前
            </span>
            <el-button text size="small" :loading="timelineLoading" @click="loadTimeline">
              刷新
            </el-button>
          </header>

          <el-skeleton v-if="timelineLoading && timeline.length === 0" :rows="4" animated />

          <EmptyState
            v-else-if="timeline.length === 0"
            :icon="Timer"
            title="还没有生命周期记录"
            description="建档、访视、评估、复核与随访事件会按时间汇总在这里。"
          />

          <el-timeline v-else class="timeline">
            <el-timeline-item
              v-for="event in timeline"
              :key="event.key"
              :timestamp="event.timeLabel"
              :color="event.color"
              :hollow="event.type !== 'ASSESSMENT'"
              placement="top"
              class="timeline__item"
              @click="selectEvent(event)"
            >
              <div class="timeline__card" :class="{ 'is-active': activeEvent?.key === event.key }">
                <div class="timeline__row">
                  <el-icon class="timeline__icon" :style="{ color: event.color }">
                    <component :is="event.icon" />
                  </el-icon>
                  <span class="timeline__type">{{ event.typeLabel }}</span>
                  <span class="timeline__summary">{{ event.summary }}</span>
                  <StatusTag
                    v-if="event.statusType"
                    :type="event.statusType"
                    :label="event.statusLabel"
                  />
                </div>
                <p v-if="event.detail" class="timeline__detail">{{ event.detail }}</p>
                <p v-if="event.hint" class="timeline__hint">{{ event.hint }}</p>
              </div>
            </el-timeline-item>
          </el-timeline>

          <p
            v-if="timeline.length === 1 && timeline[0].type === 'SUBJECT'"
            class="timeline__empty-hint"
          >
            目前只有建档记录；后续就诊、评估、复核与随访事件会按时间汇总在这里。
          </p>
        </div>

        <!-- 选中事件详情：评估展示图与解释，复核展示意见，访视/随访展示明细 -->
        <div v-if="activeEvent" class="drawer__section">
          <section class="event-detail">
            <header class="event-detail__head">
              <h3 class="event-detail__title">{{ activeEvent.typeLabel }}详情</h3>
              <span class="event-detail__time">{{ activeEvent.timeLabel }}</span>
              <el-button text size="small" @click="activeEvent = null">收起</el-button>
            </header>

            <template v-if="activeEvent.type === 'ASSESSMENT'">
              <el-skeleton v-if="assessmentDetailLoading" :rows="3" animated />
              <template v-else-if="assessmentDetail">
                <div class="event-detail__facts">
                  <span>风险概率 <strong>{{ formatScore(assessmentDetail) }}</strong></span>
                  <span>
                    等级
                    <StatusTag :type="assessmentDetail.agentResult?.riskLevel || 'UNAVAILABLE'" />
                  </span>
                  <span>模型 {{ assessmentDetail.agentResult?.modelVersion || '—' }}</span>
                  <span>质控 {{ assessmentDetail.agentResult?.qualityStatus || '—' }}</span>
                </div>
                <ul
                  v-if="assessmentDetail.agentResult?.explanations?.length"
                  class="event-detail__list"
                >
                  <li
                    v-for="(item, index) in assessmentDetail.agentResult.explanations"
                    :key="index"
                  >
                    <strong>{{ item.label || item.feature }}</strong>
                    {{ item.interpretation || item.message }}
                  </li>
                </ul>
                <CausalGraph
                  v-if="assessmentDetail.agentResult?.graph"
                  :graph="assessmentDetail.agentResult.graph"
                  :height="260"
                />
                <div class="event-detail__actions">
                  <el-button size="small" type="primary" @click="openReport(activeEvent)">
                    查看完整报告
                  </el-button>
                </div>
              </template>
              <EmptyState
                v-else
                :icon="WarningFilled"
                title="评估详情载入失败"
                description="请稍后重试，或直接查看完整报告。"
              >
                <template #default>
                  <el-button size="small" @click="openReport(activeEvent)">查看完整报告</el-button>
                </template>
              </EmptyState>
            </template>

            <template v-else-if="activeEvent.type === 'REVIEW'">
              <p class="event-detail__comment">
                {{ activeEvent.comment || '本次复核没有留下文字意见。' }}
              </p>
              <p class="event-detail__meta">
                复核状态：{{ activeEvent.statusLabel || '—' }} · 复核人：{{
                  activeEvent.reviewerName || '—'
                }}
              </p>
            </template>

            <template v-else-if="activeEvent.type === 'VISIT'">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="访视编号">
                  {{ activeEvent.visit?.visitCode || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="访视日期">
                  {{ activeEvent.visit?.visitDate || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="MMSE">
                  {{ activeEvent.visit?.mmse ?? '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="MoCA">
                  {{ activeEvent.visit?.moca ?? '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="CDR-SB">
                  {{ activeEvent.visit?.cdrSb ?? '—' }}
                </el-descriptions-item>
              </el-descriptions>
            </template>

            <template v-else-if="activeEvent.type === 'FOLLOWUP'">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="计划时间">
                  {{ formatTime(activeEvent.at) }}
                </el-descriptions-item>
                <el-descriptions-item label="状态">
                  <StatusTag :type="activeEvent.statusType" />
                </el-descriptions-item>
                <el-descriptions-item label="目的" :span="2">
                  {{ activeEvent.followUp?.purpose || '—' }}
                </el-descriptions-item>
              </el-descriptions>
            </template>

            <template v-else>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="档案编号">
                  {{ selected.subjectCode }}
                </el-descriptions-item>
                <el-descriptions-item label="别名">{{ selected.alias || '—' }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <StatusTag :type="selected.status" />
                </el-descriptions-item>
                <el-descriptions-item label="数据版本">
                  {{ selected.dataVersion || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="建档时间" :span="2">
                  {{ formatTime(selected.createdAt) }}
                </el-descriptions-item>
              </el-descriptions>
            </template>
          </section>
        </div>
          </el-tab-pane>

          <!-- Tab 4：就诊与随访记录 -->
          <el-tab-pane label="就诊与随访记录" name="records">
            <div v-if="canEditSubject" class="drawer__section drawer__actions">
              <el-button size="small" :icon="FirstAidKit" @click="openVisitRecord()">
                录入访视量表
              </el-button>
              <el-button size="small" :icon="DataAnalysis" @click="openFeatureRecord()">
                补录检验和影像结果
              </el-button>
              <el-button size="small" :icon="DocumentAdd" @click="observationDialogOpen = true">
                补录观测（含来源）
              </el-button>
            </div>

            <div class="drawer__section">
              <VisitHistory
                :show-model-inputs="doctorView && isCareArchive"
                :visits="visits"
                :loading="timelineLoading"
                :can-correct="canEditSubject"
                @correct="openCorrection"
              />
            </div>

            <section v-if="doctorView && canQualityCheck" class="drawer__section">
              <h3>已保存的检验和影像结果</h3>
              <p v-if="timelineLoading" role="status">正在读取结果…</p>
              <p v-else-if="archiveFeatures === null" role="alert">暂时无法读取结果。</p>
              <p v-else-if="!archiveFeatures.length">尚未保存检验或影像结果。</p>
              <el-table v-else :data="archiveFeatures" size="small" max-height="220">
                <el-table-column label="指标" min-width="150">
                  <template #default="{ row }">{{ featureFields[row.featureName]?.label || row.featureName }}</template>
                </el-table-column>
                <el-table-column label="结果" min-width="130">
                  <template #default="{ row }">
                    {{ row.featureValue == null ? '未记录' : `${row.featureValue} ${{ count: '个', normalized: '（归一化）' }[row.unit] || row.unit || ''}` }}
                  </template>
                </el-table-column>
                <el-table-column prop="recordedAt" label="日期" min-width="112" />
              </el-table>
            </section>

            <div class="drawer__section">
              <section class="followup">
            <header class="followup__head">
              <h3 class="followup__title">随访计划</h3>
              <el-button size="small" type="primary" @click="followUpDialogOpen = true">
                新建随访
              </el-button>
            </header>

            <el-skeleton v-if="followUpLoading" :rows="2" animated />

            <EmptyState
              v-else-if="followUps.length === 0"
              :icon="Timer"
              title="还没有随访安排"
              description="点击「新建随访」安排复诊时间与目的。"
            />

            <el-table v-else :data="followUps" size="small" class="followup__table" max-height="220">
              <el-table-column label="计划时间" width="150">
                <template #default="{ row }">{{ formatTime(row.scheduledAt) }}</template>
              </el-table-column>
              <el-table-column prop="purpose" label="目的" min-width="160" show-overflow-tooltip />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <StatusTag :type="row.status" />
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!-- 快速预览：报告库里的次级弹窗，展示因果图与关键解释 -->
    <el-dialog
      v-model="previewOpen"
      :title="previewItem ? `快速预览 · 评估 #${previewItem.assessmentId}` : '快速预览'"
      width="760px"
      destroy-on-close
      append-to-body
      class="report-preview"
    >
      <el-skeleton v-if="previewLoading" :rows="5" animated />
      <template v-else-if="previewDetail">
        <div class="preview-facts">
          <div><span>风险概率</span><strong>{{ formatScore(previewDetail) }}</strong></div>
          <div>
            <span>风险等级</span>
            <StatusTag :type="previewDetail.agentResult?.riskLevel || 'UNAVAILABLE'" />
          </div>
          <div>
            <span>预测窗口</span>
            {{ previewWindow ?? '—' }} 个月
          </div>
          <div>
            <span>复核状态</span>
            <StatusTag
              :type="previewDetail.reviewStatus"
              :label="reviewStatusLabel(previewDetail.reviewStatus)"
            />
          </div>
        </div>
        <ul
          v-if="previewDetail.agentResult?.explanations?.length"
          class="preview-list"
        >
          <li v-for="(item, index) in previewDetail.agentResult.explanations" :key="index">
            <strong>{{ item.label || item.feature }}</strong>
            {{ item.interpretation || item.message }}
          </li>
        </ul>
        <CausalGraph
          v-if="previewDetail.agentResult?.graph"
          :graph="previewDetail.agentResult.graph"
          :height="300"
        />
      </template>
      <EmptyState
        v-else
        :icon="WarningFilled"
        title="预览载入失败"
        description="可以直接打开完整报告查看。"
      />
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
        <el-button
          type="primary"
          :disabled="!previewItem"
          @click="previewOpen = false; openReport(previewItem)"
        >
          查看完整报告
        </el-button>
      </template>
    </el-dialog>

    <FollowUpDialog
      v-if="selected"
      v-model="followUpDialogOpen"
      :subject-id="selected.id"
      @created="onFollowUpCreated"
    />

    <!-- 新建 / 编辑档案 -->
    <SubjectFormDialog
      v-model="subjectFormOpen"
      :mode="subjectFormMode"
      :subject="selected"
      @saved="onSubjectSaved"
    />
    <DoctorArchiveIntake v-if="doctorView && archiveIntakeOpen" v-model="archiveIntakeOpen"
      @saved="load()" @view="openDrawer" />

    <!-- 补录访视 / 特征 / 观测 -->
    <VisitRecordDialog
      v-if="selected"
      v-model="visitDialogOpen"
      :subject-id="selected.id"
      :initial="visitInitial"
      :model-inputs-enabled="doctorView && isCareArchive"
      @saved="onRecordSaved"
    />
    <FeatureRecordDialog
      v-if="selected"
      v-model="featureDialogOpen"
      :subject-id="selected.id"
      :initial-feature="featureInitial"
      @saved="onRecordSaved"
    />
    <ObservationRecordDialog
      v-if="selected"
      v-model="observationDialogOpen"
      :subject-id="selected.id"
      @saved="onRecordSaved"
    />

    <!-- 追加访视更正：原版本保留，人工审计可追溯 -->
    <VisitCorrectionDialog
      v-if="selected"
      v-model="correctionOpen"
      :subject-id="selected.id"
      :visit="correctionVisit"
      :model-inputs-enabled="doctorView && isCareArchive"
      @saved="onCorrectionSaved"
    />

    <!-- 图谱对比 -->
    <GraphDiffDialog
      v-if="selected"
      v-model="graphDiffOpen"
      :subject-id="selected.id"
      :assessments="reportLibrary"
    />
    <DoctorIntakeDialog v-if="canOpenTestIntake"
      :key="selected.id" v-model="intakeOpen" :subject-id="selected.id" :initial-view="intakeView"
      @updated="assessmentRequestRevision++" @use-for-visit="prepareVisitFromSnapshot" />

    <!-- 发起质控检查（自动输入质量检查，与人工复核单是两套记录） -->
    <QualityCheckDialog
      v-if="selected"
      v-model="qualityCheckOpen"
      :subject-id="selected.id"
      :subject-label="selected.alias || selected.subjectCode || ''"
      :can-record-visit="canEditSubject"
      :can-record-feature="canEditSubject"
      @record-visit="openVisitRecord()"
      @record-feature="openFeatureRecord"
      @checked="onArchiveQualityChecked"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  DataAnalysis,
  Document,
  DocumentAdd,
  EditPen,
  FirstAidKit,
  Loading,
  Refresh,
  Search,
  Share,
  Timer,
  User,
  WarningFilled
} from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataCard from '@/components/common/DataCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import AssessmentPanel from '@/components/doctor/AssessmentPanel.vue'
import VisitHistory from '@/components/doctor/VisitHistory.vue'
import VisitCorrectionDialog from '@/components/doctor/VisitCorrectionDialog.vue'
import CausalGraph from '@/components/charts/CausalGraph.vue'
import FollowUpDialog from '@/components/followup/FollowUpDialog.vue'
import SubjectFormDialog from '@/components/subject/SubjectFormDialog.vue'
import DoctorArchiveIntake from '@/components/subject/DoctorArchiveIntake.vue'
import VisitRecordDialog from '@/components/subject/VisitRecordDialog.vue'
import FeatureRecordDialog from '@/components/subject/FeatureRecordDialog.vue'
import { featureFields } from '@/components/subject/featureFields.js'
import ObservationRecordDialog from '@/components/subject/ObservationRecordDialog.vue'
import DataProvenancePanel from '@/components/subject/DataProvenancePanel.vue'
import GraphDiffDialog from '@/components/subject/GraphDiffDialog.vue'
import QualityCheckDialog from '@/components/subject/QualityCheckDialog.vue'
import DoctorIntakeDialog from '@/components/assessment/DoctorIntakeDialog.vue'
import DoctorAssessmentRequest from '@/components/assessment/DoctorAssessmentRequest.vue'
import { getSubjectCapabilities, getSubjectDetail, listSubjects } from '@/api/subjects.js'
import { listFollowUps } from '@/api/followups.js'
import { listResearchReports } from '@/api/research.js'
import ResearchScopeSelect from '@/components/assessment/ResearchScopeSelect.vue'
import { CARE_WORKFLOW, CONSTRUCTED_VALIDATION, CONTROLLED_RESEARCH, researchScope, subjectResearchScope } from '@/utils/researchResults.js'
import { getPatientOnboardingStatus, onboardingQualityLabel, requestPatientAccount } from '@/api/patientAccountRequests.js'
import {
  getAssessment,
  getAssessmentReviews,
  getAssessmentTaskInput,
  listAssessments,
  listAssessmentTasks
} from '@/api/assessments.js'
import { useUserStore } from '@/stores/user.js'
import { useTableKeyboard } from '@/composables/useTableKeyboard.js'
import { useEscapeToClose } from '@/composables/useEscapeToClose.js'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const doctorView = computed(() => userStore.primaryRole === 'DOCTOR')
const showLegacy = computed(() => route.query.tab === 'legacy')
const scope = computed(() => researchScope(route.query.dataScope))
let researchSeq = 0

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(true)
const error = ref(null)
const keyword = ref('')
const statusFilter = ref('')

const drawerOpen = ref(false)
const selected = ref(null)
const isCareArchive = computed(() => selected.value?.dataVersion?.startsWith('care-'))
const reportScope = computed(() => subjectResearchScope(selected.value))
const visits = ref([])
const archiveFeatures = ref(null)
const followUps = ref([])
const followUpLoading = ref(false)
const followUpDialogOpen = ref(false)

// ---- 档案维护：新建 / 编辑 / 补录 / 图谱对比 ----
const subjectFormOpen = ref(false)
const archiveIntakeOpen = ref(false)
const subjectFormMode = ref('create')
const visitDialogOpen = ref(false)
const visitInitial = ref(null)
const featureDialogOpen = ref(false)
const featureInitial = ref('')
const observationDialogOpen = ref(false)
const graphDiffOpen = ref(false)
const qualityCheckOpen = ref(false)
const correctionOpen = ref(false)
const correctionVisit = ref(null)
const subjectCapabilities = ref(null)
let capabilitiesRequest = 0
let timelineRequest = 0

/**
 * 建档与新建/补录只对 ADMIN / RESEARCHER 开放（后端 SubjectAccessPolicy.canCreate）。
 * 编辑还允许持有 MANAGE 授权的医生，因此以 capabilities.manage 为准。
 */
const canManageSubject = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER']))
const canCreateArchive = computed(() => userStore.hasRole(['ADMIN', 'RESEARCHER', 'DOCTOR']))
const intakeOpen = ref(false)
const intakeView = ref('history')
const assessmentScope = ref(null)
const canOpenTestIntake = computed(() => doctorView.value && canEditSubject.value && selected.value
  && assessmentScope.value?.subjectId === selected.value.id
  && assessmentScope.value.dataScope === CONSTRUCTED_VALIDATION)
const assessmentRequestRevision = ref(0)
const accountRequestLoading = ref(false)
const accountStatusLoading = ref(false)
const accountStatusError = ref('')
const accountStatus = ref(null)
let accountStatusRequest = 0
const onboardingMessage = computed(() => {
  const current = accountStatus.value
  if (!current) return ''
  if (current.accountStatus === 'ISSUED') return '患者账号已与本档案绑定。'
  const quality = onboardingQualityLabel(current.qualityStatus)
  if (current.resubmissionRequired) return `${quality}。原申请须由申请医生更新后，管理员才能发放。`
  if (current.accountStatus === 'PENDING') return `${quality}。等待管理员核对患者与责任医生后发放。`
  return `${quality}。请核对本次访视资料，满足质控条件后可提交账号申请。`
})
async function loadAccountStatus() {
  const sequence = ++accountStatusRequest
  const subjectId = selected.value?.id
  accountStatus.value = null
  accountStatusError.value = ''
  accountStatusLoading.value = false
  if (!subjectId || !userStore.hasRole('DOCTOR') || !canEditSubject.value) return
  accountStatusLoading.value = true
  try {
    const status = await getPatientOnboardingStatus(subjectId)
    if (sequence === accountStatusRequest) accountStatus.value = status
  } catch (problem) {
    if (sequence === accountStatusRequest) accountStatusError.value = problem.message || '无法读取开通状态'
  } finally {
    if (sequence === accountStatusRequest) accountStatusLoading.value = false
  }
}
async function applyForPatientAccount() {
  const subjectId = selected.value?.id
  if (!subjectId) return
  accountRequestLoading.value = true
  try {
    await requestPatientAccount(subjectId)
    await loadAccountStatus()
    ElMessage.success('申请已提交，请管理员在账号管理中发放并绑定。')
  } catch {
    await loadAccountStatus()
  } finally {
    accountRequestLoading.value = false
  }
}
const canEditSubject = computed(
  () => canManageSubject.value || subjectCapabilities.value?.manage === true
)
/**
 * 发起质控检查要求 canReadRawScores（ADMIN / RESEARCHER，或医生持有 READ_RAW 授权）。
 * 能力接口失败时不显示按钮，避免点了才 403。
 */
const canQualityCheck = computed(
  () => canManageSubject.value || subjectCapabilities.value?.readRaw === true
)
watch([() => selected.value?.id, canEditSubject], loadAccountStatus)
watch([visitDialogOpen, featureDialogOpen, correctionOpen, observationDialogOpen], (next, previous) => {
  if (previous.some((open, index) => open && !next[index])) onArchiveQualityChecked()
})
watch(intakeOpen, (open, previous) => {
  if (!open && previous) assessmentRequestRevision.value++
})

function onArchiveQualityChecked() {
  assessmentRequestRevision.value++
  loadAccountStatus()
}

async function loadSubjectCapabilities(subjectId) {
  const sequence = ++capabilitiesRequest
  subjectCapabilities.value = null
  if (!subjectId) return
  try {
    const capabilities = await getSubjectCapabilities(subjectId)
    if (sequence === capabilitiesRequest) subjectCapabilities.value = capabilities
  } catch {
    // 能力探测失败时按最小权限处理（隐藏编辑入口）
  }
}

function openCreateSubject() {
  if (doctorView.value) {
    archiveIntakeOpen.value = true
    return
  }
  subjectFormMode.value = 'create'
  subjectFormOpen.value = true
}

function openEditSubject() {
  if (!selected.value) return
  subjectFormMode.value = 'edit'
  subjectFormOpen.value = true
}

/** 新建或编辑成功后刷新列表；编辑时同步抽屉里的档案信息。 */
async function onSubjectSaved({ mode, subject }) {
  if (mode === 'edit' && subject) {
    selected.value = { ...selected.value, ...subject }
  }
  await load()
  if (mode === 'create' && subject && doctorView.value) openDrawer(subject)
}

function openVisitRecord(initial = null) {
  visitInitial.value = initial
  drawerTab.value = 'records'
  visitDialogOpen.value = true
}

function openFeatureRecord(code = '') {
  featureInitial.value = code
  drawerTab.value = 'records'
  featureDialogOpen.value = true
}

function openTestIntake(view) {
  intakeView.value = view
  intakeOpen.value = true
}

function prepareVisitFromSnapshot(snapshot) {
  const visitDate = snapshot.indexDate
  if (visits.value.some(visit => visit.visitDate === visitDate)) {
    drawerTab.value = 'records'
    ElMessage.warning('该日期已有访视，请在访视记录中核对并更正。')
    return
  }
  const { mmse, cdr_sb: cdrSb } = snapshot.features
  const cdrValid = Number.isFinite(cdrSb) && Math.round(cdrSb * 10) / 10 === cdrSb
  if (!Number.isInteger(mmse) || !cdrValid) {
    ElMessage.warning('快照量表格式与访视不同，请根据原记录核对量表后填写。')
  }
  openVisitRecord({ visitDate, mmse: Number.isInteger(mmse) ? mmse : null, cdrSb: cdrValid ? cdrSb : null })
}

/** 补录成功：重新拉一次 360 数据（访视、随访、评估）与来源面板 */
function onRecordSaved() {
  loadTimeline()
}

function openCorrection(visit) {
  correctionVisit.value = visit
  correctionOpen.value = true
}

/** 更正成功：刷新访视列表与时间线，原版本仍可在版本历史中查看。 */
function onCorrectionSaved() {
  loadTimeline()
}

// ---- 患者 360° 时间线 ----
const timeline = ref([])
const timelineLoading = ref(false)
const activeEvent = ref(null)
const assessmentDetail = ref(null)
const assessmentDetailLoading = ref(false)
const isNarrow = ref(false)
const drawerSize = computed(() => (isNarrow.value ? '100%' : '760px'))

// ---- 档案库（Tabs） ----
const drawerTab = ref('overview')
const researchArchive = ref([])
const researchLoading = ref(false)
const researchError = ref('')
const reportLibrary = ref([])
const reportSort = ref('timeDesc')
const reportDetailMap = ref({})
const previewOpen = ref(false)
const previewItem = ref(null)
const previewDetail = ref(null)
const previewLoading = ref(false)
/** 预测窗口来自任务输入快照（AgentResult 不含该字段） */
const previewWindow = ref(null)
const reportWindowMap = ref({})

const RISK_RANK = { HIGH: 3, MEDIUM: 2, LOW: 1, UNAVAILABLE: 0 }

/** 报告库排序：时间或风险等级（同等级再按时间倒序）。 */
const sortedReports = computed(() => {
  const list = reportLibrary.value.slice()
  const time = (item) => {
    const value = new Date(item.createdAt).getTime()
    return Number.isNaN(value) ? 0 : value
  }
  switch (reportSort.value) {
    case 'timeAsc':
      return list.sort((a, b) => time(a) - time(b))
    case 'riskDesc':
      return list.sort(
        (a, b) =>
          (RISK_RANK[b.riskLevel] || 0) - (RISK_RANK[a.riskLevel] || 0) || time(b) - time(a)
      )
    case 'riskAsc':
      return list.sort(
        (a, b) =>
          (RISK_RANK[a.riskLevel] || 0) - (RISK_RANK[b.riskLevel] || 0) || time(b) - time(a)
      )
    default:
      return list.sort((a, b) => time(b) - time(a))
  }
})

const followUpSummaryText = computed(() => {
  if (followUpLoading.value) return '正在读取随访安排…'
  if (!followUps.value.length) return '还没有随访安排，点击「新建随访」安排复诊。'
  const next =
    followUps.value.find((item) => item.status === 'PLANNED') ||
    followUps.value[followUps.value.length - 1]
  return `共 ${followUps.value.length} 次随访；下一次：${formatTime(next?.scheduledAt)}${
    next?.purpose ? `（${next.purpose}）` : ''
  }`
})

/** 首次加载 = 还没有任何数据时的加载，只有这种状态才显示骨架屏。 */
const firstLoading = computed(() => loading.value && items.value.length === 0 && !error.value)

/**
 * 请求序号：快速连点刷新 / 翻页时，先发出去的请求可能后回来。
 * 只接受"最新一次请求"的响应，避免旧数据覆盖新数据（表现为列表回退或错乱）。
 */
let requestSeq = 0

/**
 * 防御性去重：每次都用后端返回的数组整体覆盖，而不是追加；
 * 同时按 id 去掉可能出现的重复行，保证 row-key 唯一。
 */
function dedupe(rows) {
  const seen = new Set()
  const unique = []
  for (const row of rows) {
    const key = row?.id ?? row?.subjectCode
    if (key === undefined || key === null) {
      unique.push(row)
      continue
    }
    if (seen.has(key)) continue
    seen.add(key)
    unique.push(row)
  }
  return unique
}

/** 关键词搜索走服务端：回到第 1 页再请求。 */
function search() {
  page.value = 1
  load()
}

function setScope(value) {
  keyword.value = ''
  router.replace({
    query: { ...route.query, subjectId: undefined, dataScope: value === CONTROLLED_RESEARCH ? undefined : value }
  })
}

watch([scope, showLegacy], search)

async function load() {  const seq = (requestSeq += 1)
  loading.value = true
  error.value = null
  try {
    // 后端 GET /api/subjects 已支持 keyword（按档案编号匹配），搜索走后端而不是当前页
    const data = await listSubjects({
      page: page.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim(),
      status: statusFilter.value,
      dataScope: doctorView.value && !showLegacy.value ? CARE_WORKFLOW : doctorView.value ? undefined : scope.value
    })
    if (seq !== requestSeq) return // 已有更新的请求，丢弃这次结果
    items.value = dedupe(Array.isArray(data?.items) ? data.items : [])
    total.value = Number(data?.total ?? 0)
  } catch (problem) {
    if (seq !== requestSeq) return
    // request.js 已经弹过全局提示，这里只记录状态用于页面内呈现
    error.value = problem ?? { message: '' }
    items.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function openDrawer(row) {
  if (selected.value?.id !== row?.id) {
    intakeOpen.value = false
    visits.value = []
    archiveFeatures.value = null
    timeline.value = []
    followUps.value = []
    reportLibrary.value = []
  }
  selected.value = row
  drawerOpen.value = true
  activeEvent.value = null
  assessmentDetail.value = null
  loadTimeline()
  loadSubjectCapabilities(row?.id)
  // 打开哪个档案就写到 URL：从报告/图谱返回时可以直接恢复抽屉
  if (row?.id) router.replace({ query: { ...route.query, subjectId: String(row.id) } })
}

/** 模型验证结果 Tab：按受试者读取研究任务归档，只读。 */
async function loadResearchArchive() {
  const id = selected.value?.id
  if (!id) return
  const sequence = ++researchSeq
  researchArchive.value = []
  researchLoading.value = true
  researchError.value = ''
  try {
    const page = await listResearchReports({
      subjectId: id, dataScope: doctorView.value ? undefined : reportScope.value, pageSize: 50
    })
    if (sequence === researchSeq) researchArchive.value = page?.items || []
  } catch (problem) {
    if (sequence === researchSeq) researchError.value = problem?.message || '评估报告加载失败，请稍后重试'
  } finally {
    if (sequence === researchSeq) researchLoading.value = false
  }
}

function openLatestGraph() {
  router.push({ name: 'GraphView', query: {
    dataScope: doctorView.value ? undefined : reportScope.value, subjectId: selected.value.id
  } })
}

function researchCif(value) {
  return typeof value === 'number' && Number.isFinite(value)
    ? `${(value * 100).toFixed(2)}%`
    : '—'
}

watch([drawerTab, () => selected.value?.id, reportScope], ([tab]) => {
  if (tab === 'research') loadResearchArchive()
})

// 关闭抽屉时清掉 URL 上的 subjectId
watch(drawerOpen, (open) => {
  if (!open) {
    researchSeq += 1
    capabilitiesRequest += 1
    timelineRequest += 1
    researchArchive.value = []
    researchError.value = ''
    drawerTab.value = 'overview'
  }
  if (open || !route.query.subjectId) return
  const query = { ...route.query }
  delete query.subjectId
  delete query.section
  router.replace({ query })
})

const { onRowKeydown } = useTableKeyboard(items, openDrawer)

useEscapeToClose(
  drawerOpen,
  () => {
    drawerOpen.value = false
  },
  // 抽屉里开着「新建随访」对话框或报告预览时，Esc 只关上层，不关底层档案
  () =>
    followUpDialogOpen.value ||
    previewOpen.value ||
    subjectFormOpen.value ||
    archiveIntakeOpen.value ||
    visitDialogOpen.value ||
    featureDialogOpen.value ||
    observationDialogOpen.value ||
    graphDiffOpen.value ||
    qualityCheckOpen.value
)

const TASK_STATUS = {
  PENDING: '排队中',
  RUNNING: '计算中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  BLOCKED: '被阻断'
}
const REVIEW_STATUS = {
  PENDING: '待复核',
  ACKNOWLEDGED: '已确认',
  NEEDS_DATA: '要求补充数据',
  REJECTED: '已驳回'
}
const RISK_LEVEL = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险', UNAVAILABLE: '不可用' }
const FOLLOWUP_STATUS = { PLANNED: '已计划', COMPLETED: '已完成', CANCELLED: '已取消' }

function taskStatusLabel(status) {
  return TASK_STATUS[status] || status || '—'
}
function reviewStatusLabel(status) {
  return REVIEW_STATUS[status] || status || '—'
}
function riskLabel(level) {
  return RISK_LEVEL[level] || level || '不可用'
}
function followUpStatusLabel(status) {
  return FOLLOWUP_STATUS[status] || status || '—'
}

function formatScore(item) {
  const value =
    typeof item?.riskScore === 'number'
      ? item.riskScore
      : typeof item?.agentResult?.riskScore === 'number'
        ? item.agentResult.riskScore
        : null
  return value === null ? '不可用' : `${(value * 100).toFixed(1)}%`
}

/**
 * 打开抽屉时并发拉取全生命周期数据：
 *  - GET /api/subjects/{id}（基础信息 + 访视）
 *  - GET /api/assessments?subjectId=（评估历史）
 *  - GET /api/assessment-tasks?subjectId=（任务状态，用于评估事件）
 *  - GET /api/follow-ups?subjectId=（随访记录，契约的按受试者查询）
 *  - 每个评估的 GET /api/assessments/{id}/reviews（复核意见，最多并发 20 条）
 * 合并后按时间倒序渲染；任一接口失败只影响对应类型的事件。
 */
async function loadTimeline() {
  const subjectId = selected.value?.id
  if (!subjectId) return
  const sequence = ++timelineRequest
  timelineLoading.value = true
  followUpLoading.value = true
  activeEvent.value = null
  assessmentDetail.value = null
  try {
    const [detailRes, assessRes, taskRes, followRes] = await Promise.allSettled([
      getSubjectDetail(subjectId),
      showLegacy.value ? listAssessments({ subjectId, pageSize: 100 }) : null,
      showLegacy.value ? listAssessmentTasks({ subjectId, pageSize: 100 }) : null,
      listFollowUps(subjectId)
    ])
    // 抽屉已经切到别人：丢弃过期响应
    if (sequence !== timelineRequest) return

    const detail = detailRes.status === 'fulfilled' ? detailRes.value : null
    visits.value = Array.isArray(detail?.visits) ? detail.visits : []
    archiveFeatures.value = Array.isArray(detail?.features) ? detail.features : null
    const followList =
      followRes.status === 'fulfilled' && Array.isArray(followRes.value) ? followRes.value : []
    followUps.value = followList
      .slice()
      .sort((a, b) => new Date(a.scheduledAt) - new Date(b.scheduledAt))

    const assessments =
      assessRes.status === 'fulfilled' && Array.isArray(assessRes.value?.items)
        ? assessRes.value.items
        : []
    // 报告库与生命周期共用同一份评估历史；窗口月份由详情异步补齐
    reportLibrary.value = assessments.map((assessment) => ({ ...assessment }))
    loadReportDetails(assessments, sequence)
    const tasks =
      taskRes.status === 'fulfilled' && Array.isArray(taskRes.value?.items)
        ? taskRes.value.items
        : []
    const taskById = new Map(tasks.map((task) => [task.taskId, task]))

    const events = []
    const subject = detail?.subject || selected.value

    if (subject?.createdAt) {
      events.push({
        type: 'SUBJECT',
        at: subject.createdAt,
        timeLabel: formatTime(subject.createdAt),
        color: '#909399',
        icon: DocumentAdd,
        typeLabel: '建档',
        summary: `建档 ${subject.subjectCode || ''}`.trim(),
        detail: `别名 ${subject.alias || '—'} · 数据版本 ${subject.dataVersion || '—'}`
      })
    }

    for (const visit of visits.value) {
      events.push({
        type: 'VISIT',
        at: visit.visitDate,
        timeLabel: formatTime(visit.visitDate),
        color: '#13c2c2',
        icon: FirstAidKit,
        typeLabel: '就诊',
        summary: `就诊 ${visit.visitCode || ''}`.trim(),
        detail: [
          visit.mmse !== null && visit.mmse !== undefined ? `MMSE ${visit.mmse}` : '',
          visit.moca !== null && visit.moca !== undefined ? `MoCA ${visit.moca}` : '',
          visit.cdrSb !== null && visit.cdrSb !== undefined ? `CDR-SB ${visit.cdrSb}` : ''
        ]
          .filter(Boolean)
          .join(' · '),
        visit
      })
    }

    for (const assessment of assessments) {
      const task = taskById.get(assessment.taskId)
      const level = assessment.riskLevel || 'UNAVAILABLE'
      events.push({
        type: 'ASSESSMENT',
        at: assessment.createdAt,
        timeLabel: formatTime(assessment.createdAt),
        color: level === 'HIGH' ? '#f56c6c' : '#409eff',
        icon: DataAnalysis,
        typeLabel: '评估',
        summary: `评估 #${assessment.assessmentId} · ${formatScore(assessment)} · ${riskLabel(level)}`,
        detail: `任务 #${assessment.taskId}${
          task?.status ? ` · 任务状态 ${taskStatusLabel(task.status)}` : ''
        } · 复核 ${reviewStatusLabel(assessment.reviewStatus)}`,
        statusType: level,
        assessmentId: assessment.assessmentId,
        raw: assessment
      })
    }

    // 复核历史：每个评估一次请求，最多 20 个，失败忽略
    const reviewTargets = assessments.slice(0, 20)
    const reviewResults = await Promise.allSettled(
      reviewTargets.map((assessment) => getAssessmentReviews(assessment.assessmentId))
    )
    if (sequence !== timelineRequest) return
    reviewResults.forEach((result, index) => {
      if (result.status !== 'fulfilled' || !Array.isArray(result.value)) return
      const assessment = reviewTargets[index]
      for (const review of result.value) {
        if (!review?.reviewedAt) continue
        events.push({
          type: 'REVIEW',
          at: review.reviewedAt,
          timeLabel: formatTime(review.reviewedAt),
          color: '#b37feb',
          icon: ChatDotRound,
          typeLabel: '复核',
          summary: `复核 #${review.assessmentId ?? assessment?.assessmentId}：${reviewStatusLabel(
            review.decision
          )}`,
          detail: review.comment ? String(review.comment).slice(0, 80) : '',
          comment: review.comment,
          statusLabel: reviewStatusLabel(review.decision),
          statusType: review.decision,
          reviewerName: review.reviewerName,
          assessmentId: review.assessmentId ?? assessment?.assessmentId
        })
      }
    })

    for (const followUp of followList) {
      const overdue =
        followUp.status === 'PLANNED' &&
        followUp.scheduledAt &&
        new Date(followUp.scheduledAt).getTime() < Date.now()
      events.push({
        type: 'FOLLOWUP',
        at: followUp.scheduledAt,
        timeLabel: formatTime(followUp.scheduledAt),
        color: followUp.status === 'CANCELLED' ? '#909399' : overdue ? '#f56c6c' : '#67c23a',
        icon: Timer,
        typeLabel: '随访',
        summary: `随访：${followUp.purpose || '未填写目的'}`,
        detail: overdue ? '已逾期' : '',
        statusType: followUp.status,
        statusLabel: followUpStatusLabel(followUp.status),
        followUp
      })
    }

    events.sort((a, b) => {
      const ta = new Date(a.at).getTime()
      const tb = new Date(b.at).getTime()
      if (Number.isNaN(ta) && Number.isNaN(tb)) return 0
      if (Number.isNaN(ta)) return 1
      if (Number.isNaN(tb)) return -1
      return tb - ta
    })
    timeline.value = events.map((event, index) => ({ ...event, key: `${event.type}-${index}` }))
  } catch {
    if (sequence === timelineRequest) {
      timeline.value = []
      visits.value = []
      archiveFeatures.value = null
      followUps.value = []
    }
  } finally {
    if (sequence === timelineRequest) {
      timelineLoading.value = false
      followUpLoading.value = false
    }
  }
}

function selectEvent(event) {
  activeEvent.value = event
  if (event.type === 'ASSESSMENT' && event.assessmentId) loadAssessmentDetail(event.assessmentId)
}

async function loadAssessmentDetail(assessmentId) {
  assessmentDetailLoading.value = true
  assessmentDetail.value = null
  try {
    const data = await getAssessment(assessmentId)
    // 用户可能已经点了别的评估
    if (activeEvent.value?.assessmentId === assessmentId) assessmentDetail.value = data
  } catch {
    assessmentDetail.value = null
  } finally {
    assessmentDetailLoading.value = false
  }
}

function openReport(event) {
  const id = event?.assessmentId
  if (id) router.push(`/reports/${id}`)
}

/** 逐个补齐报告库卡片需要的详情（含输入快照里的预测窗口），失败保持"—"。 */
async function loadReportDetails(assessments, sequence) {
  const targets = assessments.slice(0, 20)
  if (!targets.length) return
  const results = await Promise.allSettled(
    targets.map(async (assessment) => {
      const detail = await getAssessment(assessment.assessmentId)
      const input = await getAssessmentTaskInput(assessment.taskId).catch(() => null)
      return { detail, input }
    })
  )
  if (sequence !== timelineRequest) return
  results.forEach((result, index) => {
    if (result.status !== 'fulfilled' || !result.value) return
    const id = targets[index].assessmentId
    const { detail, input } = result.value
    if (detail) reportDetailMap.value[id] = detail
    const windowValue = input?.request?.predictionWindowMonths ?? null
    reportWindowMap.value[id] = windowValue
    const row = reportLibrary.value.find((item) => item.assessmentId === id)
    if (row) row.predictionWindowMonths = windowValue ?? undefined
  })
}

/** 快速预览：优先用缓存详情，避免重复请求。 */
async function openPreview(item) {
  if (!item?.assessmentId) return
  previewItem.value = item
  previewOpen.value = true
  previewLoading.value = true
  previewDetail.value = null
  previewWindow.value = null
  try {
    previewDetail.value =
      reportDetailMap.value[item.assessmentId] || (await getAssessment(item.assessmentId))
    if (previewDetail.value) reportDetailMap.value[item.assessmentId] = previewDetail.value
    if (reportWindowMap.value[item.assessmentId] !== undefined) {
      previewWindow.value = reportWindowMap.value[item.assessmentId]
    } else if (previewDetail.value?.taskId) {
      try {
        const input = await getAssessmentTaskInput(previewDetail.value.taskId)
        previewWindow.value = input?.request?.predictionWindowMonths ?? null
        reportWindowMap.value[item.assessmentId] = previewWindow.value
      } catch {
        previewWindow.value = null
      }
    }
  } catch {
    previewDetail.value = null
  } finally {
    previewLoading.value = false
  }
}

function onAssessmentSubmitted(task) {
  ElMessage.info(`已收到评估任务 #${task.taskId} 的回执`)
}

/** 新建随访成功：刷新随访列表与时间线。 */
function onFollowUpCreated() {
  loadTimeline()
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

function onResize() {
  isNarrow.value = window.innerWidth < 900
}

onMounted(async () => {
  isNarrow.value = window.innerWidth < 900
  window.addEventListener('resize', onResize, { passive: true })
  await load()
  // 深链恢复：从报告/图谱返回时带 ?subjectId=，直接打开对应档案抽屉
  const subjectId = Number(route.query.subjectId)
  if (!Number.isInteger(subjectId) || subjectId <= 0) return
  if (route.query.section === 'research') drawerTab.value = 'research'
  const row = items.value.find((item) => item.id === subjectId)
  if (row) {
    openDrawer(row)
    return
  }
  try {
    const detail = await getSubjectDetail(subjectId)
    if (detail?.subject) openDrawer(detail.subject)
  } catch {
    // 无权限或档案不存在：保持列表视图
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.patient-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.search {
  width: 260px;
}

.status-filter {
  width: 130px;
}

.skeleton {
  padding: 18px 20px;
}

.skeleton__row {
  display: flex;
  gap: 24px;
  align-items: center;
  height: 48px;
  border-bottom: 1px solid var(--line);
}

.skeleton__row:last-child {
  border-bottom: none;
}

.table {
  width: 100%;
}

/* 刷新遮罩：盖在表格上，不卸载表格 */
.table-wrap {
  position: relative;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-soft);
}

.toolbar__hint {
  font-size: 13px;
  color: var(--muted);
}

.toolbar :deep(.el-button) {
  margin-left: auto;
}

.toolbar :deep(.el-button + .el-button) {
  margin-left: 8px;
}

.followup {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
}

.followup__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.followup__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.followup__table {
  width: 100%;
}

.table-mask {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(1px);
  backdrop-filter: blur(1px);
  color: var(--muted);
  font-size: 13px;
}

.table-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--muted);
}

.table :deep(.el-table__row) {
  cursor: pointer;
}

.table :deep(.el-table__row:hover > td) {
  background: var(--brand-50);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid var(--line);
}

.drawer__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.drawer__code {
  font-family: var(--font-display);
  font-size: 14px;
  color: var(--muted);
}

.drawer__desc :deep(.el-descriptions__label) {
  width: 96px;
  color: var(--muted);
}

.drawer__section {
  margin-top: 16px;
}

.drawer__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.drawer__actions-hint {
  font-size: 12px;
  color: var(--soft);
}

/* ---- 患者 360° 时间线 ---- */
.timeline-section {
  padding-top: 4px;
}

.timeline__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.timeline__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.timeline__count {
  font-size: 12px;
  color: var(--muted);
}

.timeline__head .el-button {
  margin-left: auto;
}

.timeline {
  padding-left: 4px;
}

.timeline__item {
  cursor: pointer;
}

.timeline__card {
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: #fff;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.timeline__card:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-card);
}

.timeline__card.is-active {
  border-color: var(--brand-600);
  box-shadow: 0 0 0 2px var(--brand-50);
}

.timeline__row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.timeline__icon {
  font-size: 16px;
}

.timeline__type {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-700);
}

.timeline__summary {
  font-size: 13px;
  color: var(--ink);
}

.timeline__detail {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}

.timeline__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--danger);
}

.timeline__empty-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

/* ---- 选中事件详情 ---- */
.event-detail {
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--bg-soft);
}

.event-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.event-detail__title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}

.event-detail__time {
  font-size: 12px;
  color: var(--muted);
}

.event-detail__head .el-button {
  margin-left: auto;
}

.event-detail__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--ink-700);
}

.event-detail__list {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700);
}

.event-detail__comment {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--ink);
  white-space: pre-wrap;
}

.event-detail__meta {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.event-detail__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

@media (max-width: 900px) {
  .timeline__head .el-button {
    margin-left: 0;
  }
}

/* ---- 档案库 Tabs ---- */
.drawer-tabs :deep(.el-tabs__content) {
  padding-top: 4px;
}

.drawer-tabs :deep(.el-tab-pane) {
  padding-bottom: 4px;
}

.followup--compact {
  padding: 14px 16px;
}

.followup__hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.research-head__actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.research-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 4px 0 14px;
}

.research-head strong {
  display: block;
  font-size: 14px;
  color: var(--ink-700);
}

.research-head p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
}

.research-head .el-button {
  flex: none;
}

.research-error {
  margin-bottom: 12px;
}

.reports-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 4px 0 14px;
}

.reports-count {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}

.reports-head .el-button {
  margin-left: auto;
}

.report-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.report-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: #fff;
}

.report-card__main {
  min-width: 0;
}

.report-card__time {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}

.report-card__window {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--soft);
}

.report-card__score {
  margin: 6px 0;
  font-family: var(--font-display);
  font-size: 30px;
  line-height: 1.1;
  font-weight: 700;
  color: var(--brand-700);
  font-variant-numeric: tabular-nums;
}

.report-card__badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.report-card__actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.report-card__actions .el-button + .el-button {
  margin-left: 0;
}

.preview-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--ink-700);
}

.preview-facts span {
  display: block;
  margin-bottom: 2px;
  font-size: 11px;
  color: var(--soft);
}

.preview-facts strong {
  font-family: var(--font-display);
  font-size: 22px;
  color: var(--brand-700);
}

.preview-list {
  margin: 0 0 12px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700);
}

@media (max-width: 900px) {
  .report-card {
    flex-direction: column;
    align-items: stretch;
  }

  .report-card__actions {
    flex-direction: row;
    justify-content: flex-end;
  }

  .reports-head .el-button {
    margin-left: 0;
  }
}
</style>
