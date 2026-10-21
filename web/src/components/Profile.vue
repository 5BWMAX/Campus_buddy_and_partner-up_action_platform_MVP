<script setup lang="ts">
import {reactive,ref,onMounted,inject} from 'vue'
import PasswordForm from './PasswordForm.vue'
import {api,session,refreshSession,describeError,getNotificationPreferences,updateNotificationPreferences,type Data} from '../api'
const emit=defineEmits(['deleted','passwordChanged']),notify=inject<(s:string,e?:boolean)=>void>('notify')!,form=reactive<Data>({nickname:'',avatarUrl:'',department:'',contact:'',contactVisibility:'HIDDEN',locationVisibility:'HIDDEN'}),error=ref(''),busy=ref(false),erasing=ref(false),confirm=ref(false),prefs=reactive<Data>({}),prefsLoading=ref(false)
const PREF_ITEMS=[
 {key:'activityPublished',label:'活动发布通知'},
 {key:'applicationSubmitted',label:'收到新申请'},
 {key:'applicationDecided',label:'申请审核结果'},
 {key:'activityChanged',label:'活动信息变更'},
 {key:'activityCancelled',label:'活动取消通知'},
 {key:'startReminder',label:'活动开始提醒'},
 {key:'pendingReminder',label:'待审核申请提醒'},
 {key:'deadlineReached',label:'报名截止提醒'},
 {key:'feedbackReminder',label:'反馈评价提醒'},
 {key:'governance',label:'治理与申诉通知'},
 {key:'groupMessage',label:'群消息提醒'}
]
onMounted(async()=>{try{Object.assign(form,await api('/me/profile'));loadPrefs()}catch(e){error.value=describeError(e)}})
async function save(privacy=false){busy.value=true;error.value='';try{await api(privacy?'/me/privacy':'/me/profile','PATCH',privacy?{contactVisibility:form.contactVisibility,locationVisibility:form.locationVisibility}:{nickname:form.nickname,avatarUrl:form.avatarUrl,department:form.department,contact:form.contact});await refreshSession();notify(privacy?'隐私设置已保存':'资料已更新')}catch(e){error.value=describeError(e)}finally{busy.value=false}}
async function erase(){busy.value=true;try{await api('/me/account','DELETE',{confirm:confirm.value});await refreshSession();notify('账号已注销，身份映射、联系方式和精确地点已清除');emit('deleted')}catch(e){error.value=describeError(e)}finally{busy.value=false}}
async function loadPrefs(){try{const p=await getNotificationPreferences();Object.keys(p).forEach(k=>{if(k!=='userId'&&k!=='updatedAt')prefs[k]=p[k]})}catch{}}
async function savePrefs(){prefsLoading.value=true;error.value='';try{const body:Data={};PREF_ITEMS.forEach(i=>body[i.key]=prefs[i.key]!==false);await updateNotificationPreferences(body);notify('通知偏好已保存')}catch(e){error.value=describeError(e)}finally{prefsLoading.value=false}}
function togglePref(key:string,e:Event){prefs[key]=(e.target as HTMLInputElement).checked}
</script>
<template><div class="page-heading"><div><span class="eyebrow">YOUR SPACE, YOUR CHOICE</span><h1>我的资料</h1><p>让同行者认识你，分享范围由你决定。</p></div><span class="badge">{{session.user?.verified?'已验证校内身份':session.demo?'待认证身份':'注册用户'}}</span></div><div v-if="error" class="error-box" role="alert">{{error}}</div><div class="two-columns"><form class="panel stack" @submit.prevent="save()"><h2>基本资料</h2><label>昵称<input v-model="form.nickname" required maxlength="40"/></label><label>院系<input v-model="form.department" maxlength="80"/></label><label>头像地址（可选，HTTPS）<input v-model="form.avatarUrl" type="url" maxlength="512" placeholder="https://…"/></label><label>联系方式（可选）<input v-model="form.contact" maxlength="200" placeholder="默认隐藏，仅在你同意后向当前组员提供"/></label><p class="field-help">公开活动卡片不会展示联系方式和精确集合点。</p><button class="primary" :disabled="busy">保存资料</button></form><div class="stack"><PasswordForm v-if="!session.demo" @changed="emit('passwordChanged')"/><form class="panel stack" @submit.prevent="save(true)"><h2>隐私与分享</h2><label>联系方式可见范围<select v-model="form.contactVisibility"><option value="HIDDEN">仅自己可见</option><option value="GROUP">同意向当前小组成员分享</option></select></label><label>我发起活动的精确地点<select v-model="form.locationVisibility"><option value="HIDDEN">默认隐藏</option><option value="GROUP">同意向当前小组成员分享</option></select></label><p class="field-help">退出或被移除后，成员将失去组内联系方式和精确地点访问权限。管理员身份也不会自动获得访问权。</p><button class="secondary" :disabled="busy">保存隐私设置</button></form><section class="panel"><h3>通知偏好设置</h3><p class="field-help">选择你希望接收的通知类型，关闭后不再推送对应站内通知。</p><div class="stack" style="gap:8px"><label v-for="item in PREF_ITEMS" :key="item.key" class="check-label"><input type="checkbox" :checked="prefs[item.key]!==false" @change="togglePref(item.key, $event)"/> {{item.label}}</label></div><button class="secondary" style="margin-top:10px" :disabled="prefsLoading" @click="savePrefs">{{prefsLoading?'保存中…':'保存通知偏好'}}</button></section><section class="panel"><h3>账号注销</h3><p class="field-help">注销会立即撤销会话、删除身份映射和联系方式，并清除你发布的精确地点。参与与治理历史按匿名身份保留；后续留存期限须由校方确认。</p><button class="text-button" @click="erasing=!erasing">了解并申请注销</button><form v-if="erasing" class="stack" style="margin-top:15px" @submit.prevent="erase"><label class="check-label"><input v-model="confirm" type="checkbox" required/>我确认注销当前账号，操作不可撤回</label><button class="danger" :disabled="busy||!confirm">确认注销账号</button></form></section></div></div></template>
