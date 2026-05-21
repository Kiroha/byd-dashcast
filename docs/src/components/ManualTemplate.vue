<script setup>
import { computed, nextTick, watch } from 'vue';

const props = defineProps({
  t: { type: Object, required: true },
  section: { type: String, default: '' },
});

const mockupBase = computed(() => `mockup_m3.html`);

function sectionHref(idx) {
  return `#/${props.t.code}/s${idx + 1}`;
}

function scrollToId(id) {
  const target = document.getElementById(id);
  if (!target) return;
  const offset = document.querySelector('.language-bar')?.getBoundingClientRect().height || 0;
  const top = target.getBoundingClientRect().top + window.scrollY - offset - 12;
  window.scrollTo({ top, behavior: 'smooth' });
}

function navigateToSection(idx) {
  const next = `/${props.t.code}/s${idx + 1}`;
  if (window.location.hash === `#${next}`) {
    scrollToId(`s${idx + 1}`);
    return;
  }
  window.location.hash = next;
}

watch(
  () => [props.t.code, props.section],
  async ([, section]) => {
    if (!section) return;
    await nextTick();
    scrollToId(section);
  },
  { immediate: true },
);
</script>

<template>
  <article class="container">
    <div class="cover">
      <h1>DashCast</h1>
      <div class="sub">{{ t.manualName }}</div>
      <div class="meta">{{ t.meta }}</div>
    </div>

    <div class="toc">
      <h2>{{ t.tocTitle }}</h2>
      <ol>
        <li>
          <a class="toc-link" href="#intro" @click.prevent="scrollToId('intro')">
            {{ t.intro.title.replace(/^\d+\.\s*/, '') }}
          </a>
        </li>
        <li v-for="(s, idx) in t.sections" :key="s.id">
          <a class="toc-link" :href="sectionHref(idx)" @click.prevent="navigateToSection(idx)">
            {{ s.title.replace(/^\d+\.\s*/, '') }}
          </a>
        </li>
        <li>
          <a class="toc-link" href="#faq" @click.prevent="scrollToId('faq')">
            {{ t.faq.title.replace(/^\d+\.\s*/, '') }}
          </a>
        </li>
      </ol>
    </div>

    <h2 id="intro" class="section-heading">
      <span>{{ t.intro.title }}</span>
      <a class="section-anchor" href="#intro" @click.prevent="scrollToId('intro')">#</a>
    </h2>
    <p>{{ t.intro.lead }}</p>
    <ul>
      <li v-for="b in t.intro.bullets" :key="b">{{ b }}</li>
    </ul>
    <div class="note" v-if="t.intro.note">{{ t.intro.note }}</div>

    <template v-for="(s, idx) in t.sections" :key="s.id">
      <h2 :id="`s${idx + 1}`" class="section-heading">
        <span>{{ s.title }}</span>
        <a class="section-anchor" :href="sectionHref(idx)" @click.prevent="navigateToSection(idx)">#</a>
      </h2>
      <p class="lead">{{ s.lead }}</p>

      <div class="mockup-frame">
        <iframe
          :src="`${mockupBase}?embed=1#${s.screen}`"
          :title="s.title"
          loading="lazy"
        ></iframe>
        <a class="mockup-link" :href="`${mockupBase}#${s.screen}`" target="_blank" rel="noopener">
          {{ s.mockupLabel }} ↗
        </a>
      </div>

      <template v-if="s.features && s.features.length">
        <h3>{{ s.featuresTitle }}</h3>
        <div class="feature-grid">
          <div v-for="f in s.features" :key="f.title" class="feature-card">
            <div class="feature-title">{{ f.title }}</div>
            <div class="feature-text">{{ f.text }}</div>
          </div>
        </div>
      </template>

      <template v-if="s.howTo && s.howTo.steps && s.howTo.steps.length">
        <h3>{{ s.howTo.title }}</h3>
        <ol class="howto">
          <li v-for="step in s.howTo.steps" :key="step">{{ step }}</li>
        </ol>
      </template>

      <template v-if="s.tips && s.tips.length">
        <h3>{{ s.tipsTitle }}</h3>
        <ul class="tips">
          <li v-for="tip in s.tips" :key="tip">{{ tip }}</li>
        </ul>
      </template>

      <div class="note" v-if="s.note">{{ s.note }}</div>
    </template>

    <h2 id="faq" class="section-heading">
      <span>{{ t.faq.title }}</span>
      <a class="section-anchor" href="#faq" @click.prevent="scrollToId('faq')">#</a>
    </h2>
    <template v-for="item in t.faq.items" :key="item.question">
      <h3>{{ item.question }}</h3>
      <p v-if="item.answer">{{ item.answer }}</p>
      <ul v-else-if="item.items">
        <li v-for="entry in item.items" :key="entry">{{ entry }}</li>
      </ul>
    </template>

    <hr>
    <p class="footer-note">{{ t.footer }}</p>
  </article>
</template>

<style scoped>
.lead { font-size: 1.05rem; line-height: 1.5; margin: 0 0 18px; }
.mockup-frame {
  position: relative;
  margin: 18px 0 24px;
  border: 1px solid #d4dbe6;
  border-radius: 12px;
  overflow: hidden;
  background: #0e1b2c;
}
.mockup-frame iframe {
  width: 100%;
  aspect-ratio: 16 / 9;
  height: auto;
  border: 0;
  display: block;
  background: #0e1b2c;
}
.mockup-link {
  display: inline-block;
  margin: 8px 14px 12px;
  font-size: 0.85rem;
  color: #1565c0;
  text-decoration: none;
}
.mockup-link:hover { text-decoration: underline; }
.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin: 14px 0 22px;
}
.feature-card {
  background: #f5f8fc;
  border: 1px solid #d4dbe6;
  border-radius: 10px;
  padding: 14px 16px;
}
.feature-title {
  font-weight: 600;
  margin-bottom: 4px;
  color: #1565c0;
}
.feature-text {
  font-size: 0.95rem;
  line-height: 1.45;
}
.howto, .tips {
  margin: 10px 0 22px 22px;
  line-height: 1.55;
}
.howto li { margin-bottom: 6px; }
.tips li { margin-bottom: 4px; }
</style>
