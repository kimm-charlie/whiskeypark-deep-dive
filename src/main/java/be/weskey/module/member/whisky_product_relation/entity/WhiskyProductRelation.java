// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.whisky_product_relation.entity;

import be.weskey.module.member.whisky_product.entity.WhiskyProduct;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(indexes = {
	@Index(name = "idx_wpr_product_deleted_base",
		columnList = "product_id, is_deleted, base_product_id")
})
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
