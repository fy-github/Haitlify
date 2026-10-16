<script setup lang="ts">
import { ref } from 'vue'
import { KeyRound, LogIn, User } from '@lucide/vue'
import { login, type AuthUser } from '../../domain/authRepository'

const emit = defineEmits<{
  authenticated: [user: AuthUser]
}>()

const username = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

const submit = async (): Promise<void> => {
  const trimmedUsername = username.value.trim()
  if (!trimmedUsername || !password.value) {
    errorMessage.value = '请输入用户名和密码'
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    const user = await login(trimmedUsername, password.value)
    emit('authenticated', user)
  } catch (error) {
    errorMessage.value = describeLoginError(error)
  } finally {
    submitting.value = false
  }
}

const describeLoginError = (error: unknown): string => {
  if (error instanceof TypeError) {
    return '无法连接服务器，请确认后端已启动'
  }
  if (error instanceof Error) {
    const reason = error.message.replace(/^Auth request failed: \d+\s*/, '')
    return reason || '登录失败，请重试'
  }
  return '登录失败，请重试'
}
</script>

<template>
  <main class="login-screen">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-card__brand">
        <span class="login-card__mark" aria-hidden="true">
          <KeyRound :size="22" />
        </span>
        <h1 class="login-card__title">Haitlify AI 测试平台</h1>
        <p class="login-card__subtitle">请使用管理员账号登录</p>
      </div>

      <label class="login-field">
        <span class="login-field__label">用户名</span>
        <span class="login-field__control">
          <User :size="16" aria-hidden="true" />
          <input
            v-model="username"
            type="text"
            name="username"
            autocomplete="username"
            :disabled="submitting"
            aria-label="用户名"
          />
        </span>
      </label>

      <label class="login-field">
        <span class="login-field__label">密码</span>
        <span class="login-field__control">
          <KeyRound :size="16" aria-hidden="true" />
          <input
            v-model="password"
            type="password"
            name="password"
            autocomplete="current-password"
            :disabled="submitting"
            aria-label="密码"
          />
        </span>
      </label>

      <p v-if="errorMessage" class="login-card__error" role="alert">{{ errorMessage }}</p>

      <button type="submit" class="login-card__submit" :disabled="submitting">
        <LogIn :size="16" aria-hidden="true" />
        <span>{{ submitting ? '登录中…' : '登录' }}</span>
      </button>
    </form>
  </main>
</template>

<style scoped>
.login-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  /* Teal 青绿氛围:两侧柔和光晕,主体走页面底色 token */
  background:
    radial-gradient(1000px 520px at 18% -8%, rgba(15, 118, 110, 0.1), transparent 60%),
    radial-gradient(860px 480px at 92% 112%, rgba(14, 116, 144, 0.1), transparent 55%),
    var(--surface-page);
}

.login-card {
  width: 100%;
  max-width: 352px;
  padding: 30px 26px;
  border-radius: var(--radius-xl);
  background: var(--surface-glass);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(14px);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-card__brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.login-card__mark {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg);
  color: var(--text-inverse);
  background: var(--gradient-primary);
  box-shadow: 0 3px 9px var(--brand-primary-shadow);
}

.login-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--text-primary);
}

.login-card__subtitle {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-secondary);
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.login-field__label {
  font-size: 12px;
  line-height: 1.35;
  color: var(--text-primary);
}

.login-field__control {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
  background: var(--surface-raised);
  color: var(--text-placeholder);
  transition: border-color var(--motion-fast) var(--ease-standard), box-shadow var(--motion-fast) var(--ease-standard), background-color var(--motion-fast) var(--ease-standard);
}

.login-field__control:focus-within {
  border-color: var(--brand-primary-border);
  background: var(--surface-raised);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--brand-primary-focus) 48%, transparent);
}

.login-field__control input {
  flex: 1;
  min-width: 0;
  padding: 9px 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--text-primary);
  caret-color: var(--brand-primary);
}

.login-field__control input::selection {
  background: color-mix(in srgb, var(--brand-primary-soft) 72%, white);
  color: var(--text-primary);
}

.login-card__error {
  margin: 0;
  font-size: 13px;
  color: var(--color-danger);
}

.login-card__submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 16px;
  border: none;
  border-radius: var(--radius-control);
  background: var(--gradient-primary);
  color: var(--text-inverse);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity var(--motion-fast) var(--ease-standard), transform var(--motion-fast) var(--ease-standard);
}

.login-card__submit:hover:not(:disabled) {
  opacity: 0.92;
  transform: translateY(-1px);
}

.login-card__submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 键盘焦点环:输入框焦点态由容器承担,避免内外双重高亮 */
.login-card__submit:focus-visible,
.login-field__control input:focus-visible {
  outline: 2px solid var(--focus-ring-color);
  outline-offset: 2px;
}

.login-field__control input:focus-visible {
  outline: none;
}
</style>
