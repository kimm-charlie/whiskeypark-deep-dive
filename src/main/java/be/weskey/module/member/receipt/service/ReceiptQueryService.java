// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.receipt.service;

import org.springframework.stereotype.Service;

import be.weskey.module.member.receipt.entity.Receipt;

@Service
public class ReceiptQueryService {

	public Receipt findById(Long receiptId) {
		throw new UnsupportedOperationException("stub");
	}

	public Receipt findByPaymentInfoId(Long paymentInfoId) {
		throw new UnsupportedOperationException("stub");
	}
}
