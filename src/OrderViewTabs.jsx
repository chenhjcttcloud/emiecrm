import React,{useRef} from 'react';
import './orderViewTabs.css';

const views=[{key:'list',label:'订单列表'},{key:'items',label:'产品明细'}];

export default function OrderViewTabs({view,onViewChange,disabled=false}){
 const selected=views.some(item=>item.key===view)?view:'list';
 const buttons=useRef({});
 function select(next){
  if(disabled||next===selected||!views.some(item=>item.key===next))return;
  onViewChange(next);
 }
 function move(event){
  if(disabled||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  event.preventDefault();
  const index=views.findIndex(item=>item.key===selected);
  const next=views[event.key==='Home'?0:event.key==='End'?views.length-1:(index+(event.key==='ArrowRight'?1:-1)+views.length)%views.length];
  buttons.current[next.key]?.focus();
  select(next.key);
 }
 return <div className="order-view-tabs" role="tablist" aria-label="订单管理视图" aria-busy={disabled} onKeyDown={move}>
  {views.map(item=><button key={item.key} ref={button=>{buttons.current[item.key]=button}} type="button" role="tab" id={'order-view-'+item.key} aria-controls={'order-panel-'+item.key} aria-selected={selected===item.key} tabIndex={selected===item.key?0:-1} disabled={disabled} onClick={()=>select(item.key)}>{item.label}</button>)}
 </div>;
}
