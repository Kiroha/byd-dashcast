import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// AUD-159 — the site used to hard-code the version in three places and all three had
// rotted independently: v0.9.92-alpha on the landing page, v1.7.0 in the thirteen
// locales, v0.8.6 in the embedded mockup, for an app at 1.8.x. Read it from the one
// place that is definitionally right, app/build.gradle, and fail the build rather than
// publish a guess.
function appVersion() {
  const gradle = fileURLToPath(new URL('../app/build.gradle', import.meta.url));
  const match = readFileSync(gradle, 'utf8').match(/versionName\s+"([^"]+)"/);
  if (!match) throw new Error('AUD-159: versionName not found in app/build.gradle');
  return match[1];
}

export default defineConfig(({ mode }) => ({
  base: mode === 'production' ? '/byd-dashcast/' : '/',
  plugins: [vue()],
  define: { __APP_VERSION__: JSON.stringify(appVersion()) },
}));
