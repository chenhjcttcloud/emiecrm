import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createSettlementApprovalRequest,submitSettlementApproval} from '../src/settlementApprovalSubmission.js';

const input=()=>({customerId:'customer',cycle:'day',periodStart:'2026-11-23',periodEnd:'2026-11-23',
  sales:[{shopId:'0',oId:'123'}],total:'100.00',expectedErpAmount:100,customerConfirmed:true});

test('a settlement retry keeps the same request identifier and submitted selection after an uncertain response',async()=>{
  const draft=input(),request=createSettlementApprovalRequest(draft),calls=[];
  draft.sales[0].oId='999';
  const api=async(path,body)=>{
    if(path==='state')throw Error('unavailable');
    calls.push(structuredClone(body));
    if(calls.length===1)throw Error('network interrupted');
    return {state:{revision:2,orders:[]},approval:{orderId:'JS-order',status:'PENDING'}};
  };
  await assert.rejects(submitSettlementApproval(api,request),error=>error.uncertain===true);
  const result=await submitSettlementApproval(api,request);
  assert.equal(result.approval.status,'PENDING');
  assert.deepEqual(calls[0],calls[1]);
  assert.match(calls[0].approvalRequestId,/^approval-/);
  assert.equal(calls[0].sales[0].oId,'123');
  assert.deepEqual(calls[0].approval,{});
});

test('a saved settlement is recovered when the create response is lost',async()=>{
  const request=createSettlementApprovalRequest(input());
  const state={revision:3,orders:[{id:'JS-order',type:'销售订单',source:'jushuitan-settlement',approvalRequestId:request.approvalRequestId}]};
  const result=await submitSettlementApproval(async path=>{
    if(path==='state')return state;
    throw Error('response lost');
  },request);
  assert.deepEqual(result,{state,approval:null});
});

test('an unrelated order never resolves an uncertain settlement request',async()=>{
  const request=createSettlementApprovalRequest(input());
  const state={orders:[{type:'销售订单',approvalRequestId:request.approvalRequestId},
    {type:'销售订单',source:'jushuitan-settlement',approvalRequestId:'another-request'}]};
  await assert.rejects(submitSettlementApproval(async path=>{
    if(path==='state')return state;
    throw Error('response lost');
  },request),error=>error.uncertain===true);
});

test('definitive validation errors allow the settlement form to be corrected',async()=>{
  const error=Object.assign(Error('金额已变化'),{status:400});
  await assert.rejects(submitSettlementApproval(async path=>{
    if(path==='state')return {orders:[]};
    throw error;
  },createSettlementApprovalRequest(input())),failure=>failure===error&&!failure.uncertain);
});
