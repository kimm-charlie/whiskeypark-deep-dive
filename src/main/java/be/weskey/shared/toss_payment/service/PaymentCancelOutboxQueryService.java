// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.service;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;

@Service
public class PaymentCancelOutboxQueryService {

	public List<Long> findPendingIds() {
		throw new UnsupportedOperationException("stub");
	}

	public PaymentCancelOutbox findById(Long outboxId) {
		throw new UnsupportedOperationException("stub");
	}

	public boolean existsByPaymentKey(String paymentKey) {
		throw new UnsupportedOperationException("stub");
	}
}
