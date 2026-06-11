package be.weskey.common.scheduler;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import be.weskey.common.log.SchedulerLoggerProvider;
import be.weskey.shared.toss_payment.facade.PaymentReconciliationFacade;
import lombok.RequiredArgsConstructor;

/**
 * PG 대사 스케줄러 — 매일 새벽, Toss 거래내역과 우리 DB 를 대조해 orphan(Toss 有/DB 無) 결제를 검출하고
 * 보상 전체취소 outbox 로 적재한다. 적재된 건은 PaymentCancelOutboxScheduler 가 자동 환불 재시도한다.
 */
@Component
@RequiredArgsConstructor
@Profile({"prod-blue", "prod-green", "dev-green", "dev-blue"})
public class PaymentReconciliationScheduler {

	private final PaymentReconciliationFacade paymentReconciliationFacade;
	private final SchedulerLoggerProvider schedulerLoggerProvider;

	@Scheduled(cron = "0 0 4 * * ?") // 매일 새벽 4시
	public void reconcileOrphanPayments() {
		long startTime = System.currentTimeMillis();
		schedulerLoggerProvider.getLogger().info("Started 결제 대사(orphan 검출) 스케줄러 task");

		try {
			int enqueued = paymentReconciliationFacade.reconcileOrphans();
			long endTime = System.currentTimeMillis();
			schedulerLoggerProvider.getLogger()
				.info("Finished 결제 대사 스케줄러 task — orphan 적재 {} 건, 소요 {} ms", enqueued, (endTime - startTime));
		} catch (Exception e) {
			// 대사 실패가 다음 스케줄에 영향을 주지 않도록 흡수 (다음 사이클에 멱등 재시도)
			schedulerLoggerProvider.getLogger().error("결제 대사 스케줄러 실행 중 예외 발생 \n 예외: {}", e.getMessage(), e);
		}
	}
}
