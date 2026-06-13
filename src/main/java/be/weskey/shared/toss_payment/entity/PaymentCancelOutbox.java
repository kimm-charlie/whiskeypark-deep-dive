package be.weskey.shared.toss_payment.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Toss 취소 API 호출 실패 시 자동 재시도 큐.
 * TossPaymentCancelService.cancelPaymentPartially/Entirely 실패 시 적재 → PaymentCancelOutboxScheduler 가 10분 주기로 재시도.
 * attempt_count 가 MAX_ATTEMPTS 도달 시 status=FAILED 로 전환되어 영구 실패. 그 이후는 관리자 엑셀 sync 흐름으로 정리한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@DynamicInsert
@DynamicUpdate
@Table(indexes = {
	@Index(name = "idx_payment_cancel_outbox_status", columnList = "status")
})
public class PaymentCancelOutbox {

	public static final int MAX_ATTEMPTS = 3;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	// 취소 발생 상황. 같은 전체취소라도 발생 맥락에 따라 재시도 방식·paymentInfo 유무가 달라지므로 부분/전체 대신 상황을 기록한다.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private PaymentCancelTrigger cancelTrigger;
	// 주문 저장 보상(ORDER_SAVE_COMPENSATION)은 확정 paymentInfo 가 없으므로 null 가능.
	private Long paymentInfoId;
	// paymentInfo 가 없는 보상 전체취소의 Toss 식별자 (paymentKey 로 재시도). 부분취소는 null.
	private String paymentKey;
	// 관리자 대사 엑셀이 주문번호 기준으로 식별할 수 있도록 기록. 도입 이전 행은 null.
	private String orderId;
	// PG 대사 보상(RECONCILE_ORPHAN)은 주문(receipt) 자체가 없으므로 null 가능.
	private Long receiptId;
	@Column(nullable = false)
	private Long cancelAmount;
	private String cancelReason;
	@Column(nullable = false)
	private Integer attemptCount;
	@Column(columnDefinition = "TEXT")
	private String lastErrorMessage;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentCancelOutboxStatus status;
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private PaymentCancelOutbox(PaymentCancelTrigger cancelTrigger, Long paymentInfoId, String paymentKey,
		String orderId, Long receiptId, Long cancelAmount, String cancelReason, String lastErrorMessage) {
		this.cancelTrigger = cancelTrigger;
		this.paymentInfoId = paymentInfoId;
		this.paymentKey = paymentKey;
		this.orderId = orderId;
		this.receiptId = receiptId;
		this.cancelAmount = cancelAmount;
		this.cancelReason = cancelReason;
		this.attemptCount = 0;
		this.lastErrorMessage = lastErrorMessage;
		this.status = PaymentCancelOutboxStatus.PENDING;
	}

	// 주문 취소(ORDER_CANCEL) 적재 — 확정 paymentInfo 기반 부분취소 재시도.
	public static PaymentCancelOutbox enqueue(Long paymentInfoId, String orderId, Long receiptId, Long cancelAmount,
		String cancelReason, String lastErrorMessage) {
		return PaymentCancelOutbox.builder()
			.cancelTrigger(PaymentCancelTrigger.ORDER_CANCEL)
			.paymentInfoId(paymentInfoId)
			.orderId(orderId)
			.receiptId(receiptId)
			.cancelAmount(cancelAmount)
			.cancelReason(cancelReason)
			.lastErrorMessage(lastErrorMessage)
			.build();
	}

	// 주문 저장 보상(ORDER_SAVE_COMPENSATION) 적재 — 승인됨+주문기록 실패로 보상 전체취소마저 실패한 경우. paymentInfo 가 없어 paymentKey 로 재시도한다.
	public static PaymentCancelOutbox enqueueOrderSaveCompensation(String paymentKey, String orderId, Long receiptId,
		Long cancelAmount, String cancelReason, String lastErrorMessage) {
		return PaymentCancelOutbox.builder()
			.cancelTrigger(PaymentCancelTrigger.ORDER_SAVE_COMPENSATION)
			.paymentKey(paymentKey)
			.orderId(orderId)
			.receiptId(receiptId)
			.cancelAmount(cancelAmount)
			.cancelReason(cancelReason)
			.lastErrorMessage(lastErrorMessage)
			.build();
	}

	// PG 대사 보상(RECONCILE_ORPHAN) 적재 — Toss 거래내역엔 있으나 우리 DB(paymentInfo)에 없는 orphan 결제.
	// 주문(receipt)·paymentInfo 가 없어 paymentKey 로 전체취소를 재시도한다.
	public static PaymentCancelOutbox enqueueOrphan(String paymentKey, String orderId, Long cancelAmount,
		String cancelReason) {
		return PaymentCancelOutbox.builder()
			.cancelTrigger(PaymentCancelTrigger.RECONCILE_ORPHAN)
			.paymentKey(paymentKey)
			.orderId(orderId)
			.cancelAmount(cancelAmount)
			.cancelReason(cancelReason)
			.build();
	}

	public void markSuccess() {
		this.status = PaymentCancelOutboxStatus.SUCCESS;
	}

	public void markRetry(String errorMessage) {
		this.attemptCount = this.attemptCount + 1;
		this.lastErrorMessage = errorMessage;
		if (this.attemptCount >= MAX_ATTEMPTS) {
			this.status = PaymentCancelOutboxStatus.FAILED;
			return;
		}
		this.status = PaymentCancelOutboxStatus.PENDING;
	}

	@PrePersist
	private void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	private void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
