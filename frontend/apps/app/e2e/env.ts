/**
 * e2e 的账号：默认取 `db/devdata` 的种子（只在本地跑时存在），
 * 指向已部署实例时（`E2E_BASE_URL`，见 `playwright.config.ts`）必须由环境变量给出——
 * 种子口令写在**公开仓库**里，放到公网可达的实例上等于开放权限。
 *
 * <p>口令只从环境变量进来，不落任何被跟踪的文件——与后端集成测试读测试库口令同一做法。
 */
export const LEARNER = {
  identifier: process.env.E2E_LEARNER_IDENTIFIER ?? 'seed-learner',
  password: process.env.E2E_LEARNER_PASSWORD ?? 'seed-learner-password',
}

/**
 * 管理员账号：**只用于造/删夹具**（图谱概念等），不参与被测页面。
 *
 * <p>放在这里与 {@link LEARNER} 并排：以前它写在 `graph-fixture.ts` 里，夹具多一处就多一份拷贝，
 * 口令来源迟早漂移。
 */
export const ADMIN = {
  identifier: process.env.E2E_ADMIN_IDENTIFIER ?? 'seed-admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'seed-admin-password',
}

/**
 * 打的是已部署实例而不是本机（见 `playwright.config.ts`）。
 *
 * <p>**有些用例只在本地成立**：它们依赖 `db/devdata` 的种子内容（那条草稿、那几篇文章），
 * 线上库里没有这些数据。这类用例用 `test.skip(REMOTE, …)` 明说，而不是让它们在线上红成一片——
 * 远程一轮里混进这种红，看起来就像界面坏了（实测踩过）。
 */
export const REMOTE = Boolean(process.env.E2E_BASE_URL)
