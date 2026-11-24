import React,{useEffect,useState} from 'react';
import OrderWarehousePicker from './OrderWarehousePicker';
import {RecipientAddressFields} from './FeishuOrderApproval';
import {approvalDetails,isCreditSalesOrder} from './feishuApprovalModel';
import './orderWarehousePicker.css';

const activePushes=new Set(['QUEUED','SUBMITTING','UNKNOWN','ACCEPTED']);

export default function CreditOrderPush({order,approval,shipment,api,canWrite=false,onPush}){
 const[open,setOpen]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[saved,setSaved]=useState(false);
 const[draft,setDraft]=useState(()=>({...order?.approvalDraft}));
 useEffect(()=>{setOpen(false);setBusy(false);setError('');setSaved(false);setDraft({...order?.approvalDraft})},[order?.id]);
 const eligible=canWrite&&typeof onPush==='function'&&isCreditSalesOrder(order)&&order.status==='已批准'&&!order.deletedAt&&!shipment&&!activePushes.has(approval?.creditPush?.status);
 if(!eligible)return null;
 async function submit(event){
  event.preventDefault();if(busy)return;
  setBusy(true);setError('');setSaved(false);
  try{
   const fields=new FormData(event.currentTarget);
   const input=approvalDetails({salesRecipientRequired:true},{...order,approvalDraft:draft},fields);
   await onPush({shopId:input.shopId,warehouseId:input.warehouseId,recipientName:input.recipientName,
    recipientPhone:input.recipientPhone,recipientProvince:input.recipientProvince,recipientCity:input.recipientCity,
    recipientDistrict:input.recipientDistrict,recipientAddress:input.recipientAddress});
   setSaved(true);setOpen(false);
  }catch(problem){setError(problem.message||'保存推送资料失败，请重试')}
  finally{setBusy(false)}
 }
 return <section className="order-credit-repair" aria-label="补充聚水潭推送资料">
  <h3>聚水潭推送资料</h3>
  <p className="form-hint">已通过的订单可补充本次推送资料；原飞书审批和实际收款记录保持。</p>
  <button type="button" className="secondary" disabled={busy} onClick={()=>{setOpen(value=>!value);setError('');setSaved(false)}}>{open?'收起推送资料':'补齐资料并推送聚水潭'}</button>
  {saved&&<p className="form-hint" role="status">推送资料已保存，聚水潭处理结果以上方状态为准。</p>}
  {open&&<form className="order-credit-repair-form" onSubmit={submit}>
   <OrderWarehousePicker api={api} order={order} owner={order.owner} value={draft} disabled={busy} onChange={setDraft}/>
   <p className="form-hint">收货人最多50字，电话7至30位。省、市、区分别填写，详细地址最多200字。原审批已有内容须保持一致；如需更正，请先核对审批及推送授权。</p>
   {[['recipientName','收货人',50,'text'],['recipientPhone','收货电话',30,'tel']].map(([name,label,max,type])=><label className="field" key={name}>
    <span>{label} <em>*</em></span><input name={name} type={type} maxLength={max} required disabled={busy}
     value={draft[name]??''} onChange={event=>setDraft(value=>({...value,[name]:event.target.value}))}/>
   </label>)}
   <RecipientAddressFields draft={draft} disabled={busy} onChange={setDraft} split/>
   {error&&<p className="system-error" role="alert">{error}</p>}
   <button type="submit" className="primary" disabled={busy}>{busy?'正在保存推送资料…':'确认资料并推送'}</button>
  </form>}
 </section>;
}
