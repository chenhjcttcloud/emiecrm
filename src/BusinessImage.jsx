import React,{useEffect,useState} from 'react';
import {authorizedFetch} from './authApi';

// Asset requests carry the same bearer token as API requests; tokens never enter URLs.
export default function BusinessImage({src,link=false,...props}){
 const[url,setUrl]=useState(''),[error,setError]=useState(false);
 useEffect(()=>{
  let active=true,objectUrl='';const controller=new AbortController();setError(false);setUrl('');
  if(!src?.startsWith('/api/assets/')){setUrl(src||'');return()=>{active=false}}
  authorizedFetch(src,{signal:controller.signal}).then(async response=>{
   if(!response.ok)throw Error('图片加载失败');
   return response.blob();
  }).then(blob=>{if(active){objectUrl=URL.createObjectURL(blob);setUrl(objectUrl)}}).catch(e=>{if(active&&e.name!=='AbortError')setError(true)});
  return()=>{active=false;controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)};
 },[src]);
 if(error)return <span title="图片加载失败">图片不可用</span>;
 if(!url)return <span aria-label="正在加载图片"/>;
 const image=<img src={url} {...props}/>;
 return link?<a href={url} target="_blank" rel="noopener noreferrer">{image}</a>:image;
}
