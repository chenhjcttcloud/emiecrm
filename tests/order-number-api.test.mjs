import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置隔离测试API地址');

test('短订单号在服务端幂等预留，首次保存占用，拒绝伪造号',async()=>{
  assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'只允许_test隔离数据库');
  const status=await(await fetch(base+'/api/status')).json();
  assert.equal(status.testMode,true);
  const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
    user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
    database:process.env.CRM_DB_NAME,connectionLimit:2});
  let token='',original;
  const keys=[];
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
    await request('orders/number-reservations',{requestId:'unauthenticated-request'},401,false);
    await request('orders/number-reservations',{requestId:123},400);
    await request('orders/number-reservations',{requestId:'short'},400);
    const key=randomUUID();keys.push(key);
    const first=await request('orders/number-reservations',{requestId:key});
    const number=first.data.orderNumber;
    assert.match(number,/^N\d{6}\d{4,}$/);
    assert.equal(first.response.headers.get('cache-control'),'no-store');
    assert.deepEqual((await request('orders/number-reservations',{requestId:key})).data,first.data);
    const moreKeys=Array.from({length:4},()=>randomUUID());keys.push(...moreKeys);
    const more=await Promise.all(moreKeys.map(requestId=>request('orders/number-reservations',{requestId})));
    assert.equal(new Set([number,...more.map(result=>result.data.orderNumber)]).size,5);
    const current=(await request('state')).data;
    const customer=current.customers[0];assert.ok(customer);
    const methods=(await request('system/payment-methods')).data;
    const method=methods.find(row=>row.status==='启用').name;
    const draft={id:number,type:'预收款订单',status:'草稿',customerId:customer.id,customer:customer.name,
      owner:'管理员',total:1,paid:0,method,items:[]};
    const next=structuredClone(current);next.orders.push(draft);
    const saved=(await request('state',{revision:current.revision,data:next})).data;
    assert.equal(saved.orders.find(order=>order.id===number).id,number);
    assert.equal((await request('orders/number-reservations',{requestId:key})).data.orderNumber,number);
    const [claimed]=await pool.query('SELECT consumed,order_id FROM order_number_reservations WHERE order_number=?',[number]);
    assert.equal(Number(claimed[0].consumed),1);assert.equal(claimed[0].order_id,number);
    const forged=structuredClone(saved);forged.orders.push({...draft,id:'N9912319999'});
    await request('state',{revision:saved.revision,data:forged},409);
    assert.deepEqual((await request('state')).data,saved);
  }finally{
    if(original!==undefined)await pool.query('UPDATE state SET payload=? WHERE id=1',[original]);
    if(keys.length)await pool.query('DELETE FROM order_number_reservations WHERE request_id IN (?)',[keys]);
    await pool.end();
  }
});
