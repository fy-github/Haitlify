<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  page: number
  pageSize: number
  total: number
  pageSizes?: number[]
  label?: string
}>(), {
  pageSizes: () => [50, 100, 200],
  label: '分页'
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))

/** 页数少时全展示;多时首尾 + 当前页邻域,中间用省略号 */
const visiblePages = computed<Array<number | 'gap'>>(() => {
  const count = pageCount.value
  const current = Math.min(Math.max(1, props.page), count)
  if (count <= 7) return Array.from({ length: count }, (_, index) => index + 1)

  const pages: Array<number | 'gap'> = [1]
  const start = Math.max(2, current - 1)
  const end = Math.min(count - 1, current + 1)
  if (start > 2) pages.push('gap')
  for (let page = start; page <= end; page += 1) pages.push(page)
  if (end < count - 1) pages.push('gap')
  pages.push(count)
  return pages
})

const go = (next: number): void => {
  const target = Math.min(Math.max(1, next), pageCount.value)
  if (target !== props.page) emit('update:page', target)
}

const changePageSize = (event: Event): void => {
  const value = Number((event.target as HTMLSelectElement).value)
  if (!Number.isFinite(value) || value <= 0) return
  emit('update:pageSize', value)
  emit('update:page', 1)
}
</script>

<template>
  <nav class="dp" :aria-label="label">
    <p class="dp-count">共 {{ total }} 条</p>
    <div class="dp-actions">
      <label class="dp-size">
        <select :value="pageSize" aria-label="每页条数" @change="changePageSize">
          <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条/页</option>
        </select>
      </label>
      <button type="button" class="dp-btn" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
      <div class="dp-pages" role="group" aria-label="页码">
        <template v-for="(item, index) in visiblePages" :key="`${item}-${index}`">
          <span v-if="item === 'gap'" class="dp-ellipsis" aria-hidden="true">…</span>
          <button
            v-else
            type="button"
            class="dp-btn"
            :class="{ 'is-current': item === page }"
            :aria-current="item === page ? 'page' : undefined"
            @click="go(item)"
          >{{ item }}</button>
        </template>
      </div>
      <button type="button" class="dp-btn" :disabled="page >= pageCount" @click="go(page + 1)">下一页</button>
      <span class="dp-hint">第 {{ Math.min(Math.max(1, page), pageCount) }} / {{ pageCount }} 页</span>
    </div>
  </nav>
</template>
