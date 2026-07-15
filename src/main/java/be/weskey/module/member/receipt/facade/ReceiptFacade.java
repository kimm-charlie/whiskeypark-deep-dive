package be.weskey.module.member.receipt.facade;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.member.service.MemberQueryService;
import be.weskey.module.member.member.validator.MemberValidator;
import be.weskey.module.member.receipt.dto.request.ReceiptRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptSaveRequest;
import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.receipt.event.ReceiptRequestedEvent;
import be.weskey.module.member.receipt.service.ReceiptService;
import be.weskey.module.member.receipt.validator.ReceiptValidator;
import be.weskey.shared.toss_payment.dto.response.TossPaymentPaymentApprovalResponse;
import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import be.weskey.shared.toss_payment.entity.PaymentInfo;
import be.weskey.shared.toss_payment.service.PaymentCancelOutboxCommandService;
import be.weskey.shared.toss_payment.service.TossPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReceiptFacade {

	private final ReceiptValidator receiptValidator;
	private final ReceiptService receiptService;
	private final MemberQueryService memberQueryService;
	private final MemberValidator memberValidator;
	private final TossPaymentService tossPaymentService;
	private final PaymentCancelOutboxCommandService paymentCancelOutboxCommandService;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;
	private final MeterRegistry meterRegistry;

	/**
	 * 주문 저장 — 외부 Toss 승인을 StoreStock 비관락 트랜잭션 밖에서 수행해 락 점유 시간을 단축한다.
	 * 분리로 잃는 자동 롤백은 보상(saga) + 기록 선행으로 보완한다.
	 *
	 * 사전검증(밖) → TX1(주문서·재고·마일리지 차감) → approve(밖, Toss 승인) → TX2(결제정보 저장·연결·이벤트)
	 * 실패 시 TX3 보상(재고·마일리지 복원 + 주문 흔적 하드 삭제). approve 성공 후 실패면 Toss 결제도 취소한다
	 * (단, payment_info UNIQUE 위반 = 동시 race 면 승자와 같은 결제를 공유하므로 Toss 취소를 하지 않는다).
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Long save(Long memberId, ReceiptSaveRequest request) {
		// 1. 사전검증 (인증 → 멱등 응답 분기 → 결제금액·마일리지 검증). 멱등이면 기존 receiptId 로 즉시 응답.
		Member member = memberQueryService.findById(memberId);
		memberValidator.validateAuthenticated(member);

		Optional<Long> idempotentReceiptId = receiptService.findIdempotentReceiptId(memberId,
			request.getTosspaymentInfo().getPaymentKey());
		if (idempotentReceiptId.isPresent()) {
			return idempotentReceiptId.get();
		}

		long totalPrice = receiptService.calculateTotalPrice(request.getReceiptRequests());
		receiptValidator.validateBeforePrepareOrder(request, member, totalPrice);
		AtomicLong lockNanos = new AtomicLong();

		// 2. TX1: 주문서 저장 + 재고·마일리지 락·차감 (외부 호출 없는 짧은 락 트랜잭션)
		Receipt receipt = transactionTemplate.execute(status ->
			receiptService.prepareOrder(member, request, totalPrice, lockNanos));
		Long receiptId = receipt.getId();
		meterRegistry.timer("receipt.save.lock")
			.record(lockNanos.get(), TimeUnit.NANOSECONDS);

		// 3. approve: Toss 승인은 락 밖에서 수행한다. 실패하면 Toss 미승인이므로 결제 취소 없이 주문 흔적만 보상 삭제한다.
		ResponseEntity<TossPaymentPaymentApprovalResponse> approvalResponse;
		try {
			approvalResponse = tossPaymentService.approvePayment(request.getTosspaymentInfo(), member);
		} catch (RuntimeException e) {
			compensateQuietly(receiptId);
			throw e;
		}
		// 4. TX2: 결제정보 저장·주문 확정·이벤트. 실패 시 보상(+상황별 Toss 취소), race 면 승자 receiptId 반환.
		return completePayment(receiptId, memberId, member, approvalResponse, request);
	}

	// TX2: PaymentInfo 저장 + 주문 확정(연결·장바구니) + 이벤트 발행.
	// 이벤트는 NOT_SUPPORTED 경계라 AFTER_COMMIT 발화를 위해 반드시 이 트랜잭션 안에서 발행한다.
	private Long completePayment(Long receiptId, Long memberId, Member member,
		ResponseEntity<TossPaymentPaymentApprovalResponse> approvalResponse, ReceiptSaveRequest request) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				PaymentInfo paymentInfo = tossPaymentService.savePaymentInfo(approvalResponse, member);
				receiptService.completeOrder(receiptId, memberId, paymentInfo, request);
				eventPublisher.publishEvent(ReceiptRequestedEvent.of(receiptId, memberId, extractStoreIds(request)));
			});
			return receiptId;
		} catch (DataIntegrityViolationException e) {
			// payment_info UNIQUE 위반 = 동시 race. 승자와 같은 결제를 공유하므로 Toss 취소 시 승자 결제까지 환불됨 → 취소 금지.
			// 로컬 주문 흔적만 보상 삭제하고 승자 receiptId 를 반환한다.
			compensateQuietly(receiptId);
			return receiptService.findIdempotentReceiptId(memberId, request.getTosspaymentInfo().getPaymentKey())
				.orElseThrow(() -> e);
		} catch (RuntimeException e) {
			// 그 외 TX2 실패 → 주문 흔적 보상 삭제 + 이미 승인된 Toss 결제 전체취소. 취소마저 실패하면 outbox 적재로 자동 재시도한다.
			compensateQuietly(receiptId);
			cancelApprovedPaymentOrEnqueue(receiptId, memberId, request);
			throw e;
		}
	}

	// 승인된 Toss 결제를 전체취소한다. 취소마저 실패하면 ENTIRE outbox 에 적재해 스케줄러가 재시도한다(돈이 Toss 에 묶이는 것을 방지).
	private void cancelApprovedPaymentOrEnqueue(Long receiptId, Long memberId, ReceiptSaveRequest request) {
		String paymentKey = request.getTosspaymentInfo().getPaymentKey();
		try {
			tossPaymentService.compensateApprovedPayment(memberId, paymentKey);
		} catch (RuntimeException cancelException) {
			log.error("[PAYMENT_CANCEL_WITHOUT_INFO_FAILED] receiptId: {} memberId: {} 승인 결제 전체취소 실패 — outbox 적재. 예외: {}",
				receiptId, memberId, cancelException.getMessage());
			transactionTemplate.executeWithoutResult(status ->
				paymentCancelOutboxCommandService.save(PaymentCancelOutbox.enqueueOrderSaveCompensation(
					paymentKey, request.getTosspaymentInfo().getOrderId(), receiptId,
					request.getTosspaymentInfo().getAmount(), "결제 오류로 인한 주문 취소",
					cancelException.getMessage())));
		}
	}

	// 보상 트랜잭션(TX3) 실행. 보상 실패는 reconciliation/크래시 복구 대상이므로 원본 예외를 가리지 않도록 로깅만 한다.
	private void compensateQuietly(Long receiptId) {
		try {
			transactionTemplate.executeWithoutResult(status -> receiptService.compensate(receiptId));
		} catch (RuntimeException e) {
			log.error("[RECEIPT_COMPENSATE_FAILED] receiptId: {} 보상 트랜잭션 실패 — 수동 확인 필요. 예외: {}", receiptId,
				e.getMessage());
		}
	}

	private List<Long> extractStoreIds(ReceiptSaveRequest request) {
		return request.getReceiptRequests().stream()
			.map(ReceiptRequest::getStoreId)
			.toList();
	}

	// ... (deep-dive 범위 외 생략: 예약 saveReservation, 주문 조회 findAll/findDetail,
	//      취소·수수료 findCancellationFee/cancel, 픽업 기한 경과 자동취소, 픽업 리마인더 등)
}
