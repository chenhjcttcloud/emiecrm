import React,{useEffect,useState} from 'react';
import OrderSelect from './OrderSelect';
import OrderLogisticsPicker from './OrderLogisticsPicker';
import {isCreditSalesOrder,suggestedCreditShop,warehouseIdentifier} from './feishuApprovalModel';
import './orderWarehousePicker.css';

export default function OrderWarehousePicker({api,value={},disabled=false,logisticsLocked=false,onChange,order,owner}){
 const draft=value&&typeof value==='object'?value:{};
 const credit=isCreditSalesOrder(order);
 const[rows,setRows]=useState([]),[shops,setShops]=useState([]),[ready,setReady]=useState(false),[message,setMessage]=useState(''),[terms,setTerms]=useState({mode:'UNCONFIGURED',description:''}),[loading,setLoading]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState(0),[loaded,setLoaded]=useState(false);
 const warehouseId=String(draft.warehouseId??''),savedName=String(draft.warehouseName??'');
 const shopId=String(draft.shopId??''),savedShopName=String(draft.shopName??'');
 useEffect(()=>{
  if(disabled)return;
  let active=true;setLoading(true);setError('');setMessage('');setTerms({mode:'UNCONFIGURED',description:''});setReady(false);setLoaded(false);
  api(credit?'jst/credit-shipping-options':'jst/warehouses').then(result=>{
   if(credit&&(!result||typeof result!=='object'||Array.isArray(result)||result.mode!=='CREDIT'||typeof result.enabled!=='boolean'))throw Error('账期推送设置格式无效，请重试');
   if(credit&&result.enabled&&!['NATIVE_CREDIT','ON_ACCOUNT_TENDER'].includes(result.accountingMode))throw Error('账期会计口径尚未确认，请核对推送设置。');
   const source=credit?result.warehouses??[]:result;
   if(!Array.isArray(source)||(credit&&!Array.isArray(result.shops??[])))throw Error('发货选项格式无效，请重试');
   function choices(list,label){
    const next=[],seen=new Set();
    for(const row of list){
     const id=warehouseIdentifier(row?.id);
     if(!id||seen.has(id))continue;
     seen.add(id);next.push({id,name:String(row.name??'').trim()||label+' '+id,main:row.main===true});
    }
    return next;
   }
   if(active){setRows(choices(source,'仓库'));setShops(credit?choices(result.shops??[],'店铺'):[]);setReady(!credit||result.enabled===true);setMessage(credit?String(result.message??''):'');setTerms(credit?{mode:result.accountingMode||'UNCONFIGURED',description:String(result.creditTermDescription??'')}:{mode:'UNCONFIGURED',description:''});setLoaded(true)}
  }).catch(problem=>{if(active)setError(problem.message||'发货设置加载失败，请重试')})
    .finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[api,disabled,attempt,credit]);
 const selected=rows.find(row=>row.id===warehouseId);
 const name=selected?.name||savedName;
 const shop=shops.find(row=>row.id===shopId),shopName=shop?.name||savedShopName;
 const suggested=credit&&!shopId&&ready?suggestedCreditShop(shops,owner??order?.owner):null;
 if(disabled)return <section className="order-warehouse-picker" aria-label="订单发货仓库">
  {credit&&<><div className="read-field"><span>聚水潭店铺</span><p>{shopId?(shopName||'店铺')+'（编号 '+shopId+'）':'未选择'}</p></div>
   <input type="hidden" name="shopId" disabled value={shopId}/><input type="hidden" name="shopName" disabled value={savedShopName}/>
   <input type="hidden" name="creditFulfillment" disabled value={draft.creditFulfillment===true?'true':'false'}/></>}
  <div className="read-field"><span>发货仓库</span><p>{warehouseId?(name||'仓库')+'（编号 '+warehouseId+'）':'未预选'}</p></div>
  <input type="hidden" name="warehouseId" disabled value={warehouseId}/><input type="hidden" name="warehouseName" disabled value={savedName}/>
  {credit&&<OrderLogisticsPicker api={api} value={draft} warehouseId={warehouseId} disabled onChange={onChange}/>}
 </section>;
 const unavailable=loaded&&warehouseId&&!selected;
 const shopUnavailable=credit&&loaded&&shopId&&!shop;
 const options=[{value:'',label:credit?'请选择发货仓库':'暂不选择（发货时再确认）'},
  ...rows.map(row=>({value:row.id,label:row.name,detail:'编号 '+row.id,badge:row.main?'本仓':undefined})),
  ...(warehouseId&&!selected?[{value:warehouseId,label:savedName||'原选仓库',detail:'编号 '+warehouseId+(unavailable?' · 当前不可用':'')}]:[])];
 function change(id){
  const policy=credit?{creditFulfillment:true}:{};
  if(!id){onChange?.({...draft,...policy,warehouseId:'',warehouseName:''});return}
  const row=rows.find(row=>row.id===id);
  if(row)onChange?.({...draft,...policy,warehouseId:row.id,warehouseName:row.name});
 }
 function changeShop(id){
  if(!id){onChange?.({...draft,creditFulfillment:true,shopId:'',shopName:''});return}
  const row=shops.find(row=>row.id===id);
  if(row)onChange?.({...draft,creditFulfillment:true,shopId:row.id,shopName:row.name});
 }
 return <section className="order-warehouse-picker" aria-label="订单发货仓库">
  {credit&&<><div className="order-credit-policy"><strong>{terms.mode==='ON_ACCOUNT_TENDER'?'账期挂账（非实收） · 提交即推送聚水潭':terms.mode==='NATIVE_CREDIT'?'原生授信账期 · 提交即推送聚水潭':'账期发货 · 待确认推送设置'}</strong>
   {terms.description&&<p>{terms.description}</p>}
   {terms.mode==='ON_ACCOUNT_TENDER'&&<p>账期挂账只满足聚水潭支付节点，不代表销售或财务审核通过；CRM 已收金额仍按实际到账记录。</p>}
   <p>正式提交即并行发起飞书审批并推送聚水潭，聚水潭销售审核、财务审核与飞书审批分别处理；保存草稿不推送。</p>
   <p>请确认店铺、仓库和收货资料。推送不登记收款，已收金额仍以实际入账为准。</p></div>
   <OrderSelect label="聚水潭店铺" required value={shopId} options={[{value:'',label:'请选择聚水潭店铺'},
    ...shops.map(row=>({value:row.id,label:row.name,detail:'编号 '+row.id})),
    ...(shopId&&!shop?[{value:shopId,label:savedShopName||'原选店铺',detail:'编号 '+shopId+(shopUnavailable?' · 当前不可用':'')}]:[])]}
    onChange={changeShop} disabled={loading||!ready} placeholder={loading?'正在加载店铺…':'请选择聚水潭店铺'} searchPlaceholder="搜索店铺名称或编号"/>
   {suggested&&<p className="order-shop-suggestion">签单人有一个同名店铺：{suggested.name}（{suggested.id}）<button type="button" className="link-btn" onClick={()=>changeShop(suggested.id)}>使用建议店铺</button></p>}
   <input type="hidden" name="shopId" value={shopId}/><input type="hidden" name="shopName" value={shopId?shopName:''}/>
   <input type="hidden" name="creditFulfillment" value="true"/><input type="hidden" name="creditShippingReady" value={ready&&!loading&&!error?'true':'false'}/>
   <input type="hidden" name="creditShippingChoiceValid" value={shopUnavailable||unavailable?'false':'true'}/></>}
  <OrderSelect label="发货仓库" required={credit} value={warehouseId} options={options} onChange={change} disabled={loading||(credit&&!ready)}
   placeholder={loading?'正在加载仓库…':credit?'请选择发货仓库':'请选择发货仓库（选填）'} searchPlaceholder="搜索仓库名称或编号"/>
  <input type="hidden" name="warehouseId" value={warehouseId}/><input type="hidden" name="warehouseName" value={warehouseId?name:''}/>
  {credit&&<OrderLogisticsPicker api={api} value={draft} warehouseId={warehouseId} disabled={logisticsLocked} onChange={onChange}/>}
  {loading&&<p className="form-hint" role="status">正在读取聚水潭{credit?'店铺和':''}仓库…</p>}
  {(error||(credit&&loaded&&!ready))&&<p className="order-warehouse-error" role="status">{error||message||'账期推送当前不可用，请核对连接设置。'}<button type="button" className="link-btn" onClick={()=>setAttempt(current=>current+1)}>重新加载{credit?'发货设置':'仓库'}</button></p>}
  {shopUnavailable&&<p className="order-warehouse-error" role="status">原选店铺当前不可用，请重新选择后提交；也可先保存草稿。</p>}
  {unavailable&&<p className="order-warehouse-error" role="status">原选仓库当前不可用，请重新选择或清空后再提交；也可先保存草稿。</p>}
  {loaded&&!rows.length&&<p className="form-hint" role="status">{credit?'暂无可用仓库，请核对连接或先保存草稿。':'暂无可用仓库，可稍后在发货时选择。'}</p>}
  {credit&&loaded&&ready&&!shops.length&&<p className="order-warehouse-error" role="status">暂无可上传的聚水潭店铺，可先保存草稿。</p>}
  <p className="form-hint">{credit?'实际发货和物流进度以聚水潭回传为准。':'用于后续手动发货时默认带入，发货前仍需核对仓库。'}</p>
 </section>;
}
