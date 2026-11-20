import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置隔离测试API地址');

test('旧客户期初预收款：核销、超额保护、不可变依据和恢复守恒',async()=>{
 assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'只允许_test隔离数据库');
 const status=await(await fetch(base+'/api/status')).json();
 assert.equal(status.testMode,true);
 const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',user:process.env.CRM_DB_USER||'emiecrm',
  password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,database:process.env.CRM_DB_NAME,connectionLimit:2});
 let token='',original;
 const snapshots=new Map(),tables=['receipts','jst_shipments','jst_document_sales','jst_document_purchases'];
 const request=async(path,body,expected=200)=>{
  const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
   headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
   body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();assert.equal(response.status,expected,JSON.stringify(data));return data;
 };
 try{
  if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
  token=(await request('login',{name:'admin',password:'test-password'})).token;
  const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');original=stored[0].payload;
  for(const table of tables){const [rows]=await pool.query('SELECT * FROM `'+table+'`');snapshots.set(table,rows);await pool.query('DELETE FROM `'+table+'`')}
  const customer={id:'legacy-opening-customer',name:'合成期初客户',balance:270,openingBalance:270,
   legacySource:{system:'legacy-feishu-crm',id:'0000000000000001',sha256:'a'.repeat(64),fileName:'synthetic.xlsx',sheet:'Sheet1',rowNumber:2,
    importedAt:'2026-11-20T09:00:00Z',fields:{ID:'0000000000000001',客户名称:'合成期初客户',预收款金额:'270'}}};
  const product={id:'legacy-test-product',name:'合成测试商品',source:'jushuitan',enabled:true};
  const order={id:'legacy-opening-order',customerId:customer.id,customer:customer.name,type:'销售订单',status:'已批准',owner:'admin',
   total:300,paid:0,items:[{product:product.id,qty:1,price:300}],created:'2026-11-20 17:00:00'};
  const fixture={revision:6000,customers:[customer],products:[product],orders:[order]};
  await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
  assert.deepEqual(await request('receipts'),[],'期初金额不能伪造成今天的新收款');
  const before=await request('backup');
  let state=await request('state');
  state.orders[0].paid=100;state.orders[0].method='预收款核销';
  state=await request('state',{revision:state.revision,data:state});
  assert.equal(state.customers[0].balance,170);
  assert.equal(state.customers[0].openingBalance,270);
  const receipts=await request('receipts');
  assert.equal(receipts.length,1);assert.equal(receipts[0].amount,100);assert.equal(receipts[0].method,'预收款核销');
  const excess=structuredClone(state);excess.orders[0].paid=300;
  await request('state',{revision:state.revision,data:excess},400);
  assert.deepEqual(await request('state'),state,'超额核销必须原子拒绝');
  for(const change of [c=>{c.openingBalance=1000},c=>{delete c.openingBalance},c=>{delete c.legacySource},c=>{c.legacySource.id='other'}]){
   const forged=structuredClone(state);change(forged.customers[0]);
   await request('state',{revision:state.revision,data:forged},400);
  }
  await request('restore',before,400,'核销前备份不能重放已消耗的期初');
  const latest=await request('backup');
  const missing=structuredClone(latest);delete missing.state.customers[0].openingBalance;delete missing.state.customers[0].legacySource;
  await request('restore',missing,400);
  await request('restore',latest);
  const restored=await request('state');assert.equal(restored.customers[0].balance,170);
  assert.equal(restored.customers[0].openingBalance,270);assert.deepEqual(await request('receipts'),receipts);
 }finally{
  if(original!==undefined){
   const connection=await pool.getConnection();
   try{
    await connection.beginTransaction();await connection.query('UPDATE state SET payload=? WHERE id=1',[original]);
    for(const [table,rows] of snapshots){
     await connection.query('DELETE FROM `'+table+'`');
     for(const row of rows){const keys=Object.keys(row);await connection.query('INSERT INTO `'+table+'` ('+keys.map(k=>'`'+k+'`').join(',')+') VALUES ('+keys.map(()=>'?').join(',')+')',keys.map(k=>row[k]))}
    }
    await connection.commit();
   }catch(error){await connection.rollback();throw error}finally{connection.release()}
  }
  await pool.end();
 }
});
