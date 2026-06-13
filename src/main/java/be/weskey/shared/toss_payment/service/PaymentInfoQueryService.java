// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.entity.PaymentInfo;

@Service
public class PaymentInfoQueryService {

	public Optional<PaymentInfo> findByPaymentKey(String paymentKey) {
		throw new UnsupportedOperationException("stub");
	}

	public PaymentInfo findById(Long paymentInfoId) {
		throw new UnsupportedOperationException("stub");
	}

	public boolean existsByPaymentKey(String paymentKey) {
		throw new UnsupportedOperationException("stub");
	}
}
