// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock_receipt_mapping.service;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.module.member.store_stock_receipt_mapping.entity.StoreStockReceiptMapping;

@Service
public class StoreStockReceiptMappingQueryService {

	public List<StoreStockReceiptMapping> findAllByReceiptIdWithLock(Long receiptId) {
		throw new UnsupportedOperationException("stub");
	}
}
