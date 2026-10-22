import {test,afterEach} from 'node:test';
import assert from 'node:assert/strict';
import {api,authorizedFetch} from '../src/authApi.js';
const original={fetch:globalThis.fetch,window:globalThis.window,sessionStorage:globalThis.sessionStorage};
afterEach(()=>{for(const [key,value] of Object.entries(original)){if(value===undefined)delete globalThis[key];else globalThis[key]=value}});
function setup(token){const storage=new Map(token?[['crm-token',token]]:[]),events=[];globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};globalThis.window={dispatchEvent:event=>events.push(event.type)};return {storage,events}}
test('迟到的旧请求401不会清除新登录令牌',async()=>{
 const{storage,events}=setup('old-token');let respond;
 globalThis.fetch=async(_url,options)=>{assert.equal(options.headers.Authorization,'Bearer old-token');return new Promise(resolve=>respond=resolve)};
 const request=api('state');storage.set('crm-token','fresh-token');respond(new Response(JSON.stringify({error:'请先登录'}),{status:401}));
 await assert.rejects(request,error=>error.status===401);assert.equal(storage.get('crm-token'),'fresh-token');assert.deepEqual(events,[]);
});
test('当前令牌的401只触发一次失效通知，即使多个请求并行失败',async()=>{
 const{storage,events}=setup('expired');const responses=[];
 globalThis.fetch=()=>new Promise(resolve=>responses.push(resolve));
 const pending=[api('me'),api('state')];for(const resolve of responses)resolve(new Response(JSON.stringify({error:'请先登录'}),{status:401}));
 await Promise.allSettled(pending);assert.equal(storage.has('crm-token'),false);assert.deepEqual(events,['crm-auth-expired']);
});
test('非JSON错误响应也按HTTP状态处理认证失败',async()=>{
 const{events}=setup('expired');globalThis.fetch=async()=>new Response('<html>Unauthorized</html>',{status:401});
 await assert.rejects(api('me'),error=>error.status===401&&error.message.includes('HTTP 401'));assert.deepEqual(events,['crm-auth-expired']);
});
test('图片等非JSON请求也不会被旧401注销新会话',async()=>{
 const{storage,events}=setup('old');let respond;globalThis.fetch=()=>new Promise(resolve=>respond=resolve);
 const request=authorizedFetch('/api/assets/image');storage.set('crm-token','new');respond(new Response('',{status:401}));await request;
 assert.equal(storage.get('crm-token'),'new');assert.deepEqual(events,[]);
});
