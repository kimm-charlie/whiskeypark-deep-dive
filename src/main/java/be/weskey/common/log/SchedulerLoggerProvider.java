// [stub] IDE 탐색·컴파일용 최소 스텁 — 운영 코드 아님
package be.weskey.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SchedulerLoggerProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger("scheduler");

	public Logger getLogger() {
		return LOGGER;
	}
}
