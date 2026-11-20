<script setup lang="ts">
import { toRefs } from 'vue'
import type { CurlPreviewDrawerModel } from '../../ui/curlPreviewModel'
import { Copy } from '@lucide/vue'
import type { ExecutableRequest } from '../../domain/apiExecution'
const props = defineProps<{ model: CurlPreviewDrawerModel }>()
const { curlVisible, copyCurl, executableRequest, curlText } = toRefs(props.model)

</script>

<template>
<el-drawer v-model="curlVisible" class="curl-preview-drawer" size="min(620px, 100vw)" title="可执行请求">
      <div class="curl-preview">
        <div class="curl-preview-head">
          <span>请求预览</span>
          <div class="drawer-actions">
            <el-button @click="curlVisible = false">关闭</el-button>
            <el-button type="primary" @click="copyCurl">
              <Copy :size="16" />
              复制 cURL
            </el-button>
          </div>
        </div>
        <div class="request-summary" v-if="executableRequest">
          <span class="method-pill" :data-method="executableRequest.method">{{ executableRequest.method }}</span>
          <code>{{ executableRequest.url }}</code>
          <el-tag size="small">预期 {{ executableRequest.expectedStatus }}</el-tag>
        </div>
        <pre>{{ curlText }}</pre>
      </div>
    </el-drawer>
</template>
