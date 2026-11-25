import { defineConfig, loadConfigFromFile } from 'vite'

export default defineConfig(async (env) => {
  const app = await loadConfigFromFile(env, 'vite.config.ts')
  if (!app) throw new Error('Missing application Vite config')

  // Keep the actual application plugins, but give test requests no path to a backend.
  return {
    ...app.config,
    define: { ...app.config.define, 'import.meta.env.VITE_API_BASE_URL': JSON.stringify('') },
    server: { ...app.config.server, proxy: undefined },
  }
})
