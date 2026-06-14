// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
// 운영 코드에서는 paymentKey 에 UNIQUE 제약이 있어 동시 결제 race 에서 한 건만 확정된다.
package be.weskey.shared.toss_payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;

@Entity
@Getter
public class PaymentInfo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(unique = true)
	private String paymentKey;

	// [stub] 운영에서는 누적 취소액·잔여 취소가능액·PG 동기화 플래그(isPgSynced)를 갱신한다. deep-dive 범위에선 시그니처만 둔다.
	public void updateCancellationInfo(Long canceledAmount, boolean hasRemainingActiveOutbox) {
		// no-op stub
	}
}
