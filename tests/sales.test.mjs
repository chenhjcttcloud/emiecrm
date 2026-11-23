import {test} from 'node:test';
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';

const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置CRM_TEST_BASE_URL，测试不能指向生产环境');

test('销售模块：商品明细、收款明细、人员目标与权限',async()=>{
  assert.ok(process.env.CRM_DB_NAME?.endsWith('_test'),'测试只能使用_test隔离数据库');
  const status=await(await fetch(base+'/api/status')).json();
  assert.equal(status.backend,'java'); assert.equal(status.testMode,true,'API必须启用独立数据库测试模式');
  const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),
    user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,
    database:process.env.CRM_DB_NAME,connectionLimit:3});
  let token='',originalState;
  const snapshots=new Map(),tables=['receipts','jst_shipments','jst_document_sales','dashboard_targets','sales_targets','sales_target_scope'];
  const suffix=Date.now().toString(36),users=['sales-read-'+suffix,'sales-target-'+suffix],roles=['销售只读测试'+suffix,'销售目标测试'+suffix];
  const departmentIds=['sales-dept-a-'+suffix,'sales-dept-b-'+suffix],departmentNames=['销售测试一部'+suffix,'销售测试二部'+suffix];
  const departmentUsers=['sales-person-one-'+suffix,'sales-person-two-'+suffix];
  const scopeUser='sales-scope-'+suffix,scopeRole='范围管理测试'+suffix;
  async function request(path,body,expected=200,auth=token){
    const response=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',
      headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth}:{})},
      body:body===undefined?undefined:JSON.stringify(body)});
    const data=await response.json(); assert.equal(response.status,expected,JSON.stringify(data)); return data;
  }
  async function restoreTable(connection,table,rows){
    await connection.query('DELETE FROM `'+table+'`');
    for(const row of rows){
      const keys=Object.keys(row),values=keys.map(key=>{
        const value=row[key]; return value&&typeof value==='object'&&!Buffer.isBuffer(value)&&!(value instanceof Date)?JSON.stringify(value):value;
      });
      await connection.query('INSERT INTO `'+table+'` ('+keys.map(key=>'`'+key+'`').join(',')+') VALUES ('+keys.map(()=>'?').join(',')+')',values);
    }
  }
  try{
    if(!status.setup)await request('setup',{name:'admin',password:'test-password'});
    token=(await request('login',{name:'admin',password:'test-password'})).token;
    const [stateRows]=await pool.query('SELECT payload FROM state WHERE id=1'); originalState=stateRows[0].payload;
    for(const table of tables){const [rows]=await pool.query('SELECT * FROM `'+table+'`');snapshots.set(table,rows)}
    for(const table of tables)await pool.query('DELETE FROM `'+table+'`');
    const environment=process.env.CRM_JST_ENV||'sandbox';
    const order=(id,total,paid,items,extra={})=>({id,type:'销售订单',status:'已批准',customerId:'sales-c1',
      customer:'旧客户名称',owner:'销售王',total,paid,created:'2026-11-02 09:00:00',items,...extra});
    const line=(qty,price,extra={})=>({product:'sales-p1',qty,price,...extra});
    const fixture={revision:1100,customers:[{id:'sales-c1',name:'改名后的客户',owner:'销售王',balance:0},
      {id:'sales-c2',name:'旧客户名称',owner:'其他人',balance:0}],products:[{id:'sales-p1',name:'销售测试商品',code:'SALES-SKU-A',
      model:'销售型号',cost:'999.99',source:'jushuitan',enabled:true}],orders:[
      order('sales-paid','1.00','1.00',[line('0.333333','1'),line('0.333333','1'),line('0.333333','1')]),
      order('sales-partial','20','5',[line('2','10')]),
      order('sales-changed','100','100',[line('1','100')],{owner:'销售李'}),
      order('sales-draft','10','0',[line('1','10')],{status:'草稿'}),
      order('sales-pre','100','100',[],{type:'预收款订单'}),
      order('sales-history','50','0',[],{source:'jushuitan-settlement',settlement:{cycle:'month',periodStart:'2026-10-01',periodEnd:'2026-10-31'}})]};
    await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(fixture)]);
    await pool.query('INSERT INTO jst_shipments(order_id,payload) VALUES(?,?)',['sales-changed',JSON.stringify({environment,
      effectiveOrder:{total:'20.00',paid:'20.00',items:[line('4','5',{unitCost:'3.00'})]}})]);
    const receipt=(id,orderId,amount,method='银行转账',extra={})=>({id,order:orderId,customerId:'sales-c1',customer:'旧客户名称',
      amount,method,type:'销售订单',time:'2026-11-05T01:00:00Z',operator:'admin',...extra});
    const legacy=receipt('sales-r2','sales-pre','100','银行转账',{type:'预收款订单',receivedOn:'2026-11-30',time:'2026-12-02T01:00:00Z'});
    delete legacy.customerId;
    const orphan=receipt('sales-r5','missing-order','2','银行转账',{customer:'历史孤儿客户'}); delete orphan.customerId;
    const receipts=[receipt('sales-r1','sales-paid','1'),legacy,
      receipt('sales-r3','sales-changed','-80','银行转账',{type:'订单变更',amendmentId:'sales-diff'}),
      receipt('sales-r4','sales-partial','5','预收款核销'),orphan,
      receipt('sales-r6','sales-paid','5','企业账户',{receivedOn:'2026-11-12',taxRate:'0.13',tax:'0.65'})];
    for(const row of receipts)await pool.query('INSERT INTO receipts(id,payload) VALUES(?,?)',[row.id,JSON.stringify(row)]);
    await pool.query('INSERT INTO dashboard_targets(month_key,amount,revision,updated_at,updated_by) VALUES(?,?,1,?,?)',
      ['2026-11','777.00','2026-11-20T01:00:00Z','admin']);
    const [receiptBaseline]=await pool.query('SELECT id,payload FROM receipts ORDER BY id');
    const beforePersonnel=(await request('dashboard/sales?month=2026-11')).target;
    assert.equal(beforePersonnel.amount,'777.00'); assert.equal(beforePersonnel.source,'manual');

    const items=await request('sales/items?month=2026-11&pageSize=2');
    assert.equal(items.total,6); assert.equal(items.rows.length,2);
    assert.equal(items.summary.amount,'51.00'); assert.equal(items.summary.quantity,'7.999999');
    assert.equal(items.summary.cost,null); assert.equal(items.summary.profit,null);
    const allItems=await request('sales/items?month=2026-11&pageSize=100');
    assert.equal(allItems.rows.length,6);
    const paidLines=allItems.rows.filter(row=>row.orderId==='sales-paid');
    assert.deepEqual(paidLines.map(row=>row.amount),['0.33','0.33','0.34']);
    assert.ok(paidLines.every(row=>row.cost===null&&row.profit===null));
    const changed=allItems.rows.find(row=>row.orderId==='sales-changed');
    assert.equal(changed.qty,'4'); assert.equal(changed.amount,'20.00'); assert.equal(changed.cost,'12.00'); assert.equal(changed.profit,'8.00');
    assert.ok(allItems.rows.every(row=>row.customer==='改名后的客户'));
    const search=await request('sales/items?'+new URLSearchParams({month:'2026-11',q:'sales-sku-a',page:'2',pageSize:'1'}));
    assert.equal(search.total,6); assert.equal(search.summary.amount,'51.00');
    assert.equal((await request('sales/items?month=2026-11&customerId=sales-c2')).total,0);
    await request('sales/items?page=0',undefined,400);
    await request('sales/items?pageSize=101',undefined,400);
    await request('sales/items?month=2026-13',undefined,400);

    const ledger=await request('sales/receipts?month=2026-11&pageSize=1');
    assert.equal(ledger.total,6); assert.equal(ledger.rows.length,1); assert.equal(ledger.summary.amount,'33.00'); assert.equal(ledger.summary.tax,null);
    const fullLedger=await request('sales/receipts?month=2026-11&pageSize=100');
    const exportedLedger=await request('sales/receipts/export?month=2026-11');
    assert.equal(exportedLedger.total,ledger.total); assert.equal(exportedLedger.rows.length,ledger.total);
    assert.ok(exportedLedger.rows.length>ledger.rows.length,'全量导出不能只返回当前分页');
    assert.deepEqual(exportedLedger.rows,fullLedger.rows); assert.deepEqual(exportedLedger.summary,ledger.summary);
    assert.ok(fullLedger.rows.some(row=>row.amount==='-80.00'));
    assert.ok(fullLedger.rows.some(row=>row.method==='预收款核销'&&row.amount==='5.00'));
    assert.equal(fullLedger.rows.find(row=>row.id==='sales-r2').customer,'改名后的客户');
    assert.ok(fullLedger.rows.some(row=>row.id==='sales-r5'&&row.customer==='历史孤儿客户'));
    const customerLedger=await request('sales/receipts?month=2026-11&customerId=sales-c1');
    assert.equal(customerLedger.total,5); assert.equal(customerLedger.summary.amount,'31.00');
    assert.equal((await request('sales/receipts?month=2026-11&customerId=sales-c2')).total,0);
    const taxed=await request('sales/receipts?'+new URLSearchParams({month:'2026-11',method:'企业账户'}));
    assert.equal(taxed.summary.tax,'0.65'); assert.equal(taxed.rows[0].taxRate,'0.13');
    assert.equal(taxed.rows[0].tax,'0.65');
    const exportFilters=new URLSearchParams({month:'2026-11',owner:'销售王',customerId:'sales-c1',method:'企业账户',q:'sales-paid'});
    const filteredLedger=await request('sales/receipts?'+exportFilters);
    const filteredExport=await request('sales/receipts/export?'+exportFilters);
    assert.equal(filteredExport.total,1); assert.equal(filteredExport.rows[0].id,'sales-r6');
    assert.deepEqual(filteredExport.rows,filteredLedger.rows); assert.deepEqual(filteredExport.summary,filteredLedger.summary);
    await request('sales/receipts/export?month=2026-13',undefined,400);
    await request('sales/receipts/export',undefined,401,'');
    const noDateShift=await request('sales/receipts?month=2026-12'); assert.equal(noDateShift.total,0);

    await request('system/roles',{action:'create',name:roles[0],permissions:['business.read']});
    await request('system/roles',{action:'create',name:roles[1],permissions:['dashboard.targets.manage']});
    for(let index=0;index<users.length;index++)await request('system/users',{action:'create',name:users[index],displayName:roles[index],
      role:roles[index],status:'启用',password:'sales-test-password'});
    const viewer=(await request('login',{name:users[0],password:'sales-test-password'})).token;
    const manager=(await request('login',{name:users[1],password:'sales-test-password'})).token;
    await request('sales/items?month=2026-11',undefined,200,viewer);
    await request('sales/receipts?month=2026-11',undefined,200,viewer);
    await request('sales/receipts/export?month=2026-11',undefined,200,viewer);
    await request('dashboard/sales?month=2026-11',undefined,403,viewer);
    const targets=await request('sales/targets?month=2026-11',undefined,200,viewer);
    assert.ok(targets.rows.every(row=>row.amount===null));
    const ownerNames=targets.rows.map(row=>row.owner); assert.deepEqual(ownerNames,[...ownerNames].sort());
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'100',revision:0},403,viewer);
    await request('sales/targets?month=2026-11',undefined,403,manager);
    await request('sales/items?month=2026-11',undefined,403,manager);
    await request('sales/receipts?month=2026-11',undefined,403,manager);
    await request('sales/receipts/export?month=2026-11',undefined,403,manager);
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'100',revision:0},200,manager);
    await request('sales/targets',{month:'2026-11',owner:'销售李',amount:'10',revision:0},200,manager);
    const progress=await request('sales/targets?month=2026-11',undefined,200,viewer);
    const wang=progress.rows.find(row=>row.owner==='销售王'),li=progress.rows.find(row=>row.owner==='销售李');
    assert.equal(wang.orderAmount,'21.00'); assert.equal(wang.settledAmount,'6.00'); assert.equal(wang.remaining,'79.00'); assert.equal(Number(wang.progress),21);
    assert.equal(li.orderAmount,'20.00'); assert.equal(li.settledAmount,'20.00'); assert.equal(li.remaining,'0.00'); assert.equal(Number(li.progress),200);
    const companyWithPersonnel=(await request('dashboard/sales?month=2026-11')).target;
    assert.equal(companyWithPersonnel.amount,'110.00'); assert.equal(companyWithPersonnel.source,'personnel');
    assert.equal(companyWithPersonnel.personCount,2); assert.equal(companyWithPersonnel.manualAmount,'777.00');

    // Department attribution follows current CRM profiles; owner text is never guessed when ambiguous.
    const financialFields=['owner','amount','revision','orderAmount','settledAmount','remaining','progress'];
    const finances=row=>Object.fromEntries(financialFields.map(field=>[field,row[field]]));
    const financialBaseline=[wang,li].map(finances);
    const viewerPermissions=(await request('me',undefined,200,viewer)).permissions.sort();
    assert.deepEqual(viewerPermissions,['business.read']);
    const unmatched=progress.rows.find(row=>row.owner==='销售李');
    assert.equal(unmatched.departmentStatus,'unmatched'); assert.equal(unmatched.departmentId,null); assert.equal(unmatched.department,null);
    const unassigned=progress.rows.find(row=>row.owner===roles[0]);
    assert.equal(unassigned.departmentStatus,'unassigned'); assert.equal(unassigned.departmentId,null); assert.equal(unassigned.department,null);
    for(let index=0;index<departmentIds.length;index++)await pool.query('INSERT INTO departments(id,name,manager,status) VALUES(?,?,?,?)',
      [departmentIds[index],departmentNames[index],'','启用']);
    async function readDepartmentTarget(){
      const result=await request('sales/targets?month=2026-11',undefined,200,viewer);
      assert.deepEqual(['销售王','销售李'].map(owner=>finances(result.rows.find(row=>row.owner===owner))),financialBaseline,
        '部门归属变更不得改变个人目标、业绩、回款和完成率');
      for(let index=0;index<departmentIds.length;index++)assert.ok(result.options.departments.some(department=>
        department.id===departmentIds[index]&&department.name===departmentNames[index]));
      const company=(await request('dashboard/sales?month=2026-11')).target;
      for(const field of ['amount','source','personCount','manualAmount'])assert.equal(company[field],companyWithPersonnel[field],
        '部门归属不得改变公司目标汇总');
      return result.rows.find(row=>row.owner==='销售王');
    }
    await request('system/users',{action:'create',name:departmentUsers[0],displayName:'销售王',role:roles[0],
      departmentId:departmentIds[0],status:'启用',password:'sales-test-password'});
    const assigned=await readDepartmentTarget();
    assert.equal(assigned.departmentStatus,'assigned'); assert.equal(assigned.departmentId,departmentIds[0]); assert.equal(assigned.department,departmentNames[0]);
    await request('system/users',undefined,403,viewer);
    await request('system/users',{action:'create',name:departmentUsers[1],displayName:'销售王',role:roles[0],
      departmentId:departmentIds[1],status:'启用',password:'sales-test-password'});
    const ambiguous=await readDepartmentTarget();
    assert.equal(ambiguous.departmentStatus,'ambiguous'); assert.equal(ambiguous.departmentId,null); assert.equal(ambiguous.department,null);
    await request('system/users',{action:'update',name:departmentUsers[1],displayName:'部门二成员'+suffix,role:roles[0],
      departmentId:departmentIds[1],status:'启用'});
    const resolved=await readDepartmentTarget();
    assert.equal(resolved.departmentStatus,'assigned'); assert.equal(resolved.departmentId,departmentIds[0]); assert.equal(resolved.department,departmentNames[0]);
    await request('system/users',{action:'update',name:departmentUsers[0],displayName:'销售王',role:roles[0],
      departmentId:departmentIds[1],status:'启用'});
    const moved=await readDepartmentTarget();
    assert.equal(moved.departmentStatus,'assigned'); assert.equal(moved.departmentId,departmentIds[1]); assert.equal(moved.department,departmentNames[1]);
    assert.deepEqual((await request('me',undefined,200,viewer)).permissions.sort(),viewerPermissions);

    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'0',revision:1},200,manager);
    const zero=(await request('sales/targets?month=2026-11')).rows.find(row=>row.owner==='销售王');
    assert.equal(zero.amount,'0.00'); assert.equal(zero.progress,null);
    const effectiveCompany=(await request('dashboard/sales?month=2026-11')).target;
    assert.equal(effectiveCompany.amount,'10.00','公司有效目标应随人员目标修改立即汇总');
    assert.equal(effectiveCompany.source,'personnel'); assert.equal(effectiveCompany.manualAmount,'777.00');
    const [manualCompany]=await pool.query('SELECT amount,revision FROM dashboard_targets WHERE month_key=?',['2026-11']);
    assert.equal(manualCompany[0].amount,'777.00','自动汇总不得改写历史公司手工目标');
    assert.equal(manualCompany[0].revision,1);
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'100',revision:1},409,manager);
    await request('sales/targets',{month:'2026-11',owner:'不存在的用户',amount:'100',revision:0},400,manager);
    for(const amount of ['-1','0.001','1000000000000','NaN'])await request('sales/targets',{month:'2026-11',owner:'销售王',amount,revision:2},400,manager);
    const concurrent=await Promise.all(['0.01','0.02'].map(amount=>fetch(base+'/api/sales/targets',{method:'POST',
      headers:{'Content-Type':'application/json',Authorization:'Bearer '+manager},body:JSON.stringify({month:'2026-12',owner:'销售王',amount,revision:0})})));
    assert.deepEqual(concurrent.map(response=>response.status).sort(),[200,409]);
    const event=(await request('system/audit')).find(event=>event.user===users[1]&&event.details?.type==='SALES_TARGET_CHANGED'&&event.details.month==='2026-12');
    assert.ok(event); assert.equal(event.details.owner,'销售王'); assert.equal(event.details.before.revision,0); assert.equal(event.details.after.revision,1);
    const december=(await request('dashboard/sales?month=2026-12')).target;
    assert.equal(december.source,'personnel'); assert.equal(december.personCount,1);
    assert.ok(['0.01','0.02'].includes(december.amount));

    // Global department scope controls target participants and denominators, while preserving source records.
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'100',revision:2},200,manager);
    const actualBaseline=await request('dashboard/sales?month=2026-11');
    assert.equal(actualBaseline.target.amount,'110.00');
    const [personalTargetsBaseline]=await pool.query('SELECT * FROM sales_targets ORDER BY month_key,owner');
    const [companyTargetsBaseline]=await pool.query('SELECT * FROM dashboard_targets ORDER BY month_key');
    await request('system/roles',{action:'create',name:scopeRole,permissions:['system.manage']});
    await request('system/users',{action:'create',name:scopeUser,displayName:scopeRole,role:scopeRole,status:'启用',password:'sales-test-password'});
    const scopeAdmin=(await request('login',{name:scopeUser,password:'sales-test-password'})).token;
    for(const auth of [viewer,manager]){
      await request('sales/target-scope',undefined,403,auth);
      await request('sales/target-scope',{mode:'all',departmentIds:[],revision:0},403,auth);
    }
    await request('sales/targets?month=2026-11',undefined,403,scopeAdmin);
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'1',revision:3},403,scopeAdmin);
    let scope=await request('sales/target-scope',undefined,200,scopeAdmin);
    assert.equal(scope.mode,'all'); assert.deepEqual(scope.departmentIds,[]); assert.equal(scope.revision,0);
    for(let index=0;index<departmentIds.length;index++)assert.ok(scope.departments.some(department=>department.id===departmentIds[index]));
    for(const invalid of [
      {mode:'invalid',departmentIds:[],revision:0},
      {mode:'all',departmentIds:[departmentIds[0]],revision:0},
      {mode:'selected',departmentIds:'invalid',revision:0},
      {mode:'selected',departmentIds:['missing-department'],revision:0},
      {mode:'selected',departmentIds:[departmentIds[0],departmentIds[0]],revision:0},
      {mode:'selected',departmentIds:[],revision:0.5},
    ])await request('sales/target-scope',invalid,400,scopeAdmin);
    const scopeWrites=await Promise.all(departmentIds.map(departmentId=>fetch(base+'/api/sales/target-scope',{method:'POST',
      headers:{'Content-Type':'application/json',Authorization:'Bearer '+scopeAdmin},
      body:JSON.stringify({mode:'selected',departmentIds:[departmentId],revision:0})})));
    assert.deepEqual(scopeWrites.map(response=>response.status).sort(),[200,409]);
    scope=await request('sales/target-scope',undefined,200,scopeAdmin);
    assert.equal(scope.revision,1);
    await request('sales/target-scope',{mode:'all',departmentIds:[],revision:0},409,scopeAdmin);
    async function configureScope(mode,departmentIds){
      scope=await request('sales/target-scope',{mode,departmentIds,revision:scope.revision},200,scopeAdmin);
      const shared=await request('sales/target-scope');
      assert.equal(shared.mode,mode); assert.deepEqual(shared.departmentIds,departmentIds); assert.equal(shared.revision,scope.revision);
    }
    async function assertScopedTargets(amount,personCount,expectedOwners){
      const result=await request('sales/targets?month=2026-11',undefined,200,viewer);
      assert.equal(result.scope.mode,scope.mode); assert.deepEqual(result.scope.departmentIds,scope.departmentIds); assert.equal(result.scope.revision,scope.revision);
      assert.deepEqual(result.rows.map(row=>row.owner).sort(),[...expectedOwners].sort());
      assert.deepEqual([...result.options.owners].sort(),[...expectedOwners].sort());
      const dashboard=await request('dashboard/sales?month=2026-11');
      assert.equal(dashboard.target.amount,amount); assert.equal(dashboard.target.source,'personnel');
      assert.equal(dashboard.target.personCount,personCount); assert.equal(dashboard.target.manualAmount,'777.00');
      assert.equal(dashboard.target.scope.mode,scope.mode); assert.deepEqual(dashboard.target.scope.departmentIds,scope.departmentIds);
      assert.deepEqual(dashboard.summary,actualBaseline.summary,'目标范围不得改变实际销售与回款业绩');
      assert.deepEqual(dashboard.orders,actualBaseline.orders); assert.deepEqual(dashboard.daily,actualBaseline.daily);
      const annual=(await request('dashboard/targets?year=2026')).targets.find(target=>target.month==='2026-11');
      assert.equal(annual.amount,amount); assert.equal(annual.source,'personnel'); assert.equal(annual.personCount,personCount);
      return result;
    }
    const secondPerson='部门二成员'+suffix;
    await configureScope('selected',[departmentIds[1]]);
    const visible=await assertScopedTargets('100.00',1,['销售王',secondPerson]);
    assert.deepEqual(visible.options.departments,[{id:departmentIds[1],name:departmentNames[1]}]);
    const [storedScope]=await pool.query('SELECT mode,department_ids,revision FROM sales_target_scope WHERE id=1');
    assert.equal(storedScope[0].mode,'selected');
    assert.deepEqual(typeof storedScope[0].department_ids==='string'?JSON.parse(storedScope[0].department_ids):storedScope[0].department_ids,[departmentIds[1]]);
    assert.equal(storedScope[0].revision,scope.revision);
    const colleague=(await request('login',{name:departmentUsers[0],password:'sales-test-password'})).token;
    const colleagueTargets=await request('sales/targets?month=2026-11',undefined,200,colleague);
    assert.deepEqual(colleagueTargets.rows,visible.rows,'范围配置应被所有业务查看账号共享');
    assert.deepEqual(colleagueTargets.scope,visible.scope);
    await request('sales/targets',{month:'2026-11',owner:'销售李',amount:'99',revision:1},409,manager);

    await request('system/users',{action:'update',name:departmentUsers[1],displayName:'销售王',role:roles[0],departmentId:departmentIds[1],status:'启用'});
    await assertScopedTargets('0.00',0,[]);
    await request('sales/targets',{month:'2026-11',owner:'销售王',amount:'99',revision:3},409,manager);
    await request('system/users',{action:'update',name:departmentUsers[1],displayName:secondPerson,role:roles[0],departmentId:departmentIds[1],status:'启用'});
    await assertScopedTargets('100.00',1,['销售王',secondPerson]);
    await request('system/users',{action:'update',name:departmentUsers[0],displayName:'销售王',role:roles[0],departmentId:departmentIds[0],status:'启用'});
    await assertScopedTargets('0.00',0,[secondPerson]);
    await request('system/users',{action:'update',name:departmentUsers[0],displayName:'销售王',role:roles[0],departmentId:departmentIds[1],status:'启用'});
    await assertScopedTargets('100.00',1,['销售王',secondPerson]);

    await configureScope('selected',[departmentIds[0]]);
    await pool.query('DELETE FROM departments WHERE id=?',[departmentIds[0]]);
    const missingDepartment=await request('sales/target-scope',undefined,200,scopeAdmin);
    assert.deepEqual(missingDepartment.missingDepartmentIds,[departmentIds[0]]);
    const missingResult=await assertScopedTargets('0.00',0,[]); assert.deepEqual(missingResult.options.departments,[]);
    await request('sales/target-scope',{mode:'selected',departmentIds:[departmentIds[0]],revision:scope.revision},400,scopeAdmin);
    await pool.query('INSERT INTO departments(id,name,manager,status) VALUES(?,?,?,?)',[departmentIds[0],departmentNames[0],'','启用']);
    await configureScope('selected',[]);
    const emptyScope=await assertScopedTargets('0.00',0,[]); assert.deepEqual(emptyScope.options.departments,[]);
    await request('dashboard/targets',{month:'2026-11',amount:'999',revision:1},409,manager);
    const itemsAfterScope=await request('sales/items?month=2026-11&pageSize=100',undefined,200,viewer);
    assert.equal(itemsAfterScope.total,allItems.total); assert.deepEqual(itemsAfterScope.summary,allItems.summary);
    const receiptsAfterScope=await request('sales/receipts?month=2026-11&pageSize=100',undefined,200,viewer);
    assert.equal(receiptsAfterScope.total,fullLedger.total); assert.deepEqual(receiptsAfterScope.summary,fullLedger.summary);
    await configureScope('all',[]);
    const restoredTargets=await request('sales/targets?month=2026-11',undefined,200,viewer);
    assert.ok(restoredTargets.rows.some(row=>row.owner==='销售李'&&row.amount==='10.00'));
    assert.ok(restoredTargets.rows.some(row=>row.owner==='销售王'&&row.amount==='100.00'));
    const restoredCompany=(await request('dashboard/sales?month=2026-11')).target;
    assert.equal(restoredCompany.amount,'110.00'); assert.equal(restoredCompany.personCount,2);
    const [personalTargetsAfter]=await pool.query('SELECT * FROM sales_targets ORDER BY month_key,owner');
    const [companyTargetsAfter]=await pool.query('SELECT * FROM dashboard_targets ORDER BY month_key');
    assert.deepEqual(personalTargetsAfter,personalTargetsBaseline,'隐藏及恢复部门不得删改原始人员目标');
    assert.deepEqual(companyTargetsAfter,companyTargetsBaseline,'范围配置不得写回原公司手工目标');
    const scopeAudit=(await request('system/audit',undefined,200,scopeAdmin)).find(event=>event.user===scopeUser&&event.details?.type==='SALES_TARGET_SCOPE_CHANGED');
    assert.ok(scopeAudit,'范围配置变更必须保留审计日志');
    assert.ok(scopeAudit.details.after.revision>scopeAudit.details.before.revision);
    assert.deepEqual((await request('me',undefined,200,viewer)).permissions.sort(),viewerPermissions);
    const [receiptAfter]=await pool.query('SELECT id,payload FROM receipts ORDER BY id');
    assert.deepEqual(receiptAfter,receiptBaseline,'人员目标维护不得改写收款流水');
    const [stored]=await pool.query('SELECT payload FROM state WHERE id=1');
    assert.deepEqual(JSON.parse(stored[0].payload),fixture,'销售读取和目标维护不得改写原始业务数据');
  }finally{
    try{
      const connection=await pool.getConnection();
      try{
        await connection.beginTransaction();
        for(const table of tables)if(snapshots.has(table))await restoreTable(connection,table,snapshots.get(table));
        if(originalState!==undefined)await connection.query('UPDATE state SET payload=? WHERE id=1',[originalState]);
        for(const user of [...users,...departmentUsers,scopeUser]){await connection.query('DELETE FROM feishu_identities WHERE user_name=?',[user]);
          await connection.query('DELETE FROM user_profiles WHERE name=?',[user]);await connection.query('DELETE FROM users WHERE name=?',[user])}
        for(const role of [...roles,scopeRole])await connection.query('DELETE FROM roles WHERE name=?',[role]);
        for(const departmentId of departmentIds)await connection.query('DELETE FROM departments WHERE id=?',[departmentId]);
        await connection.commit();
      }catch(error){await connection.rollback();throw error}finally{connection.release()}
    }finally{await pool.end()}
  }
});
