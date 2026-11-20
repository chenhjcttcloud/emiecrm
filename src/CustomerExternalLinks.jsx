import React from 'react';
import './customerExternalLinks.css';

export const externalCustomerTypes={offline:'线下客户',distributor:'分销商',buyer:'订单买家'};
export function externalLinkLabel(link){
 return `${link.environment==='sandbox'?'沙箱':'正式'} · ${externalCustomerTypes[link.type]||link.type} · ${link.id}`;
}

export default function CustomerExternalLinks({value=[],onChange,disabled=false,environment='production'}){
 const change=(index,key,next)=>onChange(value.map((row,i)=>i===index?{...row,[key]:next}:row));
 return <div className="field customer-external-links"><span>聚水潭编号关联 <small>可选</small></span>
  <p className="form-hint">用于匹配两边的客户资料。保存关联后不会向聚水潭新增或修改客户。</p>
  {value.map((link,index)=><div className="customer-external-link" key={index}>
   <label><span>环境</span><select aria-label={`关联环境 ${index+1}`} disabled={disabled} value={link.environment} onChange={e=>change(index,'environment',e.target.value)}><option value="production">正式</option><option value="sandbox">沙箱</option></select></label>
   <label><span>编号类型</span><select aria-label={`关联类型 ${index+1}`} disabled={disabled} value={link.type} onChange={e=>change(index,'type',e.target.value)}>{Object.entries(externalCustomerTypes).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
   <label><span>外部客户编号</span><input aria-label={`聚水潭客户编号 ${index+1}`} required disabled={disabled} maxLength={100} value={link.id} onChange={e=>change(index,'id',e.target.value)} placeholder="按文本填写，保留前导零"/></label>
   <button type="button" className="link-btn" disabled={disabled} onClick={()=>onChange(value.filter((_,i)=>i!==index))}>移除</button>
  </div>)}
  {value.length<20&&<button type="button" className="secondary" disabled={disabled} onClick={()=>onChange([...value,{system:'jushuitan',environment,type:'offline',id:'',name:''}])}>添加编号关联</button>}
 </div>;
}
