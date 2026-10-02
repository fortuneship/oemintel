package com.example.oemintel.oem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OemRepository {

	private final JdbcClient jdbc;

	public OemRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Oem> findByCode(String code) {
		return this.jdbc.sql("select * from oem where code = :code")
			.param("code", code)
			.query(OemRepository::map)
			.optional();
	}

	public Optional<Oem> findById(long id) {
		return this.jdbc.sql("select * from oem where id = :id").param("id", id).query(OemRepository::map).optional();
	}

	private static Oem map(ResultSet rs, int row) throws SQLException {
		return new Oem(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("base_url"),
				rs.getString("adapter_key"), rs.getString("default_locale"),
				Oem.CrawlAuthStatus.valueOf(rs.getString("crawl_auth_status")), rs.getBoolean("active"));
	}

}
