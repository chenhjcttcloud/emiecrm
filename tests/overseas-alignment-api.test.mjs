import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

test('国内保留收款规则：订单取消删除、预收参考产品和收款快照通过真实HTTP及MySQL校验',async()=>{
 const base=process.env.CRM_TEST_BASE_URL;
 assert.ok(base&&process.env.CRM_DB_NAME?.endsWith('_test'));
 const status=await(await fetch(base+'/api/status')).json();assert.equal(status.testMode,true);
 const pool=mysql.createPool({host:process.env.CRM_DB_HOST,user:process.env.CRM_DB_USER,password:process.env.MYSQL_PASSWORD,database:process.env.CRM_DB_NAME,connectionLimit:2});
 let token='',originalState;
 const saved=new Map(),tables=['receipts','feishu_order_approvals','feishu_receipt_approvals','jst_shipments','jst_document_sales','jst_document_purchases','audit'];
 async function req(path,body,expected=200){
  const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const value=await response.json();assert.equal(response.status,expected,JSON.stringify(value));return value;
 }
 async function restore(table,rows){
  await pool.query('DELETE FROM `'+table+'`');
  for(const row of rows){const keys=Object.keys(row);await pool.query('INSERT INTO `'+table+'` ('+keys.map(k=>'`'+k+'`').join(',')+') VALUES ('+keys.map(()=>'?').join(',')+')',keys.map(k=>{const v=row[k];return v&&typeof v==='object'&&!Buffer.isBuffer(v)&&!(v instanceof Date)?JSON.stringify(v):v}));}
 }
 try{
  if(!status.setup)await req('setup',{name:'admin',password:'test-password'});
  token=(await req('login',{name:'admin',password:'test-password'})).token;
  originalState=(await pool.query('SELECT payload FROM state WHERE id=1'))[0][0].payload;
  for(const table of tables){saved.set(table,(await pool.query('SELECT * FROM `'+table+'`'))[0]);await pool.query('DELETE FROM `'+table+'`');}
  const order=(id,status)=>({id,type:'销售订单',owner:'HTTP验收',customerId:'qa-c',customer:'验收客户',status,items:[{product:'qa-p',qty:1,price:10}],total:10,paid:0,note:'',created:'2026-11-23 10:00:00',updated:'2026-11-23 10:00:00'});
  const fixture={revision:9000,customers:[{id:'qa-c',name:'验收客户',balance:0}],products:[{id:'qa-p',name:'验收商品',model:'原始规格',jstSku:'QA-SKU',source:'jushuitan',enabled:true}],orders:[order('qa-draft','草稿'),order('qa-delete','草稿'),order('qa-pay','已批准')]};
  await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
  let state=await req('state');
  const receiptConfig=await req('feishu/receipt-approvals/config');
  assert.equal(receiptConfig.enabled,false,'未配置独立收款模板的测试环境保留原收款模式');
  assert.equal(receiptConfig.canSubmit,false);
  assert.deepEqual(await req('feishu/receipt-approvals'),[]);
  const authorizedToken=token;token='';
  await req('feishu/receipt-approvals/config',undefined,401);
  await req('feishu/receipt-approvals',undefined,401);token=authorizedToken;
  const unavailable=await fetch(base+'/api/feishu/receipt-approvals',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({requestId:'qa-disabled-receipt',revision:state.revision,customerId:'qa-c',method:'银行转账',amount:2,receiptDate:'2026-11-23',receiptCategory:'销售收入',allocations:[{orderId:'qa-pay',amount:2}]})});
  assert.ok([400,403,503].includes(unavailable.status),'缺少飞书配置不能创建审批或入账');
  assert.equal((await req('state')).revision,state.revision);
  assert.deepEqual(await req('receipts'),[]);
  const cancelled=await req('feishu/approvals/orders/qa-draft/cancel',{});
  state=cancelled.state;assert.equal(state.orders.find(o=>o.id==='qa-draft').status,'已取消');assert.equal(cancelled.approval,null);
  state.customers[0].name='改名验收客户';state=await req('state',{data:state,revision:state.revision});
  assert.equal(state.orders.find(o=>o.id==='qa-draft').customer,'验收客户','已取消订单保留归档名称');
  assert.equal(state.orders.find(o=>o.id==='qa-pay').customer,'改名验收客户');
  const forged=structuredClone(state);forged.orders.find(o=>o.id==='qa-draft').status='草稿';await req('state',{data:forged,revision:forged.revision},400);
  state=await req('orders/qa-delete/delete',{});assert.ok(state.orders.find(o=>o.id==='qa-delete').deletedAt);
  assert.equal((await req('orders/qa-delete/delete',{})).revision,state.revision,'重复删除不重复写');
  await req('jst/orders/qa-draft/documents/sale',{remove:true},400);
  await req('jst/orders/qa-draft/documents/purchases',{poId:'31',remove:true},400);
  const deposit={...order('qa-deposit','草稿'),type:'预收款订单',total:50,customer:'改名验收客户',method:'银行转账'};
  state.orders.push(deposit);state=await req('state',{data:state,revision:state.revision});assert.equal(state.orders.find(o=>o.id==='qa-deposit').total,50);assert.equal(state.orders.find(o=>o.id==='qa-deposit').items.length,1);
  const pay=state.orders.find(o=>o.id==='qa-pay');pay.paid=2;pay.method='银行转账';
  state=await req('state',{data:state,revision:state.revision,receiptVerification:{amount:2,customerId:'qa-c',customer:'改名验收客户',method:'银行转账'}});
  let receipts=await req('receipts');assert.equal(receipts.length,1);assert.equal(receipts[0].itemsSource,'snapshot');assert.equal(receipts[0].items[0].name,'验收商品');const receiptId=receipts[0].id;
  state.products[0].name='当前商品新名称';await req('state',{data:state,revision:state.revision},400);
  // Simulate a catalog refresh in the isolated database; CRM still rejects manual product edits.
  state.revision++;await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(state)]);state=await req('state');
  receipts=await req('receipts');assert.equal(receipts[0].items[0].name,'验收商品','新流水商品快照不能随商品资料改名');
  await pool.query('INSERT INTO receipts(id,payload) VALUES(?,?)',['qa-legacy',JSON.stringify({id:'qa-legacy',order:'qa-pay',customerId:'qa-c',customer:'改名验收客户',type:'销售订单',amount:1,method:'银行转账',time:'2026-11-23T00:00:00Z',operator:'admin'})]);
  receipts=await req('receipts');const legacy=receipts.find(r=>r.id==='qa-legacy');assert.equal(legacy.itemsSource,'order');assert.equal(legacy.items[0].name,'当前商品新名称');
  const report=await req('sales/receipts?page=1&pageSize=100');assert.equal(report.rows.find(r=>r.id===receiptId).itemsSource,'snapshot');assert.equal(report.rows.find(r=>r.id==='qa-legacy').itemsSource,'order');
  assert.equal(report.summary.amount,'3.00');
  const backup=await req('backup');assert.equal(backup.receipts.find(r=>r.id==='qa-legacy').items,undefined,'读取派生资料不能污染备份');
  await req('orders/qa-pay/delete',{},400);await req('feishu/approvals/orders/qa-pay/cancel',{},400);
  const products=await req('sales/items?page=1&pageSize=100');assert.ok(products.rows.every(row=>!['qa-delete','qa-draft'].includes(row.orderId)));
 }finally{
  if(originalState!==undefined)await pool.query('UPDATE state SET payload=? WHERE id=1',[originalState]);
  for(const [table,rows]of saved)await restore(table,rows);
  await pool.end();
 }
});
