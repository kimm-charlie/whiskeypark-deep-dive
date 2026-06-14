// [stub] 취소 응답 — deep-dive 범위에선 재시도 성공/실패 판단만 쓰므로 핵심 필드만 둔다(운영은 전체 필드 보유).
package be.weskey.shared.toss_payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TossPaymentPaymentCancelResponseWithCancelInfo {

	private String paymentKey;
	private String status;
	private Long balanceAmount;
}
