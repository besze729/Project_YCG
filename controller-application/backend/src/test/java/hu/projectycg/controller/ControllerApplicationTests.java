package hu.projectycg.controller;

import static org.assertj.core.api.Assertions.assertThat;

import hu.projectycg.controller.video.VideoProcessingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ControllerApplicationTests {
	@Autowired VideoProcessingProperties videoProperties;

	@Test
	void contextLoads() {
		assertThat(videoProperties.samplingIntervalSeconds()).isEqualByComparingTo("30");
	}

}
