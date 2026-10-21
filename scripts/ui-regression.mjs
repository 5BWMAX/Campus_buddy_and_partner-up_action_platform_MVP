import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

// Read-only browser checks. Search responses are intercepted; no activities are created.
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const base=process.env.BASE_URL||'https://localhost:8443';
const output=path.join(root,'.local/ui-regression.json');
const result={passed:false,checks:[]};
const browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'msedge',headless:true});
const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1000}});
const page=await context.newPage();
page.setDefaultTimeout(10000);
const errors=[];
page.on('pageerror',e=>errors.push(e.message));
const check=name=>{result.checks.push(name);console.log('PASS',name)};
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}};
const item=type=>({id:type,title:`Regression ${type}`,type,status:'OPEN',capacity:4,occupied:1,remaining:3,
 startAt:'2030-01-01T10:00:00Z',deadline:'2030-01-01T09:00:00Z',publicLocation:'Test location',campus:'Test campus',ownerName:'Demo',tags:[]});
const response=(type,cursor=null)=>({requestId:'ui-regression',data:{items:[item(type)],nextCursor:cursor}});
const settle=()=>page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
try{
 await page.goto(base);
 await page.getByRole('button',{name:'全部活动',exact:true}).waitFor();
 await page.locator('.identity').click();
 const dialog=page.locator('.login-modal');
 const close=dialog.getByRole('button',{name:'关闭身份选择'});
 await close.waitFor();
 await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')==='关闭身份选择');
 assert.equal(await page.locator('.main-shell').evaluate(el=>el.inert),true);
 await page.keyboard.press('Shift+Tab');
 assert.equal(await dialog.locator('button').last().evaluate(el=>el===document.activeElement),true,'Shift+Tab must wrap inside the dialog');
 await page.keyboard.press('Tab');
 assert.equal(await close.evaluate(el=>el===document.activeElement),true,'Tab must wrap to the first control');
 await page.keyboard.press('Escape');
 await dialog.waitFor({state:'hidden'});
 await page.waitForFunction(()=>document.activeElement?.classList.contains('identity'));
 assert.equal(await page.locator('.main-shell').evaluate(el=>el.inert),false);
 check('Identity dialog traps focus, closes on Escape and restores focus');

 // Check restoration to another trigger, not just the identity button.
 const publish=page.getByRole('button',{name:'发起一个行动',exact:true}).first();
 await publish.click();await close.waitFor();await close.click();
 await page.waitForFunction(()=>document.activeElement?.textContent?.includes('发起一个行动'));
 check('Identity dialog restores the original publish trigger');

 // Signing in from an activity detail must keep the identity dialog above the inert detail.
 const openCard=page.locator('.activity-card').filter({hasText:'招募中'}).first();
 await openCard.getByRole('button',{name:'看看这个计划'}).click();
 const apply=page.getByRole('button',{name:'申请一起行动'});
 await apply.click();await close.waitFor();await close.click();
 await page.waitForFunction(()=>document.activeElement?.textContent?.includes('申请一起行动'));
 await page.getByRole('button',{name:'关闭详情',exact:true}).click();
 check('Nested identity dialog remains clickable and restores the activity action');

 async function staleSearch(fail){
  const release=deferred(),started=deferred(),done=deferred();
  await page.route('**/api/v1/activities?**',async route=>{
   const type=new URL(route.request().url()).searchParams.get('type');
   if(type==='STUDY'){
    started.resolve();await release.promise;
    await route.fulfill({status:fail?503:200,json:fail?{code:'TEMPORARILY_UNAVAILABLE',message:'Stale failure',requestId:'old'}:response('STUDY','stale-cursor')});
    done.resolve();
   }else await route.fulfill({json:response('SPORT')});
  });
  await page.getByRole('button',{name:'学习自习',exact:true}).click();await started.promise;
  await page.getByRole('button',{name:'运动健身',exact:true}).click();
  await page.getByRole('heading',{name:'Regression SPORT',exact:true}).waitFor();
  release.resolve();await done.promise;await settle();
  assert.deepEqual(await page.locator('.activity-card h3').allTextContents(),['Regression SPORT']);
  assert.equal(await page.locator('.chip.active').innerText(),'运动健身');
  assert.equal(await page.locator('.load-more').count(),0,'Stale cursor must not reappear');
  assert.equal(await page.locator('.error-box').count(),0,'Stale errors must not replace current state');
  await page.unrouteAll({behavior:'wait'});
  check(fail?'Old search failures cannot overwrite newer results':'Old search results and cursors cannot overwrite newer results');
 }
 await staleSearch(false);await staleSearch(true);

 const release=deferred(),started=deferred(),done=deferred();
 await page.route('**/api/v1/activities?**',async route=>{
  const url=new URL(route.request().url());
  if(url.searchParams.has('cursor')){
   started.resolve();await release.promise;await route.fulfill({json:response('STUDY','old-page')});done.resolve();
  }else await route.fulfill({json:url.searchParams.get('type')==='SPORT'?response('SPORT'):response('STUDY','next-page')});
 });
 await page.getByRole('button',{name:'全部活动',exact:true}).click();
 await page.getByRole('button',{name:'查看更多活动'}).click();await started.promise;
 await page.getByRole('button',{name:'运动健身',exact:true}).click();
 await page.getByRole('heading',{name:'Regression SPORT',exact:true}).waitFor();
 release.resolve();await done.promise;await settle();
 assert.deepEqual(await page.locator('.activity-card h3').allTextContents(),['Regression SPORT']);
 assert.equal(await page.locator('.load-more').count(),0);
 check('Old pagination cannot append items after changing filters');
 assert.deepEqual(errors,[]);
 result.passed=true;
}catch(e){result.failure=e.message;process.exitCode=1;console.error(e.message)}
finally{
 result.finishedAt=new Date().toISOString();
 fs.mkdirSync(path.dirname(output),{recursive:true});
 fs.writeFileSync(output,JSON.stringify(result,null,2));
 await browser.close();
}
