// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import be.weskey.module.member.member.entity.Member;
import be.weskey.shared.toss_payment.dto.request.TossPaymentInfoRequest;
import be.weskey.shared.toss_payment.dto.response.TossPaymentPaymentApprovalResponse;
import be.weskey.shared.toss_payment.entity.PaymentInfo;

@Service
public class TossPaymentService {

	public ResponseEntity<TossPaymentPaymentApprovalResponse> approvePayment(TossPaymentInfoRequest tossPaymentInfo,
		Member member) {
		throw new UnsupportedOperationException("stub");
	}

	public PaymentInfo savePaymentInfo(ResponseEntity<TossPaymentPaymentApprovalResponse> approvalResponse,
		Member member) {
		throw new UnsupportedOperationException("stub");
	}

	// 승인된 결제를 paymentKey 기준으로 전체취소한다 (주문 저장 보상 경로).
	public void compensateApprovedPayment(Long memberId, String paymentKey) {
		throw new UnsupportedOperationException("stub");
	}
}
