package com.ben.services;

import com.ben.services.support.IntegrationTest;
import org.junit.jupiter.api.Test;

// Starts the whole service on a real database: Flyway migrations run and Hibernate validates the schema
class SeatServiceApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

}
