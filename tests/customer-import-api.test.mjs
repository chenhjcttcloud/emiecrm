import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置CRM_TEST_BASE_URL，测试不能指向生产环境');

test('客户文件导入：预览、关联保护、重复、权限及并发',async()=>{
 assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'仅允许_test隔离数据库');
 const status=await(await fetch(base+'/api/status')).json();
 assert.equal(status.testMode,true,'仅允许测试模式API');
 const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
  user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
  database:process.env.CRM_DB_NAME,connectionLimit:2});
 let token='',originalState;
 const snapshots=new Map(),tables=['receipts','jst_shipments','jst_document_sales','jst_document_purchases'];
 const suffix=Date.now().toString(36),role='客户只读测试'+suffix,user='customer-import-view-'+suffix;
 const request=async(path,body,expected=200,auth=token)=>{
  const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
   headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth}:{})},
   body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();assert.equal(response.status,expected,JSON.stringify(data));return data;
 };
 try{
  if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
  token=(await request('login',{name:'admin',password:'test-password'})).token;
  const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');originalState=stored[0].payload;
  for(const table of tables){const [rows]=await pool.query('SELECT * FROM `'+table+'`');snapshots.set(table,rows);await pool.query('DELETE FROM `'+table+'`')}
  const customer={id:'import-existing',name:'原有客户',entity:'原主体',owner:'原负责人',status:'达成合作',channel:'线下门店',contact:'原联系人',phone:'13800000000',balance:86.5};
  const fixture={revision:1000,customers:[customer],products:[],orders:[{id:'import-prepaid',customerId:customer.id,customer:customer.name,
   type:'预收款订单',status:'已批准',owner:'原负责人',total:86.5,paid:86.5,method:'银行转账',items:[],created:'2026-11-20 09:00:00'}]};
  await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
  const receipt={id:'import-original-receipt',order:'import-prepaid',customerId:customer.id,customer:customer.name,amount:86.5,
   type:'预收款订单',method:'银行转账',time:'2026-11-20T01:00:00Z',operator:'admin'};
  await pool.query('INSERT INTO receipts(id,payload) VALUES(?,?)',[receipt.id,JSON.stringify(receipt)]);
  const csv='\uFEFF客户名称,聚水潭客户编号,联系人手机\r\n"新客户,门店",000001,0013800000000';
  const parsedResponse=await fetch(base+'/api/customers/import/parse?filename='+encodeURIComponent('客户.csv'),{
   method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+token},body:csv});
  assert.equal(parsedResponse.status,200);
  const parsed=await parsedResponse.json();assert.equal(parsed.rows[0].values[1],'000001');assert.equal(parsed.rows[0].values[2],'0013800000000');
  const row=(rowNumber,name,externalId)=>({rowNumber,name,externalId,entity:'导入主体',owner:'导入负责人',status:'跟进中',channel:'其他',contact:'导入联系人',phone:'13900000000'});
  const body={environment:'production',customerType:'offline',rows:[row(2,'新客户','000001'),row(3,'原有客户','000002'),row(4,'主动跳过客户','000003')]};
  const before=await request('state');const [receiptsBefore]=await pool.query('SELECT id,payload FROM receipts ORDER BY seq');
  const preview=await request('customers/import/preview',body);assert.equal(preview.revision,1000);
  assert.deepEqual(preview.rows.map(r=>r.status),['NEW','REVIEW','NEW']);
  assert.deepEqual(await request('state'),before,'预览不能修改数据');
  const commit={...body,revision:preview.revision,decisions:[{rowNumber:2,action:'CREATE'},{rowNumber:3,action:'LINK',customerId:customer.id},{rowNumber:4,action:'SKIP'}]};
  const applied=await request('customers/import',commit);assert.deepEqual(applied.summary,{created:1,linked:1,skipped:1});
  assert.equal(applied.state.customers.length,2);assert.equal(applied.state.revision,1001);
  const existing=applied.state.customers.find(c=>c.id===customer.id);const {externalLinks,...existingFields}=existing;
  assert.deepEqual(existingFields,customer,'关联只追加编号，不覆盖原有档案或余额');
  assert.equal(externalLinks[0].id,'000002');assert.equal(applied.state.customers.find(c=>c.name==='新客户').externalLinks[0].id,'000001');
  assert.deepEqual(applied.state.orders,before.orders);
  const [receiptsAfter]=await pool.query('SELECT id,payload FROM receipts ORDER BY seq');assert.deepEqual(receiptsAfter,receiptsBefore);
  await request('customers/import',commit,409);
  const again=await request('customers/import/preview',body);assert.equal(again.rows[0].status,'MATCHED');
  const skipped=await request('customers/import',{...body,revision:again.revision,decisions:body.rows.map(r=>({rowNumber:r.rowNumber,action:'SKIP'}))});
  assert.equal(skipped.state.revision,1001,'全部跳过不增加版本');
  const rebound={...body,rows:[row(2,'新客户','000001')],revision:1001,decisions:[{rowNumber:2,action:'LINK',customerId:customer.id}]};
  await request('customers/import',rebound,400);
  const invalid={...body,rows:[row(2,'第一条有效客户','000004'),row(3,'第二条客户','000005')],revision:1001,
   decisions:[{rowNumber:2,action:'CREATE'},{rowNumber:3,action:'LINK',customerId:'missing'}]};
  await request('customers/import',invalid,400);assert.deepEqual(await request('state'),applied.state,'整批失败不能部分写入');
  const duplicate={...body,rows:[row(2,'重复甲','000006'),row(3,'重复乙','000006')]};
  const duplicatePreview=await request('customers/import/preview',duplicate);assert.ok(duplicatePreview.rows.every(r=>r.status==='INVALID'));
  await request('customers/import',{...duplicate,revision:1001,decisions:[{rowNumber:2,action:'CREATE'},{rowNumber:3,action:'CREATE'}]},400);
  const malformed=structuredClone(applied.state);malformed.customers[1].externalLinks=[structuredClone(externalLinks[0])];
  await request('state',{data:malformed,revision:1001},400);
  await request('customers/import/preview',body,401,'');
  await request('system/roles',{action:'create',name:role,permissions:['business.read']});
  await request('system/users',{action:'create',name:user,role,displayName:'客户导入只读验证',status:'启用',password:'customer-import-test-password'});
  const viewer=(await request('login',{name:user,password:'customer-import-test-password'})).token;
  await request('customers/import/preview',body,403,viewer);
  await request('customers/import',commit,403,viewer);
  const deniedParse=await fetch(base+'/api/customers/import/parse?filename=customers.csv',{
   method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+viewer},body:csv});
  assert.equal(deniedParse.status,403);
  const writes=await Promise.all([7,8].map(index=>fetch(base+'/api/customers/import',{
   method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},
   body:JSON.stringify({environment:'production',customerType:'offline',rows:[row(2,'并发客户'+index,'00000'+index)],revision:1001,decisions:[{rowNumber:2,action:'CREATE'}]})
  })));
  assert.deepEqual(writes.map(response=>response.status).sort(),[200,409]);
  const final=await request('state');assert.equal(final.customers.length,3);assert.equal(final.revision,1002);
  const [receiptsFinal]=await pool.query('SELECT id,payload FROM receipts ORDER BY seq');assert.deepEqual(receiptsFinal,receiptsBefore);
 }finally{
  // Only the isolated database is restored; the local service's business data is never touched.
  if(originalState!==undefined){
   const connection=await pool.getConnection();
   try{await connection.beginTransaction();
    await connection.query('UPDATE state SET payload=? WHERE id=1',[originalState]);
    for(const [table,rows] of snapshots){await connection.query('DELETE FROM `'+table+'`');
     for(const row of rows){const keys=Object.keys(row);await connection.query('INSERT INTO `'+table+'` ('+keys.map(k=>'`'+k+'`').join(',')+') VALUES ('+keys.map(()=>'?').join(',')+')',keys.map(k=>row[k]&&typeof row[k]==='object'&&!Buffer.isBuffer(row[k])&&!(row[k] instanceof Date)?JSON.stringify(row[k]):row[k]))}}
    await connection.commit();
   }catch(error){await connection.rollback();throw error}finally{connection.release()}
  }
  await pool.query('DELETE FROM user_profiles WHERE name=?',[user]);await pool.query('DELETE FROM users WHERE name=?',[user]);await pool.query('DELETE FROM roles WHERE name=?',[role]);
  await pool.end();
 }
});
