import React,{useEffect,useState} from 'react';
import OrderSelect from './OrderSelect';
import {warehouseIdentifier} from './feishuApprovalModel';
import './orderWarehousePicker.css';

export default function OrderWarehousePicker({api,value={},disabled=false,onChange}){
 const draft=value&&typeof value==='object'?value:{};
 const[rows,setRows]=useState([]),[loading,setLoading]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState(0),[loaded,setLoaded]=useState(false);
 const warehouseId=String(draft.warehouseId??''),savedName=String(draft.warehouseName??'');
 useEffect(()=>{
  if(disabled)return;
  let active=true;setLoading(true);setError('');setLoaded(false);
  api('jst/warehouses').then(result=>{
   if(!Array.isArray(result))throw Error('仓库数据格式无效，请重试');
   const next=[],seen=new Set();
   for(const row of result){
    const id=warehouseIdentifier(row?.id);
    if(!id||seen.has(id))continue;
    seen.add(id);next.push({id,name:String(row.name??'').trim()||'仓库 '+id,main:row.main===true});
   }
   if(active){setRows(next);setLoaded(true)}
  }).catch(problem=>{if(active)setError(problem.message||'仓库加载失败，请重试')})
    .finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[api,disabled,attempt]);
 const selected=rows.find(row=>row.id===warehouseId);
 const name=selected?.name||savedName;
 if(disabled)return <section className="order-warehouse-picker" aria-label="订单发货仓库">
  <div className="read-field"><span>发货仓库</span><p>{warehouseId?(name||'仓库')+'（编号 '+warehouseId+'）':'未预选'}</p></div>
  <input type="hidden" name="warehouseId" disabled value={warehouseId}/><input type="hidden" name="warehouseName" disabled value={savedName}/>
 </section>;
 const unavailable=loaded&&warehouseId&&!selected;
 const options=[{value:'',label:'暂不选择（发货时再确认）'},
  ...rows.map(row=>({value:row.id,label:row.name,detail:'编号 '+row.id,badge:row.main?'本仓':undefined})),
  ...(warehouseId&&!selected?[{value:warehouseId,label:savedName||'原选仓库',detail:'编号 '+warehouseId+(unavailable?' · 当前不可用':'')}]:[])];
 function change(id){
  if(!id){onChange?.({...draft,warehouseId:'',warehouseName:''});return}
  const row=rows.find(row=>row.id===id);
  if(row)onChange?.({...draft,warehouseId:row.id,warehouseName:row.name});
 }
 return <section className="order-warehouse-picker" aria-label="订单发货仓库">
  <OrderSelect label="发货仓库" value={warehouseId} options={options} onChange={change} disabled={loading}
   placeholder={loading?'正在加载仓库…':'请选择发货仓库（选填）'} searchPlaceholder="搜索仓库名称或编号"/>
  <input type="hidden" name="warehouseId" value={warehouseId}/><input type="hidden" name="warehouseName" value={warehouseId?name:''}/>
  {loading&&<p className="form-hint" role="status">正在读取聚水潭仓库…</p>}
  {error&&<p className="order-warehouse-error" role="status">{error}<button type="button" className="link-btn" onClick={()=>setAttempt(current=>current+1)}>重新加载仓库</button></p>}
  {unavailable&&<p className="order-warehouse-error" role="status">原选仓库当前不可用，请重新选择或清空后再提交；也可先保存草稿。</p>}
  {loaded&&!rows.length&&<p className="form-hint" role="status">暂无可用仓库，可稍后在发货时选择。</p>}
  <p className="form-hint">用于后续手动发货时默认带入，发货前仍需核对仓库。</p>
 </section>;
}
