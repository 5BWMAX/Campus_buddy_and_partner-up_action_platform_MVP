<script setup lang="ts">
import { ref, inject, onMounted } from 'vue'
import { Heart, RefreshCw, ArrowUpRight, CalendarDays, MapPin, Users, Compass } from 'lucide-vue-next'
import { api, label, date, describeError, unfavoriteActivity, type Data } from '../api'
const open = inject<(id: string) => void>('openActivity')!
const favorites = inject<{
  isFavorite: (id: string) => boolean
  toggleFavorite: (id: string) => Promise<void>
  syncFavorites: (items: Data[]) => void
  setFavorite: (id: string, value: boolean) => void
}>('favorites')!
const notify = inject<(s: string, e?: boolean) => void>('notify')!
const rows = ref<Data[]>([]), cursor = ref<string | null>(null), loading = ref(true), error = ref(''), busy = ref(false)
async function load(more = false) {
  loading.value = true; error.value = ''
  try {
    const p = await api(`/activities/favorites/mine?pageSize=12${more && cursor.value ? '&cursor=' + encodeURIComponent(cursor.value) : ''}`)
    rows.value = more ? [...rows.value, ...p.items] : p.items
    cursor.value = p.nextCursor
    favorites.syncFavorites(rows.value)
  } catch (e) { error.value = describeError(e) } finally { loading.value = false }
}
function state(a: Data) {
  if (a.status !== 'OPEN') return label(a.status)
  if (Date.now() >= new Date(a.deadline).getTime()) return '报名截止'
  return a.remaining <= 0 ? '名额已满' : '招募中'
}
async function remove(a: Data) {
  busy.value = true; error.value = ''
  try {
    await unfavoriteActivity(a.id)
    rows.value = rows.value.filter(x => x.id !== a.id)
    favorites.setFavorite(a.id, false)
    notify('已取消收藏')
  } catch (e) { error.value = describeError(e) } finally { busy.value = false }
}
onMounted(() => load())
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">KEEP THE PLANS YOU LIKE</span>
      <h1>我的收藏</h1>
      <p>把感兴趣的行动先收在这里，想出发时再回来看看。</p>
    </div>
    <button class="secondary" @click="load()"><RefreshCw :size="16"/>刷新</button>
  </div>
  <div v-if="error" class="error-box" role="alert">{{error}}</div>
  <div v-if="loading && !rows.length" class="loading">正在加载收藏…</div>
  <div class="activity-grid">
    <article v-for="a in rows" :key="a.id" class="activity-card">
      <div class="card-top">
        <span :class="['category', a.type]">{{label(a.type)}}</span>
        <span :class="['status', {terminal: state(a) !== '招募中'}]">{{state(a)}}</span>
        <button class="icon-button fav-btn active" :disabled="busy" title="取消收藏" @click.stop="remove(a)">
          <Heart :size="16" fill="currentColor"/>
        </button>
      </div>
      <h3>{{a.title}}</h3>
      <p class="card-description">{{a.description || '一个小计划，期待你的加入。'}}</p>
      <div class="card-meta">
        <div><CalendarDays :size="14"/><span>{{date(a.startAt)}}</span></div>
        <div><MapPin :size="14"/><span>{{a.publicLocation}} · {{a.campus}}</span></div>
        <div><Users :size="14"/><span>{{a.ownerName}} 发起 · {{a.occupied}} / {{a.capacity}} 人</span></div>
      </div>
      <div class="tags"><span v-for="t in a.tags" :key="t"># {{t}}</span></div>
      <div class="card-bottom">
        <small>收藏于 {{date(a.favoritedAt)}}</small>
        <button class="soft-button" @click="open(a.id)">看看这个计划<ArrowUpRight :size="13"/></button>
      </div>
      <div class="capacity-line"><span :style="{width: Math.min(100, a.occupied / a.capacity * 100) + '%'}"></span></div>
    </article>
    <div v-if="!loading && !rows.length && !error" class="empty-state">
      <Compass :size="35"/>
      <h2>还没有收藏的行动</h2>
      <p>在发现页点一下心形，就能把感兴趣的计划收在这里。</p>
    </div>
  </div>
  <button v-if="cursor" class="secondary load-more" :disabled="loading" @click="load(true)">
    {{loading ? '正在加载…' : '查看更多收藏'}}
  </button>
</template>
