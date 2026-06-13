// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.module.member.member_image.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import be.weskey.module.member.member_image.entity.MemberImage;

public interface MemberImageRepository extends JpaRepository<MemberImage, Long> {
}
