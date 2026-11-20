// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { createCasesState, provideCases, useCases } from './useCases'

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

it('keeps two provided case libraries independent after one App root unmounts', async () => {
  const first = createCasesState({ storage: localStorage })
  const second = createCasesState({ storage: sessionStorage })
  const Consumer = defineComponent({
    setup() {
      const cases = useCases()
      return () => h('span', cases.libraryCaseKind.value)
    }
  })
  const Root = (controller: typeof first) => defineComponent({
    setup() {
      provideCases(controller)
      return () => h(Consumer)
    }
  })

  const firstRoot = mount(Root(first))
  const secondRoot = mount(Root(second))
  let firstMounted = true
  try {
    first.switchLibraryCaseKind('web_automation')
    expect(first.libraryCaseKind.value).toBe('web_automation')
    expect(second.libraryCaseKind.value).toBe('interface')

    firstRoot.unmount()
    firstMounted = false
    second.switchLibraryCaseKind('web_automation')
    await nextTick()
    expect(second.libraryCaseKind.value).toBe('web_automation')
    expect(secondRoot.text()).toBe('web_automation')
  } finally {
    if (firstMounted) firstRoot.unmount()
    secondRoot.unmount()
  }
})
