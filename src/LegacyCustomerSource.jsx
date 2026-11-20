import React from 'react';
import {isLegacyCustomer,legacyOpeningBalanceText} from './legacyCustomerModel';
import './legacyCustomerSource.css';

export function LegacyCustomerNote({customer}){
 if(!isLegacyCustomer(customer))return null;
 return <small className="legacy-customer-note" title={'旧系统客户 ID：'+customer.legacySource.id}>旧系统迁入</small>;
}

export default function LegacyCustomerSource({customer}){
 if(!isLegacyCustomer(customer))return null;
 const source=customer.legacySource;
 return <section className="legacy-customer-source" aria-label="旧系统迁移信息">
  <h3>旧系统迁入</h3>
  <dl><dt>旧客户 ID</dt><dd>{source.id}</dd>
   {source.fields?.['客户编号']&&<><dt>旧客户编号</dt><dd>{source.fields['客户编号']}</dd></>}
   <dt>期初预收款</dt><dd>{legacyOpeningBalanceText(customer)}</dd>
   <dt>迁移依据</dt><dd>{source.fileName} · {source.sheet} 第 {source.rowNumber} 行</dd>
  </dl>
  <p>期初预收款计入可核销余额，不计入迁入当天的新收款。旧表缺失的资料可留空，后续补全。</p>
 </section>;
}
