// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.dto.response;

import lombok.Getter;

@Getter
public class TossPaymentTransactionResponse {

	private String paymentKey;
	private String transactionKey;
	private String orderId;
	private String status;
	private Long amount;
}
