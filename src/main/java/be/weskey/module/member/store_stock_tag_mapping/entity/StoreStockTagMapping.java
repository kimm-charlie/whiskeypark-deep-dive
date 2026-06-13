// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock_tag_mapping.entity;

import be.weskey.module.member.stock_tag.entity.StockTag;
import be.weskey.module.member.store_stock.entity.StoreStock;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class StoreStockTagMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private StoreStock storeStock;
	@ManyToOne
	private StockTag stockTag;
}
