package com.lexibridge.operations.modules.content.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ContentRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findDuplicate(long locationId, String normalizedTerm, String normalizedPhonetic) {
        return jdbcTemplate.query(
            """
            select id from content_item
            where location_id = ?
              and normalized_term = ?
              and normalized_phonetic = ?
            """,
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
            locationId,
            normalizedTerm,
            normalizedPhonetic
        );
    }

    public long createItem(long locationId,
                           String term,
                           String normalizedTerm,
                           String phonetic,
                           String normalizedPhonetic,
                           String category,
                           long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into content_item
                (location_id, term, normalized_term, phonetic, normalized_phonetic, category, status, current_version_no, created_by)
                values (?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setString(2, term);
            ps.setString(3, normalizedTerm);
            ps.setString(4, phonetic);
            ps.setString(5, normalizedPhonetic);
            ps.setString(6, category);
            ps.setLong(7, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertVersion(long itemId,
                              int versionNo,
                              String grammarPoint,
                              String phraseText,
                              String exampleSentence,
                              String definitionText,
                              String metadataJson,
                              long createdBy) {
        jdbcTemplate.update(
            """
            insert into content_item_version
            (content_item_id, version_no, grammar_point, phrase_text, example_sentence, definition_text, metadata_json, created_by)
            values (?, ?, ?, ?, ?, ?, cast(? as json), ?)
            """,
            itemId,
            versionNo,
            grammarPoint,
            phraseText,
            exampleSentence,
            definitionText,
            metadataJson,
            createdBy
        );
    }

    public int currentVersionNo(long contentItemId) {
        Integer version = jdbcTemplate.queryForObject(
            "select current_version_no from content_item where id = ?",
            Integer.class,
            contentItemId
        );
        return version == null ? 0 : version;
    }

    public void setStatus(long itemId, String status) {
        jdbcTemplate.update("update content_item set status = ? where id = ?", status, itemId);
    }

    public void setCurrentVersion(long itemId, int versionNo) {
        jdbcTemplate.update("update content_item set current_version_no = ? where id = ?", versionNo, itemId);
    }

    public int countVersions(long itemId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from content_item_version where content_item_id = ?",
            Integer.class,
            itemId
        );
        return count == null ? 0 : count;
    }

    public void pruneOldestNonCurrentVersion(long itemId, int currentVersionNo) {
        jdbcTemplate.update(
            """
            delete from content_item_version
            where id = (
                select id from (
                    select id
                    from content_item_version
                    where content_item_id = ? and version_no <> ?
                    order by version_no asc
                    limit 1
                ) t
            )
            """,
            itemId,
            currentVersionNo
        );
    }

    public boolean versionExists(long itemId, int versionNo) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from content_item_version where content_item_id = ? and version_no = ?",
            Integer.class,
            itemId,
            versionNo
        );
        return count != null && count > 0;
    }

    public Map<String, Object> summary(Long locationId) {
        String itemWhere = locationId == null ? "" : " and location_id = ?";
        String importWhere = locationId == null ? "" : " and location_id = ?";
        Integer draftCount = jdbcTemplate.queryForObject(
            "select count(*) from content_item where status = 'DRAFT'" + itemWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer publishedCount = jdbcTemplate.queryForObject(
            "select count(*) from content_item where status = 'PUBLISHED'" + itemWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        Integer pendingImports = jdbcTemplate.queryForObject(
            "select count(*) from content_import_job where status in ('UPLOADED','REVIEWING')" + importWhere,
            Integer.class,
            locationId == null ? new Object[]{} : new Object[]{locationId}
        );
        return Map.of(
            "draftCount", draftCount == null ? 0 : draftCount,
            "publishedCount", publishedCount == null ? 0 : publishedCount,
            "pendingImports", pendingImports == null ? 0 : pendingImports
        );
    }

    public long createImportJob(long locationId, long uploadedBy, String filename, String format, String status, String summaryJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                insert into content_import_job
                (location_id, uploaded_by, source_filename, source_format, status, summary_json)
                values (?, ?, ?, ?, ?, cast(? as json))
                """,
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, locationId);
            ps.setLong(2, uploadedBy);
            ps.setString(3, filename);
            ps.setString(4, format);
            ps.setString(5, status);
            ps.setString(6, summaryJson);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void createImportRowResult(long jobId,
                                      int rowNo,
                                      String actionTaken,
                                      String status,
                                      String errorCode,
                                      String errorMessage,
                                      Long duplicateItemId) {
        jdbcTemplate.update(
            """
            insert into content_import_row_result
            (job_id, row_no, action_taken, status, error_code, error_message, duplicate_content_item_id)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            jobId,
            rowNo,
            actionTaken,
            status,
            errorCode,
            errorMessage,
            duplicateItemId
        );
    }

    public List<Map<String, Object>> importRows(long jobId) {
        return jdbcTemplate.queryForList(
            """
            select row_no, action_taken, status, duplicate_content_item_id
            from content_import_row_result
            where job_id = ?
            order by row_no asc
            """,
            jobId
        );
    }

    public List<Map<String, Object>> importRowsForReport(long jobId) {
        return jdbcTemplate.queryForList(
            """
            select row_no, action_taken, status, error_code, error_message, duplicate_content_item_id
            from content_import_row_result
            where job_id = ?
              and (status = 'INVALID' or status like 'DUPLICATE%')
            order by row_no asc
            """,
            jobId
        );
    }

    public List<Map<String, Object>> exportRows(long locationId) {
        return jdbcTemplate.queryForList(
            """
            select ci.id,
                   ci.location_id,
                   ci.term,
                   ci.phonetic,
                   ci.category,
                   ci.status,
                   ci.current_version_no,
                   civ.grammar_point,
                   civ.phrase_text,
                   civ.example_sentence,
                   civ.definition_text,
                   civ.metadata_json,
                   ci.created_at,
                   ci.updated_at
            from content_item ci
            join content_item_version civ
              on civ.content_item_id = ci.id and civ.version_no = ci.current_version_no
            where ci.location_id = ?
            order by ci.id asc
            """,
            locationId
        );
    }

    public void updateImportRowResult(long jobId, int rowNo, String actionTaken, String status, String errorCode, String errorMessage) {
        jdbcTemplate.update(
            """
            update content_import_row_result
            set action_taken = ?, status = ?, error_code = ?, error_message = ?
            where job_id = ? and row_no = ?
            """,
            actionTaken,
            status,
            errorCode,
            errorMessage,
            jobId,
            rowNo
        );
    }

    public void updateImportJobStatus(long jobId, String status, String summaryJson) {
        jdbcTemplate.update(
            "update content_import_job set status = ?, summary_json = cast(? as json) where id = ?",
            status,
            summaryJson,
            jobId
        );
    }

    public boolean checksumExists(String sha256) {
        Integer count = jdbcTemplate.queryForObject(
            """
            select (
                (select count(*) from binary_storage where checksum_sha256 = ?)
                +
                (select count(*) from content_media where checksum_sha256 = ?)
                +
                (select count(*) from booking_attachment where checksum_sha256 = ?)
                +
                (select count(*) from leave_request_attachment where checksum_sha256 = ?)
                +
                (select count(*) from moderation_case_media where checksum_sha256 = ?)
            )
            """,
            Integer.class,
            sha256,
            sha256,
            sha256,
            sha256,
            sha256
        );
        return count != null && count > 0;
    }

    public List<Map<String, Object>> recentItems(long locationId, int limit) {
        return jdbcTemplate.queryForList(
            """
            select ci.id,
                   ci.location_id,
                   ci.term,
                   ci.phonetic,
                   ci.category,
                   ci.status,
                   ci.current_version_no,
                   civ.definition_text,
                   civ.example_sentence,
                   ci.updated_at
            from content_item ci
            join content_item_version civ
              on civ.content_item_id = ci.id and civ.version_no = ci.current_version_no
            where ci.location_id = ?
            order by ci.updated_at desc
            limit ?
            """,
            locationId,
            limit
        );
    }

    public List<Map<String, Object>> versions(long contentItemId) {
        return jdbcTemplate.queryForList(
            """
            select version_no, grammar_point, phrase_text, example_sentence, definition_text, metadata_json, created_at
            from content_item_version
            where content_item_id = ?
            order by version_no desc
            """,
            contentItemId
        );
    }

    public void insertMedia(long contentItemId,
                            String mediaType,
                            String storagePath,
                            String mimeType,
                            long fileSize,
                            String checksum) {
        jdbcTemplate.update(
            """
            insert into content_media
            (content_item_id, media_type, storage_path, mime_type, file_size_bytes, checksum_sha256)
            values (?, ?, ?, ?, ?, ?)
            """,
            contentItemId,
            mediaType,
            storagePath,
            mimeType,
            fileSize,
            checksum
        );
    }

    public List<Map<String, Object>> mediaForItem(long contentItemId) {
        return jdbcTemplate.queryForList(
            """
            select id, media_type, storage_path, mime_type, file_size_bytes, checksum_sha256, created_at
            from content_media
            where content_item_id = ?
            order by id desc
            """,
            contentItemId
        );
    }

    public Map<String, Object> mediaById(long mediaId) {
        return jdbcTemplate.query(
            """
            select id, content_item_id, media_type, storage_path, mime_type, file_size_bytes, checksum_sha256, created_at
            from content_media
            where id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getLong("id"),
                    "content_item_id", rs.getLong("content_item_id"),
                    "media_type", rs.getString("media_type"),
                    "storage_path", rs.getString("storage_path"),
                    "mime_type", rs.getString("mime_type"),
                    "file_size_bytes", rs.getLong("file_size_bytes"),
                    "checksum_sha256", rs.getString("checksum_sha256"),
                    "created_at", rs.getObject("created_at")
                );
            },
            mediaId
        );
    }
}
