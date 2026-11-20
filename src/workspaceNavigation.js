export const workspacePages = {
  'dashboard-sales':'dashboard.read',
  'dashboard-targets':'dashboard.targets.manage',
  'sales-orders':'business.read', 'sales-items':'business.read',
  'sales-receipts':'business.read', 'sales-targets':'business.read',
  advance:'business.read',
  customers:'business.read', products:'business.read',
  'sys-users':'system.manage', 'sys-roles':'system.manage',
  'sys-departments':'system.manage', 'sys-audit':'system.manage',
  'sys-payment-methods':'system.manage', 'sys-backup':'system.manage',
};

export function defaultWorkspacePage(permissions){
  return ['sales-orders','dashboard-sales','dashboard-targets','sys-users']
    .find(page=>permissions.includes(workspacePages[page])) || '';
}

export function resolveWorkspacePage(page,permissions){
  page=canonicalWorkspacePage(page);
  return Object.hasOwn(workspacePages,page)&&permissions.includes(workspacePages[page])
    ? page : defaultWorkspacePage(permissions);
}

export function canonicalWorkspacePage(page){
  return page==='orders'?'sales-orders':page==='receipts'?'sales-receipts':page;
}
