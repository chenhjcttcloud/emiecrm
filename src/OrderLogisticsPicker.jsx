import React,{useEffect,useState} from 'react';
import OrderSelect from './OrderSelect';
import {plannedLogistics,validLogisticsCode} from './orderLogisticsModel';

export default function OrderLogisticsPicker({api,value={},warehouseId='',disabled=false,onChange}){
 const mode=value.logisticsMode||'WAREHOUSE',code=value.logisticsCode||'',company=value.logisticsCompany||'';
 const[rows,setRows]=useState([]),[loading,setLoading]=useState(false),[error,setError]=useState(''),[loadedFor,setLoadedFor]=useState(''),[attempt,setAttempt]=useState(0);
 useEffect(()=>{
  if(disabled||mode!=='CARRIER')return;
  let active=true;setRows([]);setError('');setLoadedFor('');
  if(!warehouseId){setLoading(false);return}
  setLoading(true);
  api('jst/logistics-companies?warehouseId='+encodeURIComponent(warehouseId)).then(result=>{
   if(!Array.isArray(result))throw Error('物流目录格式无效，请重试');
   const next=[],seen=new Set();
   for(const row of result){
    if(!validLogisticsCode(row?.code)||typeof row.name!=='string'||!row.name.trim()||row.name.length>191||/[\u0000-\u001f\u007f-\u009f]/.test(row.name))continue;
    if(!seen.has(row.code)){seen.add(row.code);next.push({code:row.code,name:row.name})}
   }
   if(active){setRows(next);setLoadedFor(String(warehouseId))}
  }).catch(problem=>{if(active)setError(problem.message||'物流目录加载失败')}).finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[api,warehouseId,mode,disabled,attempt]);
 if(disabled)return <div className="order-logistics-picker"><div className="read-field"><span>计划物流</span><p>{plannedLogistics(value)}</p></div>
  {['logisticsMode','logisticsCode','logisticsCompany'].map(key=><input key={key} type="hidden" name={key} disabled value={value[key]??(key==='logisticsMode'?'WAREHOUSE':'')}/>)}</div>;
 const ready=mode==='WAREHOUSE'||Boolean(warehouseId)&&loadedFor===String(warehouseId)&&!loading&&!error;
 const chosen=rows.find(row=>row.code===code),unavailable=mode==='CARRIER'&&ready&&code&&!chosen;
 const changeMode=next=>{
  if(next==='WAREHOUSE'){const{logisticsMode,logisticsCode,logisticsCompany,...other}=value;onChange?.(other);return}
  onChange?.({...value,logisticsMode:next,logisticsCode:'',logisticsCompany:''});
 };
 return <section className="order-logistics-picker" aria-label="计划物流">
  <OrderSelect label="物流安排" required value={mode} options={[{value:'WAREHOUSE',label:'由仓库安排'},{value:'CARRIER',label:'指定物流公司'}]} onChange={changeMode}/>
  {mode==='CARRIER'&&<>
   <OrderSelect label="物流公司" required value={code} disabled={!warehouseId||loading||Boolean(error)} placeholder={loading?'正在加载物流…':'请选择物流公司'} searchPlaceholder="搜索物流公司或编码"
    options={[{value:'',label:'请选择物流公司'},...rows.map(row=>({value:row.code,label:row.name,detail:'编码 '+row.code})),
     ...(code&&!chosen?[{value:code,label:company||'原选物流',detail:'编码 '+code+(unavailable?' · 当前仓库不可用':'')}]:[])]}
    onChange={next=>{const row=rows.find(entry=>entry.code===next);if(row)onChange?.({...value,logisticsMode:'CARRIER',logisticsCode:row.code,logisticsCompany:row.name});else if(!next)onChange?.({...value,logisticsMode:'CARRIER',logisticsCode:'',logisticsCompany:''})}}/>
   {!warehouseId&&<p className="form-hint">请先选择发货仓库，再选择该仓库配置的物流公司。</p>}
   {loading&&<p className="form-hint" role="status">正在读取所选仓库的物流目录…</p>}
   {error&&<p className="order-warehouse-error" role="status">{error}；可选择由仓库安排。<button type="button" className="link-btn" onClick={()=>setAttempt(count=>count+1)}>重新加载物流</button></p>}
   {unavailable&&<p className="order-warehouse-error" role="status">原选物流在当前仓库不可用，请重新选择或改为由仓库安排。</p>}
   {ready&&!rows.length&&<p className="form-hint">当前仓库没有返回可选物流，可选择由仓库安排。</p>}
  </>}
  <input type="hidden" name="logisticsMode" value={mode}/><input type="hidden" name="logisticsCode" value={mode==='CARRIER'?code:''}/>
  <input type="hidden" name="logisticsCompany" value={mode==='CARRIER'?(chosen?.name||company):''}/><input type="hidden" name="logisticsReady" value={ready?'true':'false'}/>
  <input type="hidden" name="logisticsChoiceValid" value={unavailable?'false':'true'}/>
  <p className="form-hint">物流选择随订单传入聚水潭；实际承运公司和运单号以各出库包裹回传为准。</p>
 </section>;
}
