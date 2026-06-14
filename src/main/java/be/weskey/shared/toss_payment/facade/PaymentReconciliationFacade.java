package be.weskey.shared.toss_payment.facade;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import be.weskey.shared.toss_payment.dto.response.TossPaymentTransactionResponse;
import be.weskey.shared.toss_payment.service.PaymentReconciliationService;
import lombok.RequiredArgsConstructor;

/**
 * PG 대사 트랜잭션 경계. Toss 거래내역 조회(최대 60초)는 DB 트랜잭션 밖(NOT_SUPPORTED)에서 수행하고,
 * orphan 적재만 TransactionTemplate 으로 건별 짧은 트랜잭션을 연다 — 외부 호출 동안 커넥션을 잡지 않기 위함(결제 분리와 동일 원칙).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PaymentReconciliationFacade {

	private final PaymentReconciliationService paymentReconciliationService;
	private final TransactionTemplate transactionTemplate;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int reconcileOrphans() {
		List<TossPaymentTransactionResponse> transactions = paymentReconciliationService.fetchRecentTransactions();
		List<TossPaymentTransactionResponse> orphans = paymentReconciliationService.detectOrphans(transactions);

		int enqueued = 0;
		for (TossPaymentTransactionResponse orphan : orphans) {
			Boolean saved = transactionTemplate.execute(
				status -> paymentReconciliationService.enqueueOrphanIfAbsent(orphan));
			if (Boolean.TRUE.equals(saved)) {
				enqueued++;
			}
		}
		return enqueued;
	}
}
