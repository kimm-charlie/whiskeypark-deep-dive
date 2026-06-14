package be.weskey.module.admin.admin.facade;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import be.weskey.module.admin.admin.dto.SyncPaymentInfoResult;
import be.weskey.module.admin.admin.service.AdminService;
import be.weskey.shared.toss_payment.service.PaymentCancelOutboxCommandService;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 대사 흐름의 트랜잭션 경계.
 * 관리자가 Toss 콘솔에서 수동 환불한 결과 엑셀을 업로드하면, paymentInfo sync 후
 * 같은 결제의 미완료 outbox 를 RESOLVED_BY_ADMIN 으로 정리해 자동 재시도(이중 환불)를 멈춘다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminFacade {

	private final AdminService adminService;
	private final PaymentCancelOutboxCommandService paymentCancelOutboxCommandService;

	@Transactional
	public byte[] syncPaymentInfo(MultipartFile file) {
		// 1. 엑셀 파싱 + paymentInfo 매칭 + markAsSynced — AdminService 위임
		SyncPaymentInfoResult result = adminService.syncPaymentInfo(file);

		// 2. 같은 paymentInfo 의 미완료 outbox 를 RESOLVED_BY_ADMIN 으로 일괄 전환
		// 자동 재시도 무한 루프와 이중 환불 위험을 차단한다 (Toss 측은 이미 관리자가 수동 환불 처리 완료).
		LocalDateTime now = LocalDateTime.now();
		paymentCancelOutboxCommandService.markResolvedByAdminForPaymentInfos(result.getSyncedPaymentInfoIds(), now);

		// 3. paymentInfo 가 없는 보상 전체취소 outbox 는 paymentKey 기준으로 RESOLVED_BY_ADMIN 전환
		paymentCancelOutboxCommandService.markResolvedByAdminForPaymentKeys(result.getResolvedOutboxPaymentKeys(), now);
		return result.getExcelBytes();
	}

	public byte[] exportSyncFailedPaymentInfoToExcel() {
		return adminService.exportSyncFailedPaymentInfoToExcel();
	}
}
