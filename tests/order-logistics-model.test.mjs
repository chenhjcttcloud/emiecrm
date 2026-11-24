import {test} from 'node:test';
import assert from 'node:assert/strict';
import {logisticsDetails,logisticsDraftValues,logisticsOptionValue,logisticsParcels,plannedLogistics,validLogisticsCode,WAREHOUSE_LOGISTICS_OPTION} from '../src/orderLogisticsModel.js';
import {approvalDetails,approvalDraftValues} from '../src/feishuApprovalModel.js';

const order={type:'销售订单',approvalDraft:{creditFulfillment:true,shopId:'1',shopName:'店铺',warehouseId:'15136229',warehouseName:'国内主仓',
 recipientName:'测试人',recipientPhone:'13800000000',recipientProvince:'广东省',recipientCity:'深圳市',recipientDistrict:'龙岗区',recipientAddress:'测试路1号'}};

test('carrier codes preserve opaque punctuation and leading zeroes instead of becoming numeric IDs',()=>{
 for(const code of ['SF','SF.5','ZTZS','001','A-B_2','承运商代码'])assert.equal(validLogisticsCode(code),true);
 for(const code of [1,0,null,undefined,'',' SF','SF ','SF\n','X'.repeat(101)])assert.equal(validLogisticsCode(code),false);
});

test('single dropdown keeps real carrier codes separate from the warehouse option',()=>{
 const codes=['warehouse','WAREHOUSE','carrier:','carrier:warehouse','SF.5','001'];
 const values=codes.map(logisticsOptionValue);
 assert.equal(new Set(values).size,codes.length);
 for(const value of values)assert.notEqual(value,WAREHOUSE_LOGISTICS_OPTION);
 for(const code of codes){
  const details=logisticsDetails({},new Map([['logisticsMode','CARRIER'],['logisticsCode',code],['logisticsCompany','真实物流']]));
  assert.equal(details.logisticsCode,code);
 }
});

test('warehouse arrangement omits newly introduced plan fields and preserves legacy snapshots',()=>{
 const legacy={recipientAddress:'原地址'},copy=structuredClone(legacy);
 assert.deepEqual(logisticsDraftValues(legacy,new Map()),{});
 assert.deepEqual(logisticsDetails(legacy,new Map([['logisticsMode','WAREHOUSE'],['logisticsCode','SF.5'],['logisticsCompany','旧物流'],['logisticsReady','false']])),{});
 assert.deepEqual(legacy,copy);assert.equal(plannedLogistics(legacy),'由仓库安排');
 assert.deepEqual(logisticsDraftValues({logisticsMode:'WAREHOUSE'},new Map()),{logisticsMode:'WAREHOUSE'});
 const saved=approvalDraftValues({},order,new Map([['logisticsMode','WAREHOUSE']]));
 assert.equal(Object.hasOwn(saved,'logisticsMode'),false);assert.equal(Object.hasOwn(approvalDetails({},order,new Map([['logisticsMode','WAREHOUSE']])),'logisticsCode'),false);
});

test('chosen carrier survives drafts and mirrors the structured approval request',()=>{
 const fields=new Map([['logisticsMode','CARRIER'],['logisticsCode','SF.5'],['logisticsCompany','顺丰到付'],['logisticsReady','true'],['logisticsChoiceValid','true']]);
 const plan={logisticsMode:'CARRIER',logisticsCode:'SF.5',logisticsCompany:'顺丰到付'};
 assert.deepEqual(logisticsDraftValues({},fields),plan);assert.deepEqual(logisticsDetails({},fields),plan);
 assert.deepEqual(logisticsDraftValues(plan,new Map()),plan);
 const saved={...order,approvalDraft:{...order.approvalDraft,...approvalDraftValues({},order,fields)}};
 const details=approvalDetails({},saved,new Map());
 for(const key of Object.keys(plan))assert.equal(details[key],saved.approvalDraft[key]);
 assert.equal(plannedLogistics(saved.approvalDraft),'顺丰到付（编码 SF.5）');
 assert.equal(Object.hasOwn(saved.approvalDraft,'logisticsReady'),false);
 for(const other of [{...order,type:'预收款订单'},{...order,type:'海外提货单'},{...order,source:'jushuitan-settlement'}])
  assert.equal(Object.hasOwn(approvalDraftValues({},other,fields),'logisticsCode'),false);
});

test('carrier draft can remain incomplete but submission requires valid current warehouse choices',()=>{
 const incomplete=new Map([['logisticsMode','CARRIER'],['logisticsCode',''],['logisticsCompany','']]);
 assert.equal(logisticsDraftValues({},incomplete).logisticsMode,'CARRIER');
 assert.throws(()=>logisticsDetails({},incomplete),/有效的物流公司/);
 const valid=new Map([['logisticsMode','CARRIER'],['logisticsCode','001'],['logisticsCompany','原仓物流']]);
 assert.throws(()=>logisticsDetails({},new Map([...valid,['logisticsReady','false']])),/目录暂不可用/);
 assert.throws(()=>logisticsDetails({},new Map([...valid,['logisticsChoiceValid','false']])),/当前仓库不可用/);
 for(const name of ['', '公'.repeat(192), '物流\n公司'])assert.throws(()=>logisticsDetails({},new Map([...valid,['logisticsCompany',name]])),/名称无效/);
 assert.throws(()=>logisticsDetails({},new Map([...valid,['logisticsMode','UNKNOWN']])),/物流安排/);
});

test('real outbound parcels remain separate and never acquire a synthetic combined tracking number',()=>{
 const shipments=[{id:'out-1',company:'物流甲',trackingNumber:'001'},{id:'out-2',company:'物流乙',trackingNumber:'002'},{id:'out-3',company:'',trackingNumber:''}];
 const original=structuredClone(shipments),rows=logisticsParcels(shipments);
 assert.equal(rows.length,3);assert.deepEqual(rows.map(row=>row.trackingNumber),['001','002','']);
 assert.equal(rows.some(row=>row.trackingNumber==='001,002'),false);assert.deepEqual(shipments,original);
 assert.deepEqual(logisticsParcels(undefined),[]);assert.deepEqual(logisticsParcels('001,002'),[]);
});
