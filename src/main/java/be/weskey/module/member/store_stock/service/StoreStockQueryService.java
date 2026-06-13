// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import be.weskey.module.member.store_stock.entity.StoreStock;

@Service
public class StoreStockQueryService {

	public List<StoreStock> findAllById(List<Long> storeStockIds) {
		throw new UnsupportedOperationException("stub");
	}

	public List<StoreStock> findAllByIdWithLock(List<Long> storeStockIds) {
		throw new UnsupportedOperationException("stub");
	}

	public Map<Long, StoreStock> findAllByIdsWithStoreAndIsDeletedFalseWithLock(List<Long> storeStockIds) {
		throw new UnsupportedOperationException("stub");
	}
}
