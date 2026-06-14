package be.weskey.shared.toss_payment.dto.request;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@AllArgsConstructor
public class TossPaymentCancelPartiallyRequest {

	private String cancelReason;
	private Long cancelAmount;

	public static TossPaymentCancelPartiallyRequest of(String cancelReason, Long cancelAmount) {
		return TossPaymentCancelPartiallyRequest.builder()
			.cancelReason(cancelReason)
			.cancelAmount(cancelAmount)
			.build();
	}
}
