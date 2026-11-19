import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

// Dedicated focused verification: never load the application's proxy or .env.
export default defineConfig({
  root: path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
  envDir: false,
  cacheDir: path.join(os.homedir(), '.hermes/cache/scratch/haitlify-controls-closeout/vitest-cache'),
  plugins: [vue()],
  test: { environment: 'node', globals: true, maxWorkers: 1, exclude: ['e2e/**', '**/node_modules/**', '**/dist/**'] }
})
