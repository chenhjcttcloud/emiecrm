import {useState,useEffect} from 'react';
import {canonicalWorkspacePage,resolveWorkspacePage} from './workspaceNavigation';
export default function usePageNavigation(permissions){
 const key=permissions.join('|');
 const readPage=()=>resolveWorkspacePage(window.location.hash.slice(1),permissions);
 const[page,setPage]=useState(readPage);
 useEffect(()=>{
  const sync=()=>{const next=readPage();if(window.location.hash!=='#'+next)window.history.replaceState(null,'',window.location.pathname+window.location.search+'#'+next);setPage(next)};
  sync();
  window.addEventListener('popstate',sync);window.addEventListener('hashchange',sync);
  return()=>{window.removeEventListener('popstate',sync);window.removeEventListener('hashchange',sync)};
 },[key]);
 function navigate(next){
  next=canonicalWorkspacePage(next);
  if(resolveWorkspacePage(next,permissions)!==next)return;
  if(window.location.hash!=='#'+next)window.history.pushState(null,'',window.location.pathname+window.location.search+'#'+next);
  setPage(next);
 }
 return[resolveWorkspacePage(page,permissions),navigate];
}
