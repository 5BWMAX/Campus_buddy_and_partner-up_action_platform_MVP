import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';

// Mutates only an explicitly selected local formal-mode test instance.
const base=process.env.FORMAL_TEST_URL;
assert.ok(base&&['localhost','127.0.0.1'].includes(new URL(base).hostname),'Set FORMAL_TEST_URL to an isolated local test instance');
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const output=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../.local/formal-ui');
fs.mkdirSync(output,{recursive:true});
const browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'msedge',headless:true});
const contexts=[],errors=[],serverErrors=[],checks=[];
const password=`Test phrase ${randomUUID()}`,nextPassword=`Changed phrase ${randomUUID()}`;
const suffix=Date.now().toString(36),ownerName=`owner_${suffix}`,memberName=`member_${suffix}`,title=`正式版浏览器测试 ${suffix}`;
const check=name=>{checks.push(name);console.log('PASS',name)};
async function client(mobile=false){
 const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:mobile?{width:360,height:800}:{width:1440,height:1000}});
 contexts.push(context);
 const page=await context.newPage();page.setDefaultTimeout(12000);
 page.on('pageerror',e=>errors.push(e.message));
 page.on('response',r=>{if(r.status()>=500)serverErrors.push(new URL(r.url()).pathname)});
 await page.goto(base);
 await page.locator('.identity:not(:disabled)').waitFor();
 return page;
}
async function state(page){return (await (await page.request.get(base+'/api/v1/auth/session')).json()).data}
async function register(page,username,nickname){
 await page.locator('.identity').click();
 await page.getByRole('button',{name:'没有账号，去注册'}).click();
 await page.getByLabel('用户名',{exact:true}).fill(username);
 await page.getByLabel('昵称',{exact:true}).fill(nickname);
 await page.getByLabel('密码',{exact:true}).fill(password);
 await page.getByLabel('确认密码',{exact:true}).fill(password);
 await page.getByRole('button',{name:'注册并登录',exact:true}).click();
 await page.locator('.login-modal').waitFor({state:'hidden'});
 assert.equal((await state(page)).user.role,'USER');
}
async function login(page,username,secret=password){
 if(!await page.locator('.login-modal').isVisible())await page.locator('.identity').click();
 await page.getByLabel('用户名',{exact:true}).fill(username);
 await page.getByLabel('密码',{exact:true}).fill(secret);
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await page.locator('.login-modal').waitFor({state:'hidden'});
}
async function nav(page,name){
 const button=page.getByRole('button',{name,exact:true});
 if(!await button.isVisible())await page.getByRole('button',{name:'展开导航'}).click();
 await button.click();
}
let passed=false;
try{
 const owner=await client();
 assert.equal((await state(owner)).demo,false,'Must use formal profile');
 assert.equal(await owner.getByText('虚拟身份 · 模拟外部推送',{exact:true}).count(),0);
 assert.equal((await owner.request.get(base+'/api/v1/auth/demo/accounts')).status(),404);
 check('Formal visitor can browse; demo accounts endpoint and selector are absent');
 await owner.locator('.identity').click();
 await owner.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')==='关闭登录窗口');
 await owner.keyboard.press('Shift+Tab');
 assert.equal(await owner.getByRole('button',{name:'没有账号，去注册'}).evaluate(el=>el===document.activeElement),true);
 await owner.keyboard.press('Tab');
 assert.equal(await owner.getByRole('button',{name:'关闭登录窗口'}).evaluate(el=>el===document.activeElement),true);
 assert.equal(await owner.locator('.main-shell').evaluate(el=>el.inert),true);
 await owner.keyboard.press('Escape');
 await owner.waitForFunction(()=>document.activeElement?.classList.contains('identity'));
 check('Formal login traps focus, supports Escape and restores its trigger');
 const anonymous=await state(owner);
 await register(owner,ownerName,'测试发起人');
 const signedIn=await state(owner);
 assert.notEqual(signedIn.csrf,anonymous.csrf);
 assert.equal(signedIn.user.verified,false);
 await owner.reload();await owner.getByRole('button',{name:'退出登录'}).waitFor();
 await owner.locator('.identity').click();
 await owner.getByText('注册用户',{exact:true}).waitFor();
 assert.equal(await owner.getByText('待认证身份',{exact:true}).count(),0);
 check('Registration rotates CSRF, survives reload and identifies ordinary registered users');
 await nav(owner,'发现搭子');
 await owner.getByRole('button',{name:'发起一个行动',exact:true}).first().click();
 await owner.getByLabel('行动标题').fill(title);
 await owner.getByLabel('公开集合地点',{exact:true}).fill('测试图书馆');
 await owner.getByRole('button',{name:'发布行动',exact:true}).click();
 await owner.getByRole('heading',{name:title,exact:true}).waitFor();
 check('Registered unverified user publishes an activity through the UI');
 const member=await client(true);
 await member.locator('.identity').click();await member.getByRole('button',{name:'没有账号，去注册'}).click();
 await member.getByLabel('用户名',{exact:true}).fill(memberName);
 await member.getByLabel('昵称',{exact:true}).fill('测试参与人');
 await member.getByLabel('密码',{exact:true}).fill(password);
 await member.getByLabel('确认密码',{exact:true}).fill('different');
 await member.getByRole('button',{name:'注册并登录',exact:true}).click();
 await member.getByText('两次输入的密码不一致。',{exact:true}).waitFor();
 assert.equal(await member.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 await member.screenshot({path:path.join(output,'mobile-registration.png')});
 await member.getByLabel('确认密码',{exact:true}).fill(password);
 await member.getByRole('button',{name:'注册并登录',exact:true}).click();await member.locator('.login-modal').waitFor({state:'hidden'});
 check('Mobile registration validates confirmation without horizontal overflow');
 await member.locator('.activity-card').filter({hasText:title}).getByRole('button',{name:'看看这个计划'}).click();
 await member.getByRole('button',{name:'申请一起行动',exact:true}).click();
 await member.getByText('申请已提交，等待发起人审核',{exact:true}).waitFor();
 await nav(owner,'我的行动');
 await owner.getByRole('button',{name:'审核申请 / 管理成员'}).click();
 await owner.getByRole('button',{name:'通过申请',exact:true}).click();
 await owner.getByText('审核通过，成员已入组',{exact:true}).waitFor();
 await nav(member,'我的行动');
 await member.getByRole('button',{name:'进入小组',exact:true}).click();
 await member.getByLabel('群聊消息',{exact:true}).fill('正式账号测试消息');
 await member.getByRole('button',{name:'发送',exact:true}).click();
 await member.locator('.message p').filter({hasText:'正式账号测试消息'}).waitFor();
 await owner.getByRole('button',{name:'进入小组',exact:true}).click();
 await owner.locator('.message p').filter({hasText:'正式账号测试消息'}).waitFor();
 await owner.getByRole('button',{name:'关闭小组'}).click();
 check('Two registered users apply, approve and exchange group messages through the UI');
 const other=await client();
 await other.locator('.identity').click();
 await other.getByLabel('用户名',{exact:true}).fill(ownerName);
 await other.getByLabel('密码',{exact:true}).fill('Wrong password phrase');
 await other.getByRole('button',{name:'登录',exact:true}).click();
 await other.getByText(/用户名或密码不正确/).waitFor();
 await login(other,ownerName);
 check('Wrong credentials show an error; a valid second-device login succeeds');
 await owner.locator('.identity').click();
 await owner.getByLabel('当前密码',{exact:true}).fill(password);
 await owner.getByLabel('新密码',{exact:true}).fill(nextPassword);
 await owner.getByLabel('确认新密码',{exact:true}).fill(nextPassword);
 await owner.getByRole('button',{name:'修改密码并退出登录'}).click();
 await owner.getByRole('heading',{name:'登录同行',exact:true}).waitFor();
 await other.reload();
 await other.getByRole('button',{name:'登录 / 注册'}).waitFor();
 await login(owner,ownerName,nextPassword);
 check('Password change logs out all devices; revoked-session reload recovers; new password works');
 await owner.screenshot({path:path.join(output,'desktop-formal.png'),fullPage:true});
 // The backend separately tests actual offline administrator initialization. Here exercise its UI state.
 const forcedState=await state(owner);forcedState.user={...forcedState.user,role:'ADMIN',passwordChangeRequired:true};
 await owner.route('**/api/v1/auth/session',route=>route.fulfill({json:{data:forcedState}}));
 const forbidden=[];
 owner.on('request',req=>{if(/\/api\/v1\/(activities|notifications|me\/profile)/.test(req.url()))forbidden.push(req.url())});
 await owner.reload();
 await owner.getByRole('heading',{name:'首次登录，请修改临时密码'}).waitFor();
 await nav(owner,'我的资料');
 await owner.getByRole('heading',{name:'首次登录，请修改临时密码'}).waitFor();
 assert.equal(forbidden.length,0);
 check('First-login administrator UI blocks business views until password change (session fixture)');
 assert.deepEqual(errors,[]);assert.deepEqual(serverErrors,[]);
 check('No browser exceptions or HTTP 5xx responses');
 passed=true;
}catch(e){console.error(e.message);process.exitCode=1}
finally{
 fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({passed,checks,errors,serverErrors},null,2));
 for(const context of contexts)await context.close();
 await browser.close();
}
