import {test} from 'node:test';
import assert from 'node:assert/strict';
import {approvalDetails,approvalDraftValues,creditPushView,isCreditSalesOrder,orderExecutionView,plannedCreditShop,plannedOrderWarehouse,recipientAddressText,shippingWarehouseId,suggestedCreditShop,warehouseIdentifier} from '../src/feishuApprovalModel.js';

const order={id:'warehouse-order',type:'销售订单',approvalDraft:{warehouseId:'15136229',warehouseName:'国内主仓',recipientName:'旧收货人'}};

test('warehouse selection survives absent form controls but explicit clearing removes the saved name',()=>{
 assert.deepEqual(approvalDraftValues({},order,new Map()),{warehouseId:'15136229',warehouseName:'国内主仓'});
 assert.deepEqual(approvalDetails({},order,new Map()),{warehouseId:'15136229',warehouseName:'国内主仓'});
 assert.deepEqual(approvalDraftValues({},order,new Map([['warehouseId',''],['warehouseName','旧名字']])),{warehouseId:'',warehouseName:''});
 assert.deepEqual(approvalDetails({},order,new Map([['warehouseId','']])),{});
 assert.deepEqual(approvalDraftValues({},order,new Map([['warehouseId','42']])),{warehouseId:'42',warehouseName:''});
 assert.deepEqual(approvalDraftValues({}, {...order,approvalDraft:{}},new Map()),{});
});

test('selected warehouse is optional, validates exact positive IDs and does not change recipient or discount fields',()=>{
 const fields=new Map([['warehouseId',' 15136230 '],['warehouseName',' 另一个仓库 '],['recipientName',' 张三 '],['recipientPhone','13800000000'],['recipientAddress','深圳市地址'],['approvalDiscount-0','0']]);
 const sales={...order,items:[{product:'p',qty:1,price:10}]};
 assert.deepEqual(approvalDetails({salesRecipientRequired:true,salesDiscountRequired:true},sales,fields),{
  warehouseId:'15136230',warehouseName:'另一个仓库',recipientName:'张三',recipientPhone:'13800000000',recipientAddress:'深圳市地址',discounts:['0'],
 });
 assert.deepEqual(approvalDetails({}, {...order,approvalDraft:{}},new Map()),{});
 assert.throws(()=>approvalDetails({},order,new Map([['warehouseId','0']])),/有效的发货仓库/);
 assert.throws(()=>approvalDetails({},order,new Map([['warehouseId','1'],['warehouseName','仓'.repeat(192)]])),/最多191字/);
});

test('warehouse identifiers preserve long string precision and reject invalid numeric forms',()=>{
 for(const value of ['1','15136229','9007199254740993','9223372036854775807'])assert.equal(warehouseIdentifier(value),value);
 assert.equal(warehouseIdentifier(15136229),'15136229');
 for(const value of [0,-1,1.5,Number.MAX_SAFE_INTEGER+1,{},null,undefined,'0','01','-2','1.2','1e3','9223372036854775808','1'.repeat(20)])assert.equal(warehouseIdentifier(value),'');
});

test('prepayments and settlement supplements never reuse an ordinary order warehouse',()=>{
 for(const other of [{...order,type:'预收款订单'},{...order,source:'jushuitan-settlement'}]){
  assert.deepEqual(approvalDraftValues({},other,new Map([['warehouseId','15136230'],['warehouseName','无关仓库']])),{});
  assert.deepEqual(approvalDetails({},other,new Map([['warehouseId','15136230']])),{});
  assert.equal(plannedOrderWarehouse(other),null);
  assert.equal(shippingWarehouseId(other), '');
 }
 assert.deepEqual(plannedOrderWarehouse({...order,type:'海外提货单'}),{id:'15136229',name:'国内主仓'});
});

test('manual shipment prefill respects its original request and never replaces it with the plan',()=>{
 assert.equal(shippingWarehouseId(order),'15136229');
 assert.equal(shippingWarehouseId(order,{request:{wms_co_id:42}}),42);
 assert.equal(shippingWarehouseId(order,{request:{wms_co_id:'42'}}),'42');
 for(const request of [{wms_co_id:0},{wms_co_id:null},{}])assert.equal(shippingWarehouseId(order,{request}),request.wms_co_id??'');
 assert.equal(plannedOrderWarehouse({...order,approvalDraft:{warehouseId:'0',warehouseName:'错误仓库'}}),null);
});

test('credit route saves chosen shop and warehouse in drafts and requires both for submission',()=>{
 const fields=new Map([['creditFulfillment','true'],['shopId','18797425'],['shopName','签单人店铺'],['warehouseId','15136229'],['warehouseName','国内主仓'],['creditShippingReady','true'],
  ['recipientName','测试收货人'],['recipientPhone','13800000000'],['recipientProvince','广东省'],['recipientCity','深圳市'],['recipientDistrict','龙岗区'],['recipientAddress','万科星火online10栋']]);
 const expected={creditFulfillment:true,shopId:'18797425',shopName:'签单人店铺',warehouseId:'15136229',warehouseName:'国内主仓',
  recipientName:'测试收货人',recipientPhone:'13800000000',recipientProvince:'广东省',recipientCity:'深圳市',recipientDistrict:'龙岗区',recipientAddress:'万科星火online10栋'};
 assert.deepEqual(approvalDraftValues({},order,fields),expected);
 assert.deepEqual(approvalDetails({},order,fields),expected);
 const saved={...order,approvalDraft:expected};
 assert.deepEqual(approvalDraftValues({},saved,new Map()),expected);
 assert.deepEqual(plannedCreditShop(saved),{id:'18797425',name:'签单人店铺'});
 assert.deepEqual(approvalDraftValues({},saved,new Map([['shopId',''],['shopName','旧店铺']])),{...expected,shopId:'',shopName:''});
 assert.throws(()=>approvalDetails({},order,new Map([['creditFulfillment','true'],['warehouseId','15136229']])),/请选择有效的聚水潭店铺/);
 assert.throws(()=>approvalDetails({},order,new Map([['creditFulfillment','true'],['shopId','18797425'],['warehouseId','']])),/请选择发货仓库/);
 for(const id of ['0','1.5','-1','01','9223372036854775808'])assert.throws(()=>approvalDetails({},order,new Map([...fields,['shopId',id]])),/有效的聚水潭店铺/);
});

test('credit settings failures block submission while drafts and original choices remain available',()=>{
 const fields=new Map([['creditFulfillment','true'],['shopId','18797425'],['shopName','店铺'],['warehouseId','15136229'],['warehouseName','国内主仓'],['creditShippingReady','false']]);
 const draft=approvalDraftValues({},order,fields);
 assert.equal(draft.creditFulfillment,true);
 assert.equal(draft.shopId,'18797425');
 assert.equal(draft.warehouseId,'15136229');
 assert.equal(Object.hasOwn(draft,'creditShippingReady'),false);
 assert.throws(()=>approvalDetails({},order,fields),/设置暂不可用/);
 assert.throws(()=>approvalDetails({},order,new Map([...fields,['creditShippingReady','true'],['shopName','店'.repeat(192)]])),/店铺名称无效/);
 assert.throws(()=>approvalDetails({},order,new Map([...fields,['creditShippingReady','true'],['creditShippingChoiceValid','false']])),/当前不可用/);
});

test('credit recipient limits reject unshippable details before approval without restricting legacy flows',()=>{
 const config={salesRecipientRequired:true};
 const fields=new Map([['creditFulfillment','true'],['shopId','18797425'],['shopName','店铺'],['warehouseId','15136229'],['warehouseName','国内主仓'],
  ['recipientName','收'.repeat(50)],['recipientPhone','1'.repeat(30)],['recipientAddress','址'.repeat(200)],['recipientProvince','广东省'],['recipientCity','深圳市'],['recipientDistrict','龙岗区']]);
 const accepted=approvalDetails(config,order,fields);
 assert.equal(accepted.recipientName.length,50);
 assert.equal(accepted.recipientPhone.length,30);
 assert.equal(accepted.recipientAddress.length,200);
 for(const [key,value,error] of [['recipientName','收'.repeat(51),/收货人.*50/],['recipientPhone','1'.repeat(31),/收货电话.*30/],['recipientAddress','址'.repeat(201),/收货地址.*200/],
  ['recipientPhone','123456',/7至30/],['recipientPhone','请联系客户补充电话号码',/7至30/],['recipientPhone','13800000000转123',/7至30/]])
  assert.throws(()=>approvalDetails(config,order,new Map([...fields,[key,value]])),error);
 for(const phone of ['13800000000','+86 (755) 1234-5678',' 13800000000 '])
  assert.equal(approvalDetails(config,order,new Map([...fields,['recipientPhone',phone]])).recipientPhone,phone.trim());
 // Drafts retain unfinished values so they can be corrected before submitting.
 assert.equal(approvalDraftValues(config,order,new Map([...fields,['recipientAddress','址'.repeat(201)]])).recipientAddress.length,201);
 const legacyFields=new Map([...fields,['creditFulfillment','false'],['recipientName','收'.repeat(100)],['recipientPhone','电话'.repeat(25)],['recipientAddress','址'.repeat(500)]]);
 assert.equal(approvalDetails(config,order,legacyFields).recipientAddress.length,500);
 assert.equal(approvalDetails(config,{...order,type:'海外提货单'},legacyFields).recipientName.length,100);
 assert.deepEqual(approvalDetails(config,{...order,source:'jushuitan-settlement'},fields),{});
});

test('structured addresses survive draft save and submit while legacy full addresses remain untouched',()=>{
 const fields=new Map([['creditFulfillment','true'],['shopId','18797425'],['shopName','店铺'],['warehouseId','15136229'],['warehouseName','国内主仓'],
  ['recipientName',' 测试人 '],['recipientPhone','13800000000'],['recipientProvince',' 广东省 '],['recipientCity',' 深圳市 '],['recipientDistrict',' 龙岗区 '],['recipientAddress',' 万科星火online10栋 ']]);
 const saved=approvalDraftValues({},order,fields);
 const reopened={...order,approvalDraft:saved};
 assert.deepEqual(approvalDraftValues({},reopened,new Map()),saved);
 const details=approvalDetails({},reopened,new Map());
 assert.equal(details.recipientProvince,'广东省');assert.equal(details.recipientCity,'深圳市');assert.equal(details.recipientDistrict,'龙岗区');
 assert.equal(details.recipientAddress,'万科星火online10栋');
 assert.equal(recipientAddressText(details),'广东省深圳市龙岗区万科星火online10栋');
 for(const [key,label] of [['recipientProvince','省份'],['recipientCity','城市'],['recipientDistrict','区 / 县']]){
  for(const value of ['', '地'.repeat(51)])assert.throws(()=>approvalDetails({},order,new Map([...fields,[key,value]])),new RegExp(label+'.*50'));
  assert.equal(approvalDraftValues({},reopened,new Map([[key,'']]))[key],'');
 }
 const legacy={recipientAddress:'广东省深圳市龙岗区万科星火online10栋'};
 assert.equal(recipientAddressText(legacy),legacy.recipientAddress);
 assert.deepEqual(legacy,{recipientAddress:'广东省深圳市龙岗区万科星火online10栋'});
 const manual=approvalDetails({},order,new Map([...fields,['recipientProvince','手工省份'],['recipientCity','手工城市'],['recipientDistrict','新设新区']]));
 assert.equal(manual.recipientDistrict,'新设新区');
 for(const province of ['北京市','上海市','天津市','重庆市']){
  const raw={recipientProvince:province,recipientCity:province,recipientDistrict:'测试区',recipientAddress:'测试路1号'};
  assert.equal(recipientAddressText(raw),province+'测试区测试路1号');assert.equal(raw.recipientCity,province);
 }
 for(const grouping of ['市辖区','县','省直辖县级行政区划','自治区直辖县级行政区划','省直辖行政单位','自治区直辖行政单位'])
  assert.equal(recipientAddressText({recipientProvince:'测试省',recipientCity:grouping,recipientDistrict:'直辖市',recipientAddress:'测试路1号'}),'测试省直辖市测试路1号');
});

test('only ordinary domestic sales use credit routing and owner matches remain explicit suggestions',()=>{
 assert.equal(isCreditSalesOrder(order),true);
 const fields=new Map([['creditFulfillment','true'],['shopId','18797425'],['shopName','店铺']]);
 for(const other of [{...order,type:'预收款订单'},{...order,type:'海外提货单'},{...order,source:'jushuitan-settlement'}]){
  assert.equal(isCreditSalesOrder(other),false);
  assert.equal(Object.hasOwn(approvalDraftValues({},other,fields),'creditFulfillment'),false);
  assert.equal(plannedCreditShop({...other,approvalDraft:{shopId:'18797425',shopName:'店铺',creditFulfillment:true}}),null);
  assert.equal(creditPushView(other,{creditPush:{status:'QUEUED'}}),null);
 }
 const shops=[{id:1,name:'孙瑞婷'},{id:2,name:'其他店铺'}];
 assert.deepEqual(suggestedCreditShop(shops,' 孙瑞婷 '),{id:'1',name:'孙瑞婷'});
 assert.equal(suggestedCreditShop([...shops,{id:3,name:'孙瑞婷'}],'孙瑞婷'),null);
 assert.equal(suggestedCreditShop(shops,'孙'),null);
 assert.equal(suggestedCreditShop(shops,''),null);
 assert.equal(suggestedCreditShop([{id:0,name:'孙瑞婷'}],'孙瑞婷'),null);
});

test('credit push states show unknown and native waiting-payment honestly without booking money',()=>{
 const sale={...order,status:'已批准',total:12200,paid:0,approvalDraft:{...order.approvalDraft,creditFulfillment:true}};
 const original=structuredClone(sale);
 assert.match(creditPushView(sale,{creditPush:{status:'QUEUED'}}).label,/待自动推送/);
 assert.match(creditPushView(sale,{creditPush:{status:'BLOCKED',message:'请补店铺'}}).message,/请补店铺/);
 assert.match(creditPushView(sale,{creditPush:{status:'UNKNOWN'}}).label,/结果待核对/);
 const accepted=creditPushView(sale,{creditPush:{status:'ACCEPTED',soId:'CRM-credit',oId:20}});
 assert.equal(accepted.label,'聚水潭已接单');
 assert.match(accepted.message,/尚未回传审核状态/);
 assert.doesNotMatch(accepted.message,/已客审|等待财务审核放行/);
 assert.equal(accepted.oId,20);
 assert.equal(accepted.soId,'CRM-credit');
 const native=creditPushView(sale,{creditPush:{status:'ACCEPTED'}},{creditFulfillment:true,status:'ACCEPTED',nativeStatus:'WaitPay',oId:20});
 assert.equal(native.label,'聚水潭待付款 / 账期待处理');
 assert.match(native.message,/待付款/);
 assert.equal(creditPushView(sale,{}, {creditFulfillment:true,status:'SHIPPED',nativeStatus:'Sent'}).label,'聚水潭已发货');
 assert.equal(creditPushView({...sale,status:'已取消'},{creditPush:{status:'QUEUED'}}),null);
 assert.equal(creditPushView({...sale,deletedAt:'2026-11-24'},{creditPush:{status:'QUEUED'}}),null);
 assert.deepEqual(sale,original);
});

test('formal submission tracks ERP independently of Feishu outcome and retains the original ERP identity',()=>{
 const sale={...order,status:'审批中',total:12200,paid:0,approvalDraft:{creditFulfillment:true}};
 const shipment={creditFulfillment:true,status:'ACCEPTED',nativeStatus:'WaitConfirm',soId:'CRM-original',oId:25};
 const queued=creditPushView(sale,{status:'SUBMITTING',creditPush:{status:'QUEUED',trigger:'ORDER_SUBMISSION',orderSubmissionId:'submit-1'}});
 assert.equal(queued.label,'待自动推送');assert.equal(queued.trigger,'ORDER_SUBMISSION');assert.equal(queued.orderSubmissionId,'submit-1');
 assert.doesNotMatch(queued.message,/审批通过后/);
 const waiting=creditPushView(sale,{status:'PENDING'},shipment);assert.equal(waiting.label,'聚水潭待销售审核');assert.match(waiting.message,/尚未进入待财务审核/);assert.match(waiting.message,/需在聚水潭完成销售审核/);assert.match(waiting.message,/账期挂账已付不代表审核通过/);assert.equal(waiting.approvalWarning,'');
 const waitingFinance=creditPushView(sale,{status:'PENDING'},{...shipment,nativeStatus:'WaitFConfirm'});
 assert.equal(waitingFinance.label,'聚水潭待财务审核');assert.match(waitingFinance.message,/已客审，等待财务审核放行/);
 for(const status of ['FAILED','REJECTED','CANCELED','DELETED']){
  const result=creditPushView({...sale,status:'草稿'},{status},shipment);
  assert.equal(result.label,'聚水潭待销售审核');assert.match(result.approvalWarning,/飞书审批异常，请核对聚水潭原单/);
  assert.match(result.approvalWarning,/不会自动取消/);assert.match(result.approvalWarning,/继续沿用原单/);assert.equal(result.soId,'CRM-original');
 }
 const pendingShipped=creditPushView(sale,{status:'PENDING'},{...shipment,status:'SHIPPED',nativeStatus:'Sent'});
 assert.match(pendingShipped.approvalWarning,/并行处理中/);assert.doesNotMatch(pendingShipped.approvalWarning,/审批异常/);
 assert.equal(pendingShipped.approvalAttentionLabel,'飞书审批并行处理中');
 const uncertain=creditPushView(sale,{status:'UNKNOWN'},shipment);assert.match(uncertain.approvalWarning,/飞书结果待核对/);
 assert.doesNotMatch(uncertain.approvalWarning,/审批异常/);
 const rejectedShipped=creditPushView({...sale,status:'草稿'},{status:'REJECTED'},{...shipment,status:'SHIPPED',nativeStatus:'Sent'});
 assert.equal(rejectedShipped.label,'聚水潭已发货');assert.match(rejectedShipped.approvalWarning,/飞书审批异常/);
 for(const result of [pendingShipped,rejectedShipped]){
  assert.match(result.message,/仅表示已发货/);
  assert.match(result.message,/不据此推定销售审核或财务审核履历/);
  assert.doesNotMatch(result.message,/已客审|已完成.*审核|已通过.*审核/);
 }
 assert.equal(creditPushView({...sale,status:'已取消'},{status:'CANCELED'},shipment).soId,'CRM-original');
 assert.equal(orderExecutionView({...sale,status:'已取消'},shipment).unpaid,12200);
 assert.equal(creditPushView({...sale,status:'草稿'},null).label,'草稿未推送');
 assert.match(creditPushView({...sale,status:'草稿'},null).message,/保存草稿不会推送/);
 assert.equal(creditPushView({...sale,status:'草稿'},{status:'REJECTED'},{...shipment,status:'CANCELLED',nativeStatus:'Cancelled'}).approvalWarning,'');
});

test('credit fulfillment always displays actual CRM collections instead of ERP settlement tender',()=>{
 const sale={...order,status:'已批准',total:12200,paid:0};
 const external={creditFulfillment:true,effectiveOrder:{total:12200,paid:12200,items:[]}};
 assert.equal(orderExecutionView(sale,external).paid,0);
 assert.equal(orderExecutionView(sale,external).unpaid,12200);
 assert.equal(orderExecutionView({...sale,paid:200},external).paid,200);
 assert.equal(orderExecutionView({...sale,paid:200},external).unpaid,12000);
 assert.equal(orderExecutionView(sale,{...external,creditFulfillment:false}).paid,12200);
});
