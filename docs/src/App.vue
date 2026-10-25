<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watchEffect } from 'vue';
import ManualTemplate from './components/ManualTemplate.vue';
import { languages, resolveLocale } from './locales';
import { readLocaleFromLocation, readSectionFromLocation } from './locales/navigation.js';

// AUD-159 — injected from app/build.gradle by vite.config.js, never hand-written.
const appVersion = __APP_VERSION__;

const activeCode = ref(readLocaleFromLocation(window.location));
const activeSection = ref(readSectionFromLocation(window.location));

function handleNavigation() {
  activeCode.value = readLocaleFromLocation(window.location);
  activeSection.value = readSectionFromLocation(window.location);
}

function selectLocale(code) {
  window.location.hash = `/${code}`;
  activeCode.value = code;
  activeSection.value = '';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function goHome() {
  history.pushState('', document.title, window.location.pathname);
  activeCode.value = '';
  activeSection.value = '';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

const currentLocale = computed(() => resolveLocale(activeCode.value));
const isManual = computed(() => Boolean(currentLocale.value));

watchEffect(() => {
  document.documentElement.lang = currentLocale.value?.code || 'en';
  document.documentElement.dir = currentLocale.value?.code === 'ar' ? 'rtl' : 'ltr';
  document.title = currentLocale.value?.title || 'DashCast Documentation';
});

onMounted(() => {
  window.addEventListener('hashchange', handleNavigation);
  window.addEventListener('popstate', handleNavigation);
});

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', handleNavigation);
  window.removeEventListener('popstate', handleNavigation);
});
</script>

<template>
  <main v-if="!isManual" class="landing">
    <section class="landing-wrap" aria-labelledby="landing-title">
      <h1 id="landing-title">DashCast</h1>
      <p class="landing-sub">Dashboard Controller - User Manual</p>
      <p class="landing-version">v{{ appVersion }} · BYD Seal / Dolphin / Atto 3 · DiLink 3 &amp; DiLink 5 · Android 10–13</p>

      <nav class="landing-languages" aria-label="Documentation languages">
        <button
          v-for="language in languages"
          :key="language.code"
          class="landing-language"
          type="button"
          @click="selectLocale(language.code)"
        >
          <span class="landing-flag" aria-hidden="true">{{ language.flag }}</span>
          <span>{{ language.name }}</span>
        </button>
      </nav>

      <footer>
        <a href="https://github.com/Kiroha/byd-dashcast">github.com/Kiroha/byd-dashcast</a>
      </footer>
    </section>
  </main>

  <main v-else class="manual-page">
    <header class="language-bar">
      <button class="brand-button" type="button" @click="goHome">
        DashCast
      </button>
      <nav class="language-list" aria-label="Documentation languages">
        <button
          v-for="language in languages"
          :key="language.code"
          :class="['language-button', { active: language.code === currentLocale.code }]"
          type="button"
          @click="selectLocale(language.code)"
        >
          <span aria-hidden="true">{{ language.flag }}</span>
          <span>{{ language.name }}</span>
        </button>
      </nav>
    </header>

    <ManualTemplate :t="currentLocale" :section="activeSection" />
  </main>
</template>
