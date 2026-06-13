// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.pick_up.entity;

import java.time.LocalDate;

import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.store.entity.Store;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class PickUp {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private Store store;
	@ManyToOne
	private Receipt receipt;
	private LocalDate pickUpDate;

	public static PickUp of(Store store, Receipt receipt, LocalDate pickUpDate) {
		return new PickUp();
	}
}
