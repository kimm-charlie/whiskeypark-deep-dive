package be.weskey.shared.toss_payment.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;

public interface PaymentCancelOutboxRepository extends JpaRepository<PaymentCancelOutbox, Long> {

	@Query("""
			SELECT o.id
			FROM PaymentCancelOutbox o
			WHERE o.status = be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING
			ORDER BY o.id ASC
		""")
	List<Long> findPendingIds();

	/**
	 * 다중 worker(스케줄러 인스턴스 2개 이상) 환경에서 동일 row 중복 처리를 막기 위한 조건부 선점.
	 * status=PENDING 일 때만 PROCESSING 으로 전환하고 영향 row 수를 반환한다.
	 * 0 이면 다른 worker 가 이미 선점한 것으로 보고 호출자는 즉시 스킵한다.
	 * clearAutomatically=true 필수 — 이 벌크 UPDATE 직후 호출자(Facade)가 같은 outbox 를 findById 로 재조회하므로,
	 * PC 에 남은 PENDING 스냅샷을 clear 해야 fresh PROCESSING 을 읽어 이후 markRetry(PENDING) 가 dirty 로 잡힌다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE PaymentCancelOutbox o
			SET o.status = be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PROCESSING,
				o.updatedAt = :now
			WHERE o.id = :id
			AND o.status = be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING
		""")
	int markProcessingIfPending(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 관리자가 paymentInfo 를 수동 sync 마킹할 때, 같은 paymentInfo 의 미완료(PENDING / PROCESSING / FAILED) outbox 를
	 * 일괄 RESOLVED_BY_ADMIN 으로 전환. 자동 재시도 무한 루프와 이중 환불 위험을 차단한다. SUCCESS 는 그대로 두어 이력 보존.
	 * FAILED 도 전환한다 — sync 가 미완료 합계 일치 검증을 통과한 수동 정리이므로, 남겨두면 이후 같은 paymentInfo 재실패 시
	 * 미완료 합계에 중복 합산된다.
	 * clearAutomatically 는 쓰지 않는다 — 호출 직전 AdminService 가 markAsSynced 로 만든 미flush dirty PaymentInfo 가
	 * 컨텍스트 clear 로 폐기되는 것을 막기 위함. (이 벌크 UPDATE 이후 outbox 엔티티를 같은 트랜잭션에서 재조회하지 않으므로 clear 불필요)
	 */
	@Modifying
	@Query("""
			UPDATE PaymentCancelOutbox o
			SET o.status = be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.RESOLVED_BY_ADMIN,
				o.updatedAt = :now
			WHERE o.paymentInfoId IN :paymentInfoIds
			AND o.status IN (be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PROCESSING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.FAILED)
		""")
	int markResolvedByAdminForPaymentInfos(@Param("paymentInfoIds") List<Long> paymentInfoIds,
		@Param("now") LocalDateTime now);

	/**
	 * sync-failed paymentInfo 들의 미완료(PENDING / PROCESSING / FAILED) outbox 조회.
	 * 관리자 대사 엑셀에 "환불해야 할 총액(미완료 취소합계)" 노출 + sync 업로드 시 기입 금액 일치 검증에 사용한다.
	 */
	@Query("""
			SELECT o
			FROM PaymentCancelOutbox o
			WHERE o.paymentInfoId IN :paymentInfoIds
			AND o.status IN (be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PROCESSING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.FAILED)
			ORDER BY o.id ASC
		""")
	List<PaymentCancelOutbox> findActiveByPaymentInfoIds(@Param("paymentInfoIds") List<Long> paymentInfoIds);

	/**
	 * paymentInfo 가 없는(보상 전체취소) 미완료 outbox. 관리자 대사 엑셀이 paymentInfo 기준 목록에 안 잡는 사각지대를
	 * paymentKey 로 노출하기 위함. paymentInfoId IS NULL 기준이라 향후 paymentInfo 없는 trigger 도 자동 포함된다.
	 */
	@Query("""
			SELECT o
			FROM PaymentCancelOutbox o
			WHERE o.paymentInfoId IS NULL
			AND o.status IN (be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PROCESSING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.FAILED)
			ORDER BY o.id ASC
		""")
	List<PaymentCancelOutbox> findActiveWithoutPaymentInfo();

	/**
	 * 관리자가 paymentKey 기준으로 수동 대사 마킹할 때(보상 전체취소 = paymentInfo 없음), 해당 paymentKey 의 미완료 outbox 를
	 * RESOLVED_BY_ADMIN 으로 일괄 전환한다. paymentInfo 기반 버전과 달리 FAILED 도 포함한다 — outbox-only 는 outbox 가 유일한
	 * 기록이므로 관리자가 수동 처리한 영구실패 건을 RESOLVED 로 구분해야 한다.
	 * clearAutomatically 는 쓰지 않는다 — 직전 markAsSynced 로 만든 미flush dirty PaymentInfo 가 폐기되지 않게.
	 */
	@Modifying
	@Query("""
			UPDATE PaymentCancelOutbox o
			SET o.status = be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.RESOLVED_BY_ADMIN,
				o.updatedAt = :now
			WHERE o.paymentKey IN :paymentKeys
			AND o.status IN (be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PENDING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.PROCESSING,
				be.weskey.shared.toss_payment.entity.PaymentCancelOutboxStatus.FAILED)
		""")
	int markResolvedByAdminForPaymentKeys(@Param("paymentKeys") List<String> paymentKeys,
		@Param("now") LocalDateTime now);

	/**
	 * 대사 orphan 적재 멱등성 — 같은 paymentKey 의 outbox 가 (상태 무관) 이미 있으면 재적재하지 않는다.
	 * 이전 적재가 SUCCESS(이미 환불됨)여도 매칭되어 이중 환불을 차단한다.
	 */
	@Query("""
			SELECT COUNT(o) > 0
			FROM PaymentCancelOutbox o
			WHERE o.paymentKey = :paymentKey
		""")
	boolean existsByPaymentKey(@Param("paymentKey") String paymentKey);
}
