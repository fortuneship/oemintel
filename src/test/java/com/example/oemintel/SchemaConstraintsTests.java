package com.example.oemintel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 2 acceptance: the natural-key and immutability rules hold at the database level. */
@SpringBootTest(properties = "oemintel.engine.enabled=false")
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTests {

	@Autowired
	JdbcClient jdbc;

	long oemId;

	long familyId;

	@BeforeEach
	void createFamily() {
		this.oemId = this.jdbc.sql("select id from oem where code = 'ROXTEC'").query(Long.class).single();
		this.familyId = insertFamily("frame-s");
	}

	@Test
	void duplicateNormalisedSkuIsRejected() {
		insertVariant(this.familyId, "RM 20");
		long otherFamily = insertFamily("frame-g");
		assertThatThrownBy(() -> insertVariant(otherFamily, "RM20")).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void onlyOneCurrentRevisionPerFamily() {
		insertRevision(1, "CURRENT");
		insertRevision(2, "SUPERSEDED");
		assertThatThrownBy(() -> insertRevision(3, "CURRENT")).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void onlyOneActiveOverridePerAttribute() {
		insertOverride(true);
		insertOverride(false);
		assertThatThrownBy(() -> insertOverride(true)).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void approvalSnapshotCannotBeChangedOrDeleted() {
		long revisionId = insertRevision(1, "CURRENT");
		long eventId = this.jdbc.sql("""
				insert into review_event (family_id, from_status, to_status, actor)
				values (:familyId, 'IN_REVIEW', 'APPROVED', 'tester') returning id
				""").param("familyId", this.familyId).query(Long.class).single();
		long snapshotId = this.jdbc.sql("""
				insert into approval_snapshot (family_id, review_event_id, revision_id, family_version, snapshot, snapshot_hash)
				values (:familyId, :eventId, :revisionId, 0, '{}'::jsonb, repeat('a', 64)) returning id
				""")
			.param("familyId", this.familyId)
			.param("eventId", eventId)
			.param("revisionId", revisionId)
			.query(Long.class)
			.single();
		assertThatThrownBy(() -> this.jdbc.sql("update approval_snapshot set family_version = 1 where id = :id")
			.param("id", snapshotId)
			.update()).isInstanceOf(UncategorizedSQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void relationNeedsATarget() {
		long revisionId = insertRevision(1, "CURRENT");
		assertThatThrownBy(() -> this.jdbc.sql("""
				insert into product_relation (revision_id, from_family_id, relation_type)
				values (:revisionId, :familyId, 'ACCESSORY')
				""").param("revisionId", revisionId).param("familyId", this.familyId).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertFamily(String key) {
		return this.jdbc.sql("""
				insert into product_family (oem_id, locale, family_key) values (:oemId, 'en', :key) returning id
				""").param("oemId", this.oemId).param("key", key).query(Long.class).single();
	}

	private void insertVariant(long familyId, String sku) {
		this.jdbc.sql("""
				insert into product_variant (family_id, oem_id, locale, sku_display, sku_normalized)
				values (:familyId, :oemId, 'en', :sku, upper(regexp_replace(:sku, '[^A-Za-z0-9]', '', 'g')))
				""").param("familyId", familyId).param("oemId", this.oemId).param("sku", sku).update();
	}

	private long insertRevision(int number, String status) {
		return this.jdbc.sql("""
				insert into extraction_revision (family_id, revision_number, status, cause)
				values (:familyId, :number, :status, 'CRAWL') returning id
				""").param("familyId", this.familyId).param("number", number).param("status", status)
			.query(Long.class)
			.single();
	}

	private void insertOverride(boolean active) {
		this.jdbc.sql("""
				insert into field_override (family_id, attribute_definition_id, action, value_text, active, created_by)
				values (:familyId, (select id from attribute_definition where code = 'name'), 'SET', 'Frame S', :active,
				        'tester')
				""").param("familyId", this.familyId).param("active", active).update();
	}

}
