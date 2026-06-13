package be.weskey.shared.toss_payment.entity;

/**
 * Toss 취소 재시도 outbox 의 취소 발생 상황(트리거).
 * 같은 "전체취소"라도 발생 맥락에 따라 재시도 방식과 paymentInfo 유무가 달라지므로,
 * 단순 부분/전체 구분 대신 발생 상황을 기록한다.
 */
public enum PaymentCancelTrigger {
	// 주문 취소: paymentInfo 기반 부분취소 재시도.
	ORDER_CANCEL,
	// 주문 저장 중 승인 후 기록 실패: paymentInfo 없이 paymentKey 기반 전체취소 재시도.
	ORDER_SAVE_COMPENSATION,
	// PG 대사에서 발견된 orphan 결제: paymentInfo/receipt 없이 paymentKey 기반 전체취소 재시도.
	RECONCILE_ORPHAN
}
