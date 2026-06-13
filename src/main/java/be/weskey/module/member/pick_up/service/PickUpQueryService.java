// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.pick_up.service;

import java.util.List;

import org.springframework.stereotype.Service;

import be.weskey.module.member.pick_up.entity.PickUp;

@Service
public class PickUpQueryService {

	public List<PickUp> findAllByReceiptId(Long receiptId) {
		throw new UnsupportedOperationException("stub");
	}
}
