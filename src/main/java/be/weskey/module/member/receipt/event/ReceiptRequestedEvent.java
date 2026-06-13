// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
// 운영 코드에서는 AFTER_COMMIT 리스너가 점주 카카오 알림톡 발송을 비동기 처리한다.
package be.weskey.module.member.receipt.event;

import java.util.List;

public class ReceiptRequestedEvent {

	public static ReceiptRequestedEvent of(Long receiptId, Long memberId, List<Long> storeIds) {
		return new ReceiptRequestedEvent();
	}
}
