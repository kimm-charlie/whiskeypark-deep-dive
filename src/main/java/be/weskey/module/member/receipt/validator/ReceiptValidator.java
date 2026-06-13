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

	public void validatePaymentAndMileage(ReceiptSaveRequest request, Member member, long totalPrice) {
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
