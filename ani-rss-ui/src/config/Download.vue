<template>
  <el-form @submit.prevent label-width="auto"
           class="full-width">
    <el-form-item label="下载工具">
      <el-select v-model:model-value="props.config.downloadToolType">
        <el-option v-for="item in downloadSelect"
                   :key="item"
                   :label="item"
                   :value="item"/>
      </el-select>
    </el-form-item>
    <el-form-item label="地址">
      <el-input v-model:model-value="props.config.downloadToolHost" placeholder="http://192.168.1.x:8080"/>
    </el-form-item>
    <el-form-item v-if="props.config.downloadToolType === 'Aria2'" label="RPC 密钥">
      <el-input v-model:model-value="props.config.downloadToolPassword" placeholder="" show-password>
        <template #prefix>
          <el-icon class="el-input__icon">
            <Key/>
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
    <template v-else-if="props.config.downloadToolType === 'OpenList'">
      <el-form-item label="Token">
        <el-input v-model:model-value="props.config.downloadToolPassword" placeholder="OpenList-xxxxxx" show-password>
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
        <br/>
        <el-text class="mx-1" size="small">
          请设置好 <strong>保存位置</strong> 才能通过测试<br/>
          请在 OpenList -> 设置-> 其他 -> 配置临时目录<br/>
          支持离线下载到 115、PikPak、迅雷云盘
        </el-text>
      </el-form-item>
      <el-form-item label="Driver">
        <el-select v-model="props.config['provider']" class="width-150">
          <el-option v-for="it in offlineList" :key="it.label" :label="it.label" :value="it.value"/>
        </el-select>
      </el-form-item>
      <el-form-item label="重试次数">
        <div>
          <el-input-number v-model="props.config['alistDownloadRetryNumber']" :min="-1"/>
          <br>
          <el-text class="mx-1" size="small">
            设置为 -1 将一直进行重试
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="离线超时">
        <el-input-number v-model:model-value="props.config['alistDownloadTimeout']" :min="1">
          <template #suffix>
            <span>分钟</span>
          </template>
        </el-input-number>
      </el-form-item>
    </template>
    <template v-else-if="props.config.downloadToolType === 'Pan115'">
      <el-form-item label="登录状态">
        <div>
          <el-tag v-if="pan115LoggedIn" type="success">已登录 115 网盘</el-tag>
          <el-tag v-else type="info">未登录</el-tag>
          <el-button text bg size="small" style="margin-left:8px" @click="refreshPan115Status" :loading="pan115StatusLoading">
            刷新状态
          </el-button>
        </div>
      </el-form-item>
      <el-form-item label="扫码登录">
        <div class="pan115-login">
          <el-button @click="getPan115QrCode" :loading="pan115QrLoading" type="primary">
            获取登录二维码
          </el-button>
          <div v-if="pan115QrUrl" class="pan115-qrcode-container">
            <img :src="pan115QrUrl" class="pan115-qrcode-img" alt="115 QR Code"/>
            <div class="pan115-status">
              <el-text v-if="pan115Status === 'waiting'" type="info">
                请使用 115 手机 APP 扫码登录
              </el-text>
              <el-text v-else-if="pan115Status === 'scanned'" type="warning">
                已扫码，请在手机上确认
              </el-text>
              <el-text v-else-if="pan115Status === 'confirmed' && pan115LoggedIn" type="success">
                登录成功！凭证已保存
              </el-text>
              <el-text v-else-if="pan115Status === 'confirmed' && !pan115LoggedIn" type="danger">
                已确认登录，但获取凭证失败，请重试
              </el-text>
              <el-text v-else-if="pan115Status === 'expired'" type="danger">
                二维码已过期，请重新获取
              </el-text>
              <el-text v-else-if="pan115Status === 'canceled'" type="danger">
                已取消登录
              </el-text>
            </div>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="下载模式">
        <el-select v-model="props.config.pan115DownloadMode" class="width-150">
          <el-option label="仅云端" value="cloud_only"/>
          <el-option label="仅本地" value="local_only"/>
          <el-option label="混合模式" value="hybrid"/>
        </el-select>
        <div>
          <el-text class="mx-1" size="small">
            <strong>仅云端</strong>: 文件保存在 115 云端，不下载到本地<br/>
            <strong>仅本地</strong>: 离线下载完成后自动下载到本地<br/>
            <strong>混合模式</strong>: 根据默认设置决定
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="默认本地下载" v-if="props.config.pan115DownloadMode === 'hybrid'">
        <el-switch v-model="props.config.pan115DefaultToLocal"/>
        <div>
          <el-text class="mx-1" size="small">
            混合模式下默认是否下载到本地
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="请求间隔">
        <el-input-number v-model="props.config.pan115MinRequestInterval" :min="100" :max="5000" :step="100">
          <template #suffix>
            <span>毫秒</span>
          </template>
        </el-input-number>
        <div>
          <el-text class="mx-1" size="small">
            每次 API 请求的最小间隔，避免触发 115 的限速
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="离线下载超时">
        <el-input-number v-model="props.config.pan115OfflineTimeout" :min="1" :max="180">
          <template #suffix>
            <span>分钟</span>
          </template>
        </el-input-number>
      </el-form-item>
      <el-form-item label="下载分块大小" v-if="props.config.pan115DownloadMode !== 'cloud_only'">
        <el-input-number v-model="props.config.pan115ChunkSizeMb" :min="1" :max="16">
          <template #suffix>
            <span>MB</span>
          </template>
        </el-input-number>
        <div>
          <el-text class="mx-1" size="small">
            本地下载时的分块大小
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="最大重试次数" v-if="props.config.pan115DownloadMode !== 'cloud_only'">
        <el-input-number v-model="props.config.pan115MaxRetries" :min="1" :max="10"/>
        <div>
          <el-text class="mx-1" size="small">
            下载失败时的最大重试次数
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="带宽限制" v-if="props.config.pan115DownloadMode !== 'cloud_only'">
        <el-input-number v-model="props.config.pan115BandwidthLimit" :min="0" :step="100">
          <template #suffix>
            <span>KB/s</span>
          </template>
        </el-input-number>
        <div>
          <el-text class="mx-1" size="small">
            本地下载带宽限制，0 表示无限制
          </el-text>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button @click="pan115Logout" type="danger" plain>
          退出登录
        </el-button>
      </el-form-item>
    </template>
    <template v-else>
      <el-form-item label="用户名">
        <el-input v-model:model-value="props.config.downloadToolUsername" placeholder="username"
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <User/>
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model:model-value="props.config.downloadToolPassword" placeholder="password" show-password
                  autocomplete="new-password">
          <template #prefix>
            <el-icon class="el-input__icon">
              <Key/>
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
    </template>
    <el-form-item>
      <div class="download-test-button">
        <el-button @click="downloadLoginTest" bg text :loading="downloadLoginTestLoading" icon="Odometer">测试
        </el-button>
      </div>
    </el-form-item>
    <el-form-item label="保存位置">
      <div class="full-width">
        <el-input v-model:model-value="props.config['downloadPathTemplate']"/>
        <el-alert
            v-if="!testPathTemplate(props.config['downloadPathTemplate'])"
            class="download-alert"
            type="warning"
            show-icon
            :closable="false"
        >
          <template #title>
            你的 保存位置 并未按照模版填写, 可能会遇到下载位置错误
          </template>
        </el-alert>
      </div>
    </el-form-item>
    <el-form-item label="剧场版保存位置">
      <div class="full-width">
        <el-input v-model:model-value="props.config['ovaDownloadPathTemplate']"/>
        <el-alert
            v-if="!testPathTemplate(props.config['ovaDownloadPathTemplate'])"
            class="download-alert"
            type="warning"
            show-icon
            :closable="false"
        >
          <template #title>
            你的 剧场版保存位置 并未按照模版填写, 可能会遇到下载位置错误
          </template>
        </el-alert>
      </div>
    </el-form-item>
    <el-form-item label="自动删除">
      <div>
        <el-switch v-model:model-value="props.config.delete"/>
        <br>
        <el-text class="mx-1" size="small">
          自动删除已完成的任务
          <br>
          如果同时开启了 <strong>备用rss功能</strong> 将会自动删除对应洗版视频, 以实现 <strong>主rss</strong> 的替换
        </el-text>
        <br>
        <el-checkbox v-model:model-value="props.config.awaitStalledUP"
                     :disabled="!props.config.delete"
                     label="等待做种完毕"/>
        <br>
        <el-checkbox v-model:model-value="props.config.deleteStandbyRSSOnly"
                     :disabled="!props.config.delete"
                     label="仅在主RSS更新后删除备用RSS"/>
        <br>
        <el-text class="mx-1" size="small">
          <strong>主RSS</strong> 将 <span class="download-danger-text">不会自动删除</span>，仅在其更新后删除对应备用RSS的任务与文件
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="失败重试次数">
      <el-input-number v-model:model-value="props.config['downloadRetry']" :max="100" :min="3"/>
    </el-form-item>
    <el-form-item label="同时下载限制">
      <div>
        <el-input-number v-model:model-value="props.config.downloadCount" :min="0"/>
        <div>
          设置为时 0 不做限制
        </div>
      </div>
    </el-form-item>
    <el-form-item label="延迟下载">
      <el-input-number v-model:model-value="props.config.delayedDownload" :min="0">
        <template #suffix>
          <span>分钟</span>
        </template>
      </el-input-number>
    </el-form-item>
    <el-form-item label="优先保留">
      <div class="full-width">
        <el-switch v-model:model-value="props.config.priorityKeywordsEnable"/>
        <div>
          <el-text class="mx-1" size="small">
            启用多文件种子的文件优先保留过滤
          </el-text>
        </div>
        <div v-if="props.config.priorityKeywordsEnable">
          <PrioKeys
              v-model:keywords="props.config.priorityKeywords"
              :import-global="false"
              :show-text="true"
          />
        </div>
      </div>
    </el-form-item>
    <el-form-item label="自定义标签">
      <custom-tags :config="props.config"/>
    </el-form-item>
    <el-collapse v-model="activeName">
      <el-collapse-item name="qBittorrent" title="qBittorrent 设置">
        <QBittorrent v-if="activeName.indexOf('qBittorrent') > -1" :config="props.config"/>
      </el-collapse-item>
    </el-collapse>
  </el-form>
</template>

<script setup>
import {ref, onMounted, onUnmounted, watch} from "vue";
import {ElMessage, ElText, ElTag} from "element-plus";
import {Key, User} from "@element-plus/icons-vue";
import QBittorrent from "@/config/download/qBittorrent.vue";
import PrioKeys from "@/config/PrioKeys.vue";
import CustomTags from "@/config/CustomTags.vue";
import * as http from "@/js/http.js";
import api from "@/js/api.js";

// Pan115 QR Code Login
const pan115QrLoading = ref(false)
const pan115QrUrl = ref('')
const pan115Status = ref('')
const pan115QrData = ref(null)
const pan115LoggedIn = ref(false)
const pan115StatusLoading = ref(false)
let pan115PollInterval = null

const refreshPan115Status = () => {
  pan115StatusLoading.value = true
  return api.post('api/pan115/status')
    .then(res => {
      const d = res.data || {}
      // 同时要求凭证存在且会话有效才算真正登录成功
      pan115LoggedIn.value = Boolean(d.hasCredentials && d.sessionValid)
      props.config.pan115Enabled = Boolean(d.enabled || d.hasCredentials)
      return d
    })
    .catch(() => {
      pan115LoggedIn.value = false
    })
    .finally(() => {
      pan115StatusLoading.value = false
    })
}

const getPan115QrCode = () => {
  pan115QrLoading.value = true
  pan115QrUrl.value = ''
  pan115Status.value = 'waiting'

  api.post('api/pan115/qrcode')
    .then(res => {
      const data = res.data || {}
      pan115QrData.value = data
      pan115QrUrl.value = data.qrcodeUrl
      // Start polling for status
      startPan115Polling()
    })
    .catch(err => {
      ElMessage.error('获取二维码失败: ' + (err.message || 'Unknown error'))
    })
    .finally(() => {
      pan115QrLoading.value = false
    })
}

const startPan115Polling = () => {
  // Clear any existing interval
  if (pan115PollInterval) {
    clearInterval(pan115PollInterval)
  }
  
  // Poll every 3 seconds
  pan115PollInterval = setInterval(() => {
    if (!pan115QrData.value) return
    const {uid, time, sign} = pan115QrData.value
    api.post(`api/pan115/qrcode/status?uid=${encodeURIComponent(uid)}&time=${encodeURIComponent(time)}&sign=${encodeURIComponent(sign)}`)
      .then(res => {
        const data = res.data || {}
        pan115Status.value = data.status

        if (data.status === 'confirmed') {
          clearInterval(pan115PollInterval)
          pan115PollInterval = null
          if (data.success) {
            ElMessage.success('115 登录成功，凭证已保存')
          } else {
            ElMessage.error('115 登录未完成: ' + (data.error || '获取凭证失败'))
          }
          refreshPan115Status()
        } else if (data.status === 'expired' || data.status === 'canceled') {
          clearInterval(pan115PollInterval)
          pan115PollInterval = null
          ElMessage.warning(data.status === 'expired' ? '二维码已过期' : '已取消登录')
        }
      })
      .catch(() => {
        // Ignore polling errors
      })
  }, 3000)
}

const pan115Logout = () => {
  api.post('api/pan115/logout')
    .then(() => {
      ElMessage.success('已退出 115 登录')
      pan115QrUrl.value = ''
      pan115Status.value = ''
      pan115QrData.value = null
      pan115LoggedIn.value = false
    })
    .catch(err => {
      ElMessage.error('退出失败: ' + (err.message || 'Unknown error'))
    })
}

onMounted(() => {
  if (props.config.downloadToolType === 'Pan115') {
    refreshPan115Status()
  }
})

watch(() => props.config.downloadToolType, (t) => {
  if (t === 'Pan115') {
    refreshPan115Status()
  }
})

// Clean up interval on component unmount
onUnmounted(() => {
  if (pan115PollInterval) {
    clearInterval(pan115PollInterval)
  }
})

const downloadSelect = ref([
  'qBittorrent',
  'Transmission',
  'Aria2',
  'OpenList',
  'Pan115'
])

const offlineList = ref([
  {
    label: '115 网盘',
    value: '115 Cloud'
  },
  {
    label: '115 开放平台',
    value: '115 Open'
  },
  {
    label: '123 网盘',
    value: '123Pan'
  },
  {
    label: '123 开放平台',
    value: '123 Open'
  },
  {
    label: '迅雷',
    value: 'Thunder'
  },
  {
    label: 'PikPak',
    value: 'PikPak'
  }
])

const downloadLoginTestLoading = ref(false)
const downloadLoginTest = () => {
  downloadLoginTestLoading.value = true
  http.downloadLoginTest(props.config)
      .then(res => {
        ElMessage.success(res.message)
      })
      .finally(() => {
        downloadLoginTestLoading.value = false
      })
}

let testPathTemplate = (path) => {
  return new RegExp('\\$\{[A-z]+\}').test(path);
}

let activeName = ref([])

let props = defineProps(['config'])
</script>

<style scoped>
.download-test-button {
  display: flex;
  width: 100%;
  justify-content: end;
}

.download-alert {
  margin-top: 8px;
}

.download-danger-text {
  color: red;
}

.pan115-login {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pan115-qrcode-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background-color: #f5f7fa;
}

.pan115-qrcode-img {
  width: 200px;
  height: 200px;
  border-radius: 4px;
}

.pan115-status {
  margin-top: 8px;
}
</style>
