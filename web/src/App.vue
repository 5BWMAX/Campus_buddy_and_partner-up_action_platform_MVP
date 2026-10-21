<script setup lang="ts">
import { ref, reactive, onMounted, provide, computed, watch, nextTick } from 'vue'
import { Compass, CalendarDays, Bell, Heart, UserRound, ShieldCheck, ArrowUpRight, Plus, Sprout, LogOut, Menu, X, Check, CircleAlert } from 'lucide-vue-next'
import { api, session, refreshSession, login, describeError, favoriteActivity, unfavoriteActivity, type Data } from './api'
import Discover from './components/Discover.vue'
import Favorites from './components/Favorites.vue'
import ActivityDialog from './components/ActivityDialog.vue'
import MyActivities from './components/MyActivities.vue'
import GroupChat from './components/GroupChat.vue'
import Notifications from './components/Notifications.vue'
import Profile from './components/Profile.vue'
import Governance from './components/Governance.vue'
import AccountForm from './components/AccountForm.vue'
import PasswordForm from './components/PasswordForm.vue'
const tab = ref('discover'), revision = ref(0), accounts = ref<Data[]>([]), loginOpen = ref(false), mobileOpen = ref(false), busy = ref(false)
const loginDialog=ref<HTMLElement|null>(null)
let loginTrigger:HTMLElement|null=null
watch(loginOpen,async open=>{
 if(open)loginTrigger=document.activeElement instanceof HTMLElement?document.activeElement:null
 await nextTick()
 if(open&&loginOpen.value)loginDialog.value?.querySelector<HTMLElement>('button')?.focus()
 else if(!loginOpen.value){
  const target=loginTrigger?.isConnected?loginTrigger:document.querySelector<HTMLElement>('.identity')
  target?.focus()
 }
})
function loginKeydown(event:KeyboardEvent){
 if(event.key==='Escape'){event.preventDefault();event.stopPropagation();loginOpen.value=false;return}
 if(event.key!=='Tab')return
 const controls=Array.from(loginDialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])')||[]).filter(el=>el.getClientRects().length>0)
 const first=controls[0],last=controls[controls.length-1]
 if(!first){event.preventDefault();loginDialog.value?.focus();return}
 if(event.shiftKey&&(document.activeElement===first||document.activeElement===loginDialog.value)){event.preventDefault();last?.focus()}
 else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
}
const dialog = ref<{id?:string, create?:boolean, edit?:boolean}|null>(null), group = ref<Data|null>(null)
const alert = ref(''), alertError = ref(false), ready = ref(false), unread = ref(0)
let timer: ReturnType<typeof setTimeout> | undefined
function notify(message:string, error=false) { alert.value=message; alertError.value=error; if(timer)clearTimeout(timer);timer=setTimeout(()=>alert.value='',error?15000:5500) }
provide('notify',notify)
provide('openActivity',(id:string)=>dialog.value={id})
provide('openGroup',(a:Data)=>group.value=a)
provide('requireLogin',()=>{loginOpen.value=true})
provide('changed',()=>{revision.value++; updateUnread()})
const favorites = reactive<Record<string, boolean>>({})
function isFavorite(id:string){ return favorites[id]===true }
function setFavorite(id:string, value:boolean){ if(value) favorites[id]=true; else delete favorites[id] }
function syncFavorites(items:Data[]){ for(const a of items) if(a?.id) setFavorite(String(a.id), a.favorited===true) }
async function toggleFavorite(id:string){
 const was=isFavorite(id)
 await (was?unfavoriteActivity(id):favoriteActivity(id))
 setFavorite(id,!was)
 notify(was?'已取消收藏':'已收藏活动')
}
provide('favorites',{isFavorite,toggleFavorite,syncFavorites,setFavorite})
async function updateUnread(){if(!session.user || session.user.status!=='NORMAL'||session.user.passwordChangeRequired){unread.value=0;return}try{unread.value=(await api('/notifications?pageSize=1')).unreadCount}catch(e){notify(describeError(e),true)}}
async function bootstrap(){try{await refreshSession();if(session.demo)accounts.value=await api('/auth/demo/accounts');await updateUnread();ready.value=true}catch(e){notify(describeError(e),true)}}
onMounted(bootstrap)
async function choose(id:string){busy.value=true;try{await login(id);loginOpen.value=false;group.value=null;dialog.value=null;revision.value++;if(session.user?.status!=='NORMAL')tab.value='governance';await updateUnread();notify(`已切换为${session.user?.nickname}`)}catch(e){notify(describeError(e),true)}finally{busy.value=false}}
async function logout(){try{await api('/auth/logout','POST');await refreshSession();group.value=null;dialog.value=null;tab.value='discover';revision.value++;unread.value=0}catch(e){notify(describeError(e),true)}}
function navigate(name:string){if(name!=='discover'&&!session.user){loginOpen.value=true;return}tab.value=name;mobileOpen.value=false;group.value=null}
function publish(){if(!session.user){loginOpen.value=true;return}dialog.value={create:true}}
function clearUserViews(){group.value=null;dialog.value=null;unread.value=0;Object.keys(favorites).forEach(key=>delete favorites[key]);revision.value++}
async function authenticated(){clearUserViews();loginOpen.value=false;tab.value=session.user?.status==='NORMAL'?'discover':'governance';await updateUnread();notify(`欢迎，${session.user?.nickname}`)}
async function passwordChanged(){
 clearUserViews()
 try{await refreshSession()}catch(e){session.user=null;session.csrf='';notify(describeError(e),true)}
 tab.value='discover';loginOpen.value=true;notify('密码已修改，请使用新密码重新登录。')
}
function openIdentity(){if(!session.demo&&session.user){navigate('profile');return}loginOpen.value=true}
watch(()=>session.user?.id,(current,previous)=>{if(current!==previous){clearUserViews();if(!current)tab.value='discover'}})
const nav=computed(()=>[
 {id:'discover',name:'发现搭子',icon:Compass}, {id:'mine',name:'我的行动',icon:CalendarDays}, {id:'favorites',name:'我的收藏',icon:Heart}, {id:'notifications',name:'通知中心',icon:Bell}, {id:'profile',name:'我的资料',icon:UserRound}, {id:'governance',name:session.user?.role==='ADMIN'?'治理与统计':'举报与申诉',icon:ShieldCheck}
])
</script>
<template>
<div class="app-shell">
 <aside class="sidebar" :class="{expanded:mobileOpen}" :inert="loginOpen">
  <a class="brand" href="#" @click.prevent="navigate('discover')"><span class="brand-mark"><Sprout :size="27"/></span><span>同行<span class="brand-small">校园搭子 · 行动平台</span></span></a>
  <button class="mobile-close icon-button" aria-label="关闭导航" @click="mobileOpen=false"><X/></button>
  <div class="campus-label"><span class="dot"></span> 主校区 <span>校园生活，从这里开始</span></div>
  <nav aria-label="主导航"><button v-for="n in nav" :key="n.id" :class="['nav-item',{selected:tab===n.id}]" @click="navigate(n.id)"><component :is="n.icon" :size="20"/><span>{{n.name}}</span><b v-if="n.id==='notifications'&&unread" class="count">{{unread}}</b></button></nav>
  <div class="sidebar-note"><span class="eyebrow">A LITTLE PLAN, TOGETHER</span><p>小计划，<br>也值得一起完成。</p><span>找一个同频的人，<br>让校园日常多一点可能。</span><ArrowUpRight :size="26"/></div>
  <div class="sidebar-bottom"><template v-if="session.demo"><span class="demo-tag">本地演示</span><small>虚拟身份 · 模拟外部推送</small></template><template v-else><span class="demo-tag">开放参与</span><small>浏览活动 · 注册后参与</small></template></div>
 </aside>
 <div class="main-shell" :inert="loginOpen">
  <header class="topbar"><div class="header-left"><button class="mobile-menu icon-button" aria-label="展开导航" @click="mobileOpen=true"><Menu/></button><span>校园 / <strong>{{nav.find(n=>n.id===tab)?.name}}</strong></span></div><div class="header-actions"><button class="icon-button" aria-label="查看通知" @click="navigate('notifications')"><Bell :size="20"/><i v-if="unread" class="notification-dot"></i></button><button class="identity" :disabled="!ready" @click="openIdentity"><span class="avatar">{{session.user?.nickname?.slice(0,1)||'访'}}</span><span>{{session.user?.nickname||(session.demo?'选择演示身份':'登录 / 注册')}}</span></button><button v-if="session.user" class="icon-button" aria-label="退出登录" @click="logout"><LogOut :size="18"/></button></div></header>
  <main id="main-content">
   <div v-if="!ready" class="empty-state"><CircleAlert/><h2>正在连接校园平台</h2><p>如果连接失败，可重试加载。</p><button class="primary" @click="bootstrap">重新连接</button></div>
   <PasswordForm v-else-if="session.user?.passwordChangeRequired" required-change @changed="passwordChanged"/>
   <template v-else>
    <div v-if="session.user && session.user.status!=='NORMAL'" class="warning-box">当前账号已受限，仍可查看相关举报并提出申诉。</div>
    <Discover v-if="tab==='discover'" :key="`discover-${revision}`" @publish="publish"/>
    <MyActivities v-else-if="tab==='mine'" :key="`mine-${revision}`" @edit="id=>dialog={id,edit:true}" @publish="publish"/>
    <Favorites v-else-if="tab==='favorites'" :key="`favorites-${revision}`"/>
    <Notifications v-else-if="tab==='notifications'" :key="`notices-${revision}`" @unread="n=>unread=n"/>
    <Profile v-else-if="tab==='profile'" :key="`profile-${revision}`" @deleted="bootstrap();tab='discover';revision++" @password-changed="passwordChanged"/>
    <Governance v-else-if="tab==='governance'" :key="`governance-${revision}`"/>
   </template>
  </main>
  <footer>让每一次相约，都有回应。 <span>同行 · 校园搭子与搭伙行动平台</span></footer>
 </div>
 <div v-if="alert" :inert="loginOpen" :class="['toast',{error:alertError}]" role="alert"><CircleAlert v-if="alertError" :size="21"/><Check v-else :size="21"/><span>{{alert}}</span><button class="icon-button" aria-label="关闭提示" @click="alert=''"><X :size="18"/></button></div>
 <ActivityDialog :inert="loginOpen" v-if="dialog&&!session.user?.passwordChangeRequired" :options="dialog" @close="dialog=null" @saved="revision++;updateUnread()"/>
 <GroupChat :inert="loginOpen" v-if="group&&!session.user?.passwordChangeRequired" :activity="group" @close="group=null"/>
 <div v-if="loginOpen" class="modal-backdrop" @click.self="loginOpen=false"><section ref="loginDialog" class="modal login-modal" tabindex="-1" @keydown="loginKeydown" role="dialog" aria-modal="true" aria-labelledby="login-title"><button class="modal-close icon-button" :aria-label="session.demo?'关闭身份选择':'关闭登录窗口'" @click="loginOpen=false"><X/></button><span class="eyebrow">WELCOME TO TOGETHER</span><template v-if="session.demo"><h2 id="login-title">以一个身份，开始同行</h2><p class="muted">以下均为虚拟演示账号。切换身份可以体验发起、申请与审核流程。</p><div class="account-list"><button v-for="a in accounts" :key="a.id" :disabled="busy" @click="choose(a.id)"><span class="avatar">{{a.nickname.slice(0,1)}}</span><span><strong>{{a.nickname}}</strong><small>{{a.role==='ADMIN'?'平台管理员':a.verified?'已验证校内身份':'待认证身份'}}{{a.status!=='NORMAL'?' · 已受限':''}}</small></span><ArrowUpRight :size="19"/></button></div></template><AccountForm v-else @authenticated="authenticated"/></section></div>
</div>
</template>
