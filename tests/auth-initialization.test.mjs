import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createAuthInitializer,feishuLoginUrl,loadAuthentication} from '../src/authInitialization.js';
const storageWith=token=>{const data=new Map(token?[['crm-token',token]]:[]);return {getItem:key=>data.get(key)||null,setItem:(key,value)=>data.set(key,value),removeItem:key=>data.delete(key)}};
const config={enabled:true,redirectUri:'http://127.0.0.1:8088/api/auth/feishu/callback'};

test('首次回调仅兑换一次票据，取得新令牌后才读取当前用户',async()=>{
 const storage=storageWith('expired-token'),calls=[],replaced=[];
 let finishExchange;const exchanged=new Promise(resolve=>finishExchange=resolve);
 const api=async(path,body)=>{
  calls.push(path);
  if(path==='auth/feishu/exchange'){assert.equal(storage.getItem('crm-token'),null);assert.deepEqual(body,{ticket:'one-use'});await exchanged;return {token:'fresh-token'}}
  if(path==='status')return {loginRequired:true};
  if(path==='auth/feishu/config')return config;
  if(path==='me'){assert.equal(storage.getItem('crm-token'),'fresh-token');return {name:'member'}}
  assert.fail(path);
 };
 const initialize=createAuthInitializer({api,storage,location:{href:'http://127.0.0.1:8088/?sso_ticket=one-use&keep=1#orders'},history:{replaceState:(_state,_title,url)=>replaced.push(url)}});
 const first=initialize(),second=initialize();assert.strictEqual(first,second);assert.deepEqual(calls,['auth/feishu/exchange']);
 finishExchange();const result=await first;assert.equal(result.user.name,'member');assert.equal(result.error,'');assert.equal(calls.filter(path=>path==='auth/feishu/exchange').length,1);assert.deepEqual(replaced,['/?keep=1#orders']);
});
test('旧令牌过期时正常展示登录入口，不把首次打开当作登录失败',async()=>{
 const storage=storageWith('expired');
 const api=async path=>{if(path==='status')return {loginRequired:true};if(path==='auth/feishu/config')return config;const error=Error('请先登录');error.status=401;throw error};
 const result=await loadAuthentication({api,storage});assert.equal(result.user,null);assert.equal(result.feishu.enabled,true);
});
test('无效SSO票据不能回退到上一个账号的令牌',async()=>{
 const storage=storageWith('old-account-token');let meCalls=0;
 const api=async path=>{if(path==='auth/feishu/exchange')throw Error('登录票据已失效');if(path==='status')return {loginRequired:true};if(path==='auth/feishu/config')return config;if(path==='me'){meCalls++;return {name:'old-account'}}};
 const result=await createAuthInitializer({api,storage,location:{href:'http://127.0.0.1:8088/?sso_ticket=bad'},history:{replaceState(){}}})();
 assert.equal(result.user,null);assert.equal(result.error,'登录票据已失效');assert.equal(storage.getItem('crm-token'),null);assert.equal(meCalls,0);
});
test('非认证错误不会被当成正常退出登录吞掉',async()=>{
 const api=async path=>{if(path==='status')return {loginRequired:true};if(path==='auth/feishu/config')return config;const error=Error('数据库操作失败');error.status=500;throw error};
 await assert.rejects(loadAuthentication({api,storage:storageWith('token')}),/数据库操作失败/);
});
test('从localhost或内网地址发起登录时先到回调域名建立会话Cookie',()=>{
 assert.equal(feishuLoginUrl(config.redirectUri,'http://localhost:8088'),'http://127.0.0.1:8088/api/auth/feishu/start');
 assert.equal(feishuLoginUrl('https://crm.example:9002/api/auth/feishu/callback','http://192.168.1.2:8088'),'https://crm.example:9002/api/auth/feishu/start');
});
