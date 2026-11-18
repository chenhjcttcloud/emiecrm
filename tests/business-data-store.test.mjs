import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createBusinessDataStore} from '../src/businessDataStore.js';

const state=(revision,ids=[])=>({revision,customers:ids.map(id=>({id,name:id})),products:[],orders:[]});
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}};

test('刷新取得其他会话新增客户，并合并重复刷新请求',async()=>{
 const next=deferred();let calls=0;
 const store=createBusinessDataStore(async()=>++calls===1?state(1,['c1']):next.promise);
 await store.start(false);
 const first=store.refresh(),second=store.refresh();
 assert.strictEqual(first,second);
 await Promise.resolve();
 assert.equal(calls,2);
 assert.equal(store.getSnapshot().refreshing,true);
 next.resolve(state(2,['c1','c2']));
 assert.deepEqual((await first).customers.map(c=>c.id),['c1','c2']);
 assert.equal(store.getSnapshot().refreshing,false);
});

test('首次加载失败可重试，后台刷新失败保留客户并单独显示错误',async()=>{
 let outcome=Error('暂时离线');
 const store=createBusinessDataStore(async()=>{if(outcome instanceof Error)throw outcome;return outcome});
 assert.equal(await store.start(false),null);
 assert.equal(store.getSnapshot().loading,false);
 assert.equal(store.getSnapshot().loadError,'暂时离线');
 outcome=state(1,['c1']);
 assert.equal((await store.retry()).revision,1);
 assert.equal(store.getSnapshot().loadError,'');
 outcome=Error('刷新离线');
 assert.equal(await store.refresh(),null);
 assert.equal(store.getSnapshot().loadError,'');
 assert.equal(store.getSnapshot().refreshError,'刷新离线');
 assert.deepEqual(store.getSnapshot().data.customers.map(c=>c.id),['c1']);
 outcome=state(2,['c1','c2']);await store.refresh();
 assert.equal(store.getSnapshot().refreshError,'');
});

test('迟到的读取不会覆盖已保存的新版本，函数式更新同样遵守版本顺序',async()=>{
 const oldRead=deferred();let reads=0;
 const store=createBusinessDataStore(async(_path,body)=>body?state(3,['saved']):++reads===1?state(1):oldRead.promise);
 await store.start(true);
 const pending=store.refresh();await Promise.resolve();
 await store.save(state(1,['draft']));
 oldRead.resolve(state(2,['stale']));
 assert.equal((await pending).revision,3);
 assert.deepEqual(store.getSnapshot().data.customers.map(c=>c.id),['saved']);
 store.setData(previous=>({...previous,revision:4,customers:[{id:'updated'}]}));
 store.setData(state(2,['old']));
 assert.equal(store.getSnapshot().data.revision,4);
 assert.deepEqual(store.getSnapshot().data.customers.map(c=>c.id),['updated']);
});

test('后台刷新不会替旧表单提升提交版本，服务器仍能拒绝冲突',async()=>{
 let received;let reads=0;
 const store=createBusinessDataStore(async(_path,body)=>{
  if(body){received=body;const error=Error('数据已更新');error.status=409;throw error}
  return state(++reads);
 });
 const old=await store.start(true);await store.refresh();
 await assert.rejects(store.save({...old,customers:[{id:'draft'}]}),error=>error.status===409);
 assert.equal(received.revision,1);
 assert.equal(store.getSnapshot().data.revision,2);
});

test('卸载或权限变化后忽略过时读取，且不会执行旧权限下的初始化写入',async()=>{
 const old=deferred();let reads=0,posts=0;const notifications=[];
 const store=createBusinessDataStore(async(_path,body)=>{
  if(body){posts++;return state(1)}
  return ++reads===1?old.promise:state(2,['current']);
 },snapshot=>notifications.push(snapshot));
 const stale=store.start(true);await Promise.resolve();
 store.dispose();
 await store.start(false);
 old.resolve(null);
 assert.equal(await stale,null);
 assert.equal(posts,0);
 assert.equal(store.getSnapshot().data.revision,2);
 const count=notifications.length;
 store.dispose();
 assert.equal(await store.refresh(),null);
 store.setData(state(3));
 assert.equal(notifications.length,count);
});

test('仅写权限账号初始化空库，初始化竞争409后重新读取',async()=>{
 let reads=0,posts=0;
 const store=createBusinessDataStore(async(_path,body)=>{
  if(body){posts++;const error=Error('已由其他会话初始化');error.status=409;throw error}
  return ++reads===1?null:state(1,['existing']);
 });
 assert.equal((await store.start(true)).customers[0].id,'existing');
 assert.equal(posts,1);
 const viewer=createBusinessDataStore(async(_path,body)=>{assert.equal(body,undefined);return null});
 assert.deepEqual((await viewer.start(false)).customers,[]);
});
