import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置隔离测试API地址');

test('业务状态条件读取：相同版本轻量返回，变化时完整读取',async()=>{
 assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'只允许_test隔离数据库');
 const status=await(await fetch(base+'/api/status')).json();
 assert.equal(status.testMode,true);
 const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
  user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
  database:process.env.CRM_DB_NAME,connectionLimit:2});
 let token='',original;
 const request=async(path,body,expected=200,authenticated=true)=>{
  const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
   headers:{'Content-Type':'application/json',...(token&&authenticated?{Authorization:'Bearer '+token}:{})},
   body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();assert.equal(response.status,expected,JSON.stringify(data));
  return {data,response};
 };
 try{
  if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
  token=(await request('login',{name:'admin',password:'test-password'})).data.token;
  const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');original=stored[0].payload;
  const source={system:'legacy-feishu-crm',id:'0000000000000001',sha256:'a'.repeat(64),fileName:'synthetic.xlsx',sheet:'Sheet1',rowNumber:2,
   importedAt:'2026-11-20T09:00:00Z',fields:{ID:'0000000000000001',客户名称:'条件刷新测试客户',预收款金额:'80'}};
  const fixture={revision:7000,customers:[{id:'state-refresh-customer',name:'条件刷新测试客户',balance:80,openingBalance:80,legacySource:source}],
   products:[],orders:[{id:'state-refresh-order',customer:'条件刷新测试客户',type:'销售订单',status:'草稿',total:0,paid:0,items:[]}]};
  await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
  const full=await request('state');
  assert.equal(full.data.revision,7000);assert.deepEqual(full.data.customers,fixture.customers);
  assert.equal(full.data.orders[0].customerId,fixture.customers[0].id,'完整读取仍补齐历史订单客户归属');
  assert.equal(full.response.headers.get('cache-control'),'private, no-store');
  const same=await request('state?knownRevision=7000');
  assert.deepEqual(same.data,{unchanged:true,revision:7000});
  assert.equal(same.response.headers.get('cache-control'),'private, no-store');
  assert.ok(JSON.stringify(same.data).length<100);
  const [afterRead]=await pool.query('SELECT payload FROM state WHERE id=1');
  assert.deepEqual(JSON.parse(afterRead[0].payload),fixture,'读取不得改写业务文档或期初依据');
  const changed=structuredClone(fixture);changed.revision=7001;changed.customers[0].contact='更新资料';
  await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(changed)]);
  const next=(await request('state?knownRevision=7000')).data;
  assert.equal(next.revision,7001);assert.deepEqual(next.customers,changed.customers);assert.ok(!next.unchanged);
  assert.deepEqual((await request('state?knownRevision=7001')).data,{unchanged:true,revision:7001});
  assert.equal((await request('state?knownRevision=0')).data.revision,7001);
  for(const version of ['-1','1.5','1e3','invalid','','9223372036854775808'])
   await request('state?knownRevision='+encodeURIComponent(version),undefined,400);
  await request('state?knownRevision=7001',undefined,401,false);
  await request('state?knownRevision=invalid',undefined,401,false);
 }finally{
  if(original!==undefined)await pool.query('UPDATE state SET payload=? WHERE id=1',[original]);
  await pool.end();
 }
});
