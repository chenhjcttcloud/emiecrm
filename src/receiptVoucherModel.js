export const RECEIPT_VOUCHER_LIMIT = 10;
export const RECEIPT_VOUCHER_SIZE = 5 * 1024 * 1024;
export const RECEIPT_VOUCHER_ACCEPT = 'image/png,image/jpeg,image/gif,image/webp,.png,.jpg,.jpeg,.gif,.webp';

export function privateVoucherUrl(value) {
  return typeof value === 'string' && /^\/api\/assets\/[A-Za-z0-9_-][A-Za-z0-9._-]*$/.test(value)
    && !value.split('/').at(-1).includes('..');
}

export function copyReceiptVouchers(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > RECEIPT_VOUCHER_LIMIT) throw Error('收款凭证最多上传10张图片');
  return value.map(image => {
    if (!image || !privateVoucherUrl(image.url)) throw Error('收款凭证须使用已上传的内部图片');
    const name = typeof image.name === 'string' && image.name ? image.name : '收款凭证图片';
    if (name.length > 255 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) throw Error('收款凭证图片名称无效');
    return {name, url: image.url};
  });
}

export function validateVoucherFiles(files, currentCount = 0) {
  if (currentCount + files.length > RECEIPT_VOUCHER_LIMIT) throw Error('收款凭证最多上传10张图片，请删除部分图片后重试');
  for (const file of files) {
    if (!file.size || file.size > RECEIPT_VOUCHER_SIZE) throw Error('每张收款凭证须大于零且不能超过5MB');
    const image = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type)
      || !file.type && /\.(?:png|jpe?g|gif|webp)$/i.test(file.name || '');
    if (!image) throw Error('收款凭证仅支持PNG、JPEG、GIF、WebP图片');
    if (!file.name || file.name.length > 255 || /[\u0000-\u001f\u007f-\u009f]/.test(file.name)) throw Error('收款凭证图片名称无效');
  }
}
