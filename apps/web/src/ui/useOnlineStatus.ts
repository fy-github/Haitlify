import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export const useOnlineStatus = (): Ref<boolean> => {
  const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
  const setOnline = () => { online.value = true }
  const setOffline = () => { online.value = false }
  onMounted(() => {
    window.addEventListener('online', setOnline)
    window.addEventListener('offline', setOffline)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('online', setOnline)
    window.removeEventListener('offline', setOffline)
  })
  return online
}
