// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.receipt.validator;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import be.weskey.module.member.member.entity.Member;
import be.weskey.module.member.receipt.dto.request.ReceiptRequest;
import be.weskey.module.member.receipt.dto.request.ReceiptSaveRequest;
import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.store.entity.Store;
import be.weskey.module.member.store_stock.entity.StoreStock;

@Component
public class ReceiptValidator {

	public void validateAuthenticated(Member member) {
		throw new UnsupportedOperationException("stub");
	}

	public void validateReceiptMember(Long memberId, Receipt receipt) {
		throw new UnsupportedOperationException("stub");
	}

	// 주문 저장 사전 검증 — 결제 금액(paymentTotalPrice·Toss amount)이 totalPrice * 0.1 과 일치하는지,
	// 마일리지 사용 시 규칙(단일 매장·최소/최대 금액·100단위·잔액 초과 여부)을 검증한다.
	public void validateBeforePrepareOrder(ReceiptSaveRequest request, Member member, long totalPrice) {
		throw new UnsupportedOperationException("stub");
	}

	// TX1 마일리지 차감 직전 재검증 — 비관락으로 가져온 member 기준으로 잔액·규칙을 재확인한다.
	// 사전검증(Facade)과 TX1 사이에 다른 요청이 마일리지를 소진했을 경우를 방어한다.
	public void validateMileageUsage(List<ReceiptRequest> receiptRequests, Integer mileage, Member member,
		long totalPrice) {
		throw new UnsupportedOperationException("stub");
	}

	public void validateStoreStockNotHidden(Map<Long, StoreStock> storeStockMap, List<Long> storeStockIds) {
		throw new UnsupportedOperationException("stub");
	}

	public void validatePickUpDate(Store store, LocalDate pickUpDate, Map<Long, StoreStock> storeStockMap) {
		throw new UnsupportedOperationException("stub");
	}

	public void validateStockQuantityAndPrice(ReceiptRequest receiptRequest, Map<Long, StoreStock> storeStockMap,
		Member member) {
		throw new UnsupportedOperationException("stub");
	}
}
