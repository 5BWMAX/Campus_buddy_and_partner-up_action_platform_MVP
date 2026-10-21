<script setup lang="ts">
import {ref} from 'vue'
import {api,refreshSession,describeError} from '../api'
const emit=defineEmits(['authenticated'])
const registering=ref(false),username=ref(''),nickname=ref(''),password=ref(''),confirmation=ref(''),busy=ref(false),error=ref('')
function switchMode(){registering.value=!registering.value;password.value='';confirmation.value='';error.value=''}
async function submit(){
 if(busy.value)return
 error.value=''
 if(registering.value){
  const length=Array.from(password.value).length
  if(length<15||length>128){error.value='密码需要 15–128 个字符，可以使用多个词组成的密码短语。';return}
  if(password.value!==confirmation.value){error.value='两次输入的密码不一致。';return}
 }
 busy.value=true
 try{
  await refreshSession()
  await api(registering.value?'/auth/register':'/auth/login','POST',registering.value?{username:username.value,nickname:nickname.value,password:password.value}:{username:username.value,password:password.value})
  password.value='';confirmation.value=''
  await refreshSession()
  emit('authenticated')
 }catch(e){error.value=describeError(e)}finally{busy.value=false}
}
</script>
<template>
 <h2 id="login-title">{{registering?'注册账号':'登录同行'}}</h2>
 <p class="muted">{{registering?'注册后即可发布活动、报名和交流。':'使用你的账号继续参与活动。'}}</p>
 <form class="stack" @submit.prevent="submit" :aria-busy="busy">
  <div v-if="error" class="error-box" role="alert">{{error}}</div>
  <label>用户名<input v-model="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" required pattern="[A-Za-z0-9_]{3,32}" maxlength="32" :readonly="busy" aria-describedby="username-help"/></label>
  <p id="username-help" class="field-help">3–32 位英文字母、数字或下划线，不区分大小写。</p>
  <label v-if="registering">昵称<input v-model="nickname" name="nickname" autocomplete="nickname" required maxlength="40" :readonly="busy"/></label>
  <label>密码<input v-model="password" name="password" type="password" :autocomplete="registering?'new-password':'current-password'" required :readonly="busy" aria-describedby="password-help"/></label>
  <p id="password-help" class="field-help">{{registering?'15–128 个字符，允许空格和粘贴。请妥善保存密码，目前不支持自助找回。':'忘记密码时请联系平台管理员，目前不支持自助找回。'}}</p>
  <label v-if="registering">确认密码<input v-model="confirmation" name="confirmation" type="password" autocomplete="new-password" required :readonly="busy"/></label>
  <button class="primary" :disabled="busy">{{busy?'正在处理…':registering?'注册并登录':'登录'}}</button>
  <button type="button" class="text-button" :disabled="busy" @click="switchMode">{{registering?'已有账号，去登录':'没有账号，去注册'}}</button>
 </form>
</template>
