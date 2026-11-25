import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const config = fileURLToPath(new URL('./vite.config.ts', import.meta.url))
const apps = [
  { name: 'learner', directory: '..', port: 59202 },
  { name: 'admin', directory: '../../admin', port: 59203 },
]
const origin = (port: number) => `http://127.0.0.1:${port}`

// Deliberately separate from the normal e2e runners: no backend or E2E_BASE_URL.
export default defineConfig({
  testDir: '.',
  outputDir: '../test-results/session',
  timeout: 60_000,
  workers: 1,
  reporter: 'list',
  use: {
    browserName: 'chromium',
    channel: process.env.E2E_CHANNEL === 'chrome' ? 'chrome' : undefined,
    serviceWorkers: 'block',
  },
  projects: apps.map(({ name, port }) => ({ name, use: { baseURL: origin(port) } })),
  webServer: apps.map(({ directory, port }) => ({
    cwd: fileURLToPath(new URL(directory, import.meta.url)),
    command: `pnpm exec vite --config "${config}" --host 127.0.0.1 --port ${port} --strictPort`,
    url: origin(port),
    reuseExistingServer: false,
    timeout: 60_000,
  })),
})
