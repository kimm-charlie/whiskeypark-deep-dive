// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;

@Service
public class PaymentCancelOutboxCommandService {

	public PaymentCancelOutbox save(PaymentCancelOutbox outbox) {
		throw new UnsupportedOperationException("stub");
	}

	// status=PENDING 일 때만 PROCESSING 으로 조건부 UPDATE (다중 worker 중복 처리 차단). 영향 행 수를 반환한다.
	public int markProcessingIfPending(Long outboxId, LocalDateTime now) {
		throw new UnsupportedOperationException("stub");
	}
}
