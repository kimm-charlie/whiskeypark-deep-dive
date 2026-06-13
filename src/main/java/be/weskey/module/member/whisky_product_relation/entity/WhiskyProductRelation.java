// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.whisky_product_relation.entity;

import be.weskey.module.member.whisky_product.entity.WhiskyProduct;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class WhiskyProductRelation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private WhiskyProduct product;
	@ManyToOne
	private WhiskyProduct baseProduct;
	private Boolean isDeleted;
}
