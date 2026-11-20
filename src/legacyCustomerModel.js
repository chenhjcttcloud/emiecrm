export const customerRequiredFields=[['name','客户名称'],['entity','客户主体'],['owner','负责人'],['status','客户状态'],['channel','销售渠道'],['contact','联系人名称'],['phone','联系人手机']];

export function isLegacyCustomer(customer){
 return Boolean(customer?.id&&customer.legacySource?.system==='legacy-feishu-crm'&&customer.legacySource.id);
}

// Preserve genuinely missing historical details; once completed they stay required.
export function customerFieldRequired(original,key){
 return key==='name'||!isLegacyCustomer(original)||Boolean(String(original[key]??'').trim());
}

export function firstMissingCustomerField(customer,original){
 return customerRequiredFields.find(([key])=>customerFieldRequired(original,key)&&!String(customer[key]??'').trim());
}

export function legacyOpeningBalanceText(customer){
 if(!Object.hasOwn(customer||{},'openingBalance'))return '旧表未填写';
 return '¥ '+Number(customer.openingBalance).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
}
