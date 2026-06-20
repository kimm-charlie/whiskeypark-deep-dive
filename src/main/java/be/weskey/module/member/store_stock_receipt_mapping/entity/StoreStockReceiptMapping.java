// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock_receipt_mapping.entity;

import be.weskey.module.member.receipt.entity.Receipt;
import be.weskey.module.member.recipient.entity.Recipient;
import be.weskey.module.member.store_stock.entity.StoreStock;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Getter
public class StoreStockReceiptMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private StoreStock storeStock;
	@ManyToOne
	private Recipient recipient;
	@ManyToOne
	private Receipt receipt;
	private Integer quantity;
}
