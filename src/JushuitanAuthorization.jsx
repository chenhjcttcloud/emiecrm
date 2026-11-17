import React from 'react';
export default function JushuitanAuthorization({authorization:a}){
 if(!a)return null;
 const messages={READY:a.enabled?'自动续期已开启':'自动续期未开启',DISABLED:'自动续期未开启',NEEDS_CONFIGURATION:'自动续期待配置：请管理员补齐刷新凭证和授权日期',EXPIRED:'聚水潭授权已过期，请管理员重新授权',REJECTED:'授权刷新被拒绝，请管理员核对授权',UNCERTAIN:'授权刷新结果未确认，请管理员核对，系统已停止重复刷新',STORAGE_ERROR:'授权凭证存储异常，请联系管理员',UNAVAILABLE:'暂时无法查询聚水潭授权状态',NOT_IMPLEMENTED:'当前网关尚未接入自动续期'};
 const warning=!['READY','DISABLED'].includes(a.status);
 return <p className={warning?'catalog-error':'catalog-caption'} role={warning?'alert':'status'} aria-label="聚水潭授权状态">{messages[a.status]||'正在检查聚水潭授权状态'}{a.expiresAt>0&&<> · 到期时间 {new Date(a.expiresAt*1000).toLocaleString()}</>}{a.lastSuccessAt>0&&<> · 最近续期 {new Date(a.lastSuccessAt*1000).toLocaleString()}</>}</p>;
}
