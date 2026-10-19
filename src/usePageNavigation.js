import {useState,useEffect} from 'react';
const pages=new Set(['orders','advance','receipts','customers','products','sys-users','sys-roles','sys-departments','sys-audit','sys-payment-methods','sys-backup']);
const readPage=()=>{const page=window.location.hash.slice(1);return pages.has(page)?page:'orders'};
export default function usePageNavigation(){
 const[page,setPage]=useState(readPage);
 useEffect(()=>{
  const sync=()=>{const next=readPage();if(window.location.hash!=='#'+next)window.history.replaceState(null,'',window.location.pathname+window.location.search+'#'+next);setPage(next)};
  sync();
  window.addEventListener('popstate',sync);window.addEventListener('hashchange',sync);
  return()=>{window.removeEventListener('popstate',sync);window.removeEventListener('hashchange',sync)};
 },[]);
 function navigate(next){
  if(!pages.has(next))return;
  if(window.location.hash!=='#'+next)window.history.pushState(null,'',window.location.pathname+window.location.search+'#'+next);
  setPage(next);
 }
 return[page,navigate];
}
