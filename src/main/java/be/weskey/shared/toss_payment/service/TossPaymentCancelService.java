package be.weskey.shared.toss_payment.service;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.exception.CustomRuntimeException;
import be.weskey.exception.exceptions.TossPaymentCancelException;
import be.weskey.shared.toss_payment.controller.TossPaymentClient;
import be.weskey.shared.toss_payment.dto.request.TossPaymentCancelEntirelyRequest;
import be.weskey.shared.toss_payment.dto.request.TossPaymentCancelPartiallyRequest;
import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus;
import be.weskey.shared.toss_payment.entity.PaymentInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * outbox 에 적재된 Toss 취소 실패 건을 재시도한다.
 * 핵심은 멱등성 — 크래시로 Toss 취소 성공 직후 DB 커밋 전에 죽은 뒤 재시도하면 Toss 가 ALREADY_CANCELED 를 반환하는데,
 * 이미 원하는 종료상태(취소됨)에 도달했으므로 성공으로 간주해 FAILED 오탐을 막는다.
 * 실제 HTTP 호출(TossPaymentClient)은 외부 연동부라 stub 으로 두고, 성공/멱등/재시도 판단 로직만 운영 코드로 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TossPaymentCancelService {

	private final TossPaymentClient tossPaymentClient;
	private final PaymentCancelOutboxQueryService paymentCancelOutboxQueryService;
	private final TossPaymentAuthProvider tossPaymentAuthProvider;

	/**
	 * outbox 에 적재된 부분 취소 요청을 Toss 에 재시도한다.
	 * 성공 시 paymentInfo 동기화 정보 갱신 + outbox 완료 처리. 실패 시 attempt 갱신(MAX_ATTEMPTS 도달 시 FAILED 전환).
	 */
	public void retryPendingCancellation(PaymentCancelOutbox outbox, PaymentInfo paymentInfo) {
		// 호출자(Facade) 에서 markProcessingIfPending 으로 이미 PROCESSING 으로 전환됐다고 가정한다.
		TossPaymentCancelPartiallyRequest request = TossPaymentCancelPartiallyRequest.of(outbox.getCancelReason(),
			outbox.getCancelAmount());

		try {
			tossPaymentClient.paymentCancelPartially(paymentInfo.getPaymentKey(), request,
				tossPaymentAuthProvider.getAuthorization());
		} catch (CustomRuntimeException e) {
			handleRetryFailure(outbox, e.getMessage());
			return;
		}

		// 본인 outbox 는 성공했으므로 잔여 판정에서 제외한다 — 같은 결제의 다른 미완료 건이 남아 있으면 isPgSynced 를 유지해 대사 목록에 남긴다.
		paymentInfo.updateCancellationInfo(outbox.getCancelAmount(),
			hasRemainingActiveOutboxExcluding(paymentInfo.getId(), outbox.getId()));
		outbox.markSuccess();
		log.info("결제 취소 재시도 성공 outboxId: {} \n receiptId: {}", outbox.getId(), outbox.getReceiptId());
	}

	/**
	 * outbox 에 적재된 전체취소(ENTIRE) 요청을 paymentKey 로 Toss 에 재시도한다.
	 * 승인됨+주문기록 실패로 보상 전체취소마저 실패한 케이스라 paymentInfo 가 없으므로 paymentKey 만으로 취소한다.
	 */
	public void retryPendingEntireCancellation(PaymentCancelOutbox outbox) {
		// 호출자(Facade) 에서 markProcessingIfPending 으로 이미 PROCESSING 으로 전환됐다고 가정한다.
		try {
			requestEntireCancel(outbox.getPaymentKey(), outbox.getCancelReason());
		} catch (CustomRuntimeException e) {
			// 크래시로 Toss 취소 성공 직후 DB 커밋 전에 죽은 뒤 재시도하면 Toss 가 ALREADY_CANCELED 를 반환한다.
			// 이미 원하는 종료상태(취소됨)에 도달했으므로 성공으로 간주한다(멱등). FAILED 오탐 방지.
			if (e.getCustomException() == TossPaymentCancelException.ALREADY_CANCELED_PAYMENT) {
				outbox.markSuccess();
				log.info("결제 전체취소 재시도 — 이미 취소됨(ALREADY_CANCELED) → 성공 처리 outboxId: {} receiptId: {}",
					outbox.getId(), outbox.getReceiptId());
				return;
			}
			handleRetryFailure(outbox, e.getMessage());
			return;
		}

		outbox.markSuccess();
		log.info("결제 전체취소 재시도 성공 outboxId: {} \n receiptId: {}", outbox.getId(), outbox.getReceiptId());
	}

	// 재시도 성공 경로용 — 방금 성공한 본인 outbox 는 제외하고 잔여 미완료 건을 판정한다.
	private boolean hasRemainingActiveOutboxExcluding(Long paymentInfoId, Long outboxId) {
		return paymentCancelOutboxQueryService.findActiveByPaymentInfoIds(List.of(paymentInfoId)).stream()
			.anyMatch(active -> !active.getId().equals(outboxId));
	}

	// 전체취소(ENTIRE) Toss 호출. 예외 처리는 호출처가 케이스별로 담당한다.
	private void requestEntireCancel(String paymentKey, String cancelReason) {
		TossPaymentCancelEntirelyRequest request = TossPaymentCancelEntirelyRequest.from(cancelReason);
		tossPaymentClient.paymentCancelEntirely(paymentKey, request, tossPaymentAuthProvider.getAuthorization());
	}

	// 재시도 실패 공통 처리 — attempt 갱신 + MAX 도달 시 FAILED 마커 로그. (부분/전체 공통)
	private void handleRetryFailure(PaymentCancelOutbox outbox, String errorMessage) {
		outbox.markRetry(errorMessage);
		if (outbox.getStatus() == PaymentCancelOutboxStatus.FAILED) {
			// 영구 실패 — 운영자 알림 필요 (후속: Slack/카카오 알림톡 채널 연동)
			log.error("[PAYMENT_CANCEL_OUTBOX_PERMANENTLY_FAILED] outboxId: {} receiptId: {} trigger: {} paymentKey: {} paymentInfoId: {} 예외: {}",
				outbox.getId(), outbox.getReceiptId(), outbox.getCancelTrigger(), outbox.getPaymentKey(),
				outbox.getPaymentInfoId(), errorMessage);
		} else {
			log.error("결제 취소 재시도 실패 outboxId: {} attempt: {} 예외: {}", outbox.getId(), outbox.getAttemptCount(),
				errorMessage);
		}
	}
}
