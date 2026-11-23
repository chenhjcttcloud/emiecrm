// Explicit opt-in: creates one sandbox order through the isolated CRM database.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';
const enabled=process.env.CRM_JST_LIVE_TEST==='1';
test('隔离CRM → 固定出口网关 → 聚水潭沙箱，含重复提交与状态回查',{skip:!enabled},async()=>{
 const base=process.env.CRM_TEST_BASE_URL;
 assert.ok(base,'Explicit isolated base URL required');
 assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'Only an explicitly named _test database may contain gateway fixtures');
 const status=await(await fetch(base+'/api/status')).json();assert.equal(status.testMode,true);
 let token='';
 async function request(path,body,expected=200){
  const response=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  const result=await response.json();assert.equal(response.status,expected,JSON.stringify(result));return result;
 }
 token=(await request('login',{name:'admin',password:'test-password'})).token;
 const config=await request('jst/config');assert.equal(config.environment,'sandbox');assert.equal(config.configured,true);
 const suffix=Date.now();const id='jst-live-'+suffix, customerId='jst-c-'+suffix;
 let state=await request('jst/products/sync',{keyword:'2609271415-80',mode:'sku'});
 const productId=state.products.find(p=>p.jstSku==='2609271415-80'&&p.enabled&&p.jstEnvironment==='sandbox')?.id;assert.ok(productId);
 const warehouse=(await request('jst/warehouses')).find(w=>w.main);assert.ok(warehouse);
 state.customers.push({id:customerId,name:'聚水潭沙箱联调-'+suffix,balance:0});
 state.orders.push({id,customerId,customer:'聚水潭沙箱联调-'+suffix,type:'销售订单',status:'草稿',total:0.01,paid:0,items:[{product:productId,qty:1,price:0.01}],note:'沙箱联调，请勿实际发货'});
 async function save(){state=await request('state',{data:state,revision:state.revision});}
 await save();
 const body={shopId:10402930,warehouseId:warehouse.id,recipient:{name:'CRM沙箱测试勿发货',phone:'00000000000',province:'广东省',city:'深圳市',district:'南山区',address:'CRM自动化测试虚拟地址，请勿实际投递'},items:[{skuId:'2609271415-80'}]};
 await request('jst/orders/'+id+'/submit',body,400);
 state=await request('state');state.orders.find(o=>o.id===id).status='已批准';
 await request('state',{data:state,revision:state.revision},400);
 // This test verifies the real sandbox gateway, not Feishu. Its prerequisite is a
 // historical externally approved fixture, written only to the guarded test DB.
 // No HTTP route or runtime approval bypass is enabled to create this state.
 const fixtureConnection=await mysql.createConnection({host:process.env.CRM_DB_HOST||'mysql',
  port:Number(process.env.CRM_DB_PORT||3306),user:process.env.CRM_DB_USER||'emiecrm',
  password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,database:process.env.CRM_DB_NAME});
 try{
  const [[database]]=await fixtureConnection.query('SELECT DATABASE() AS name');
  assert.equal(database.name,process.env.CRM_DB_NAME);assert.ok(database.name.endsWith('_test'));
  await fixtureConnection.beginTransaction();
  const [[stored]]=await fixtureConnection.query('SELECT payload FROM state WHERE id=1 FOR UPDATE');
  const fixture=JSON.parse(stored.payload),record=fixture.orders.find(order=>order.id===id);
  assert.ok(record);assert.equal(record.customerId,customerId);assert.equal(record.status,'草稿');
  assert.equal(Number(record.paid),0);assert.equal(Number(record.total),0.01);
  const [[approval]]=await fixtureConnection.query('SELECT COUNT(*) AS total FROM feishu_order_approvals WHERE order_id=?',[id]);
  assert.equal(approval.total,0,'A gateway fixture must not alter an order with a real approval association');
  record.status='已批准';fixture.revision++;
  await fixtureConnection.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
  await fixtureConnection.commit();
 }catch(error){await fixtureConnection.rollback();throw error}finally{await fixtureConnection.end()}
 state=await request('state');
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
