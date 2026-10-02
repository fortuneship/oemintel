package com.example.oemintel;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "oemintel.engine.enabled=false")
@Import(TestcontainersConfiguration.class)
class OemintelApplicationTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void migrationsCreateAllFourLayersAndSeedRoxtec() {
		int tables = this.jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public'")
			.query(Integer.class)
			.single();
		assertThat(tables).isGreaterThanOrEqualTo(35);
		assertThat(this.jdbc.sql("select crawl_auth_status from oem where code = 'ROXTEC'").query(String.class).single())
			.isEqualTo("PENDING");
	}

}
