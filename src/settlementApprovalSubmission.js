import {recordId} from './recordId.js';
import {isSettlementApprovalOrder} from './feishuApprovalModel.js';

export function createSettlementApprovalRequest(input) {
  return {...structuredClone(input), approval: {}, approvalRequestId: recordId('approval-')};
}

export async function submitSettlementApproval(api, request) {
  try {
    return await api('jst/settlements', request);
  } catch (error) {
    const state = await api('state').catch(() => null);
    if (state?.orders?.some(order => isSettlementApprovalOrder(order) &&
        order.approvalRequestId === request.approvalRequestId)) return {state, approval: null};
    const clientError = error.status > 0 && error.status < 500;
    if (clientError && (state || error.status !== 409)) throw error;
    const uncertain = new Error('暂未确认结算补录提交结果。请在当前表单重试，系统会沿用本次申请编号，不会重复创建补录单。', {cause: error});
    uncertain.uncertain = true;
    throw uncertain;
  }
}
