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
public class TossPaymentCancelEntirelyRequest {

	private String cancelReason;

	public static TossPaymentCancelEntirelyRequest from(String cancelReason) {
		return TossPaymentCancelEntirelyRequest.builder()
			.cancelReason(cancelReason)
			.build();
	}
}
