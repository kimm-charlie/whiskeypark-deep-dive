// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock_receipt_mapping.dto.request;

import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.recipient.entity.Recipient;
import be.weskey.module.member.store_stock.entity.StoreStock;
import be.weskey.module.member.store_stock_receipt_mapping.entity.StoreStockReceiptMapping;
import lombok.Getter;

@Getter
public class StoreStockReceiptMappingRequest {

	private Long id;
	private Integer quantity;
	private Long price;
	private Long shoppingCartId;

	public StoreStockReceiptMapping toStoreStockReceiptMappingEntity(StoreStock storeStock, Receipt receipt,
		Recipient recipient) {
		throw new UnsupportedOperationException("stub");
	}

	public StoreStockReceiptMapping toReservationStoreStockReceiptMappingEntity(StoreStock storeStock, Receipt receipt,
		Recipient recipient) {
		throw new UnsupportedOperationException("stub");
	}
}
