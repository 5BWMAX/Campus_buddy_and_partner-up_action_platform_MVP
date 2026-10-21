<script setup lang="ts">
import { ref, reactive, inject, onMounted, onBeforeUnmount } from 'vue'
import { Search, SlidersHorizontal, MapPin, CalendarDays, Users, ArrowUpRight, Plus, BookOpen, Bike, Sparkles, Compass, Heart, History, X } from 'lucide-vue-next'
import { api, session, label, date, describeError, getSearchHistory, clearSearchHistory, deleteSearchHistory, type Data } from '../api'
defineEmits(['publish'])
const open=inject<(id:string)=>void>('openActivity')!
const items=ref<Data[]>([]), loading=ref(true), error=ref(''), cursor=ref<string|null>(null), advanced=ref(false), sort=ref('START_TIME')
const filters=reactive({q:'',type:'',campus:'',tag:'',from:'',to:'',minRemaining:''})
const geo=ref<{lat:number,lon:number}|null>(null)
const favorites=inject<{isFavorite:(id:string)=>boolean,toggleFavorite:(id:string)=>Promise<void>,syncFavorites:(items:Data[])=>void}>('favorites')!, searchHistory=ref<Data[]>([]), showHistory=ref(false)
let requestVersion=0
onBeforeUnmount(()=>{requestVersion++})
async function load(more=false){
 if(more&&(loading.value||!cursor.value))return
 const version=++requestVersion
 if(!more)cursor.value=null
 loading.value=true;error.value='';try{
 const body:Data={sort:sort.value,pageSize:12,...Object.fromEntries(Object.entries(filters).filter(([,v])=>v!==''))}
 for(const k of ['from','to'])if(body[k])body[k]=new Date(body[k]).toISOString()
 if(more&&cursor.value)body.cursor=cursor.value
 if(sort.value==='DISTANCE'){if(!geo.value)throw new Error('请先允许浏览器使用当前位置');Object.assign(body,geo.value,{locationConsent:true})}
 const data=sort.value==='DISTANCE'?await api('/activities/search','POST',body):await api('/activities?'+new URLSearchParams(body).toString())
 if(version!==requestVersion)return
 items.value=more?[...items.value,...data.items]:data.items;cursor.value=data.nextCursor;if(session.user)favorites.syncFavorites(items.value)
 }catch(e){if(version===requestVersion)error.value=describeError(e)}finally{if(version===requestVersion)loading.value=false}}
async function changeSort(){if(sort.value==='DISTANCE'){try{const p=await new Promise<GeolocationPosition>((resolve,reject)=>navigator.geolocation.getCurrentPosition(resolve,reject,{timeout:10000}));geo.value={lat:p.coords.latitude,lon:p.coords.longitude}}catch{sort.value='START_TIME';error.value='未获得位置授权，已保留按开始时间排序。';return}}else geo.value=null;await load()}
function pick(type:string){filters.type=type;load()}
function state(a:Data){if(a.status!=='OPEN')return label(a.status);if(Date.now()>=new Date(a.deadline).getTime())return '报名截止';return a.remaining<=0?'名额已满':'招募中'}
async function toggleFavorite(id:string){
 try{await favorites.toggleFavorite(id)}catch(e){error.value=describeError(e)}
}
async function loadSearchHistory(){try{searchHistory.value=await getSearchHistory()}catch{}}
async function useHistory(kw:string){filters.q=kw;showHistory.value=false;await load()}
async function removeHistory(kw:string){try{await deleteSearchHistory(kw);searchHistory.value=searchHistory.value.filter(h=>h.keyword!==kw)}catch(e){error.value=describeError(e)}}
async function clearHistory(){try{await clearSearchHistory();searchHistory.value=[]}catch(e){error.value=describeError(e)}}
function hideHistoryDelay(){setTimeout(()=>showHistory.value=false,200)}
onMounted(()=>{load();loadSearchHistory()})
</script>
<template>
 <div class="page-heading"><div><span class="eyebrow">FIND YOUR CAMPUS PEOPLE</span><h1>你好，今天想一起做什么？</h1><p>从一顿午饭到一个小目标，找到与你同频的校园搭子。</p></div><button class="primary" @click="$emit('publish')"><Plus :size="17"/>发起一个行动</button></div>
 <section class="hero"><div><div class="hero-kicker">MAKE A LITTLE PLAN</div><h2>一个人想做的事，<br>一起就出发。</h2><p>明确时间 · 认真相约 · 让每个计划都有回应</p></div><div class="hero-art" aria-hidden="true"><div class="art-orbit"></div><div class="art-card"><BookOpen :size="27"/>一起专注一小时</div><div class="art-card second"><Bike :size="29"/>下课后，去运动</div><Sparkles class="art-spark" :size="25"/></div></section>
 <form class="filter-panel" @submit.prevent="load()"><div class="search-row"><label class="search-box"><Search :size="19"/><span class="visually-hidden">搜索活动</span><input v-model="filters.q" placeholder="搜索活动、目标或地点…" maxlength="100" @focus="showHistory=true;loadSearchHistory()" @blur="hideHistoryDelay"/></label><button class="secondary" type="submit">搜索</button><button v-if="searchHistory.length" class="icon-button" type="button" title="搜索历史" @click="showHistory=!showHistory"><History :size="18"/></button></div><div v-if="showHistory&&searchHistory.length" class="search-history"><div class="history-header"><span>最近搜索</span><button type="button" class="text-button" @click="clearHistory">清空</button></div><div class="history-items"><span v-for="h in searchHistory" :key="h.keyword" class="history-chip" @mousedown.prevent="useHistory(h.keyword)">{{h.keyword}}<button class="icon-button" @click.stop="removeHistory(h.keyword)"><X :size="12"/></button></span></div></div><div class="filter-row"><button v-for="t in ['', 'STUDY','SPORT','MEAL','TRAVEL','OTHER']" :key="t" type="button" :class="['chip',{active:filters.type===t}]" @click="pick(t)">{{t?label(t):'全部活动'}}</button><button class="filter-toggle" type="button" :aria-expanded="advanced" @click="advanced=!advanced"><SlidersHorizontal :size="14"/>更多筛选</button></div><div v-if="advanced" class="advanced-filter"><label>校区<input v-model="filters.campus" placeholder="例如：主校区"/></label><label>标签<input v-model="filters.tag" placeholder="例如：自习"/></label><label>至少剩余名额<input v-model="filters.minRemaining" type="number" min="0"/></label><label>开始时间自<input v-model="filters.from" type="datetime-local"/></label><label>开始时间至（不含）<input v-model="filters.to" type="datetime-local"/></label><button type="submit" class="secondary">应用筛选</button></div></form>
 <div class="results-heading"><h2>发现同行的可能<span>{{items.length}} 个已加载</span></h2><label><span class="visually-hidden">活动排序</span><select v-model="sort" @change="changeSort"><option value="START_TIME">按开始时间</option><option value="DISTANCE">授权位置后按距离</option></select></label></div>
 <p v-if="sort==='DISTANCE'" class="subtle">位置仅用于本次排序，不保存。距离基于活动公开位置。</p>
 <div v-if="error" class="error-box" role="alert">{{error}} <button class="text-button" @click="load()">重试</button></div>
 <div v-if="loading&&!items.length" class="loading">正在寻找校园里的新计划…</div>
 <div class="activity-grid"><article v-for="a in items" :key="a.id" class="activity-card"><div class="card-top"><span :class="['category',a.type]">{{label(a.type)}}</span><span :class="['status',{terminal:state(a)!=='招募中'}]">{{state(a)}}</span><button class="icon-button fav-btn" :class="{active:favorites.isFavorite(a.id)}" @click.stop="toggleFavorite(a.id)" :title="favorites.isFavorite(a.id)?'取消收藏':'收藏活动'"><Heart :size="16" :fill="favorites.isFavorite(a.id)?'currentColor':'none'"/></button></div><h3>{{a.title}}</h3><p class="card-description">{{a.description||'一个小计划，期待你的加入。'}}</p><div class="card-meta"><div><CalendarDays :size="14"/><span>{{date(a.startAt)}}</span></div><div><MapPin :size="14"/><span>{{a.publicLocation}} · {{a.campus}}</span></div><div><Users :size="14"/><span>{{a.ownerName}} 发起 · {{a.occupied}} / {{a.capacity}} 人</span></div></div><div class="tags"><span v-for="t in a.tags" :key="t"># {{t}}</span><span v-if="a.distance!=null&&a.distance<1e19">约 {{(a.distance/1000).toFixed(1)}} km</span></div><div class="card-bottom"><small v-if="state(a)==='招募中'">还差 <strong>{{a.remaining}}</strong> 位同行者</small><small v-else>{{state(a)}}</small><button class="soft-button" @click="open(a.id)">看看这个计划<ArrowUpRight :size="13"/></button></div><div class="capacity-line"><span :style="{width:Math.min(100,a.occupied/a.capacity*100)+'%'}"></span></div></article><div v-if="!loading&&!items.length&&!error" class="empty-state"><Compass :size="35"/><h2>还没有找到这样的计划</h2><p>试试其他关键词，或成为第一个发起人。</p><button class="primary" @click="$emit('publish')">发起一个行动</button></div></div>
 <button v-if="cursor" class="secondary load-more" :disabled="loading" @click="load(true)">{{loading?'正在加载…':'查看更多活动'}}</button>
</template>
