import React,{useRef,useState} from 'react';
import DashboardTargets from './DashboardTargets';
import SalesTargets from './SalesTargets';
import './targetWorkspace.css';

export default function TargetWorkspace({api,Icon,DialogLayer,user,view,onViewChange,initialMonth,onPersonnelMonth}){
 const permissions=user.permissions;
 const canCompany=permissions.includes('dashboard.targets.manage');
 const canPersonnel=permissions.includes('business.read');
 const views=[...(canCompany?[{key:'company',label:'公司汇总'}]:[]),...(canPersonnel?[{key:'personnel',label:'人员目标'}]:[])];
 const selected=views.find(item=>item.key===view)?.key||views[0]?.key;
 const[busy,setBusy]=useState(false);
 const tabButtons=useRef({});
 function select(next){
  if(busy||next===selected||!views.some(item=>item.key===next))return;
  onViewChange(next);
 }
 function move(event){
  if(busy||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
  event.preventDefault();
  const index=views.findIndex(item=>item.key===selected);
  const next=views[event.key==='Home'?0:event.key==='End'?views.length-1:(index+(event.key==='ArrowRight'?1:-1)+views.length)%views.length];
  tabButtons.current[next.key]?.focus();
  select(next.key);
 }
 if(!selected)return null;
 return <div className="target-workspace">
  <div className="page-heading"><div><div className="breadcrumb">经营分析 <span>/</span> 业绩目标</div><h1>业绩目标</h1><p>汇总公司目标，设置人员月度目标并跟踪完成进度。</p></div><div className="page-heading-symbol"><Icon name="orders" size={28}/></div></div>
  <div className="target-workspace-tabs" role="tablist" aria-label="业绩目标视图" aria-busy={busy} onKeyDown={move}>
   {views.map(item=><button key={item.key} ref={button=>{tabButtons.current[item.key]=button}} type="button" role="tab" id={'target-tab-'+item.key} aria-controls={'target-panel-'+item.key} aria-selected={selected===item.key} tabIndex={selected===item.key?0:-1} disabled={busy} onClick={()=>select(item.key)}>{item.label}</button>)}
  </div>
  {views.map(item=><section key={item.key} className="target-workspace-panel" role="tabpanel" id={'target-panel-'+item.key} aria-labelledby={'target-tab-'+item.key} hidden={selected!==item.key} tabIndex={0}>
   {selected===item.key&&(item.key==='company'?<DashboardTargets embedded api={api} Icon={Icon} onBusyChange={setBusy} onSalesTargets={canPersonnel?onPersonnelMonth:undefined}/>:<SalesTargets key={initialMonth||'personnel'} embedded api={api} Icon={Icon} DialogLayer={DialogLayer} initialMonth={initialMonth} onBusyChange={setBusy} canManageTargets={canCompany} canConfigureDepartments={permissions.includes('system.manage')}/>)}
  </section>)}
 </div>;
}
