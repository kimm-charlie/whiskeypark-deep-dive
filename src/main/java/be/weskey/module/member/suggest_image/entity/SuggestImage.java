// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.suggest_image.entity;

import be.weskey.module.member.member.entity.Member;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

@Entity
@Getter
public class SuggestImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne
	private Member member;
	private String cdnUrl;
	private String originUrl;
	private String fileKey;
	private Long fileSize;

	public static SuggestImage ofPresigned(String cdnUrl, String originUrl, String fileKey, Long fileSize, Member member) {
		return new SuggestImage();
	}
}
