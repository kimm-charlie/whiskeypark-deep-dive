package be.weskey.shared.toss_payment.facade;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.entity.PaymentInfo;
import be.weskey.shared.toss_payment.service.PaymentCancelOutboxCommandService;
import be.weskey.shared.toss_payment.service.PaymentCancelOutboxQueryService;
import be.weskey.shared.toss_payment.service.PaymentInfoQueryService;
import be.weskey.shared.toss_payment.service.TossPaymentCancelService;
import lombok.RequiredArgsConstructor;

/**
 * Toss 취소 실패 outbox 재시도 트랜잭션 경계.
 * Scheduler 가 batch loop 를 돌고, 각 row 별로 본 Facade 의 retryOne(id) 을 호출해 row 단위 트랜잭션을 보장한다.
 * 한 row 실패가 다른 row 처리에 영향을 주지 않으며, 다중 worker(스케줄러 인스턴스 2개 이상) 환경에서도 조건부 UPDATE 로 중복 처리를 차단한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentCancelOutboxFacade {

	private final PaymentCancelOutboxQueryService paymentCancelOutboxQueryService;
	private final PaymentCancelOutboxCommandService paymentCancelOutboxCommandService;
	private final PaymentInfoQueryService paymentInfoQueryService;
	private final TossPaymentCancelService tossPaymentCancelService;

	public List<Long> findPendingIds() {
		return paymentCancelOutboxQueryService.findPendingIds();
	}

	@Transactional
	public void retryOne(Long outboxId) {
		// 다중 worker 중복 처리 방지: status=PENDING 일 때만 PROCESSING 으로 전환. 0 영향이면 다른 worker 가 이미 선점.
		int updated = paymentCancelOutboxCommandService.markProcessingIfPending(outboxId, LocalDateTime.now());
		if (updated == 0) {
			return;
		}
		PaymentCancelOutbox outbox = paymentCancelOutboxQueryService.findById(outboxId);

		// paymentInfo 가 없는 보상(주문 저장 보상 / PG 대사 orphan)은 paymentKey 로 전체취소를 재시도한다.
		// 주문 부분취소(paymentInfo 기반)만 paymentInfo 를 로딩한다.
		if (outbox.getPaymentInfoId() == null) {
			tossPaymentCancelService.retryPendingEntireCancellation(outbox);
			return;
		}

		PaymentInfo paymentInfo = paymentInfoQueryService.findById(outbox.getPaymentInfoId());
		tossPaymentCancelService.retryPendingCancellation(outbox, paymentInfo);
	}
}
