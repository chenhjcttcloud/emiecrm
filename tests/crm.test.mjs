import{test}from'node:test';import assert from'node:assert/strict';import mysql from'mysql2/promise';
const base=process.env.CRM_TEST_BASE_URL;
if(!base)throw Error('必须设置CRM_TEST_BASE_URL，测试不能指向生产环境');
const pool=mysql.createPool({host:process.env.CRM_DB_HOST||'mysql',port:Number(process.env.CRM_DB_PORT||3306),user:process.env.CRM_DB_USER||'emiecrm',password:process.env.CRM_DB_PASSWORD||process.env.MYSQL_PASSWORD,database:process.env.CRM_DB_NAME,connectionLimit:3});
test('本地数据库、账户权限、审批、收款、核销和备份',async()=>{if(!process.env.CRM_DB_NAME?.endsWith('_test'))throw Error('测试只能使用以_test结尾的隔离数据库');const readiness=await(await fetch(base+'/api/status')).json();assert.equal(readiness.backend,'java');assert.equal(readiness.testMode,true,'API必须启用独立数据库测试模式');try{for(const table of ['jst_shipments','feishu_identities','receipts','audit','user_profiles','users','departments'])await pool.query('DELETE FROM '+table);await pool.query('UPDATE state SET payload=NULL WHERE id=1');await pool.query('DELETE FROM roles WHERE builtin=0');await pool.query("DELETE FROM payment_methods WHERE id NOT IN ('bank','alipay','wechat','cash')");await pool.query("UPDATE payment_methods SET name=CASE id WHEN 'bank' THEN '银行转账' WHEN 'alipay' THEN '支付宝' WHEN 'wechat' THEN '微信' ELSE '现金' END,status='启用'");let token='';async function req(path,body,ok=true){const r=await fetch(base+'/api/'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:body?JSON.stringify(body):undefined});const data=await r.json();assert.equal(r.ok,ok,JSON.stringify(data));return data}
await req('state',undefined,false);await req('setup',{name:'admin',password:'test-password'});token=(await req('login',{name:'admin',password:'test-password'})).token;assert.equal((await req('me')).role,'管理员');assert.equal((await req('auth/feishu/config')).enabled,false);await req('users',{name:'sales',password:'test-password',role:'销售'});let state={customers:[{id:'c1',name:'测试客户',balance:0}],products:[{id:'p1',name:'测试产品',price:100}],orders:[{id:'pre',customer:'测试客户',type:'预收款订单',total:200,paid:0,status:'审批中',items:[]},{id:'sale',customer:'测试客户',type:'销售订单',total:100,paid:0,status:'审批中',items:[{product:'p1',qty:1,price:100}]}]};const store=async(ok=true)=>{const result=await req('state',{data:state,revision:state.revision},ok);if(ok)state=result;return result};await store();
state.products.push({id:'unused-delete-test',name:'未使用商品',price:10});await store();
state.products=state.products.filter(p=>p.id!=='unused-delete-test');await store();assert.ok(!state.products.some(p=>p.id==='unused-delete-test'));
state.orders[1].items=[{product:'p1',qty:1,price:100}];await store();
state.products=state.products.filter(p=>p.id!=='p1');await store(false);state=await req('state');assert.ok(state.products.some(p=>p.id==='p1'));
state.customers.push({id:'unused-customer-delete-test',name:'未使用客户',balance:0});await store();
state.customers=state.customers.filter(c=>c.id!=='unused-customer-delete-test');await store();assert.ok(!state.customers.some(c=>c.id==='unused-customer-delete-test'));
state.customers=state.customers.filter(c=>c.id!=='c1');await store(false);state=await req('state');assert.ok(state.customers.some(c=>c.id==='c1'));
const admin=token;token=(await req('login',{name:'sales',password:'test-password'})).token;state.orders[0].status='已批准';await store(false);token=admin;await store();state.orders[0].paid=200;state.orders[0].method='银行转账';await store();assert.equal(state.customers[0].balance,200);state.orders[1].status='已批准';await store();state.orders[1].paid=40;state.orders[1].method='预收款核销';await store();assert.equal(state.customers[0].balance,160);state.orders[1].paid=101;await store(false);state=await req('state');assert.equal(state.orders[1].paid,40);assert.equal((await req('receipts')).length,2);state.orders.push({id:'sale2',customer:'测试客户',type:'销售订单',total:300,paid:0,status:'已批准',items:[{product:'p1',qty:3,price:100}]});await store();state.orders[2].paid=200;state.orders[2].method='预收款核销';await store(false);state=await req('state');assert.equal(state.customers[0].balance,160);token=(await req('login',{name:'sales',password:'test-password'})).token;state.orders[1].paid=41;await store(false);token=admin;state=await req('state');state.customers[0].name='新客户名';await store();assert.equal(state.orders[0].customer,'新客户名');assert.equal(state.customers[0].balance,160);assert.equal((await req('receipts'))[0].customer,'新客户名');const backup=await req('backup');assert.equal(backup.receipts.length,2);await req('restore',backup);state=await req('state');assert.equal(state.customers[0].balance,160);assert.ok((await req('audit')).length>0);const stale=structuredClone(state);state.orders[1].note='更新';await store();await req('state',{data:stale,revision:stale.revision},false);const revision=state.revision;const concurrent=await Promise.all([1,2].map(n=>fetch(base+'/api/state',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({data:{...state,marker:n},revision})})));assert.deepEqual(concurrent.map(r=>r.status).sort(),[200,409]);
await req('users',{name:'pending',password:'pending-password',role:'销售'});await pool.query("UPDATE users SET role='待授权' WHERE name='pending'");token=(await req('login',{name:'pending',password:'pending-password'})).token;assert.equal((await req('me')).pending,true);await req('state',undefined,false);await req('system/users',undefined,false);await req('logout',{});await req('me',undefined,false);token=admin;
const meta=await req('system/meta');assert.equal(meta.permissions.length,6);
let departments=await req('system/departments',{name:'研发部',manager:'测试负责人',status:'启用'});const parent=departments.find(d=>d.name==='研发部');departments=await req('system/departments',{name:'研发一组',parentId:parent.id,status:'启用'});const childDept=departments.find(d=>d.name==='研发一组');await req('system/departments',{...parent,parentId:childDept.id},false);
await req('system/roles',{action:'create',name:'观察员',description:'仅查看业务',permissions:['business.read']});await req('system/roles',{action:'update',name:'管理员',permissions:[]},false);
let members=await req('system/users',{action:'create',name:'viewer',displayName:'测试观察员',role:'观察员',departmentId:parent.id,status:'启用',password:'viewer-password'});assert.equal(members.find(u=>u.name==='viewer').department,'研发部');assert.ok(members.every(u=>!('password'in u)&&!('salt'in u)));await req('system/departments',{...parent,status:'停用'},false);
const adminToken=token;token=(await req('login',{name:'viewer',password:'viewer-password'})).token;const oldViewerToken=token;await req('system/users',undefined,false);await req('state');await req('state',{data:await req('state'),revision:(await req('state')).revision},false);
token=adminToken;const viewer=members.find(u=>u.name==='viewer');await req('system/users',{...viewer,action:'update',status:'停用'});await req('login',{name:'viewer',password:'viewer-password'},false);await req('system/users',{...viewer,action:'update',status:'启用',password:'changed-password'});await req('login',{name:'viewer',password:'viewer-password'},false);const resetAdminToken=token;token=oldViewerToken;await req('me',undefined,false);token=resetAdminToken;const viewerToken=(await req('login',{name:'viewer',password:'changed-password'})).token;assert.ok(viewerToken);await req('system/users',{action:'update',name:'admin',displayName:'管理员',role:'管理员',status:'停用'},false);
await req('system/roles',{action:'update',name:'观察员',description:'可查看及维护业务',permissions:['business.read','business.write']});token=viewerToken;const fresh=await req('state');await req('state',{data:fresh,revision:fresh.revision});token=adminToken;assert.ok((await req('system/audit')).some(r=>r.action==='创建用户 viewer'));

let methods=await req('system/payment-methods');assert.equal(methods.filter(m=>m.status==='启用').length,4);
methods=await req('system/payment-methods',{name:'测试POS',sortOrder:1,status:'启用'});let pos=methods.find(m=>m.name==='测试POS');assert.equal(methods[0].id,pos.id);
await req('system/payment-methods',{name:'测试POS',sortOrder:1,status:'启用'},false);
await req('system/payment-methods',{name:'非法排序',sortOrder:-1,status:'启用'},false);
await req('system/payment-methods',{name:'预收款核销',sortOrder:1,status:'启用'},false);
state=await req('state');state.orders[2].paid=10;state.orders[2].method='测试POS';await store();
await req('system/payment-methods',{...pos,name:'POS改名'});assert.ok((await req('receipts')).some(r=>r.method==='测试POS'));
state.orders[2].paid=11;await store(false);state=await req('state');
await req('system/payment-methods',{...pos,name:'POS改名',status:'停用'});state.orders[2].paid=11;state.orders[2].method='POS改名';await store(false);state=await req('state');
await req('system/payment-methods',{...pos,name:'POS改名',status:'启用'});state.orders[2].paid=11;state.orders[2].method='POS改名';await store();
token=viewerToken;await req('system/payment-methods');await req('system/payment-methods',{name:'无权限',sortOrder:1,status:'启用'},false);token=adminToken;
assert.ok((await req('system/audit')).some(r=>r.action.includes('保存收款方式')));

state=await req('state');const beforeCheck=structuredClone(state);const beforeReceipts=(await req('receipts')).length;
state.orders[1].paid+=10;state.orders[1].method='银行转账';state.orders[2].paid+=10;state.orders[2].method='银行转账';
const verify={customer:state.orders[1].customer,method:'银行转账',amount:19};
await req('state',{data:state,revision:state.revision,receiptVerification:verify},false);
assert.deepEqual(await req('state'),beforeCheck);assert.equal((await req('receipts')).length,beforeReceipts);
await req('state',{data:state,revision:state.revision,receiptVerification:{...verify,amount:21}},false);
await req('state',{data:state,revision:state.revision,receiptVerification:{...verify,amount:20.001}},false);
await req('state',{data:state,revision:state.revision,receiptVerification:{...verify,amount:20,customer:'其他客户'}},false);
await req('state',{data:state,revision:state.revision,receiptVerification:{...verify,amount:20,method:'现金'}},false);
state=await req('state',{data:state,revision:state.revision,receiptVerification:{...verify,amount:20}});
assert.equal((await req('receipts')).length,beforeReceipts+2);

// Server-side business invariants, independent of browser validation.
state=await req('state');const invariantBaseline=structuredClone(state);
state.customers.push({id:'duplicate-name',name:state.customers[0].name,balance:0});await store(false);state=await req('state');
state.orders.push({id:'approval-regression',customerId:'c1',customer:state.customers[0].name,type:'销售订单',total:100,paid:0,status:'已批准',items:[{product:'p1',qty:1,price:100}]});await store();
const regressionOrder=()=>state.orders.find(o=>o.id==='approval-regression');
token=(await req('login',{name:'sales',password:'test-password'})).token;
regressionOrder().total=200;regressionOrder().items[0].price=200;await store(false);state=await req('state');
regressionOrder().status='草稿';await store(false);state=await req('state');
regressionOrder().note='销售可补充备注';await store();token=adminToken;
regressionOrder().paid=0.004;regressionOrder().method='银行转账';await store(false);state=await req('state');
regressionOrder().total=200;await store(false);state=await req('state');
regressionOrder().total=100.001;await store(false);state=await req('state');
regressionOrder().paid=0.01;regressionOrder().method='银行转账';await store();
assert.equal((await req('receipts')).find(r=>r.order==='approval-regression').amount,0.01);

// Customer rename keeps identity and historical payloads remain untouched.
const [rawBefore]=await pool.query('SELECT id,payload FROM receipts ORDER BY seq');
const previousBalance=state.customers[0].balance;state.customers[0].name='按ID关联的新客户名';await store();
const [rawAfter]=await pool.query('SELECT id,payload FROM receipts ORDER BY seq');assert.deepEqual(rawAfter,rawBefore);
assert.ok((await req('receipts')).every(r=>r.customerId==='c1'&&r.customer==='按ID关联的新客户名'));
state.customers.push({id:'c2',name:invariantBaseline.customers[0].name,balance:0});await store();
assert.equal(state.customers.find(c=>c.id==='c2').balance,0);assert.equal(state.customers.find(c=>c.id==='c1').balance,previousBalance);

// Paginated results and summary must use the entire filtered collection.
const allReceipts=await req('receipts');const receiptPage=await req('receipts?page=1&pageSize=1');
assert.equal(receiptPage.rows.length,1);assert.equal(receiptPage.total,allReceipts.length);assert.equal(receiptPage.rows[0].id,allReceipts[0].id);
assert.ok(Math.abs(receiptPage.amount-allReceipts.reduce((sum,r)=>sum+Number(r.amount),0))<0.000001);
const secondPage=await req('receipts?page=2&pageSize=1');assert.equal(secondPage.rows[0].id,allReceipts[1].id);
const renamedFilter=await req('receipts?page=1&pageSize=100&q='+encodeURIComponent('按ID关联的新客户名'));assert.equal(renamedFilter.total,allReceipts.length);
const emptyFilter=await req('receipts?page=1&pageSize=20&q=does-not-exist');assert.equal(emptyFilter.total,0);assert.deepEqual(emptyFilter.rows,[]);
const methodFilter=await req('receipts?page=1&pageSize=100&method='+encodeURIComponent('银行转账'));assert.ok(methodFilter.rows.every(r=>r.method==='银行转账'));
await req('receipts?page=0',undefined,false);await req('receipts?page=1&pageSize=101',undefined,false);

// Images are authenticated, externalized from state, and included in portable backups.
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=','base64');
const upload=await fetch(base+'/api/assets',{method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+token},body:png});assert.equal(upload.status,200);const asset=await upload.json();
assert.match(asset.url,/^\/api\/assets\/[a-f0-9]{64}$/);
assert.equal((await fetch(base+asset.url)).status,401);
const image=await fetch(base+asset.url,{headers:{Authorization:'Bearer '+token}});assert.equal(image.status,200);assert.equal(image.headers.get('content-type'),'image/png');assert.deepEqual(Buffer.from(await image.arrayBuffer()),png);
const invalidUpload=await fetch(base+'/api/assets',{method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+token},body:'<svg></svg>'});assert.equal(invalidUpload.status,400);
state.products[0].image='data:image/png;base64,'+png.toString('base64');await store();assert.equal(state.products[0].image,asset.url);
const receiptOrderBeforeBackup=(await req('receipts')).map(r=>r.id);
const portableBackup=await req('backup');assert.equal(Object.keys(portableBackup.assets).length,1);
const beforeRestore=structuredClone(state);const brokenBackup=structuredClone(portableBackup);brokenBackup.assets[asset.url.split('/').at(-1)].data=Buffer.from('broken').toString('base64');
await req('restore',brokenBackup,false);assert.deepEqual(await req('state'),beforeRestore);
await pool.query('DELETE FROM assets');await req('restore',portableBackup);state=await req('state');
assert.equal(state.products[0].image,asset.url);assert.equal((await fetch(base+asset.url,{headers:{Authorization:'Bearer '+token}})).status,200);
assert.deepEqual((await req('receipts')).map(r=>r.id),receiptOrderBeforeBackup);

// Pre-existing duplicate names and orphan orders stay readable without guessing associations.
const legacy=structuredClone(state);legacy.customers.push({id:'legacy-duplicate',name:legacy.customers[0].name,balance:0});
legacy.orders.push({id:'legacy-orphan',customer:'历史已移除客户',type:'预收款订单',status:'草稿',total:10,paid:0,items:[]});
await pool.query('UPDATE state SET payload=? WHERE id=1',[JSON.stringify(legacy)]);state=await req('state');
assert.ok(!state.orders.find(o=>o.id==='legacy-orphan').customerId);await store();
const legacyPage=await req('receipts?page=1&pageSize=100');assert.equal(legacyPage.total,allReceipts.length);assert.equal(legacyPage.rows.length,allReceipts.length);
state.orders.find(o=>o.id==='legacy-orphan').paid=1;await store(false);state=await req('state');
state.orders.find(o=>o.id==='legacy-orphan').customerId='c2';await store();assert.equal(state.orders.find(o=>o.id==='legacy-orphan').customerId,'c2');
const pendingToken=(await req('login',{name:'pending',password:'pending-password'})).token;
const forbiddenUpload=await fetch(base+'/api/assets',{method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+pendingToken},body:png});assert.equal(forbiddenUpload.status,403);
const oversizedImage=await fetch(base+'/api/assets',{method:'POST',headers:{'Content-Type':'application/octet-stream',Authorization:'Bearer '+token},body:Buffer.alloc(5*1024*1024+1)});assert.equal(oversizedImage.status,400);

}finally{await pool.end()}});
