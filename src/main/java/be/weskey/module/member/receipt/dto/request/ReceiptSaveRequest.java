// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.receipt.dto.request;

import java.util.List;

import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.shared.toss_payment.dto.request.TossPaymentInfoRequest;
import lombok.Getter;

@Getter
public class ReceiptSaveRequest {

	private List<ReceiptRequest> receiptRequests;
	private Integer mileage;
	private Long paymentTotalPrice;
	private TossPaymentInfoRequest tosspaymentInfo;

	public Receipt toReceiptEntity(Member member, long totalPrice, long paymentTotalPrice) {
		throw new UnsupportedOperationException("stub");
	}
}
