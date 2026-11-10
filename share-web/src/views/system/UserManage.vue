<template>
  <div class="page-card" v-loading="loading">
    <div class="page-header">
      <div>
        <h3 class="page-title">用户与权限</h3>
        <div class="page-subtitle">用户账号、角色权限与组织架构管理</div>
      </div>
      <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- 用户 -->
      <el-tab-pane label="用户管理" name="users">
        <el-form inline class="filter-bar" @submit.prevent>
          <el-form-item label="关键词">
            <el-input v-model="userQuery.keyword" placeholder="用户名 / 姓名" clearable @keyup.enter="searchUsers" />
          </el-form-item>
          <el-form-item label="部门">
            <el-select v-model="userQuery.deptId" clearable filterable placeholder="全部部门" style="width: 150px">
              <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="userQuery.roleCode" clearable placeholder="全部角色" style="width: 140px">
              <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="userQuery.status" clearable placeholder="全部" style="width: 110px">
              <el-option label="启用" :value="1" />
              <el-option label="停用" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="searchUsers">查询</el-button>
            <el-button :icon="RefreshLeft" @click="resetUsers">重置</el-button>
            <el-button type="primary" :icon="Plus" @click="openUser()">新增用户</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="users" size="small" stripe border empty-text="暂无用户">
          <el-table-column label="用户名" min-width="130">
            <template #default="{ row }">{{ pick(row, ['username', 'account'], '-') }}</template>
          </el-table-column>
          <el-table-column label="姓名" min-width="110">
            <template #default="{ row }">{{ pick(row, ['realName', 'name', 'nickname'], '-') }}</template>
          </el-table-column>
          <el-table-column label="角色" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ pick(row, ['roleName'], row.roleCode || '-') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="部门" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['deptName'], '-') }}</template>
          </el-table-column>
          <el-table-column label="手机号" width="140">
            <template #default="{ row }">{{ pick(row, ['phone', 'mobile'], '-') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-switch
                :model-value="isUserEnabled(row)"
                :loading="statusLoadingId === row.id"
                inline-prompt
                active-text="启用"
                inactive-text="停用"
                @change="(v) => toggleUser(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(pick(row, ['createTime', 'createdAt'])) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openUser(row)">编辑</el-button>
              <el-button link type="warning" @click="resetPassword(row)">重置口令</el-button>
              <el-button link type="danger" :disabled="row.username === 'admin'" @click="removeUser(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="userPage"
            v-model:page-size="userSize"
            background
            layout="total, sizes, prev, pager, next"
            :total="userTotal"
            :page-sizes="[10, 20, 50]"
            @current-change="loadUsers"
            @size-change="searchUsers"
          />
        </div>
      </el-tab-pane>

      <!-- 角色 -->
      <el-tab-pane label="角色权限" name="roles">
        <div class="flex-between mb-12">
          <span class="muted">角色决定菜单与接口访问权限，权限点由后端统一提供</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openRole()">新增角色</el-button>
        </div>
        <el-table :data="roles" size="small" stripe border empty-text="暂无角色">
          <el-table-column label="角色名称" min-width="150">
            <template #default="{ row }">{{ pick(row, ['roleName', 'name'], '-') }}</template>
          </el-table-column>
          <el-table-column label="角色编码" width="150">
            <template #default="{ row }">{{ pick(row, ['roleCode', 'code'], '-') }}</template>
          </el-table-column>
          <el-table-column label="权限数" width="100" align="center">
            <template #default="{ row }">{{ permissionCount(row) }}</template>
          </el-table-column>
          <el-table-column label="用户数" width="100" align="center">
            <template #default="{ row }">{{ pick(row, ['userCount'], '-') }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ pick(row, ['description', 'remark'], '-') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRole(row)">配置权限</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 组织 -->
      <el-tab-pane label="组织架构" name="depts">
        <div class="flex-between mb-12">
          <span class="muted">部门树用于数据归属与共享范围控制</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openDept()">新增部门</el-button>
        </div>
        <div class="dept-layout">
          <div class="tree-box">
            <el-tree
              :data="deptTree"
              node-key="id"
              :props="{ label: 'name', children: 'children' }"
              default-expand-all
              highlight-current
              :expand-on-click-node="false"
              @node-click="(d) => (selectedDept = d)"
            >
              <template #default="{ data }">
                <div class="flex-between" style="width: 100%">
                  <span>{{ data.name }}</span>
                  <span class="flex-gap">
                    <el-button link type="primary" :icon="Edit" @click.stop="openDept(data)" />
                    <el-button link type="danger" :icon="Delete" @click.stop="removeDept(data)" />
                  </span>
                </div>
              </template>
            </el-tree>
            <el-empty v-if="!deptTree.length" description="暂无部门数据" :image-size="70" />
          </div>
          <div class="dept-detail">
            <el-descriptions v-if="selectedDept" :column="1" border size="small">
              <el-descriptions-item label="部门名称">{{ pick(selectedDept, ['deptName', 'name'], '-') }}</el-descriptions-item>
              <el-descriptions-item label="部门编码">{{ pick(selectedDept, ['deptCode', 'code'], '-') }}</el-descriptions-item>
              <el-descriptions-item label="负责人">{{ pick(selectedDept, ['leader', 'leaderName'], '-') }}</el-descriptions-item>
              <el-descriptions-item label="联系电话">{{ pick(selectedDept, ['phone', 'telephone'], '-') }}</el-descriptions-item>
              <el-descriptions-item label="排序">{{ pick(selectedDept, ['sortNo', 'sort'], '-') }}</el-descriptions-item>
              <el-descriptions-item label="用户数">{{ pick(selectedDept, ['userCount'], '-') }}</el-descriptions-item>
            </el-descriptions>
            <el-empty v-else description="选择左侧部门查看详情" :image-size="70" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 用户编辑 -->
    <el-dialog v-model="userVisible" :title="userForm.id ? '编辑用户' : '新增用户'" width="600px" append-to-body destroy-on-close>
      <el-form ref="userRef" :model="userForm" :rules="userRules" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="userForm.username" :disabled="!!userForm.id" placeholder="登录账号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名" prop="realName">
              <el-input v-model="userForm.realName" placeholder="真实姓名" />
            </el-form-item>
          </el-col>
          <el-col v-if="!userForm.id" :span="12">
            <el-form-item label="初始口令" prop="password">
              <el-input v-model="userForm.password" type="password" show-password placeholder="默认 123456" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色" prop="roleCode">
              <el-select v-model="userForm.roleCode" style="width: 100%">
                <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="部门">
              <el-select v-model="userForm.deptId" clearable filterable placeholder="选择部门" style="width: 100%">
                <el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号">
              <el-input v-model="userForm.phone" placeholder="手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱">
              <el-input v-model="userForm.email" placeholder="邮箱" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-switch v-model="userForm.enabled" inline-prompt active-text="启用" inactive-text="停用" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="userVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色编辑 -->
    <el-dialog v-model="roleVisible" :title="roleForm.id ? '编辑角色权限' : '新增角色'" width="720px" top="6vh" append-to-body destroy-on-close>
      <el-form ref="roleRef" :model="roleForm" :rules="roleRules" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="角色名称" prop="name">
              <el-input v-model="roleForm.name" placeholder="如：数据提供方" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色编码" prop="code">
              <el-input v-model="roleForm.code" :disabled="!!roleForm.id" placeholder="如：PROVIDER" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="说明">
              <el-input v-model="roleForm.description" type="textarea" :rows="2" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider content-position="left">权限点</el-divider>
        <el-collapse v-if="permissionGroups.length">
          <el-collapse-item v-for="g in permissionGroups" :key="g.name" :title="`${g.name}（${g.items.length}）`" :name="g.name">
            <el-checkbox-group v-model="roleForm.permissions">
              <el-checkbox v-for="p in g.items" :key="p.code" :value="p.code" style="width: 220px">
                {{ p.name || p.code }}
              </el-checkbox>
            </el-checkbox-group>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else description="暂无权限点字典（/user/v1/permissions 未就绪）" :image-size="70" />
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 部门编辑 -->
    <el-dialog v-model="deptVisible" :title="deptForm.id ? '编辑部门' : '新增部门'" width="480px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="部门名称" required>
          <el-input v-model="deptForm.name" placeholder="如：财务部" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="deptForm.parentId"
            :data="deptTree"
            clearable
            check-strictly
            :props="{ label: 'name', value: 'id', children: 'children' }"
            placeholder="不选则为顶级部门"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="部门编码">
          <el-input v-model="deptForm.code" placeholder="如：TAX" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="deptForm.leader" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="deptForm.phone" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="deptForm.sort" :min="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deptVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDept">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, RefreshLeft, Edit, Delete } from '@element-plus/icons-vue'
import request from '@/api/request'
import { confirmBox, fmtDateTime, listOf, pageOf, pick, tagType } from '@/api/helpers'

const loading = ref(false)
const tab = ref('users')
const saving = ref(false)

const users = ref([])
const userPage = ref(1)
const userSize = ref(10)
const userTotal = ref(0)
const userQuery = reactive({ keyword: '', deptId: null, roleCode: '', status: '' })
const statusLoadingId = ref(null)

const roles = ref([])
const permissions = ref([])
const permissionGroups = ref([])

const deptTree = ref([])
const deptOptions = ref([])
const selectedDept = ref(null)

const userVisible = ref(false)
const userRef = ref()
const userForm = reactive(emptyUser())
const userRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const roleVisible = ref(false)
const roleRef = ref()
const roleForm = reactive({ id: null, name: '', code: '', description: '', permissions: [] })
const roleRules = {
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }]
}

const deptVisible = ref(false)
const deptForm = reactive({ id: null, name: '', parentId: null, code: '', leader: '', phone: '', sort: 0 })

function emptyUser() {
  return { id: null, username: '', realName: '', password: '123456', roleCode: '', deptId: null, phone: '', email: '', enabled: true }
}

function roleName(row) {
  const code = pick(row, ['roleCode', 'role'], '')
  const found = roles.value.find((r) => (r.code || r.roleCode) === code)
  return pick(row, ['roleName'], '') || found?.name || code || '-'
}

function permissionCount(row) {
  const p = row.permissionCodes || row.permissions
  return Array.isArray(p) ? p.length : pick(row, ['permissionCount'], 0)
}

function isUserEnabled(row) {
  if (typeof row.enabled === 'boolean') return row.enabled
  if (typeof row.status === 'boolean') return row.status
  return !['DISABLED', '0', 'FALSE'].includes(String(row.status ?? '').toUpperCase())
}

function deptNameById(id) {
  const found = deptOptions.value.find((d) => d.id === id)
  return found?.name || '-'
}

function flattenDepts(list, prefix = '') {
  const out = []
  list.forEach((d) => {
    out.push({ id: d.id, name: `${prefix}${d.name || d.deptName || ''}` })
    const children = d.children || d.childList || []
    if (children.length) out.push(...flattenDepts(children, `${prefix}　`))
  })
  return out
}

function buildPermissionGroups(list) {
  const groups = new Map()
  list.forEach((p) => {
    const code = typeof p === 'string' ? p : pick(p, ['code', 'permission', 'name'], '')
    if (!code) return
    const name = typeof p === 'string' ? code : pick(p, ['name', 'label', 'description'], code)
    const moduleName = typeof p === 'string' ? '' : pick(p, ['moduleName', 'module'], '')
    const group = moduleName || (code.includes(':') ? code.split(':')[0] : '其他')
    if (!groups.has(group)) groups.set(group, [])
    groups.get(group).push({ code, name })
  })
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await request.get('/user/v1/users', {
      params: {
        pageNum: userPage.value,
        pageSize: userSize.value,
        keyword: userQuery.keyword || undefined,
        deptId: userQuery.deptId || undefined,
        roleCode: userQuery.roleCode || undefined,
        status: userQuery.status === '' || userQuery.status === null ? undefined : userQuery.status
      }
    })
    users.value = listOf(res.data)
    userTotal.value = pageOf(res).total
  } catch (e) {
    users.value = []
    userTotal.value = 0
  } finally {
    loading.value = false
  }
}

function searchUsers() {
  userPage.value = 1
  loadUsers()
}

function resetUsers() {
  userQuery.keyword = ''
  userQuery.deptId = null
  userQuery.roleCode = ''
  userQuery.status = ''
  searchUsers()
}

async function loadRoles() {
  try {
    const res = await request.get('/user/v1/roles', { params: { pageNum: 1, pageSize: 100 } })
    roles.value = listOf(res.data)
  } catch (e) {
    roles.value = []
  }
}

async function loadPermissions() {
  try {
    const res = await request.get('/user/v1/permissions')
    permissions.value = listOf(res.data)
    permissionGroups.value = buildPermissionGroups(permissions.value)
  } catch (e) {
    permissions.value = []
    permissionGroups.value = []
  }
}

async function loadDepts() {
  try {
    const res = await request.get('/user/v1/depts')
    deptTree.value = normalizeDepts(listOf(res.data))
    deptOptions.value = flattenDepts(deptTree.value)
  } catch (e) {
    deptTree.value = []
    deptOptions.value = []
  }
}

function normalizeDepts(list) {
  return list.map((d) => ({
    ...d,
    name: d.name || d.deptName || `部门 #${d.id}`,
    children: Array.isArray(d.children) && d.children.length ? normalizeDepts(d.children) : undefined
  }))
}

function reloadAll() {
  loadUsers()
  loadRoles()
  loadPermissions()
  loadDepts()
}

function onTab(name) {
  if (name === 'users') loadUsers()
  if (name === 'roles') loadRoles()
  if (name === 'depts') loadDepts()
}

function openUser(row) {
  if (row) {
    Object.assign(userForm, {
      id: row.id,
      username: pick(row, ['username', 'account'], ''),
      realName: pick(row, ['realName', 'name', 'nickname'], ''),
      password: '',
      roleCode: pick(row, ['roleCode', 'role'], ''),
      deptId: row.deptId ?? null,
      phone: pick(row, ['phone', 'mobile'], ''),
      email: pick(row, ['email'], ''),
      enabled: isUserEnabled(row)
    })
  } else {
    Object.assign(userForm, emptyUser())
  }
  userVisible.value = true
}

async function saveUser() {
  try {
    await userRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    const payload = {
      username: userForm.username,
      realName: userForm.realName,
      roleCode: userForm.roleCode,
      deptId: userForm.deptId,
      phone: userForm.phone,
      email: userForm.email,
      status: userForm.enabled ? 1 : 0
    }
    if (userForm.password) payload.password = userForm.password
    if (userForm.id) await request.put(`/user/v1/users/${userForm.id}`, payload)
    else await request.post('/user/v1/users', payload)
    ElMessage.success('用户已保存')
    userVisible.value = false
    loadUsers()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleUser(row, enabled) {
  statusLoadingId.value = row.id
  try {
    await request.put(`/user/v1/users/${row.id}/status`, { status: enabled ? 1 : 0 })
    ElMessage.success(enabled ? '用户已启用' : '用户已停用')
    loadUsers()
  } catch (e) {
    // 拦截器已提示
  } finally {
    statusLoadingId.value = null
  }
}

async function resetPassword(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `为用户「${pick(row, ['realName', 'username'], '')}」设置新口令`,
      '重置口令',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputValue: '123456', inputPattern: /^.{6,}$/, inputErrorMessage: '口令至少 6 位' }
    )
    await request.put(`/user/v1/users/${row.id}/reset-password`, { password: value })
    ElMessage.success('口令已重置')
  } catch (e) {
    // 用户取消或接口错误
  }
}

async function removeUser(row) {
  const ok = await confirmBox(`确认删除用户「${pick(row, ['realName', 'username'], '')}」？`, '删除用户', 'error')
  if (!ok) return
  try {
    await request.delete(`/user/v1/users/${row.id}`)
    ElMessage.success('用户已删除')
    loadUsers()
  } catch (e) {
    // 拦截器已提示
  }
}

function openRole(row) {
  if (row) {
    const perms = row.permissionCodes || row.permissions || []
    Object.assign(roleForm, {
      id: row.id,
      name: pick(row, ['roleName', 'name'], ''),
      code: pick(row, ['roleCode', 'code'], ''),
      description: pick(row, ['description', 'remark'], ''),
      permissions: Array.isArray(perms)
        ? perms.map((p) => (typeof p === 'string' ? p : pick(p, ['code', 'permission'], ''))).filter(Boolean)
        : []
    })
  } else {
    Object.assign(roleForm, { id: null, name: '', code: '', description: '', permissions: [] })
  }
  roleVisible.value = true
}

async function saveRole() {
  try {
    await roleRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    const payload = {
      roleCode: roleForm.code,
      roleName: roleForm.name,
      description: roleForm.description,
      status: 1,
      sortNo: 0,
      permissionCodes: roleForm.permissions
    }
    if (roleForm.id) await request.put(`/user/v1/roles/${roleForm.id}`, payload)
    else await request.post('/user/v1/roles', payload)
    ElMessage.success('角色已保存')
    roleVisible.value = false
    loadRoles()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

function openDept(row) {
  if (row) {
    Object.assign(deptForm, {
      id: row.id,
      name: row.deptName || row.name || '',
      parentId: row.parentId ?? null,
      code: pick(row, ['deptCode', 'code'], ''),
      leader: pick(row, ['leader', 'leaderName'], ''),
      phone: pick(row, ['phone', 'telephone'], ''),
      sort: Number(pick(row, ['sortNo', 'sort'], 0)) || 0
    })
  } else {
    Object.assign(deptForm, { id: null, name: '', parentId: selectedDept.value?.id ?? null, code: '', leader: '', phone: '', sort: 0 })
  }
  deptVisible.value = true
}

async function saveDept() {
  if (!deptForm.name) {
    ElMessage.warning('请输入部门名称')
    return
  }
  try {
    const payload = {
      deptName: deptForm.name,
      deptCode: deptForm.code,
      parentId: deptForm.parentId,
      sortNo: deptForm.sort,
      leader: deptForm.leader,
      phone: deptForm.phone,
      status: 1
    }
    if (deptForm.id) await request.put(`/user/v1/depts/${deptForm.id}`, payload)
    else await request.post('/user/v1/depts', payload)
    ElMessage.success('部门已保存')
    deptVisible.value = false
    loadDepts()
  } catch (e) {
    // 拦截器已提示
  }
}

async function removeDept(row) {
  const ok = await confirmBox(`确认删除部门「${row.name}」？`, '删除部门', 'error')
  if (!ok) return
  try {
    await request.delete(`/user/v1/depts/${row.id}`)
    ElMessage.success('部门已删除')
    loadDepts()
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(reloadAll)
</script>

<style scoped>
.dept-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.dept-detail {
  min-height: 200px;
}

@media (max-width: 900px) {
  .dept-layout {
    grid-template-columns: 1fr;
  }
}
</style>
