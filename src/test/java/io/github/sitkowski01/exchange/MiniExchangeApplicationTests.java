package io.github.sitkowski01.exchange;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MiniExchangeApplicationTests {

	@Test
	void contextLoads() {
	}

}
