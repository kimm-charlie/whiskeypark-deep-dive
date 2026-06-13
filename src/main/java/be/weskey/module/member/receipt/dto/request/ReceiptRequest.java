// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.receipt.dto.request;

import java.time.LocalDate;
import java.util.List;

import be.weskey.module.member.recipient.dto.request.RecipientRequest;
import be.weskey.module.member.store_stock_receipt_mapping.dto.request.StoreStockReceiptMappingRequest;
import lombok.Getter;

@Getter
public class ReceiptRequest {

	private Long storeId;
	private LocalDate pickUpDate;
	private RecipientRequest recipient;
	private List<StoreStockReceiptMappingRequest> orderStoreStocks;
}
