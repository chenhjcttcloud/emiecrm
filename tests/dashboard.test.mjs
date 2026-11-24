import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置CRM_TEST_BASE_URL，测试不能指向生产环境');

test('独立经营看板：统计口径、目标并发和模块权限',async()=>{
  assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'测试只能使用_test隔离数据库');
  const status=await(await fetch(base+'/api/status')).json();
  assert.equal(status.backend,'java');
  assert.equal(status.testMode,true,'API必须启用独立数据库测试模式');
  const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
    user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
    database:process.env.CRM_DB_NAME,connectionLimit:3});
  let token='';
  async function request(path,body,expected=200,auth=token){
    const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
      headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth}:{})},
      body:body===undefined?undefined:JSON.stringify(body)});
    const data=await response.json();
    assert.equal(response.status,expected,JSON.stringify(data));
    return data;
  }
  const snapshots=new Map();
  const tables=['receipts','jst_shipments','jst_document_sales','dashboard_targets','sales_targets','sales_target_scope'];
  const suffix=Date.now().toString(36);
  const users=['dash-view-'+suffix,'dash-target-'+suffix];
  const roles=['看板查看测试'+suffix,'目标管理测试'+suffix];
  let originalState;
  async function restoreTable(connection,table,rows){
    await connection.query('DELETE FROM `'+table+'`');
    for(const row of rows){
      const keys=Object.keys(row);
      const values=keys.map(key=>{
        const value=row[key];
        return value&&typeof value==='object'&&!Buffer.isBuffer(value)&&!(value instanceof Date)?JSON.stringify(value):value;
      });
      await connection.query('INSERT INTO `'+table+'` ('+keys.map(key=>'`'+key+'`').join(',')+') VALUES ('+keys.map(()=>'?').join(',')+')',values);
    }
  }
  try{
    if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
    token=(await request('login',{name:'admin',password:'test-password'})).token;
    const me=await request('me');
    assert.ok(me.permissions.includes('dashboard.read'));
    assert.ok(me.permissions.includes('dashboard.targets.manage'));
    const [stateRows]=await pool.query('SELECT payload FROM state WHERE id=1');
    originalState=stateRows[0].payload;
    for(const table of tables){const [rows]=await pool.query('SELECT * FROM `'+table+'`');snapshots.set(table,rows)}
    for(const table of tables)await pool.query('DELETE FROM `'+table+'`');
    const environment=process.env.CRM_JST_ENV||'sandbox';
    const order=(id,total,paid,created='2026-11-02 09:00:00',extra={})=>({id,type:'销售订单',status:'已批准',
      customerId:'dash-c1',customer:'看板客户甲',owner:'看板签单人',total,paid,created,items:[],...extra});
    const period=(start,end)=>({periodStart:start,periodEnd:end,cycle:'month'});
    const fixture={revision:999,customers:[{id:'dash-c1',name:'看板客户甲',owner:'看板签单人',balance:40},
      {id:'dash-c2',name:'看板客户乙',owner:'其他人',balance:25}],products:[],orders:[
      order('dash-paid',100,100),order('dash-partial',200.02,50.01,'2026-11-03'),
      order('dash-changed',100,100,'2026-11-04'),order('dash-draft',999,0,'2026-11-05',{status:'草稿'}),
      order('dash-pre',100,100,'2026-10-01',{type:'预收款订单'}),order('dash-pre2',50,40,'2026-11-01',{type:'预收款订单'}),
      order('dash-boundary',10,0,'2026-10-31T16:00:00Z'),
      order('dash-month',120,20,'2026-12-01',{source:'jushuitan-settlement',settlement:period('2026-11-01','2026-11-30')}),
      order('dash-history',900,0,'2026-11-19',{source:'jushuitan-settlement',settlement:period('2026-10-01','2026-10-31')}),
      order('dash-duplicate',300,0,'2026-11-19',{source:'jushuitan-settlement',settlement:period('2026-11-01','2026-11-30')})]};
    await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
    const shipments=[['dash-changed',{environment,effectiveOrder:{total:60,paid:60}}],
      ['dash-paid',{environment,oId:987654,soId:'CRM-dashboard-paid',request:{shop_id:1}}]];
    for(const [id,payload] of shipments)await pool.query('INSERT INTO jst_shipments(order_id,payload) VALUES(?,?)',[id,JSON.stringify(payload)]);
    await pool.query('INSERT INTO jst_document_sales(environment,order_id,shop_id,o_id,so_id) VALUES(?,?,?,?,?)',
      [environment,'dash-duplicate','1','987654','CRM-dashboard-paid']);
    const receipt=(id,order,amount,type='预收款订单',method='银行转账')=>({id,order,customerId:'dash-c1',customer:'看板客户甲',
      amount,type,method,time:'2026-11-02T01:00:00Z',operator:'admin'});
    for(const entry of [receipt('dash-r1','dash-pre',100),receipt('dash-r2','dash-pre2',50),receipt('dash-r3','dash-pre2',-10),
      receipt('dash-r4','dash-paid',100,'销售订单','预收款核销'),receipt('dash-r5','dash-changed',-40,'订单变更')])
      await pool.query('INSERT INTO receipts(id,payload) VALUES(?,?)',[entry.id,JSON.stringify(entry)]);
    const [receiptBaseline]=await pool.query('SELECT id,payload FROM receipts ORDER BY id');

    const report=await request('dashboard/sales?month=2026-11');
    const moneyFields=new Set(['orderAmount','settledAmount','outstandingAmount','advanceReceived','advanceBalance']);
    const numericSummary=Object.fromEntries(Object.entries(report.summary).map(([key,value])=>[key,moneyFields.has(key)?Number(value):value]));
    assert.deepEqual(numericSummary,{orderCount:5,settledCount:2,pendingCount:3,orderAmount:490.02,
      settledAmount:230.01,outstandingAmount:260.01,advanceReceived:140,advanceBalance:65,fulfillmentExceptionCount:0});
    assert.deepEqual(report.fulfillmentExceptions,{count:0,rows:[]});
    assert.equal(report.daily.length,30);
    assert.equal(Number(report.daily[0].amount),10);
    assert.equal(Number(report.daily[29].amount),120);
    assert.equal(report.orders.length,5);
    assert.equal(report.advanceReceipts.length,3);
    assert.equal(report.target.amount,null);
    assert.equal(report.target.source,'unset');
    assert.equal(report.target.personCount,0);
    assert.equal(report.target.manualAmount,null);
    assert.equal(report.targetApplicable,true);
    assert.ok(report.warnings.some(warning=>warning.includes('dash-duplicate')));
    const filtered=await request('dashboard/sales?'+new URLSearchParams({month:'2026-11',owner:'看板签单人',customerId:'dash-c1'}));
    assert.equal(filtered.targetApplicable,false);
    assert.equal(filtered.summary.advanceBalance,null);
    assert.equal(Number((await request('dashboard/sales?month=2026-11&customerId=dash-c1')).summary.advanceBalance),40);
    assert.equal((await request('dashboard/sales?month=2026-11&customerId=missing')).summary.orderCount,0);
    await request('dashboard/sales?month=2026-13',undefined,400);
    await request('dashboard/sales?month=2026-11',undefined,401,'');

    await request('system/roles',{action:'create',name:roles[0],permissions:['dashboard.read']});
    await request('system/roles',{action:'create',name:roles[1],permissions:['dashboard.targets.manage']});
    for(let index=0;index<users.length;index++)await request('system/users',{action:'create',name:users[index],
      displayName:roles[index],role:roles[index],status:'启用',password:'dashboard-test-password'});
    const viewer=(await request('login',{name:users[0],password:'dashboard-test-password'})).token;
    const manager=(await request('login',{name:users[1],password:'dashboard-test-password'})).token;
    await request('dashboard/sales?month=2026-11',undefined,200,viewer);
    await request('state',undefined,403,viewer);
    await request('dashboard/targets?year=2026',undefined,403,viewer);
    await request('dashboard/targets',{month:'2026-11',amount:'1',revision:0},403,viewer);
    await request('dashboard/sales?month=2026-11',undefined,403,manager);
    await request('state',undefined,403,manager);
    const targets=await request('dashboard/targets?year=2026',undefined,200,manager);
    assert.equal(targets.targets.length,12);
    assert.ok(targets.targets.every(target=>target.amount===null));
    const zero=await request('dashboard/targets',{month:'2026-11',amount:'0',revision:0},200,manager);
    assert.equal(Number(zero.amount),0);
    assert.equal(zero.revision,1);
    assert.equal(zero.source,'manual');
    assert.equal(zero.manualAmount,'0.00');
    assert.equal(Number((await request('dashboard/sales?month=2026-11')).target.amount),0);
    for(const amount of ['-1','0.001','1000000000000','NaN','1e3'])
      await request('dashboard/targets',{month:'2026-11',amount,revision:1},400,manager);
    await request('dashboard/targets',{month:'2026-11',amount:'100'},400,manager);
    await request('dashboard/targets',{month:'2026-11',amount:'100',revision:0},409,manager);
    const writes=await Promise.all(['0.01','0.02'].map(amount=>fetch(base+'/api/dashboard/targets',{method:'POST',
      headers:{'Content-Type':'application/json',Authorization:'Bearer '+manager},
      body:JSON.stringify({month:'2026-12',amount,revision:0})})));
    assert.deepEqual(writes.map(response=>response.status).sort(),[200,409]);
    const december=(await request('dashboard/targets?year=2026',undefined,200,manager)).targets.find(target=>target.month==='2026-12');
    assert.equal(december.revision,1);
    assert.ok([0.01,0.02].includes(Number(december.amount)));
    const audit=await request('system/audit');
    const event=audit.find(event=>event.user===users[1]&&event.details?.type==='DASHBOARD_TARGET_CHANGED'&&event.details.month==='2026-12');
    assert.ok(event,'成功修改目标必须记录审计日志');
    assert.equal(event.details.before.revision,0);
    assert.equal(event.details.after.revision,1);

    // Company targets are derived from personnel targets when any amount is explicitly set.
    // The old company amount remains an intact fallback, including its own CAS revision.
    const manual=await request('dashboard/targets',{month:'2026-11',amount:'1000.00',revision:1},200,manager);
    assert.equal(manual.source,'manual'); assert.equal(manual.revision,2);
    async function assertEffectiveTarget(month,amount,source,personCount,manualAmount){
      const dashboard=await request('dashboard/sales?'+new URLSearchParams({month}),undefined,200,viewer);
      const annual=await request('dashboard/targets?'+new URLSearchParams({year:month.slice(0,4)}),undefined,200,manager);
      const target=annual.targets.find(target=>target.month===month);
      for(const value of [dashboard.target,target]){
        assert.equal(value.amount,amount); assert.equal(value.source,source);
        assert.equal(value.personCount,personCount); assert.equal(value.manualAmount,manualAmount);
      }
      return dashboard.target;
    }
    await assertEffectiveTarget('2026-11','1000.00','manual',0,'1000.00');
    await request('sales/targets',{month:'2026-11',owner:'看板签单人',amount:'0.10',revision:0},200,manager);
    await request('sales/targets',{month:'2026-11',owner:roles[0],amount:'0.20',revision:0},200,manager);
    const aggregate=await assertEffectiveTarget('2026-11','0.30','personnel',2,'1000.00');
    assert.equal(aggregate.revision,2,'人员目标汇总不应改写旧公司目标版本');
    await request('dashboard/targets',{month:'2026-11',amount:'999',revision:2},409,manager);
    await request('sales/targets',{month:'2026-11',owner:'看板签单人',amount:'0.11',revision:1},200,manager);
    await assertEffectiveTarget('2026-11','0.31','personnel',2,'1000.00');
    const filteredAggregate=await request('dashboard/sales?'+new URLSearchParams({month:'2026-11',owner:'看板签单人'}),undefined,200,viewer);
    assert.equal(filteredAggregate.target.amount,'0.31'); assert.equal(filteredAggregate.target.source,'personnel');
    assert.equal(filteredAggregate.targetApplicable,false,'人员筛选不应将公司目标误用为个人完成率');
    await request('sales/targets',{month:'2026-11',owner:'看板签单人',amount:'1',revision:2},403,viewer);

    await request('sales/targets',{month:'2026-11',owner:'看板签单人',amount:'0',revision:2},200,manager);
    await request('sales/targets',{month:'2026-11',owner:roles[0],amount:'0',revision:1},200,manager);
    await assertEffectiveTarget('2026-11','0.00','personnel',2,'1000.00');
    await pool.query('UPDATE sales_targets SET amount=NULL WHERE month_key=?',['2026-11']);
    await assertEffectiveTarget('2026-11','1000.00','manual',0,'1000.00');
    await pool.query('INSERT INTO sales_targets(month_key,owner,amount,revision) VALUES(?,?,NULL,0)',['2027-01','看板签单人']);
    await assertEffectiveTarget('2027-01',null,'unset',0,null);

    await assertEffectiveTarget('2026-12',december.amount,'manual',0,december.amount);
    await request('sales/targets',{month:'2026-12',owner:'看板签单人',amount:'0.50',revision:0},200,manager);
    await assertEffectiveTarget('2026-12','0.50','personnel',1,december.amount);
    await assertEffectiveTarget('2026-11','1000.00','manual',0,'1000.00');

    const simultaneous=await Promise.all([
      ['dashboard/targets',{month:'2027-02',amount:'500.00',revision:0}],
      ['sales/targets',{month:'2027-02',owner:'看板签单人',amount:'0.10',revision:0}],
    ].map(([path,body])=>fetch(base+'/api/'+path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+manager},body:JSON.stringify(body)})));
    assert.ok([200,409].includes(simultaneous[0].status),'公司与个人目标首次并发时，公司写入只能成功或报告来源冲突');
    assert.equal(simultaneous[1].status,200);
    await assertEffectiveTarget('2027-02','0.10','personnel',1,simultaneous[0].status===200?'500.00':null);
    const [legacyCompany]=await pool.query('SELECT amount,revision FROM dashboard_targets WHERE month_key=?',['2026-11']);
    assert.equal(legacyCompany[0].amount,'1000.00'); assert.equal(legacyCompany[0].revision,2);
    const [receiptAfter]=await pool.query('SELECT id,payload FROM receipts ORDER BY id');
    assert.deepEqual(receiptAfter,receiptBaseline,'目标维护与派生读取不得修改收款流水');
    const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');
    assert.deepEqual(JSON.parse(stored[0].payload),fixture,'读取看板和维护目标不得修改业务数据');
  }finally{
    try{
      const connection=await pool.getConnection();
      try{
        await connection.beginTransaction();
        for(const table of tables)if(snapshots.has(table))await restoreTable(connection,table,snapshots.get(table));
        if(originalState!==undefined)await connection.query('UPDATE state SET payload=? WHERE id=1',[originalState]);
        for(const user of users){
          await connection.query('DELETE FROM feishu_identities WHERE user_name=?',[user]);
          await connection.query('DELETE FROM user_profiles WHERE name=?',[user]);
          await connection.query('DELETE FROM users WHERE name=?',[user]);
        }
        for(const role of roles)await connection.query('DELETE FROM roles WHERE name=?',[role]);
        await connection.commit();
      }catch(error){await connection.rollback();throw error}finally{connection.release()}
    }finally{await pool.end()}
  }
});
