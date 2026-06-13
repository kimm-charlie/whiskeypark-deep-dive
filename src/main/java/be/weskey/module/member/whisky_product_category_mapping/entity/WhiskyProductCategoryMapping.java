// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.whisky_product_category_mapping.entity;

import be.weskey.module.member.whisky_product.entity.WhiskyProduct;
import be.weskey.module.member.whisky_product_category.entity.WhiskyProductCategory;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class WhiskyProductCategoryMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private WhiskyProduct whiskyProduct;
	@ManyToOne
	private WhiskyProductCategory whiskyProductCategory;
}
