import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置隔离测试API地址');

test('多笔直接收款按真实方式及到账日逐笔入账，核销只扣对应部分',async()=>{
  assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'只允许_test隔离数据库');
  const status=await(await fetch(base+'/api/status')).json();assert.equal(status.testMode,true);
  const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
    user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
    database:process.env.CRM_DB_NAME,connectionLimit:2});
  let token='',original;
  const customerId='receipt-details-'+randomUUID(),prefix='receipt-details-'+randomUUID();
  const request=async(path,body,expected=200)=>{
    const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
      headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
      body:body===undefined?undefined:JSON.stringify(body)});
    const value=await response.json();assert.equal(response.status,expected,JSON.stringify(value));return value;
  };
  try{
    if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
    token=(await request('login',{name:'admin',password:'test-password'})).token;
    const config=await request('feishu/receipt-approvals/config');assert.equal(config.enabled,false);
    const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');original=stored[0].payload;
    const fixture=JSON.parse(original);fixture.revision+=100;
    const customer={id:customerId,name:customerId,owner:'admin',balance:50};fixture.customers.push(customer);
    const order=(id,type,total,paid)=>({id,type,total,paid,status:'已批准',customerId,customer:customer.name,
      owner:'admin',method:'银行转账',items:[]});
    fixture.orders.push(order(prefix+'-fund','预收款订单',50,50),order(prefix+'-one','销售订单',100,0),order(prefix+'-two','销售订单',100,0));
    await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
    await pool.query('INSERT INTO receipts(id,payload) VALUES(?,?)',[prefix+'-fund',JSON.stringify({
      id:prefix+'-fund',order:prefix+'-fund',customerId,customer:customer.name,type:'预收款订单',amount:50,
      method:'银行转账',time:'2026-10-01T00:00:00Z',operator:'fixture',items:[],itemsSource:'snapshot'})]);
    const current=await request('state'),next=structuredClone(current);
    next.orders.find(row=>row.id===prefix+'-one').paid=60;
    next.orders.find(row=>row.id===prefix+'-two').paid=40;
    for(const row of next.orders)if([prefix+'-one',prefix+'-two'].includes(row.id))row.method='组合收款';
    const verification={customerId,customer:customer.name,method:'组合收款',amount:100,
      paymentDetails:[{method:'银行转账',amount:70,receiptDate:'2026-10-31'},
        {method:'预收款核销',amount:30,receiptDate:'2026-11-25'}],
      allocations:[{orderId:prefix+'-one',amount:60},{orderId:prefix+'-two',amount:40}]};
    const invalid=structuredClone(verification);invalid.paymentDetails[0].amount=69;
    await request('state',{revision:current.revision,data:next,receiptVerification:invalid},400);
    assert.deepEqual(await request('state'),current);
    const saved=await request('state',{revision:current.revision,data:next,receiptVerification:verification});
    assert.equal(saved.customers.find(row=>row.id===customerId).balance,20);
    const [records]=await pool.query("SELECT payload FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.customerId'))=?",[customerId]);
    const splits=records.map(row=>JSON.parse(row.payload)).filter(row=>row.id!==prefix+'-fund');
    assert.equal(splits.length,3);
    assert.deepEqual(splits.map(row=>[row.method,Number(row.amount),row.receivedOn,row.paymentLineNo]).sort(),[
      ['银行转账',10,'2026-10-31',1],['银行转账',60,'2026-10-31',1],['预收款核销',30,'2026-11-25',2],
    ].sort());
    assert.ok(splits.every(row=>row.receiptDate===row.receivedOn&&row.method!=='组合收款'));
    const query='customerId='+encodeURIComponent(customerId)+'&page=1&pageSize=20';
    const october=await request('sales/receipts?'+query+'&month=2026-10&method='+encodeURIComponent('银行转账'));
    assert.equal(october.rows.filter(row=>[prefix+'-one',prefix+'-two'].includes(row.orderId)).length,2);
    const november=await request('sales/receipts?'+query+'&month=2026-11');
    assert.equal(november.rows.length,1);assert.equal(Number(november.summary.amount),30);
    assert.equal(november.rows[0].paymentLineNo,2);
  }finally{
    if(original!==undefined)await pool.query('UPDATE state SET payload=? WHERE id=1',[original]);
    await pool.query("DELETE FROM receipts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.customerId'))=?",[customerId]);
    await pool.end();
  }
});
