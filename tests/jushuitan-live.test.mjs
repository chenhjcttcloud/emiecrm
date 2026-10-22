// Explicit opt-in: creates one sandbox order through the isolated CRM database.
import {test} from 'node:test';
import assert from 'node:assert/strict';
const enabled=process.env.CRM_JST_LIVE_TEST==='1';
test('隔离CRM → 固定出口网关 → 聚水潭沙箱，含重复提交与状态回查',{skip:!enabled},async()=>{
 const base=process.env.CRM_TEST_BASE_URL;
 assert.ok(base,'Explicit isolated base URL required');
 const status=await(await fetch(base+'/api/status')).json();assert.equal(status.testMode,true);
 let token='';
 async function request(path,body,expected=200){
  const response=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  const result=await response.json();assert.equal(response.status,expected,JSON.stringify(result));return result;
 }
 token=(await request('login',{name:'admin',password:'test-password'})).token;
 const config=await request('jst/config');assert.equal(config.environment,'sandbox');assert.equal(config.configured,true);
 const suffix=Date.now();const id='jst-live-'+suffix, customerId='jst-c-'+suffix, productId='jst-p-'+suffix;
 let state=await request('state');
 state.customers.push({id:customerId,name:'聚水潭沙箱联调-'+suffix,balance:0});
 state.products.push({id:productId,name:'ZCode冒烟测试云枕棉',jstSku:'2609271415-80',price:0.01});
 state.orders.push({id,customerId,customer:'聚水潭沙箱联调-'+suffix,type:'销售订单',status:'审批中',total:0.01,paid:0,items:[{product:productId,qty:1,price:0.01}],note:'沙箱联调，请勿实际发货'});
 async function save(){state=await request('state',{data:state,revision:state.revision});}
 await save();
 const body={shopId:10402930,recipient:{name:'CRM沙箱测试勿发货',phone:'00000000000',province:'广东省',city:'深圳市',district:'南山区',address:'CRM自动化测试虚拟地址，请勿实际投递'},items:[{skuId:'2609271415-80'}]};
 await request('jst/orders/'+id+'/submit',body,400);
 state=await request('state');state.orders.find(o=>o.id===id).status='已批准';await save();
 await request('jst/orders/'+id+'/submit',body,400);
 state=await request('state');Object.assign(state.orders.find(o=>o.id===id),{paid:0.01,method:'银行转账'});await save();
 const outcomes=await Promise.all([1,2].map(async()=>{
  const r=await fetch(base+'/api/jst/orders/'+id+'/submit',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body)});
  return {status:r.status,body:await r.json()};
 }));
 assert.deepEqual(outcomes.map(r=>r.status).sort(),[200,400]);
 const submitted=outcomes.find(r=>r.status===200).body;
 assert.equal(submitted.status,'ACCEPTED',JSON.stringify(submitted));assert.ok(submitted.oId>0);
 const synced=await request('jst/orders/'+id+'/sync',{});
 assert.equal(synced.soId,submitted.soId);assert.equal(synced.oId,submitted.oId);
 assert.equal(synced.status,'ACCEPTED');assert.ok(synced.lastSyncedAt);assert.ok(!synced.syncError, synced.syncError);
 state=await request('state');const order=state.orders.find(o=>o.id===id);order.status='草稿';
 await request('state',{data:state,revision:state.revision},400);
 state=await request('state');state.orders.find(o=>o.id===id).note='已验证，仅保留测试单';await save();
 const backup=await request('backup');assert.ok(backup.jstShipments.some(r=>r.orderId===id));
 await request('restore',backup);
 const rows=await request('jst/shipments');assert.equal(rows.filter(r=>r.orderId===id).length,1);
 console.log(JSON.stringify({crmOrderId:id,soId:submitted.soId,oId:submitted.oId,status:synced.status}));
});
