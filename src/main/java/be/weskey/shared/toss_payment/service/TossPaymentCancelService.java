// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.service;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.entity.PaymentInfo;

@Service
public class TossPaymentCancelService {

	// outbox 재시도 — paymentInfo 기반 부분취소. ALREADY_CANCELED 는 성공으로 해석해 멱등 처리한다.
	public void retryPendingCancellation(PaymentCancelOutbox outbox, PaymentInfo paymentInfo) {
		throw new UnsupportedOperationException("stub");
	}

	// outbox 재시도 — paymentKey 기반 전체취소 (주문 저장 보상 / PG 대사 orphan).
	public void retryPendingEntireCancellation(PaymentCancelOutbox outbox) {
		throw new UnsupportedOperationException("stub");
	}
}
