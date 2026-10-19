import {useLayoutEffect,useRef} from 'react';

// Animate the existing content so focus and selection survive page changes.
export default function usePageMotion(page,pageSize){
  const ref=useRef(null),previous=useRef({page,pageSize});
  useLayoutEffect(()=>{
    const old=previous.current;
    previous.current={page,pageSize};
    if(old.page===page&&old.pageSize===pageSize)return;
    const element=ref.current;
    if(!element?.animate||window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    const offset=page<old.page?-8:8;
    const animation=element.animate([
      {opacity:0.35,transform:`translateX(${offset}px)`},
      {opacity:1,transform:'translateX(0)'}
    ],{duration:200,easing:'cubic-bezier(.2,.7,.2,1)'});
    return()=>animation.cancel();
  },[page,pageSize]);
  return ref;
}
