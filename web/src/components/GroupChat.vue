<script setup lang="ts">
import {ref, onMounted, onUnmounted, inject} from 'vue'
import {X, Send, RefreshCw, Users, Pin, Image as ImageIcon, Undo2} from 'lucide-vue-next'
import {api,session,date,label,describeError,uploadImage,recallMessage,type Data} from '../api'
const props=defineProps<{activity:Data}>(),emit=defineEmits(['close']),notify=inject<(s:string,e?:boolean)=>void>('notify')!
const members=ref<Data[]>([]),messages=ref<Data[]>([]),mode=ref('READ_ONLY'),pin=ref<Data|null>(null),error=ref(''),body=ref(''),busy=ref(false),cursor=ref<string|null>(null),showMembers=ref(false),showPin=ref(false),pinText=ref(''),reportTarget=ref<Data|null>(null),reason=ref(''),evidence=ref(''),uploading=ref(false)
let draft={text:'',id:crypto.randomUUID()},timer:ReturnType<typeof setInterval>|undefined
let loading=false
async function load(older=false,refresh=false){
 if(loading)return;loading=true
 try{
  const prefix=`/groups/${props.activity.groupId}/messages?pageSize=30`
  let p=await api(prefix+(older&&cursor.value?'&cursor='+encodeURIComponent(cursor.value):''))
  const first=p,items:Data[]=[...p.items]
  if(refresh){
   const target=Math.max(30,messages.value.length)
   while(p.nextCursor&&items.length<target){p=await api(prefix+'&cursor='+encodeURIComponent(p.nextCursor));items.push(...p.items)}
  }
  const all=older?[...messages.value,...items]:items
  messages.value=[...new Map(all.map(m=>[m.id,m])).values()]
  cursor.value=p.nextCursor;mode.value=first.accessMode;pin.value=first.pin
  members.value=await api(`/groups/${props.activity.groupId}/members`);error.value=''
 }catch(e){messages.value=[];members.value=[];cursor.value=null;mode.value='BLOCKED';error.value=describeError(e)}
 finally{loading=false}
}
onMounted(()=>{load();timer=setInterval(()=>{if(!document.hidden&&!busy.value)load(false,true)},5000)});onUnmounted(()=>{if(timer)clearInterval(timer)})
async function send(){busy.value=true;try{if(body.value!==draft.text)draft={text:body.value,id:crypto.randomUUID()};await api(`/groups/${props.activity.groupId}/messages`,'POST',{clientMessageId:draft.id,body:body.value});body.value='';draft={text:'',id:crypto.randomUUID()};await load()}catch(e){error.value=describeError(e)}finally{busy.value=false}}
async function sendImage(file:File){
 if(!file.type.startsWith('image/')){error.value='仅支持图片文件';return}
 if(file.size>5*1024*1024){error.value='图片大小不能超过5MB';return}
 uploading.value=true;error.value=''
 try{
  const result=await uploadImage(file)
  await api(`/groups/${props.activity.groupId}/messages`,'POST',{clientMessageId:crypto.randomUUID(),mediaType:'IMAGE',mediaUrl:result.url})
  await load()
 }catch(e){error.value=describeError(e)}finally{uploading.value=false}
}
async function onImagePick(e:Event){const f=(e.target as HTMLInputElement).files?.[0];if(f)await sendImage(f);(e.target as HTMLInputElement).value=''}
function canRecall(m:Data){return m.senderId===session.user?.id&&!m.recalledAt&&!m.hidden&&Date.now()-new Date(m.createdAt).getTime()<120000}
async function recall(m:Data){busy.value=true;try{await recallMessage(props.activity.groupId,m.id);notify('消息已撤回');await load()}catch(e){error.value=describeError(e)}finally{busy.value=false}}
function openImage(url:string){window.open(url,'_blank')}
async function savePin(){busy.value=true;try{await api(`/groups/${props.activity.groupId}/pin`,'PUT',{body:pinText.value,expectedVersion:pin.value?.version??0});showPin.value=false;await load();notify('小组置顶已更新')}catch(e){error.value=describeError(e)}finally{busy.value=false}}
function reporting(type:string,id:string,name:string){reportTarget.value={type,id,name};reason.value='';evidence.value=''}
async function report(){busy.value=true;try{await api('/reports','POST',{targetType:reportTarget.value!.type,targetId:reportTarget.value!.id,reason:reason.value,evidence:evidence.value});reportTarget.value=null;notify('举报已提交，可在举报与申诉中查看进度')}catch(e){error.value=describeError(e)}finally{busy.value=false}}
</script>
<template><div class="modal-backdrop" @click.self="emit('close')"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="group-title"><button class="modal-close icon-button" aria-label="关闭小组" @click="emit('close')"><X/></button><span class="eyebrow">A SMALL GROUP, A SHARED PLAN</span><h2 id="group-title">{{activity.title}}</h2><div class="row-between"><span class="badge">{{mode==='ACTIVE'?'临时小组':label(mode)}}</span><div class="actions"><button class="text-button" @click="showMembers=!showMembers"><Users :size="15"/> {{members.length}} 位成员</button><button class="icon-button" aria-label="刷新群聊" @click="load()"><RefreshCw :size="16"/></button></div></div><div v-if="showMembers" class="stack" style="margin:15px 0"><div v-for="m in members" :key="m.id" class="member-chip"><span class="avatar">{{m.nickname.slice(0,1)}}</span><div><strong>{{m.nickname}}</strong><small>{{label(m.role)}} · {{label(m.confirmation)}}<span v-if="m.contact"> · {{m.contact}}</span></small></div><button v-if="m.id!==session.user?.id" class="text-button" style="margin-left:auto" @click="reporting('USER',m.id,m.nickname)">举报</button></div></div><div v-if="pin?.body" class="pin-box"><Pin :size="14"/> {{pin.body}}</div><button v-if="activity.ownerId===session.user?.id&&mode==='ACTIVE'" class="text-button" @click="pinText=pin?.body||'';showPin=!showPin">{{pin?.body?'编辑置顶安排':'添加置顶安排'}}</button><form v-if="showPin" class="stack" style="margin:12px 0" @submit.prevent="savePin"><label>小组置顶内容<textarea v-model="pinText" required maxlength="2000"></textarea></label><button class="secondary" :disabled="busy">保存置顶</button></form><div v-if="error" class="error-box" role="alert">{{error}}</div><button v-if="cursor" class="text-button" @click="load(true)">加载更早消息</button><div class="messages" aria-label="聊天消息"><article v-for="m in messages" :key="m.id" :class="['message',{own:m.senderId===session.user?.id}]"><small>{{m.nickname}} · {{date(m.createdAt)}}</small><template v-if="m.recalledAt"><p class="recalled">消息已撤回</p></template><template v-else-if="m.hidden"><p>此消息已被屏蔽</p></template><template v-else><p v-if="m.body">{{m.body}}</p><div v-if="m.mediaType==='IMAGE'" class="msg-image"><img :src="m.mediaUrl" alt="图片消息" @click="openImage(m.mediaUrl)"/></div><div class="msg-actions"><button v-if="m.senderId!==session.user?.id" class="subtle" @click="reporting('MESSAGE',m.id,'这条消息')">举报</button><button v-if="canRecall(m)" class="subtle" @click="recall(m)"><Undo2 :size="12"/>撤回</button></div></template></article><p v-if="!messages.length&&!error" class="muted" style="text-align:center;margin:auto">第一句「你好」，让计划开始。</p></div><form v-if="mode==='ACTIVE'" class="chat-compose" @submit.prevent="send"><label class="icon-button image-upload" title="发送图片"><ImageIcon :size="18"/><input type="file" accept="image/*" @change="onImagePick" style="display:none"/></label><label style="flex:1"><span class="visually-hidden">群聊消息</span><textarea v-model="body" maxlength="1000" placeholder="说说你的安排…"></textarea></label><button class="primary" :disabled="busy||uploading">{{uploading?'上传中…':'发送'}}<Send :size="17"/></button></form><p v-else class="field-help">当前小组只可阅读历史内容，无法发送新消息。</p><form v-if="reportTarget" class="report-form stack" @submit.prevent="report"><h3>举报 {{reportTarget.name}}</h3><label>具体理由<textarea v-model="reason" required maxlength="1000"></textarea></label><label>证据说明<textarea v-model="evidence" maxlength="2000"></textarea></label><div class="actions"><button type="button" class="secondary" @click="reportTarget=null">取消</button><button class="danger" :disabled="busy">提交举报</button></div></form></section></div></template>
