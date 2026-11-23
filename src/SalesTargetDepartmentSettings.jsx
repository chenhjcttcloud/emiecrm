import React,{useEffect,useLayoutEffect,useRef,useState} from 'react';
import{dashboardTime}from'./dashboardModel';
import{normalizeSalesTargetScopeSettings}from'./salesModel';

export default function SalesTargetDepartmentSettings({api,Icon,DialogLayer,open,onClose,onSaved}){
 const[settings,setSettings]=useState(null),[draft,setDraft]=useState(null),[query,setQuery]=useState(''),[loading,setLoading]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState(''),[conflict,setConflict]=useState(false);
 const mounted=useRef(true),request=useRef(0),saving=useRef(false);
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;request.current++}},[]);
 async function load(preserveDraft=false){
  const id=++request.current;setLoading(true);
  try{
   const next=normalizeSalesTargetScopeSettings(await api('sales/target-scope'));
   if(!mounted.current||id!==request.current)return;
   setSettings(next);
   if(!preserveDraft){setDraft({mode:next.mode,departmentIds:[...next.departmentIds],revision:next.revision});setConflict(false)}
  }catch(problem){if(mounted.current&&id===request.current)setError(problem.message||'部门设置加载失败，请重试')}
  finally{if(mounted.current&&id===request.current)setLoading(false)}
 }
 useLayoutEffect(()=>{
  if(!open){request.current++;return}
  setSettings(null);setDraft(null);setQuery('');setError('');setConflict(false);load();
  return()=>{request.current++};
 },[open,api]);
 const departments=settings?.departments||[];
 const selected=new Set(draft?.departmentIds||[]);
 const visible=departments.filter(item=>item.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
 const missing=(draft?.departmentIds||[]).filter(id=>!departments.some(item=>item.id===id));
 const stale=Boolean(conflict||settings&&draft&&settings.revision!==draft.revision);
 const latestNames=settings?.mode==='all'?'全部部门':settings?.departmentIds.length?settings.departmentIds.map(id=>departments.find(item=>item.id===id)?.name||'已删除部门').join('、'):'未选择部门（无人参与）';
 function updateSelection(id,checked){setDraft(previous=>({...previous,departmentIds:checked?[...previous.departmentIds,id]:previous.departmentIds.filter(value=>value!==id)}))}
 function close(){if(!busy)onClose()}
 async function save(event){
  event.preventDefault();if(saving.current||busy||loading||!draft||!settings||stale||draft.mode==='selected'&&missing.length)return;
  saving.current=true;setBusy(true);setError('');
  try{
   const saved=normalizeSalesTargetScopeSettings(await api('sales/target-scope',{mode:draft.mode,departmentIds:draft.mode==='all'?[]:draft.departmentIds,revision:draft.revision}));
   if(mounted.current)onSaved(saved);
  }catch(problem){
   if(!mounted.current)return;
   if(problem.status===409){setConflict(true);setError('参与范围已被其他管理员修改，已保留你的选择，请核对最新设置后重新保存。');await load(true)}
   else setError(problem.message||'部门设置保存失败，请重试');
  }finally{saving.current=false;if(mounted.current)setBusy(false)}
 }
 if(!DialogLayer)return null;
 return <DialogLayer open={open} onClose={close} label="销售目标部门设置">
  <form className="modal sales-target-scope-modal" aria-label="销售目标部门设置表单" onSubmit={save}>
   <div className="modal-title"><h2>销售目标部门设置</h2><button type="button" aria-label="关闭部门设置" disabled={busy} onClick={close}><Icon name="close"/></button></div>
   <div className="modal-body">
    <p className="sales-scope-intro">人员列表和公司目标按参与部门汇总，已有个人目标会保留。订单、回款实际业绩沿用原统计口径。</p>
    {error&&<div className="sales-scope-error" role="alert">{error}{!draft&&<button type="button" className="link-btn" disabled={loading} onClick={()=>{setError('');load()}}>重新加载</button>}</div>}
    {!draft&&loading&&<div className="sales-scope-loading" role="status">正在读取参与部门…</div>}
    {draft&&<>
     <fieldset className="sales-scope-mode" disabled={busy||loading}><legend className="sales-sr-only">参与方式</legend>
      <label><input type="radio" name="sales-target-scope" value="all" aria-label="全部部门参与" aria-describedby="sales-scope-all-note" checked={draft.mode==='all'} onChange={()=>setDraft(previous=>({...previous,mode:'all'}))}/><span><strong>全部部门参与</strong><small id="sales-scope-all-note">自动包含新部门及部门归属待完善的人员</small></span></label>
      <label><input type="radio" name="sales-target-scope" value="selected" aria-label="仅勾选部门参与" aria-describedby="sales-scope-selected-note" checked={draft.mode==='selected'} onChange={()=>setDraft(previous=>({...previous,mode:'selected'}))}/><span><strong>仅勾选部门参与</strong><small id="sales-scope-selected-note">未分配、未匹配或人员重名的记录暂不参与</small></span></label>
     </fieldset>
     {draft.mode==='selected'&&<div className="sales-scope-choices">
      <div className="sales-scope-search"><Icon name="search" size={16}/><input aria-label="搜索参与部门" value={query} maxLength={191} placeholder="搜索部门" disabled={busy||loading} onChange={event=>setQuery(event.target.value)} onKeyDown={event=>{if(event.key==='Enter')event.preventDefault()}}/></div>
      <div className="sales-scope-choice-toolbar"><span>已勾选 {draft.departmentIds.length} 个部门</span><div><button type="button" className="link-btn" disabled={busy||loading} onClick={()=>setDraft(previous=>({...previous,departmentIds:departments.map(item=>item.id)}))}>全选所有部门</button><button type="button" className="link-btn" disabled={busy||loading||!draft.departmentIds.length} onClick={()=>setDraft(previous=>({...previous,departmentIds:[]}))}>清空选择</button></div></div>
      <div className="sales-scope-department-list">{visible.map(item=><label key={item.id}><input type="checkbox" value={item.id} aria-label={'参与部门 '+item.name} checked={selected.has(item.id)} disabled={busy||loading} onChange={event=>updateSelection(item.id,event.target.checked)}/><span>{item.name}</span></label>)}{!visible.length&&<p>{query?'暂无匹配部门':'暂无可选部门'}</p>}</div>
      {missing.length>0&&<p className="sales-scope-empty-warning">有 {missing.length} 个部门已不存在，请移除后保存。<button type="button" className="link-btn" disabled={busy||loading} onClick={()=>setDraft(previous=>({...previous,departmentIds:previous.departmentIds.filter(id=>departments.some(item=>item.id===id))}))}>移除已删除部门</button></p>}
      {!draft.departmentIds.length&&<p className="sales-scope-empty-warning">尚未勾选部门。保存后暂无参与人员，公司目标为 0。</p>}
     </div>}
     {stale&&<div className="sales-scope-conflict" role="status"><span>{loading?'正在读取最新参与范围…':<>最新范围：{latestNames}{settings?.updatedAt&&<small>{settings.updatedBy||'管理员'} · {dashboardTime(settings.updatedAt)}</small>}</>}</span>{settings?.revision===draft.revision&&<button type="button" className="secondary" disabled={loading||busy} onClick={()=>{setError('');load(true)}}>重新读取最新范围</button>}<button type="button" className="secondary" disabled={loading||busy||!settings||settings.revision===draft.revision} onClick={()=>{setDraft(previous=>({...previous,revision:settings.revision}));setConflict(false);setError('')}}>核对后使用最新版本</button></div>}
    </>}
   </div>
   <div className="modal-footer"><button type="button" className="secondary" disabled={busy} onClick={close}>取消</button><button type="submit" className="primary" disabled={busy||loading||!draft||!settings||stale||draft.mode==='selected'&&missing.length>0}>{busy?'正在保存…':'保存参与范围'}</button></div>
  </form>
 </DialogLayer>;
}
