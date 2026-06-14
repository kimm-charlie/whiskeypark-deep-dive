package be.weskey.module.admin.admin.dto;

import java.util.List;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AdminService.syncPaymentInfo 결과 — 엑셀 응답과 함께, 후속 outbox 정리 대상을 전달한다.
 * AdminFacade 가 sync 후 paymentInfo 기준(syncedPaymentInfoIds) + paymentKey 기준(resolvedOutboxPaymentKeys) 으로
 * 각각 outbox 를 RESOLVED_BY_ADMIN 처리하는 데 사용한다. (paymentKey 기준 = 보상 전체취소 = paymentInfo 없는 outbox)
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SyncPaymentInfoResult {

	private List<Long> syncedPaymentInfoIds;
	private List<String> resolvedOutboxPaymentKeys;
	private byte[] excelBytes;

	private SyncPaymentInfoResult(List<Long> syncedPaymentInfoIds, List<String> resolvedOutboxPaymentKeys,
		byte[] excelBytes) {
		this.syncedPaymentInfoIds = syncedPaymentInfoIds;
		this.resolvedOutboxPaymentKeys = resolvedOutboxPaymentKeys;
		this.excelBytes = excelBytes;
	}

	public static SyncPaymentInfoResult of(List<Long> syncedPaymentInfoIds, List<String> resolvedOutboxPaymentKeys,
		byte[] excelBytes) {
		return new SyncPaymentInfoResult(syncedPaymentInfoIds, resolvedOutboxPaymentKeys, excelBytes);
	}
}
