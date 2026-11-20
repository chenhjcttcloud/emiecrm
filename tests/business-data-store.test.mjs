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

test('同版刷新只请求版本确认，保留数据对象及迁移原始资料',async()=>{
 const loaded=state(2710,['旧客户']);
 loaded.customers[0].openingBalance=86.5;
 loaded.customers[0].legacySource={id:'0000000000000001',fields:{预收款金额:'86.50',负责人:null}};
 const paths=[];
 const store=createBusinessDataStore(async path=>{
  paths.push(path);return paths.length===1?loaded:{unchanged:true,revision:2710};
 });
 await store.start(false);
 assert.strictEqual(await store.refresh(),loaded);
 assert.strictEqual(store.getSnapshot().data,loaded);
 assert.deepEqual(paths,['state','state?knownRevision=2710']);
 assert.deepEqual(loaded.customers[0].legacySource.fields,{预收款金额:'86.50',负责人:null});
 assert.equal(store.getSnapshot().refreshing,false);
});

test('后台短时刷新复用最近确认，手动刷新始终向服务器核对版本',async()=>{
 let clock=1000,calls=0;
 const store=createBusinessDataStore(async()=>++calls===1?state(1,['c1']):{unchanged:true,revision:1},()=>{},{now:()=>clock});
 const original=await store.start(false);
 clock=3999;
 assert.strictEqual(await store.refresh({maxAgeMs:3000}),original);
 assert.equal(calls,1);
 clock=4000;
 assert.strictEqual(await store.refresh({maxAgeMs:3000}),original);
 assert.equal(calls,2,'有效期结束须重新确认版本');
 clock=4001;
 await store.refresh();
 assert.equal(calls,3,'显式刷新不受后台有效期影响');
});

test('已有强制刷新在途时，后台刷新复用同一请求而非返回旧缓存',async()=>{
 const pending=deferred();let calls=0;
 const store=createBusinessDataStore(async()=>++calls===1?state(1):pending.promise,()=>{},{now:()=>1000});
 await store.start(false);
 const strong=store.refresh();
 assert.strictEqual(store.refresh({maxAgeMs:3000}),strong);
 await Promise.resolve();assert.equal(calls,2);
 pending.resolve(state(2,['latest']));
 assert.equal((await strong).revision,2);
});

test('失败的版本确认不会伪装成近期成功，后台请求能立即重试',async()=>{
 let clock=1000,calls=0;
 const store=createBusinessDataStore(async()=>{
  calls++;if(calls===1)return state(1,['c1']);
  if(calls===2)throw Error('网络断开');
  return state(2,['c1','c2']);
 },()=>{},{now:()=>clock});
 await store.start(false);clock=1100;
 assert.equal(await store.refresh(),null);
 assert.equal(store.getSnapshot().refreshError,'网络断开');
 assert.equal((await store.refresh({maxAgeMs:3000})).revision,2);
 assert.equal(calls,3);
 assert.equal(store.getSnapshot().refreshError,'');
});

test('保存和服务端推送确认新版本，忽略旧推送不延长有效期',async()=>{
 let clock=1000,reads=0;
 const store=createBusinessDataStore(async(_path,body)=>body?state(2,['saved']):state(++reads),()=>{},{now:()=>clock});
 await store.start(true);clock=5000;
 await store.save(state(1,['draft']));
 clock=7999;await store.refresh({maxAgeMs:3000});assert.equal(reads,1);
 store.setData(previous=>({...previous,revision:3,customers:[{id:'server-push'}]}));
 clock=10000;store.setData(state(2,['old-push']));
 clock=10998;await store.refresh({maxAgeMs:3000});assert.equal(reads,1);
 clock=10999;await store.refresh({maxAgeMs:3000});assert.equal(reads,2,'旧推送不能为当前缓存延期');
 assert.equal(store.getSnapshot().data.revision,3);
});

test('迟到的旧版本确认不会回滚已保存数据或延长新版本确认时间',async()=>{
 let clock=1000,reads=0;const old=deferred(),paths=[];
 const store=createBusinessDataStore(async(path,body)=>{
  if(body)return state(3,['saved']);
  paths.push(path);reads++;return reads===1?state(1):reads===2?old.promise:{unchanged:true,revision:3};
 },()=>{},{now:()=>clock});
 await store.start(true);clock=5000;
 const pending=store.refresh();await Promise.resolve();clock=5100;
 const saved=await store.save(state(1,['draft']));
 clock=10000;old.resolve({unchanged:true,revision:1});
 assert.strictEqual(await pending,saved);
 assert.strictEqual(store.getSnapshot().data,saved);
 await store.refresh({maxAgeMs:3000});
 assert.equal(reads,3);
 assert.equal(paths[2],'state?knownRevision=3');
});

test('异常版本确认不能清空客户或变成可复用缓存',async()=>{
 let reads=0;
 const original=state(1,['kept']);
 const store=createBusinessDataStore(async()=>++reads===1?original:reads===2?{unchanged:true,revision:2}:{unchanged:true,revision:1});
 await store.start(false);
 assert.equal(await store.refresh(),null);
 assert.strictEqual(store.getSnapshot().data,original);
 assert.match(store.getSnapshot().refreshError,/业务版本/);
 assert.strictEqual(await store.refresh({maxAgeMs:3000}),original);
 assert.equal(reads,3);
 const initial=createBusinessDataStore(async()=>({unchanged:true,revision:1}));
 assert.equal(await initial.start(false),null);
 assert.match(initial.getSnapshot().loadError,/业务版本/);
});

test('旧权限生命周期的版本确认不能改变新生命周期数据',async()=>{
 const pending=deferred();let reads=0;
 const store=createBusinessDataStore(async()=>++reads===1?state(1):reads===2?pending.promise:state(2,['current']));
 await store.start(true);const old=store.refresh();await Promise.resolve();
 store.dispose();await store.start(false);
 pending.resolve({unchanged:true,revision:1});
 assert.equal(await old,null);
 assert.equal(store.getSnapshot().data.revision,2);
 assert.equal(store.getSnapshot().refreshing,false);
});
