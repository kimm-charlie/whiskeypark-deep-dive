# 01. 주문/결제/재고 트랜잭션 파이프라인 — 정합성 + 복구 안전망

> 문제 발견·대안 검토·검증 과정은 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드의 어디에 있는지**만 안내합니다.

## 한 줄 요약

외부 PG 호출을 재고 락 트랜잭션 밖으로 분리(락 점유 초 단위 → 수십 ms)하고,
분리로 잃은 자동 롤백을 **보상 트랜잭션 + outbox 재시도 + PG 대사** 3단 복구 안전망으로 보강.

## 흐름

```
사전검증 → TX1(주문서+재고차감, 짧은 커밋) → PG 승인(트랜잭션 밖)
→ TX2(PaymentInfo 확정) → 실패 시 TX3 보상(하드삭제+재고복원) → PG 취소 실패 시 outbox
```

## 코드 맵 (설계 포인트 → 파일)

IDE 탐색 방법은 [루트 README](../README.md#코드-탐색-ide) 참고. 발췌 코드는 deep-dive 범위만 남겼습니다 (범위 외 메서드는 생략 주석으로 표시).

| 설계 포인트 | 코드 |
|------------|------|
| 사전검증 → TX1 → 승인 → TX2 → TX3 오케스트레이션 | [`ReceiptFacade`](../src/main/java/be/weskey/module/member/receipt/facade/ReceiptFacade.java) |
| TX1/TX2/TX3 본문 (재고 차감·확정·보상 하드삭제) | [`ReceiptService`](../src/main/java/be/weskey/module/member/receipt/service/ReceiptService.java) |
| 동시결제 race (paymentKey UNIQUE → 승자 ID 반환) | [`ReceiptFacade#completePayment`](../src/main/java/be/weskey/module/member/receipt/facade/ReceiptFacade.java) |
| outbox 복구 레코드 (트리거 3종·상태) | [`PaymentCancelOutbox`](../src/main/java/be/weskey/shared/toss_payment/entity/PaymentCancelOutbox.java) · [`…Trigger`](../src/main/java/be/weskey/shared/toss_payment/entity/PaymentCancelTrigger.java) · [`…Status`](../src/main/java/be/weskey/shared/toss_payment/entity/PaymentCancelOutboxStatus.java) |
| 10분 재시도 + 멀티워커 조건부 선점 | [`PaymentCancelOutboxScheduler`](../src/main/java/be/weskey/common/scheduler/PaymentCancelOutboxScheduler.java) · [`…Facade`](../src/main/java/be/weskey/shared/toss_payment/facade/PaymentCancelOutboxFacade.java) · [`…Repository#markProcessingIfPending`](../src/main/java/be/weskey/shared/toss_payment/repository/PaymentCancelOutboxRepository.java) |
| PG 취소 재시도 + `ALREADY_CANCELED` 멱등 | [`TossPaymentCancelService`](../src/main/java/be/weskey/shared/toss_payment/service/TossPaymentCancelService.java) |
| PG 대사 (04:00 · 직전 36h · orphan 탐지) | [`PaymentReconciliationScheduler`](../src/main/java/be/weskey/common/scheduler/PaymentReconciliationScheduler.java) · [`…Service`](../src/main/java/be/weskey/shared/toss_payment/service/PaymentReconciliationService.java) · [`…Facade`](../src/main/java/be/weskey/shared/toss_payment/facade/PaymentReconciliationFacade.java) |
| 관리자 fallback (`RESOLVED_BY_ADMIN` 벌크 정리) | [`AdminFacade`](../src/main/java/be/weskey/module/admin/admin/facade/AdminFacade.java) · [`PaymentCancelOutboxRepository#markResolvedByAdmin*`](../src/main/java/be/weskey/shared/toss_payment/repository/PaymentCancelOutboxRepository.java) |
