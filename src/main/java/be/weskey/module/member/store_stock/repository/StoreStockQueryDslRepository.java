package be.weskey.module.member.store_stock.repository;

import static be.weskey.module.member.store_stock.entity.QStoreStock.*;
import static be.weskey.module.member.store_stock_tag_mapping.entity.QStoreStockTagMapping.*;
import static be.weskey.module.member.whisky_product.entity.QWhiskyProduct.*;
import static be.weskey.module.member.whisky_product_category.entity.QWhiskyProductCategory.*;
import static be.weskey.module.member.whisky_product_category_mapping.entity.QWhiskyProductCategoryMapping.*;
import static be.weskey.module.member.whisky_product_relation.entity.QWhiskyProductRelation.*;
import static be.weskey.module.member.whisky_product_relation.entity.QWhiskyProductRelation.whiskyProductRelation;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import be.weskey.module.member.store_stock.entity.OrderType;
import be.weskey.module.member.store_stock.entity.StoreStock;
import be.weskey.module.member.whisky_product_category.entity.QWhiskyProductCategory;
import be.weskey.module.member.whisky_product_category_mapping.entity.QWhiskyProductCategoryMapping;
import be.weskey.module.member.whisky_product_relation.entity.QWhiskyProductRelation;
import be.weskey.shared.constant.AlcoholType;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StoreStockQueryDslRepository {

	private final JPAQueryFactory queryFactory;

	public Slice<StoreStock> findAllByStoreId(Long storeId, List<Long> categoryIds, Integer orderType,
		Boolean isIncludeOutOfStock, Pageable pageable, Integer alcoholType, List<Long> tagIds) {
		JPAQuery<StoreStock> query = queryFactory
			.select(storeStock)
			.from(storeStock)
			.leftJoin(storeStock.whiskyProduct, whiskyProduct)
			.fetchJoin()
			.where(
				likeStoreId(storeId),
				likeCategoryIds(categoryIds),
				likeTagIds(tagIds),
				likeAlcoholType(alcoholType),
				includingOutOfStock(isIncludeOutOfStock),
				isDeletedFalse()
			)
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize() + 1)
			.orderBy(getOrderSpecifier(orderType));

		List<StoreStock> results = query.fetch();

		boolean hasNext = results.size() > pageable.getPageSize();

		if (hasNext) {
			results.remove(results.size() - 1);
		}

		return new SliceImpl<>(results, pageable, hasNext);
	}

	// 여기도 실제 set 상품이 많아짐면 병목이 일어날수 있다.
	// 지금은 set 이 50 개 수준이라 병목이 없을수 있지만 참고해야한다.
	private Predicate likeAlcoholType(Integer alcoholTypeCode) {
		AlcoholType alcoholType = AlcoholType.from(alcoholTypeCode);
		QWhiskyProductRelation relation = new QWhiskyProductRelation("relation");

		if (!alcoholType.equals(AlcoholType.ALL)) {
			// 직접 매칭 OR SET 상품의 relation base가 해당 alcoholType인 경우
			return storeStock.whiskyProduct.alcoholType.eq(alcoholType.getCode())
				.or(
					storeStock.whiskyProduct.alcoholType.eq(AlcoholType.SET.getCode())
						.and(JPAExpressions.selectOne()
							.from(relation)
							.join(whiskyProduct).on(whiskyProduct.eq(relation.baseProduct))
							.where(
								relation.product.eq(storeStock.whiskyProduct),
								relation.isDeleted.isFalse(),
								relation.baseProduct.isDeleted.isFalse(),
								relation.baseProduct.alcoholType.eq(alcoholType.getCode())
							)
							.exists()
						)
				);
		}
		return null;
	}

	private Predicate includingOutOfStock(Boolean isIncludeOutOfStock) {
		if (isIncludeOutOfStock) {
			return storeStock.stockQuantity.goe(0); // 재고가 0 이상인 경우 (품절 포함)
		} else {
			return storeStock.stockQuantity.gt(0);  // 재고가 0보다 큰 경우 (품절 제외)
		}
	}

	// 일단 카테고리는 2026-06-21 기준 44개
	// 물론 주류 종류별로 걸수있는 카테고리는 나뉘어져 있음
	private Predicate likeCategoryIds(List<Long> categoryIds) {
		if (categoryIds != null && !categoryIds.isEmpty()) {
			BooleanExpression result = null;
			for (Long categoryId : categoryIds) {

				// 직접 카테고리 매칭
				BooleanExpression directMatch = JPAExpressions.selectOne()
					.from(whiskyProductCategoryMapping)
					.join(whiskyProductCategoryMapping.whiskyProductCategory, whiskyProductCategory)
					.where(
						whiskyProductCategory.id.eq(categoryId),
						whiskyProductCategoryMapping.whiskyProduct.eq(storeStock.whiskyProduct)
					)
					.exists();

				// SET 상품의 relation base가 해당 카테고리인 경우
				QWhiskyProductRelation relation = whiskyProductRelation;
				QWhiskyProductCategoryMapping relCatMapping = new QWhiskyProductCategoryMapping("relCatMapping");
				QWhiskyProductCategory relCat = new QWhiskyProductCategory("relCat");

				BooleanExpression setMatch = storeStock.whiskyProduct.alcoholType.eq(AlcoholType.SET.getCode())
					.and(JPAExpressions.selectOne()
						.from(relation)
						.join(relCatMapping).on(relCatMapping.whiskyProduct.eq(relation.baseProduct))
						.join(relCatMapping.whiskyProductCategory, relCat)
						.where(
							relation.product.eq(storeStock.whiskyProduct),
							relation.isDeleted.isFalse(),
							relation.baseProduct.isDeleted.isFalse(),
							relCat.id.eq(categoryId)
						)
						.exists()
					);

				BooleanExpression categoryMatch = directMatch.or(setMatch);
				result = result == null ? categoryMatch : result.and(categoryMatch);
			}
			return result;
		}
		return null;
	}

	// 합집합이 아닌 교집합의 형태로 가야하기 떄문에 for 문을 돌린다.
	private BooleanExpression likeTagIds(List<Long> tagIds) {
		if (tagIds != null && !tagIds.isEmpty()) {
			BooleanExpression result = null;
			for (Long tagId : tagIds) {
				BooleanExpression exists = JPAExpressions.selectOne()
					.from(storeStockTagMapping)
					.where(
						storeStockTagMapping.storeStock.eq(storeStock),
						storeStockTagMapping.stockTag.id.eq(tagId)
					)
					.exists();
				result = result == null ? exists : result.and(exists);
			}
			return result;
		}
		return null;
	}

	private BooleanExpression likeStoreId(Long storeId) {
		return storeStock.store.id.eq(storeId);
	}

	private OrderSpecifier<?> getOrderSpecifier(Integer orderType) {
		return switch (OrderType.from(orderType)) {
			case NEWEST -> storeStock.sortOrder.asc();
			case POPULARITY -> storeStock.whiskyProduct.totalSearchCount.desc();
			case HIGH_TO_LOW -> storeStock.price.desc();
			case LOW_TO_HIGH -> storeStock.price.asc();
		};
	}

	private Predicate isDeletedFalse() {
		return storeStock.isHidden.eq(false).and(whiskyProduct.isDeleted.isFalse());
	}

}
