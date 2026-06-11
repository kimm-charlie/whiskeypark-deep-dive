package be.weskey.shared.toss_payment.entity;

public enum PaymentCancelOutboxStatus {
	PENDING,
	PROCESSING,
	SUCCESS,
	FAILED,
	/**
	 * 관리자가 Toss 측에서 수동으로 환불 처리 후 paymentInfo 를 sync 마킹할 때, 같은 paymentInfo 의 미완료 outbox 도 함께 정리되는 상태.
	 * 자동 재시도가 더 이상 의미 없으며 (Toss 측 이미 환불됨) 이중 환불을 막기 위해 SUCCESS 와 구분한다.
	 */
	RESOLVED_BY_ADMIN
}
