// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.store_stock.entity;

import be.weskey.module.member.store.entity.Store;
import be.weskey.module.member.whisky_product.entity.WhiskyProduct;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class StoreStock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private Store store;
	@ManyToOne
	private WhiskyProduct whiskyProduct;
	private Integer stockQuantity;
	private Long price;
	private Integer sortOrder;
	private Boolean isHidden;

	public void increaseQuantity(Integer quantity) {
		this.stockQuantity = this.stockQuantity + quantity;
	}

	public void decreaseQuantity(Integer quantity) {
		this.stockQuantity = this.stockQuantity - quantity;
	}
}
