export const workspacePages = {
  'dashboard-sales':'dashboard.read',
  'targets-company':'dashboard.targets.manage',
  'sales-orders':'business.read', 'sales-items':'business.read',
  'targets-personnel':'business.read',
  advance:'business.read', 'receipt-orders':'business.read',
  customers:'business.read', products:'business.read',
  'sys-users':'system.manage', 'sys-roles':'system.manage',
  'sys-departments':'system.manage', 'sys-audit':'system.manage',
  'sys-payment-methods':'system.manage', 'sys-backup':'system.manage',
};

export function defaultWorkspacePage(permissions){
  return ['sales-orders','dashboard-sales','targets-company','sys-users']
    .find(page=>permissions.includes(workspacePages[page])) || '';
}

export function resolveWorkspacePage(page,permissions){
  page=canonicalWorkspacePage(page);
  return Object.hasOwn(workspacePages,page)&&permissions.includes(workspacePages[page])
    ? page : defaultWorkspacePage(permissions);
}

export function canonicalWorkspacePage(page){
  if(page==='dashboard-targets')return 'targets-company';
  if(page==='sales-targets')return 'targets-personnel';
  return page==='orders'?'sales-orders':['receipts','sales-receipts'].includes(page)?'receipt-orders':page;
}
