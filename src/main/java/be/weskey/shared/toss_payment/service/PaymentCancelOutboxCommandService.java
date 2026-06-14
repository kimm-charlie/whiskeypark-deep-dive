package be.weskey.shared.toss_payment.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.repository.PaymentCancelOutboxRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentCancelOutboxCommandService {

	private final PaymentCancelOutboxRepository paymentCancelOutboxRepository;

	public PaymentCancelOutbox save(PaymentCancelOutbox outbox) {
		return paymentCancelOutboxRepository.save(outbox);
	}

	// status=PENDING 일 때만 PROCESSING 으로 조건부 UPDATE (다중 worker 중복 처리 차단). 영향 행 수를 반환한다.
	public int markProcessingIfPending(Long id, LocalDateTime now) {
		return paymentCancelOutboxRepository.markProcessingIfPending(id, now);
	}

	// 관리자 수동 sync — paymentInfo 기준 미완료 outbox 를 RESOLVED_BY_ADMIN 으로 일괄 전환.
	public int markResolvedByAdminForPaymentInfos(List<Long> paymentInfoIds, LocalDateTime now) {
		if (paymentInfoIds.isEmpty()) {
			return 0;
		}
		return paymentCancelOutboxRepository.markResolvedByAdminForPaymentInfos(paymentInfoIds, now);
	}

	// 관리자 수동 sync — paymentKey 기준(보상 전체취소 = paymentInfo 없음) 미완료 outbox 를 RESOLVED_BY_ADMIN 으로 일괄 전환.
	public int markResolvedByAdminForPaymentKeys(List<String> paymentKeys, LocalDateTime now) {
		if (paymentKeys.isEmpty()) {
			return 0;
		}
		return paymentCancelOutboxRepository.markResolvedByAdminForPaymentKeys(paymentKeys, now);
	}
}
