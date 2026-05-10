<script setup>
import { computed, nextTick, watch } from 'vue';
import { languages } from '../locales';

const props = defineProps({
  t: {
    type: Object,
    required: true,
  },
  section: {
    type: String,
    default: '',
  },
});

const appStyles = ['#E3F2FD', '#FCE4EC', '#E8F5E9', '#FFF3E0'];
const appIcons = ['🗺', '▶️', '🎵', '🧭'];
const logClasses = ['log-i', 'log-i', 'log-d', 'log-w', 'log-i'];

const welcomeHintLines = computed(() => props.t.firstLaunch.welcomeHint.split('\n'));
const mainButtons = computed(() => props.t.main.buttons.slice(0, 4));
const mainCloseButton = computed(() => props.t.main.buttons.at(-1) || '✕');
const projectionButtons = computed(() => props.t.projection.buttons.slice(0, 5));
const projectionMainButton = computed(() => props.t.projection.buttons[5] || '');
const projectionCloseButton = computed(() => props.t.projection.buttons[6] || '✕');
const projectionClusterButton = computed(() => props.t.projection.buttons[7] || '');
const splitButton = computed(() => props.t.projection.buttons.at(-2) || '');
const hideButton = computed(() => props.t.projection.buttons.at(-1) || '');
const resizeButton = computed(() => {
  const btn = props.t.projection.buttons.at(-3);
  return (btn === '✕' || !btn) ? '📐 Ajuster' : btn;
});
const resetRowIndex = computed(() => Math.max(0, props.t.stopping.table.rows.length - 1));

function sectionHref(index) {
  return `#/${props.t.code}/s${index + 1}`;
}

function markerClass(annotation) {
  return ['ann-num', annotation.tone].filter(Boolean);
}

function scrollToSectionId(sectionId) {
  const target = document.getElementById(sectionId);
  if (!target) return;

  const headerOffset = document.querySelector('.language-bar')?.getBoundingClientRect().height || 0;
  const top = target.getBoundingClientRect().top + window.scrollY - headerOffset - 12;
  window.scrollTo({ top, behavior: 'smooth' });
}

function scrollToSection(index) {
  scrollToSectionId(`s${index + 1}`);
}

function navigateToSection(index) {
  const nextHash = `/${props.t.code}/s${index + 1}`;
  if (window.location.hash === `#${nextHash}`) {
    scrollToSection(index);
    return;
  }

  window.location.hash = nextHash;
}

watch(
  () => [props.t.code, props.section],
  async ([, section]) => {
    if (!section) return;
    await nextTick();
    scrollToSectionId(section);
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
        <li v-for="(title, index) in t.sections" :key="title">
          <a class="toc-link" :href="sectionHref(index)" @click.prevent="navigateToSection(index)">
            {{ title.replace(/^\d+\.\s*/, '') }}
          </a>
        </li>
      </ol>
    </div>

    <h2 id="s1" class="section-heading">
      <span>{{ t.overview.title }}</span>
      <a class="section-anchor" :href="sectionHref(0)" :aria-label="`Link to ${t.overview.title}`" @click.prevent="navigateToSection(0)">#</a>
    </h2>
    <p>{{ t.overview.text }}</p>
    <ul>
      <li v-for="item in t.overview.bullets" :key="item">{{ item }}</li>
    </ul>
    <div class="note">{{ t.overview.note }}</div>

    <h2 id="s2" class="section-heading">
      <span>{{ t.firstLaunch.title }}</span>
      <a class="section-anchor" :href="sectionHref(1)" :aria-label="`Link to ${t.firstLaunch.title}`" @click.prevent="navigateToSection(1)">#</a>
    </h2>
    <p>{{ t.firstLaunch.text }}</p>
    <div class="device-wrap">
      <div class="device">
        <div class="screen">
          <div class="welcome-screen">
            <div class="wc-title">DashCast</div>
            <div class="wc-sub">{{ t.firstLaunch.welcomeSubtitle }}</div>
            <div class="wc-hint">
              <template v-for="(line, index) in welcomeHintLines" :key="line">
                <br v-if="index > 0">
                {{ line }}
              </template>
            </div>
            <button
              v-for="language in languages"
              :key="language.code"
              :class="['lang-btn', { selected: language.code === t.code }]"
              type="button"
            >
              {{ language.flag }}&nbsp;&nbsp;{{ language.name }}
            </button>
          </div>
        </div>
      </div>
    </div>
    <p class="caption">{{ t.firstLaunch.caption }}</p>

    <h2 id="s3" class="section-heading">
      <span>{{ t.main.title }}</span>
      <a class="section-anchor" :href="sectionHref(2)" :aria-label="`Link to ${t.main.title}`" @click.prevent="navigateToSection(2)">#</a>
    </h2>
    <p>{{ t.main.text }}</p>
    <div class="device-wrap">
      <div class="device">
        <div class="screen">
          <div class="sb">
            <span class="sb-text">{{ t.main.status }}</span>
            <button class="btn-ui btn-blue" type="button">{{ mainButtons[0] }}</button>
            <button class="btn-ui btn-red" type="button">{{ mainButtons[1] }}</button>
            <button class="btn-ui btn-green btn-small" type="button">{{ mainButtons[2] }}</button>
            <button class="btn-ui btn-gray" type="button">{{ mainButtons[3] }}</button>
          </div>
          <div class="list-title">{{ t.main.listTitle }}</div>
          <div v-for="(app, index) in t.main.apps" :key="app" class="app-item">
            <div class="app-icon" :style="{ background: appStyles[index % appStyles.length] }">
              {{ appIcons[index % appIcons.length] }}
            </div>
            <div class="app-name">{{ app }}</div>
            <button class="btn-ui btn-red btn-small" type="button">{{ mainCloseButton }}</button>
          </div>
        </div>
      </div>
    </div>
    <p class="caption">{{ t.main.caption }}</p>
    <div class="annotation-grid">
      <div v-for="annotation in t.main.annotations" :key="annotation.marker + annotation.label" class="ann">
        <div :class="markerClass(annotation)">{{ annotation.marker }}</div>
        <div><strong>{{ annotation.label }}</strong> - {{ annotation.text }}</div>
      </div>
    </div>

    <h2 id="s4" class="section-heading">
      <span>{{ t.projection.title }}</span>
      <a class="section-anchor" :href="sectionHref(3)" :aria-label="`Link to ${t.projection.title}`" @click.prevent="navigateToSection(3)">#</a>
    </h2>
    <div v-for="(step, index) in t.projection.steps" :key="step" class="step">
      <div class="step-num">{{ index + 1 }}</div>
      <div class="step-content">{{ step }}</div>
    </div>
    <div class="device-wrap">
      <div class="device">
        <div class="screen">
          <div class="sb">
            <span class="sb-text active">{{ t.projection.activeStatus }}</span>
            <button class="btn-ui btn-blue" type="button">{{ projectionButtons[0] }}</button>
            <button class="btn-ui btn-teal" type="button">{{ projectionButtons[1] }}</button>
            <button class="btn-ui btn-red" type="button">{{ projectionButtons[2] }}</button>
            <button class="btn-ui btn-green btn-small" type="button">{{ projectionButtons[3] }}</button>
            <button class="btn-ui btn-gray" type="button">{{ projectionButtons[4] }}</button>
          </div>
          <div class="list-title">{{ t.projection.listTitle }}</div>
          <div v-for="(app, index) in t.projection.apps" :key="app" class="app-item">
            <div class="app-icon" :style="{ background: appStyles[index % appStyles.length] }">
              {{ appIcons[index % appIcons.length] }}
            </div>
            <div class="app-name">{{ app }}</div>
            <div v-if="index === 0" class="active-dot"></div>
            <button
              :class="['btn-ui', index === 0 ? 'btn-dark' : 'btn-blue', 'btn-small']"
              type="button"
            >
              {{ index === 0 ? projectionMainButton : projectionClusterButton }}
            </button>
            <button class="btn-ui btn-red btn-small" type="button">{{ projectionCloseButton }}</button>
          </div>
          <div class="control-panel">
            <div class="cp-header">
              <span class="cp-label">{{ t.projection.controlLabel }}</span>
              <span class="cp-app">{{ t.projection.controlApp }}</span>
              <button class="btn-ui btn-indigo btn-small" type="button">{{ splitButton }}</button>
              <button class="btn-ui btn-dark btn-small" type="button">{{ hideButton }}</button>
            </div>
            <div class="mirror-area">
              <span class="mirror-text">{{ t.projection.mirrorText }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <p class="caption">{{ t.projection.caption }}</p>
    <div v-if="t.projection.annotations.length" class="annotation-grid">
      <div v-for="annotation in t.projection.annotations" :key="annotation.marker + annotation.label" class="ann">
        <div :class="markerClass(annotation)">{{ annotation.marker }}</div>
        <div><strong>{{ annotation.label }}</strong> - {{ annotation.text }}</div>
      </div>
    </div>

    <h2 id="s5" class="section-heading">
      <span>{{ t.control.title }}</span>
      <a class="section-anchor" :href="sectionHref(4)" :aria-label="`Link to ${t.control.title}`" @click.prevent="navigateToSection(4)">#</a>
    </h2>
    <p>{{ t.control.intro }}</p>
    <h3>{{ t.control.mirror.title }}</h3>
    <p>{{ t.control.mirror.text }}</p>
    <div class="note">{{ t.control.mirror.note }}</div>
    <h3>{{ t.control.split.title }}</h3>
    <p>{{ t.control.split.text }}</p>
    <ul>
      <li v-for="item in t.control.split.items" :key="item">{{ item }}</li>
    </ul>
    <p v-if="t.control.split.extra">{{ t.control.split.extra }}</p>
    <h3>{{ t.control.hide.title }}</h3>
    <p>{{ t.control.hide.text }}</p>

    <h2 id="s6" class="section-heading">
      <span>{{ t.stopping.title }}</span>
      <a class="section-anchor" :href="sectionHref(5)" :aria-label="`Link to ${t.stopping.title}`" @click.prevent="navigateToSection(5)">#</a>
    </h2>
    <p v-if="t.stopping.intro">{{ t.stopping.intro }}</p>
    <table class="doc-table">
      <thead>
        <tr>
          <th v-for="header in t.stopping.table.headers" :key="header">{{ header }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, rowIndex) in t.stopping.table.rows" :key="row.join('|')">
          <td v-for="(cell, cellIndex) in row" :key="cell">
            <strong v-if="cellIndex === 0" :class="rowIndex === resetRowIndex ? 'text-green' : 'text-red'">
              {{ cell }}
            </strong>
            <template v-else>{{ cell }}</template>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="warn">{{ t.stopping.warning }}</div>

    <h2 id="s7" class="section-heading">
      <span>{{ t.settings.title }}</span>
      <a class="section-anchor" :href="sectionHref(6)" :aria-label="`Link to ${t.settings.title}`" @click.prevent="navigateToSection(6)">#</a>
    </h2>
    <p>{{ t.settings.intro }}</p>
    <div class="device-wrap">
      <div class="device">
        <div class="screen">
          <div class="settings-screen">
            <div class="settings-title">{{ t.settings.titleLabel }}</div>
            <div class="card">
              <div class="card-title">{{ t.settings.clusterTypeLabel }}</div>
              <div v-for="(option, index) in t.settings.clusterOptions" :key="option" class="radio-item">
                <div :class="['radio-circle', { checked: index === 1 }]"></div>
                {{ option }}
              </div>
            </div>
            <div class="card">
              <div class="card-title">{{ t.settings.marginsLabel }}</div>
              <div class="slider-row">
                <div class="slider-label">{{ t.settings.horizontalMarginLabel }}</div>
                <div class="slider-bar"><div class="slider-fill slider-fill-horizontal"></div><div class="slider-thumb slider-thumb-horizontal"></div></div>
                <div class="slider-val">80 px</div>
              </div>
              <div class="slider-row">
                <div class="slider-label">{{ t.settings.verticalMarginLabel }}</div>
                <div class="slider-bar"><div class="slider-fill slider-fill-vertical"></div><div class="slider-thumb slider-thumb-vertical"></div></div>
                <div class="slider-val">50 px</div>
              </div>
              <div class="settings-actions">
                <button class="btn-ui btn-blue" type="button">{{ t.settings.applyButton }}</button>
                <button class="btn-ui btn-secondary" type="button">{{ t.settings.resetButton }}</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <p class="caption">{{ t.settings.caption }}</p>
    <h3>{{ t.settings.type.title }}</h3>
    <p>{{ t.settings.type.text }}</p>
    <h3>{{ t.settings.margins.title }}</h3>
    <p>{{ t.settings.margins.text }}</p>
    <ul>
      <li v-for="item in t.settings.margins.items" :key="item">{{ item }}</li>
    </ul>
    <p>{{ t.settings.margins.applyText }}</p>
    <div class="note">{{ t.settings.margins.note }}</div>

    <h2 id="s8" class="section-heading">
      <span>{{ t.tools.title }}</span>
      <a class="section-anchor" :href="sectionHref(7)" :aria-label="`Link to ${t.tools.title}`" @click.prevent="navigateToSection(7)">#</a>
    </h2>
    <p v-if="t.tools.intro">{{ t.tools.intro }}</p>
    <table class="doc-table">
      <thead>
        <tr>
          <th v-for="header in t.tools.table.headers" :key="header">{{ header }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in t.tools.table.rows" :key="row.join('|')">
          <td v-for="cell in row" :key="cell">{{ cell }}</td>
        </tr>
      </tbody>
    </table>

    <template v-if="t.tools.logs">
      <h3>{{ t.tools.logs.title }}</h3>
      <div class="device-wrap">
        <div class="device">
          <div class="screen">
            <div class="log-screen">
              <div class="log-header">
                <span class="log-title-text">{{ t.tools.logs.header }}</span>
                <button class="btn-ui btn-dark btn-small" type="button">{{ t.tools.logs.clearButton }}</button>
                <button class="btn-ui btn-blue btn-small" type="button">{{ t.tools.logs.shareButton }}</button>
              </div>
              <input class="log-filter" :placeholder="t.tools.logs.filterPlaceholder" readonly>
              <div
                v-for="(line, index) in t.tools.logs.lines"
                :key="line"
                :class="['log-entry', logClasses[index % logClasses.length]]"
              >
                {{ line }}
              </div>
            </div>
          </div>
        </div>
      </div>
      <p class="caption">{{ t.tools.logs.caption }}</p>
    </template>

    <h2 id="s9" class="section-heading">
      <span>{{ t.faq.title }}</span>
      <a class="section-anchor" :href="sectionHref(8)" :aria-label="`Link to ${t.faq.title}`" @click.prevent="navigateToSection(8)">#</a>
    </h2>
    <template v-for="item in t.faq.items" :key="item.question">
      <h3>{{ item.question }}</h3>
      <p v-if="item.answer">{{ item.answer }}</p>
      <ul v-else>
        <li v-for="entry in item.items" :key="entry">{{ entry }}</li>
      </ul>
    </template>

    <hr>
    <p class="footer-note">{{ t.footer }}</p>
  </article>
</template>
