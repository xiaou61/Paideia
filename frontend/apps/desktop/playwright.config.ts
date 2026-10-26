import { defineConfig } from '@playwright/test'

/**
 * 桌面端 e2e。
 *
 * 这里只负责拉起后端；桌面应用由测试自己用 Playwright 的 Electron 支持启动
 * （从源码启动，不依赖安装包，因此即使打包环节被网络卡住也能验证运行时行为）。
 */
const BACKEND_DIR = '../../../backend'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  // 启动 Electron 需要真实窗口，超时给宽一些
  timeout: 60_000,
  // 同其他两端：retries 默认 0，失败就留 trace 才有现场
  use: { trace: 'retain-on-failure' },
  webServer: [
    {
      command: 'java -jar paideia-app/target/paideia-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local',
      cwd: BACKEND_DIR,
      url: 'http://127.0.0.1:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 120_000,
    },
  ],
})
