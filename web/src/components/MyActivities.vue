<script setup lang="ts">
import { ref, computed, onMounted, inject } from 'vue'
import { CalendarDays, Plus, RefreshCw, X } from 'lucide-vue-next'
import { api, session, label, date, describeError, type Data } from '../api'
import FeedbackDialog from './FeedbackDialog.vue'
defineEmits(['edit','publish'])
const notify=inject<(s:string,e?:boolean)=>void>('notify')!,open=inject<(id:string)=>void>('openActivity')!,openGroup=inject<(a:Data)=>void>('openGroup')!
const rows=ref<Data[]>([]),filter=ref('all'),loading=ref(true),busy=ref(false),error=ref(''),expanded=ref<string|null>(null),applications=ref<Data[]>([]),members=ref<Data[]>([]),feedback=ref<Data|null>(null)
const action=ref<{activity:Data,kind:string,member?:Data,application?:Data}|null>(null),reason=ref('')
const filtered=computed(()=>rows.value.filter(a=>filter.value==='all'||(filter.value==='owner'?a.ownerId===session.user?.id:filter.value==='pending'?a.applicationStatus==='PENDING':a.participationStatus==='ACTIVE'&&a.ownerId!==session.user?.id)))
async function load(){loading.value=true;error.value='';try{rows.value=await api('/me/participations')}catch(e){error.value=describeError(e)}finally{loading.value=false}}
onMounted(load)
async function manage(a:Data){if(expanded.value===a.id){expanded.value=null;return}try{const [apps,people]=await Promise.all([api(`/activities/${a.id}/applications`),api(`/activities/${a.id}/participations`)]);applications.value=apps;members.value=people;expanded.value=a.id}catch(e){notify(describeError(e),true)}}
async function refreshManagement(a:Data){if(expanded.value===a.id){expanded.value=null;await manage(a)}}
async function approve(a:Data,p:Data){busy.value=true;try{await api(`/activities/${a.id}/applications/${p.id}/approval`,'POST',{round:p.round,expectedVersion:p.version},true);notify('审核通过，成员已入组');await load();await refreshManagement(a)}catch(e){notify(describeError(e),true)}finally{busy.value=false}}
async function confirm(a:Data,status:string){busy.value=true;try{await api(`/activities/${a.id}/participations/me/confirmation`,'PATCH',{status,expectedVersion:a.participationVersion});notify(status==='LEAVE'?'已请假，名额继续保留':'已确认参加');await load()}catch(e){notify(describeError(e),true)}finally{busy.value=false}}
async function checkin(a:Data){busy.value=true;try{await api(`/activities/${a.id}/participations/me/checkin`,'POST',{status:'SELF_REPORTED'});notify('自报签到已记录');await load()}catch(e){notify(describeError(e),true)}finally{busy.value=false}}
function choose(a:Data,kind:string,member?:Data,application?:Data){action.value={activity:a,kind,member,application};reason.value='';error.value=''}
const actionLabels:Record<string,string>={exit:'退出行动',cancel:'取消活动',complete:'结束活动',archive:'归档活动',withdraw:'撤回申请',remove:'移除成员',reject:'拒绝申请'}
async function execute(){if(!action.value)return;busy.value=true;error.value='';const {activity:a,kind,member,application:p}=action.value;try{
 let path=`/activities/${a.id}`,body:Data={expectedVersion:a.version,reason:reason.value},idem=false
 if(kind==='exit'){path+='/participations/me/exit';body={reason:reason.value};idem=true}
 if(kind==='cancel')path+='/cancellation'
 if(kind==='complete')path+='/completion'
 if(kind==='archive')path+='/archive'
 if(kind==='withdraw'){path+=`/applications/${a.applicationId}/withdrawal`;body={round:a.round,expectedVersion:a.applicationVersion}}
 if(kind==='reject'){path+=`/applications/${p!.id}/rejection`;body={reason:reason.value,round:p!.round,expectedVersion:p!.version}}
 if(kind==='remove'){path+=`/participations/${member!.id}/removal`;body={reason:reason.value}}
 await api(path,'POST',body,idem);notify(`${actionLabels[kind]}成功`);action.value=null;await load();await refreshManagement(a)
 }catch(e){error.value=describeError(e)}finally{busy.value=false}}
function active(a:Data){return a.participationStatus==='ACTIVE'}
function beforeConfirm(a:Data){return a.status==='OPEN'&&Date.now()<new Date(a.confirmationDeadline).getTime()}
function inProgress(a:Data){return ['OPEN','IN_PROGRESS'].includes(a.status)&&Date.now()>=new Date(a.startAt).getTime()&&Date.now()<new Date(a.endAt).getTime()}
</script>
<template><div class="page-heading"><div><span class="eyebrow">TURN PLANS INTO DAYS</span><h1>我的行动</h1><p>从一次申请到一次完成，把每个约定放在心上。</p></div><button class="primary" @click="$emit('publish')"><Plus :size="17"/>发起行动</button></div><div class="tabs"><button v-for="f in [{id:'all',name:'全部行动'},{id:'owner',name:'我发起的'},{id:'joined',name:'我加入的'},{id:'pending',name:'等待审核'}]" :key="f.id" :class="['chip',{active:filter===f.id}]" @click="filter=f.id">{{f.name}}</button><button class="icon-button" aria-label="刷新我的行动" @click="load"><RefreshCw :size="17"/></button></div><div v-if="error&&!action" class="error-box" role="alert">{{error}}</div><div v-if="loading" class="loading">正在加载行动记录…</div><div v-else class="stack"><article v-for="a in filtered" :key="a.id" class="panel"><div class="row-between"><div><div class="actions"><span :class="['category',a.type]">{{label(a.type)}}</span><span class="badge">{{label(a.status)}}</span><span class="subtle">{{a.ownerId===session.user?.id?'我发起的':label(a.participationStatus||a.applicationStatus)}}<template v-if="active(a)"> · {{label(a.confirmation)}}</template></span></div><h3 style="margin:13px 0 5px">{{a.title}}</h3><p class="subtle">{{date(a.startAt)}} — {{date(a.endAt)}}</p></div><button class="text-button" @click="open(a.id)">详情</button></div><div class="actions">
<button v-if="a.ownerId===session.user?.id" class="secondary" @click="manage(a)">{{expanded===a.id?'收起成员与申请':'审核申请 / 管理成员'}}</button>
<button v-if="a.ownerId===session.user?.id&&a.status==='OPEN'&&Date.now()<new Date(a.startAt).getTime()" class="secondary" @click="$emit('edit',a.id)">编辑活动</button>
<button v-if="active(a)&&a.groupId" class="soft-button" @click="openGroup(a)">进入小组</button>
<button v-if="a.applicationStatus==='PENDING'" class="secondary" @click="choose(a,'withdraw')">撤回申请</button>
<template v-if="active(a)&&beforeConfirm(a)"><button class="secondary" :disabled="busy" @click="confirm(a,'CONFIRMED')">确认参加</button><button class="secondary" :disabled="busy" @click="confirm(a,'LEAVE')">请假（保留名额）</button><button v-if="a.ownerId!==session.user?.id" class="danger" @click="choose(a,'exit')">退出行动</button></template>
<button v-if="active(a)&&inProgress(a)" class="secondary" :disabled="busy" @click="checkin(a)">{{a.checkinStatus==='SELF_REPORTED'?'已自报签到':'自报签到'}}</button>
<button v-if="a.ownerId===session.user?.id&&['OPEN','IN_PROGRESS'].includes(a.status)" class="danger" @click="choose(a,'cancel')">取消活动</button>
<button v-if="a.ownerId===session.user?.id&&inProgress(a)" class="primary" @click="choose(a,'complete')">结束活动</button>
<button v-if="a.endedAt" class="primary" @click="feedback=a">完成反馈</button>
<button v-if="a.ownerId===session.user?.id&&['ENDED','CANCELLED'].includes(a.status)" class="secondary" @click="choose(a,'archive')">归档</button>
</div>
<div v-if="expanded===a.id"><h4 class="section-title">申请记录</h4><p v-if="!applications.length" class="subtle">还没有收到申请。待审核申请不会占用名额。</p><div v-for="p in applications" :key="p.id" class="list-row row-between"><div><strong>{{p.nickname}}</strong> <span class="badge">{{label(p.status)}}</span><p class="muted">{{p.note||'未填写留言'}}</p><span class="subtle">第 {{p.round}} 轮申请</span></div><div v-if="p.status==='PENDING'" class="actions"><button class="primary" :disabled="busy" @click="approve(a,p)">通过申请</button><button class="secondary" @click="choose(a,'reject',undefined,p)">拒绝</button></div></div><h4 class="section-title">当前成员 · {{members.length}} 人（包含发起人）</h4><div v-for="m in members" :key="m.id" class="list-row row-between"><div><strong>{{m.nickname}}</strong><span class="subtle"> · {{label(m.role)}} · {{label(m.confirmation)}}</span><p v-if="m.contact" class="subtle">{{m.contact}}</p></div><button v-if="m.role!=='OWNER'" class="text-button" @click="choose(a,'remove',m)">移除</button></div></div>
</article><div v-if="!filtered.length" class="empty-state"><CalendarDays :size="35"/><h2>给今天安排一个小计划</h2><p>你的申请、加入和发起记录会出现在这里。</p><button class="primary" @click="$emit('publish')">发起一个行动</button></div></div>
<div v-if="action" class="modal-backdrop" @click.self="action=null"><form class="modal" role="dialog" aria-modal="true" @submit.prevent="execute"><button type="button" class="modal-close icon-button" aria-label="关闭操作确认" @click="action=null"><X/></button><h2>{{actionLabels[action.kind]}}</h2><p>{{action.activity.title}}</p><p class="muted">{{action.kind==='exit'?'退出后立即释放名额，并失去群聊和成员信息访问权限。再次参加需要重新申请。':action.kind==='complete'?'结束后会记录成员快照，并开启24小时反馈窗口。':action.kind==='cancel'?'活动取消后不能恢复，成员会收到通知，小组变为只读。':action.kind==='archive'?'归档保留原有结束或取消状态，不改变反馈窗口。':'请确认本次操作。'}}</p><label v-if="['exit','cancel','remove','reject'].includes(action.kind)">操作理由<textarea v-model="reason" required maxlength="500"></textarea></label><div v-if="error" class="error-box" role="alert">{{error}}</div><div class="modal-actions"><button type="button" class="secondary" @click="action=null">返回</button><button class="primary" :disabled="busy" type="submit">{{busy?'处理中…':'确认'+actionLabels[action.kind]}}</button></div></form></div><FeedbackDialog v-if="feedback" :activity="feedback" @close="feedback=null"/>
</template>
