// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.shared.toss_payment.dto.request;

import lombok.Getter;

@Getter
public class TossPaymentInfoRequest {

	private String paymentKey;
	private String orderId;
	private Long amount;
}
