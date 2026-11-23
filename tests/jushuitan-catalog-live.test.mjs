// Explicit opt-in: reads a bounded real catalog window into the isolated CRM test DB only.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';
const enabled=process.env.CRM_JST_CATALOG_LIVE_TEST==='1';
test('聚水潭后台目录分页、进度和隔离数据库事务验证',{skip:!enabled},async()=>{
 const base=process.env.CRM_TEST_BASE_URL;
 assert.ok(base);assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'));
 const status=await(await fetch(base+'/api/status')).json();assert.equal(status.testMode,true);
 let token='';
 async function req(path,body){const r=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const d=await r.json();assert.equal(r.status,200,JSON.stringify(d));return d}
 token=(await req('login',{name:'admin',password:'test-password'})).token;
 const config=await req('jst/config');assert.equal(config.configured,true);assert.equal(config.uploadsEnabled,false);assert.equal(config.changesEnabled,false);
 const before=await req('state'),receipts=await req('receipts');
 const start=new Date(Date.now()-7*86400000).toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});
 const job=await req('jst/products/catalog/start',{startDate:start});
 let completed,currentJobId=job.id;
 try{
  for(let i=0;i<120;i++){
   const progress=await req('jst/products/catalog');
   assert.equal(progress.id,job.id);assert.notEqual(progress.status,'FAILED',JSON.stringify(progress));
   if(progress.status==='COMPLETE'){completed=progress;break}
   await new Promise(resolve=>setTimeout(resolve,500));
  }
  assert.ok(completed,'Bounded catalog import did not complete');assert.equal(completed.progress,100);
  const after=await req('state');assert.deepEqual(after.orders,before.orders);assert.deepEqual(after.customers,before.customers);assert.deepEqual(await req('receipts'),receipts);
  if(completed.processedRows>0)assert.ok(after.products.some(p=>p.source==='jushuitan'&&p.jstEnvironment===config.environment));
  assert.equal(new Set(after.products.map(p=>p.id)).size,after.products.length);
  const incremental=await req('jst/products/catalog/start',{mode:'INCREMENTAL'});currentJobId=incremental.id;
  let incrementalComplete=false;
  for(let i=0;i<120;i++){
   const progress=await req('jst/products/catalog');assert.equal(progress.id,incremental.id);assert.notEqual(progress.status,'FAILED',JSON.stringify(progress));
   if(progress.status==='COMPLETE'){incrementalComplete=true;break}
   await new Promise(resolve=>setTimeout(resolve,500));
  }
  assert.ok(incrementalComplete,'Incremental catalog import did not complete');
  assert.deepEqual((await req('state')).orders,before.orders);assert.deepEqual(await req('receipts'),receipts);
  console.log(JSON.stringify({mode:completed.mode,processedRows:completed.processedRows,progress:completed.progress,incrementalComplete,ordersUnchanged:true,receiptsUnchanged:true}));
 }finally{
  const pool=await mysql.createConnection({host:process.env.CRM_DB_HOST,user:process.env.CRM_DB_USER,password:process.env.MYSQL_PASSWORD,database:process.env.CRM_DB_NAME});
  await pool.query("DELETE FROM jst_catalog_jobs WHERE environment=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.id'))=?",[config.environment,currentJobId]);await pool.end();
 }
});
