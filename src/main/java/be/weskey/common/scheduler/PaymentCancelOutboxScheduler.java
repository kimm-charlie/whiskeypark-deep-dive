package be.weskey.common.scheduler;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import be.weskey.common.log.SchedulerLoggerProvider;
import be.weskey.shared.toss_payment.facade.PaymentCancelOutboxFacade;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Profile({"prod-blue", "prod-green", "dev-green", "dev-blue"})
public class PaymentCancelOutboxScheduler {

	private final PaymentCancelOutboxFacade paymentCancelOutboxFacade;
	private final SchedulerLoggerProvider schedulerLoggerProvider;

	@Scheduled(cron = "0 */10 * * * ?") // 매 10분 정각마다
	public void retryPendingPaymentCancellations() {
		long startTime = System.currentTimeMillis();
		schedulerLoggerProvider.getLogger().info("Started 결제 취소 outbox 재시도 스케줄러 task");

		List<Long> pendingIds = paymentCancelOutboxFacade.findPendingIds();
		int processedCount = 0;
		int failedCount = 0;
		for (Long outboxId : pendingIds) {
			try {
				paymentCancelOutboxFacade.retryOne(outboxId);
				processedCount++;
			} catch (Exception e) {
				// 한 row 의 예기치 못한 예외가 다음 row 처리를 막지 않도록 흡수
				failedCount++;
				schedulerLoggerProvider.getLogger()
					.error("결제 취소 outbox 재시도 중 예외 발생 outboxId: {} \n 예외: {}", outboxId, e.getMessage());
			}
		}

		long endTime = System.currentTimeMillis();
		schedulerLoggerProvider.getLogger()
			.info("Finished 결제 취소 outbox 재시도 스케줄러 task — 대상 {} 건, 처리 {} 건, 예외 {} 건, 소요 {} ms",
				pendingIds.size(), processedCount, failedCount, (endTime - startTime));
	}
}
