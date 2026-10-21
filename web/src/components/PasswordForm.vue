<script setup lang="ts">
import {ref} from 'vue'
import {api,describeError} from '../api'
defineProps<{requiredChange?:boolean}>()
const emit=defineEmits(['changed'])
const current=ref(''),password=ref(''),confirmation=ref(''),busy=ref(false),error=ref('')
async function submit(){
 if(busy.value)return
 error.value=''
 const length=Array.from(password.value).length
 if(length<15||length>128){error.value='新密码需要 15–128 个字符。';return}
 if(password.value!==confirmation.value){error.value='两次输入的新密码不一致。';return}
 busy.value=true
 try{
  await api('/auth/password','POST',{currentPassword:current.value,newPassword:password.value})
  current.value='';password.value='';confirmation.value=''
  // Let the persistent app refresh the session; doing it here unmounts this form before emit.
  emit('changed')
 }catch(e){error.value=describeError(e)}finally{busy.value=false}
}
</script>
<template>
 <form class="panel stack" @submit.prevent="submit" :aria-busy="busy">
  <h2>{{requiredChange?'首次登录，请修改临时密码':'修改密码'}}</h2>
  <p class="field-help">{{requiredChange?'修改后才能使用账号。':''}}修改成功后，所有设备的登录都会失效，请用新密码重新登录。</p>
  <div v-if="error" class="error-box" role="alert">{{error}}</div>
  <label>当前密码<input v-model="current" name="current-password" type="password" autocomplete="current-password" required :readonly="busy"/></label>
  <label>新密码<input v-model="password" name="new-password" type="password" autocomplete="new-password" required :readonly="busy" aria-describedby="new-password-help"/></label>
  <p id="new-password-help" class="field-help">15–128 个字符，允许空格和粘贴。目前不支持自助找回密码。</p>
  <label>确认新密码<input v-model="confirmation" name="confirm-password" type="password" autocomplete="new-password" required :readonly="busy"/></label>
  <button class="primary" :disabled="busy">{{busy?'正在修改…':'修改密码并退出登录'}}</button>
 </form>
</template>
