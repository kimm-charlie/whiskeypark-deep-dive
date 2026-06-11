package be.weskey.shared.toss_payment.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import be.weskey.shared.toss_payment.controller.TossPaymentClient;
import be.weskey.shared.toss_payment.dto.response.TossPaymentTransactionResponse;
import be.weskey.shared.toss_payment.entity.PaymentCancelOutbox;
import feign.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PG 대사(reconciliation) — Toss 거래내역과 우리 DB(PaymentInfo)를 대조해
 * "Toss 엔 있으나 우리 DB 에 없는" orphan 결제를 검출하고 보상 전체취소 outbox 로 적재한다.
 * 외부 호출(거래내역 조회)은 트랜잭션 밖에서 수행하므로 본 Service 는 @Transactional 을 선언하지 않는다(Facade 가 경계).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReconciliationService {

	// 직전 36시간을 겹쳐 조회해 자정 경계 누락을 방지한다(멱등 적재라 중복 조회는 무해).
	private static final int RECONCILE_WINDOW_HOURS = 36;
	// 거래내역 조회는 최대 60초 소요될 수 있어 타임아웃을 넉넉히 잡는다.
	private static final int TRANSACTION_TIMEOUT_SECONDS = 60;
	private static final int PAGE_SIZE = 100;
	// 페이징 무한 루프 backstop — 정상 거래량을 크게 상회하게 둔다 (PAGE_SIZE * MAX_PAGES = 최대 수집 건수 = 10만).
	private static final int MAX_PAGES = 1000;
	// 승인 완료(환불되지 않은) 거래만 orphan 후보. CANCELED/PARTIAL_CANCELED 등은 이미 환불됐으므로 제외.
	private static final String TRANSACTION_STATUS_DONE = "DONE";
	private static final String ORPHAN_CANCEL_REASON = "PG 대사 - 미등록 결제 자동취소";

	private final TossPaymentClient tossPaymentClient;
	private final TossPaymentAuthProvider tossPaymentAuthProvider;
	private final PaymentInfoQueryService paymentInfoQueryService;
	private final PaymentCancelOutboxQueryService paymentCancelOutboxQueryService;
	private final PaymentCancelOutboxCommandService paymentCancelOutboxCommandService;

	/**
	 * 직전 {@link #RECONCILE_WINDOW_HOURS}시간의 Toss 거래내역을 페이징 커서로 전량 수집한다. (트랜잭션 밖에서 호출)
	 */
	public List<TossPaymentTransactionResponse> fetchRecentTransactions() {
		LocalDateTime now = LocalDateTime.now();
		String startDate = now.minusHours(RECONCILE_WINDOW_HOURS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		String endDate = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		Request.Options options = new Request.Options(
			TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS, TRANSACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS, true);

		List<TossPaymentTransactionResponse> all = new ArrayList<>();
		String startingAfter = null;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<TossPaymentTransactionResponse> body = tossPaymentClient.getTransactions(
				startDate, endDate, startingAfter, PAGE_SIZE, tossPaymentAuthProvider.getAuthorization(), options)
				.getBody();
			if (body == null || body.isEmpty()) {
				return all;
			}
			all.addAll(body);
			// 마지막 페이지(가져온 수가 PAGE_SIZE 미만)면 완전 수집 — 종료. 아니면 마지막 transactionKey 를 커서로 다음 페이지 조회.
			if (body.size() < PAGE_SIZE) {
				return all;
			}
			startingAfter = body.get(body.size() - 1).getTransactionKey();
		}
		// MAX_PAGES 소진 후에도 마지막 페이지가 꽉 참 = 더 있는데 끊겼을 수 있음. 조용히 넘기지 않고 경고한다(잘림 검출).
		log.warn("[RECONCILE_TRANSACTIONS_TRUNCATED] 대사 거래내역이 MAX_PAGES({}) 상한에 도달 — 일부 거래가 누락됐을 수 있다. 수집 {}건. window/주기 조정 검토 필요.",
			MAX_PAGES, all.size());
		return all;
	}

	/**
	 * orphan 후보 검출 — 승인 완료(DONE) + paymentKey 보유 + 우리 DB(PaymentInfo)에 없는 결제.
	 * 한 결제가 여러 transaction 으로 쪼개질 수 있어 paymentKey 기준으로 중복을 제거한다.
	 */
	public List<TossPaymentTransactionResponse> detectOrphans(List<TossPaymentTransactionResponse> transactions) {
		Map<String, TossPaymentTransactionResponse> byPaymentKey = new LinkedHashMap<>();
		for (TossPaymentTransactionResponse transaction : transactions) {
			if (transaction.getPaymentKey() == null || !TRANSACTION_STATUS_DONE.equals(transaction.getStatus())) {
				continue;
			}
			byPaymentKey.putIfAbsent(transaction.getPaymentKey(), transaction);
		}
		return byPaymentKey.values().stream()
			.filter(transaction -> !paymentInfoQueryService.existsByPaymentKey(transaction.getPaymentKey()))
			.toList();
	}

	/**
	 * orphan 1건을 보상 전체취소 outbox 로 적재한다. (짧은 트랜잭션 안에서 호출)
	 * 트랜잭션 안에서 PaymentInfo/outbox 존재를 재확인해 동시 실행·window 겹침으로 인한 이중 적재(=이중 환불)를 차단한다.
	 * 적재 시 true, 이미 존재해 건너뛰면 false 를 반환한다.
	 */
	public boolean enqueueOrphanIfAbsent(TossPaymentTransactionResponse transaction) {
		String paymentKey = transaction.getPaymentKey();
		if (paymentInfoQueryService.existsByPaymentKey(paymentKey)
			|| paymentCancelOutboxQueryService.existsByPaymentKey(paymentKey)) {
			return false;
		}
		paymentCancelOutboxCommandService.save(PaymentCancelOutbox.enqueueOrphan(
			paymentKey, transaction.getOrderId(), transaction.getAmount(), ORPHAN_CANCEL_REASON));
		return true;
	}
}
