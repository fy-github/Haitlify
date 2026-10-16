<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { ref, watch } from 'vue'
import type {
  SidebarAccent,
  SidebarBadges,
  SidebarGroup,
  SidebarItem,
  SidebarItemId
} from '../../ui/navigation/appSidebar'

const props = withDefaults(defineProps<{
  groups: readonly SidebarGroup[]
  activeItemId?: SidebarItemId
  collapsed: boolean
  badges?: SidebarBadges
}>(), {
  badges: () => ({})
})

const emit = defineEmits<{
  select: [item: SidebarItem]
  requestExpand: []
}>()

const badgeFor = (itemId: SidebarItemId) => props.badges[itemId]
const expandedItemIds = ref<Set<SidebarItemId>>(new Set())

const iconAccentClass = (accent?: SidebarAccent): string => (accent ? `studio-sidebar__icon--${accent}` : '')
const subitemIconAccentClass = (accent?: SidebarAccent): string => (accent ? `studio-sidebar__subitem-icon--${accent}` : '')

const parentForActiveItem = (activeItemId?: SidebarItemId): SidebarItem | undefined =>
  props.groups
    .flatMap((group) => group.items)
    .find((item) => item.children?.some((child) => child.id === activeItemId))

watch(
  () => [props.activeItemId, props.collapsed] as const,
  ([activeItemId, collapsed]) => {
    if (collapsed) return
    const parent = parentForActiveItem(activeItemId)
    if (!parent) return
    expandedItemIds.value = new Set([...expandedItemIds.value, parent.id])
  },
  { immediate: true }
)

const isExpanded = (item: SidebarItem) => expandedItemIds.value.has(item.id)
const isAncestorActive = (item: SidebarItem) =>
  item.children?.some((child) => child.id === props.activeItemId) || false

const toggleItem = (item: SidebarItem) => {
  if (props.collapsed) {
    expandedItemIds.value = new Set([...expandedItemIds.value, item.id])
    emit('requestExpand')
    return
  }

  const next = new Set(expandedItemIds.value)
  if (next.has(item.id)) next.delete(item.id)
  else next.add(item.id)
  expandedItemIds.value = next
}
</script>

<template>
  <aside
    class="studio-sidebar"
    :class="{ 'studio-sidebar--collapsed': collapsed }"
    aria-label="Studio Canvas 主导航"
  >
    <div class="studio-sidebar__brand">
      <span class="studio-sidebar__brand-mark" aria-hidden="true">QA</span>
      <span v-if="!collapsed" class="studio-sidebar__brand-name">AI 测试平台</span>
    </div>

    <nav class="studio-sidebar__groups" aria-label="工作区导航">
      <section v-for="group in groups" :key="group.id" class="studio-sidebar__group">
        <h2 v-if="!collapsed" class="studio-sidebar__group-label">{{ group.label }}</h2>
        <div class="studio-sidebar__items">
          <div v-for="item in group.items" :key="item.id" class="studio-sidebar__entry">
            <button
              type="button"
              class="studio-sidebar__item"
              :class="{
                'studio-sidebar__item--active': item.id === activeItemId,
                'studio-sidebar__item--ancestor-active': isAncestorActive(item),
                'studio-sidebar__item--expanded': item.children && isExpanded(item)
              }"
              :aria-current="item.id === activeItemId ? 'page' : undefined"
              :aria-expanded="item.children ? isExpanded(item) : undefined"
              :aria-controls="item.children ? `studio-sidebar-children-${item.id}` : undefined"
              :aria-label="item.label"
              :title="item.label"
              @click="item.children ? toggleItem(item) : emit('select', item)"
            >
              <component :is="item.icon" class="studio-sidebar__icon" :class="iconAccentClass(item.accent ?? group.accent)" :size="18" aria-hidden="true" />
              <span v-if="!collapsed" class="studio-sidebar__label">{{ item.label }}</span>
              <ChevronDown
                v-if="!collapsed && item.children"
                class="studio-sidebar__disclosure"
                :size="14"
                aria-hidden="true"
              />
              <span v-else-if="!collapsed && badgeFor(item.id) !== undefined" class="studio-sidebar__badge">
                {{ badgeFor(item.id) }}
              </span>
            </button>

            <div
              v-if="!collapsed && item.children && isExpanded(item)"
              :id="`studio-sidebar-children-${item.id}`"
              class="studio-sidebar__subitems"
              role="group"
              :aria-label="`${item.label}子菜单`"
            >
              <button
                v-for="child in item.children"
                :key="child.id"
                type="button"
                class="studio-sidebar__subitem"
                :class="{ 'studio-sidebar__subitem--active': child.id === activeItemId }"
                :aria-current="child.id === activeItemId ? 'page' : undefined"
                :aria-label="child.label"
                :title="child.label"
                @click="emit('select', child)"
              >
                <component :is="child.icon" class="studio-sidebar__subitem-icon" :class="subitemIconAccentClass(child.accent)" :size="15" aria-hidden="true" />
                <span>{{ child.label }}</span>
              </button>
            </div>
          </div>
        </div>
      </section>
    </nav>
  </aside>
</template>

<style>
.studio-sidebar {
  --studio-violet: var(--brand-primary);
  --studio-violet-strong: var(--brand-primary);
  --studio-violet-soft: var(--brand-primary-soft);
  width: 168px;
  min-width: 0;
  height: 100vh;
  overflow: hidden auto;
  padding: 12px 9px;
  color: var(--text-regular);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.95), rgba(247, 252, 250, 0.9) 42%, rgba(241, 248, 245, 0.93)),
    var(--surface-panel);
  backdrop-filter: blur(18px) saturate(145%);
  -webkit-backdrop-filter: blur(18px) saturate(145%);
  border-right: 1px solid color-mix(in srgb, var(--border-default) 78%, white);
  box-shadow: 1px 0 0 rgba(255, 255, 255, 0.7) inset, 8px 0 24px rgba(15, 23, 42, 0.035);
  transition: width 160ms ease;
  scrollbar-width: none;
}

.studio-sidebar::-webkit-scrollbar {
  display: none;
}

.studio-sidebar--collapsed {
  width: 56px;
  padding-right: 8px;
  padding-left: 8px;
}

.studio-sidebar__brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 36px;
  padding: 0 8px;
  margin-bottom: 14px;
  color: var(--text-primary);
}

.studio-sidebar--collapsed .studio-sidebar__brand {
  justify-content: center;
  padding: 0;
}

.studio-sidebar__brand-mark {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  color: var(--text-inverse);
  background:
    linear-gradient(145deg, rgba(47, 127, 199, 0.9), rgba(15, 118, 110, 0.96)),
    var(--gradient-primary);
  border: 1px solid rgba(255, 255, 255, 0.42);
  border-radius: 9px;
  box-shadow: 0 6px 14px rgba(15, 118, 110, 0.14);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.studio-sidebar__brand-name {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-sidebar__groups {
  display: grid;
  gap: 12px;
}

.studio-sidebar__group {
  display: grid;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px solid color-mix(in srgb, var(--border-subtle) 72%, transparent);
}

.studio-sidebar__group:first-child {
  padding-top: 0;
  border-top: 0;
}

.studio-sidebar__group-label {
  margin: 0;
  padding: 0 8px;
  color: color-mix(in srgb, var(--text-secondary) 82%, var(--brand-primary));
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  letter-spacing: 0.03em;
}

.studio-sidebar__items {
  display: grid;
  gap: 3px;
}

.studio-sidebar__entry {
  display: grid;
  gap: 2px;
}

.studio-sidebar__item {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-height: 36px;
  padding: 0 7px;
  color: var(--text-tertiary);
  text-align: left;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 140ms ease, border-color 140ms ease, color 140ms ease, box-shadow 140ms ease, transform 140ms ease;
}

.studio-sidebar__item:hover {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.78);
  border-color: rgba(206, 225, 219, 0.74);
  box-shadow: 0 6px 14px rgba(15, 23, 42, 0.03);
  transform: translateX(1px);
}

.studio-sidebar__item:focus-visible {
  outline: 2px solid var(--studio-violet);
  outline-offset: 2px;
}

/* 选中态:浅青底 + 左品牌指示条 + 微阴影,与按钮 / 表格选中态同一语族 */
.studio-sidebar__item--active {
  color: var(--brand-primary-active);
  background:
    linear-gradient(90deg, rgba(15, 118, 110, 0.1), rgba(255, 255, 255, 0.86)),
    rgba(255, 255, 255, 0.82);
  border-color: rgba(124, 190, 178, 0.72);
  box-shadow:
    0 6px 16px rgba(15, 118, 110, 0.06),
    0 0 0 1px rgba(124, 190, 178, 0.2) inset;
}

.studio-sidebar__item--ancestor-active {
  color: var(--brand-primary-active);
  background: rgba(255, 255, 255, 0.68);
  border-color: rgba(163, 213, 206, 0.54);
}

.studio-sidebar__item--active:hover {
  color: var(--brand-primary-active);
  background:
    linear-gradient(90deg, rgba(15, 118, 110, 0.12), rgba(255, 255, 255, 0.92)),
    rgba(255, 255, 255, 0.9);
}

.studio-sidebar__item--active .studio-sidebar__label {
  font-weight: var(--font-weight-semibold);
}

.studio-sidebar__icon {
  flex: 0 0 auto;
}

.studio-sidebar__disclosure {
  transition: transform var(--motion-base) var(--ease-standard);
}

.studio-sidebar__item--expanded .studio-sidebar__disclosure {
  transform: rotate(180deg);
}

.studio-sidebar__subitems {
  display: grid;
  gap: 2px;
  margin: 3px 0 3px 9px;
  padding: 4px 0 4px 13px;
  border-left: 1px solid color-mix(in srgb, var(--brand-primary-border) 58%, white);
}

.studio-sidebar__subitem {
  display: grid;
  width: 100%;
  min-height: 32px;
  grid-template-columns: 15px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 0 8px;
  color: var(--text-secondary);
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  font-size: 11px;
  font-weight: var(--font-weight-medium);
  transition: color var(--motion-fast) var(--ease-standard), background-color var(--motion-fast) var(--ease-standard), border-color var(--motion-fast) var(--ease-standard);
}

.studio-sidebar__subitem:hover {
  color: var(--brand-primary-active);
  background: rgba(255, 255, 255, 0.78);
  border-color: rgba(206, 225, 219, 0.74);
}

.studio-sidebar__subitem:focus-visible {
  outline: 2px solid var(--brand-primary-focus);
  outline-offset: 1px;
}

.studio-sidebar__subitem--active {
  color: var(--brand-primary);
  background: linear-gradient(90deg, rgba(15, 118, 110, 0.09), rgba(255, 255, 255, 0.92));
  border-color: rgba(124, 190, 178, 0.66);
  box-shadow: 0 6px 16px rgba(15, 118, 110, 0.05);
  font-weight: var(--font-weight-semibold);
}

/* 图标强调色按 accent 元数据显式绑定(替代旧 nth-child 位置染色,增删/重排分组不再静默改色) */
.studio-sidebar__icon--teal { color: var(--accent-teal); }
.studio-sidebar__icon--cyan { color: var(--accent-cyan); }
.studio-sidebar__icon--amber { color: var(--accent-amber); }
.studio-sidebar__icon--info { color: var(--color-info); }
.studio-sidebar__subitem-icon--teal { color: var(--accent-teal); }
.studio-sidebar__subitem-icon--cyan { color: var(--accent-cyan); }
.studio-sidebar__subitem-icon--amber { color: var(--accent-amber); }
.studio-sidebar__subitem-icon--info { color: var(--color-info); }

.studio-sidebar__subitem--active .studio-sidebar__subitem-icon {
  color: currentColor;
}

.studio-sidebar__item--active .studio-sidebar__icon {
  color: var(--brand-primary);
}

.studio-sidebar__label {
  overflow: hidden;
  font-size: 11px;
  font-weight: var(--font-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-sidebar__badge {
  min-width: 18px;
  padding: 1px 5px;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  text-align: center;
}

.studio-sidebar__item--active .studio-sidebar__badge {
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
}

.studio-sidebar--collapsed .studio-sidebar__group-label,
.studio-sidebar--collapsed .studio-sidebar__label,
.studio-sidebar--collapsed .studio-sidebar__badge {
  display: none;
}

.studio-sidebar--collapsed .studio-sidebar__groups {
  gap: 12px;
}

.studio-sidebar--collapsed .studio-sidebar__group {
  gap: 0;
}

.studio-sidebar--collapsed .studio-sidebar__item {
  grid-template-columns: 18px;
  justify-content: center;
  padding: 0;
  background: transparent;
}

@media (prefers-reduced-motion: reduce) {
  .studio-sidebar__disclosure {
    transition: none;
  }
}
</style>
