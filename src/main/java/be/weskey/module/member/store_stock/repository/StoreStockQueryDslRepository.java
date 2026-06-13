package be.weskey.module.member.store_stock.repository;

import static be.weskey.module.member.store_stock.entity.QStoreStock.*;
import static be.weskey.module.member.whisky_product.entity.QWhiskyProduct.*;

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
import be.weskey.module.member.store_stock_tag_mapping.entity.QStoreStockTagMapping;
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

	private Predicate likeAlcoholType(Integer alcoholTypeCode) {
		AlcoholType alcoholType = AlcoholType.from(alcoholTypeCode);
		if (!alcoholType.equals(AlcoholType.ALL)) {
			QWhiskyProductRelation relation = QWhiskyProductRelation.whiskyProductRelation;
			// 직접 매칭 OR SET 상품의 relation base가 해당 alcoholType인 경우
			return storeStock.whiskyProduct.alcoholType.eq(alcoholType.getCode())
				.or(
					storeStock.whiskyProduct.alcoholType.eq(AlcoholType.SET.getCode())
						.and(JPAExpressions.selectOne()
							.from(relation)
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

	private Predicate likeCategoryIds(List<Long> categoryIds) {
		if (categoryIds != null && !categoryIds.isEmpty()) {
			BooleanExpression result = null;
			for (Long categoryId : categoryIds) {
				QWhiskyProductCategoryMapping subCatMapping = new QWhiskyProductCategoryMapping("subCatMapping");
				QWhiskyProductCategory subCat = new QWhiskyProductCategory("subCat");

				// 직접 카테고리 매칭
				BooleanExpression directMatch = JPAExpressions.selectOne()
					.from(subCatMapping)
					.join(subCatMapping.whiskyProductCategory, subCat)
					.where(
						subCatMapping.whiskyProduct.eq(storeStock.whiskyProduct),
						subCat.id.eq(categoryId)
					)
					.exists();

				// SET 상품의 relation base가 해당 카테고리인 경우
				QWhiskyProductRelation relation = QWhiskyProductRelation.whiskyProductRelation;
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

	private Predicate likeTagIds(List<Long> tagIds) {
		if (tagIds != null && !tagIds.isEmpty()) {
			QStoreStockTagMapping subMapping = new QStoreStockTagMapping("subTagMapping");
			BooleanExpression result = null;
			for (Long tagId : tagIds) {
				BooleanExpression exists = JPAExpressions.selectOne()
					.from(subMapping)
					.where(
						subMapping.storeStock.eq(storeStock),
						subMapping.stockTag.id.eq(tagId)
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
