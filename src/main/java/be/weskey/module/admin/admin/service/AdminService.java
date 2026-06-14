// [stub] 관리자 대사 엑셀 파싱/매칭 — deep-dive 범위 외(엑셀 I/O)라 시그니처만 둔다. AdminFacade 의 sync 흐름 검증용.
package be.weskey.module.admin.admin.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import be.weskey.module.admin.admin.dto.SyncPaymentInfoResult;

@Service
public class AdminService {

	// 엑셀 파싱 + paymentInfo 매칭 + markAsSynced 후, 후속 outbox 정리 대상(paymentInfoIds / paymentKeys)을 담아 반환한다.
	public SyncPaymentInfoResult syncPaymentInfo(MultipartFile file) {
		throw new UnsupportedOperationException("stub");
	}

	// sync 실패(미일치) 건을 관리자 대사 엑셀로 내보낸다.
	public byte[] exportSyncFailedPaymentInfoToExcel() {
		throw new UnsupportedOperationException("stub");
	}
}
