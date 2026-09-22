-- 来源：官方 v0.2-beta APK 空白专用模拟器；仅 schema 与 Room identity，无用户数据。
CREATE TABLE `agents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `name_customized` INTEGER NOT NULL, `description_customized` INTEGER NOT NULL, `system_prompt` TEXT NOT NULL, `model` TEXT NOT NULL, `icon` TEXT NOT NULL, `color` TEXT NOT NULL, `avatar_path` TEXT, `is_pinned` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `temperature` REAL, `top_p` REAL, `max_tokens` INTEGER, `rag_config` TEXT, `retrieval_config` TEXT, `use_inherited_config` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `artifacts` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `preview_image` TEXT, `session_id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `workspace_path` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `tags` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `attachments` (`id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `type` TEXT NOT NULL, `uri` TEXT NOT NULL, `local_uri` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `audit_logs` (`id` TEXT NOT NULL, `action` TEXT NOT NULL, `resource_type` TEXT NOT NULL, `resource_path` TEXT, `session_id` TEXT, `agent_id` TEXT, `skill_id` TEXT, `status` TEXT NOT NULL, `error_message` TEXT, `metadata` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `context_summaries` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `start_message_id` TEXT NOT NULL, `end_message_id` TEXT NOT NULL, `summary_content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `token_usage` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `custom_skills` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `parametersSchema` TEXT NOT NULL, `code` TEXT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `document_tags` (`doc_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`doc_id`, `tag_id`), FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `file_versions` (`id` TEXT NOT NULL, `file_uuid` TEXT NOT NULL, `workspace_root_uuid` TEXT NOT NULL, `hash` TEXT NOT NULL, `content_path` TEXT NOT NULL, `created_by_session_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `kg_edges` (`id` TEXT NOT NULL, `source_id` TEXT NOT NULL, `target_id` TEXT NOT NULL, `relation` TEXT NOT NULL, `weight` REAL NOT NULL, `doc_id` TEXT, `session_id` TEXT, `agent_id` TEXT, `source_type` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `stale` INTEGER NOT NULL DEFAULT 0, `file_uuid` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`source_id`) REFERENCES `kg_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`target_id`) REFERENCES `kg_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `kg_jit_cache` (`cache_key` TEXT NOT NULL, `query_hash` TEXT NOT NULL, `chunk_ids_hash` TEXT NOT NULL, `result_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, PRIMARY KEY(`cache_key`));

CREATE TABLE `kg_nodes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `metadata` TEXT, `session_id` TEXT, `agent_id` TEXT, `source_type` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER, `stale` INTEGER NOT NULL DEFAULT 0, `file_uuid` TEXT, PRIMARY KEY(`id`));

CREATE TABLE `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `callIntervalMs` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `messages` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `model_id` TEXT, `status` TEXT, `reasoning` TEXT, `thought_signature` TEXT, `images` TEXT, `tokens` TEXT, `citations` TEXT, `rag_references` TEXT, `kg_paths` TEXT, `rag_progress` TEXT, `rag_metadata` TEXT, `rag_references_loading` INTEGER NOT NULL DEFAULT 0, `execution_steps` TEXT, `tool_calls` TEXT, `pending_approval_tool_ids` TEXT, `tool_call_id` TEXT, `parent_message_id` TEXT, `name` TEXT, `planning_task` TEXT, `is_archived` INTEGER NOT NULL DEFAULT 0, `vectorization_status` TEXT, `layout_height` REAL, `tool_results` TEXT, `files` TEXT, `user_images` TEXT, `is_error` INTEGER NOT NULL DEFAULT 0, `error_message` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`session_id`, `parent_message_id`) REFERENCES `messages`(`session_id`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);

CREATE TABLE `sessions` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, `title` TEXT NOT NULL, `last_message` TEXT, `time` TEXT, `unread` INTEGER NOT NULL, `model_id` TEXT, `custom_prompt` TEXT, `is_pinned` INTEGER NOT NULL, `scroll_offset` REAL, `draft` TEXT, `execution_mode` TEXT NOT NULL DEFAULT 'auto', `loop_status` TEXT NOT NULL DEFAULT 'idle', `pending_intervention` TEXT, `approval_request` TEXT, `rag_options` TEXT, `inference_params` TEXT, `active_task` TEXT, `stats` TEXT, `options` TEXT, `active_mcp_server_ids` TEXT, `active_skill_ids` TEXT, `workspace_path` TEXT, `workspace_root_uuid` TEXT, `active_task_tree_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`));

CREATE TABLE `task_nodes` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `parent_id` TEXT, `sort_order` INTEGER NOT NULL DEFAULT 0, `title` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL DEFAULT 'pending', `note` TEXT, `artifact_file_uuids` TEXT, `is_collapsed` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `tool_execution_ledger` (`session_id` TEXT NOT NULL, `assistant_message_id` TEXT NOT NULL, `tool_call_id` TEXT NOT NULL, `tool_name` TEXT NOT NULL, `requires_approval` INTEGER NOT NULL, `status` TEXT NOT NULL, `result_message_id` TEXT, `error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`session_id`, `assistant_message_id`, `tool_call_id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`session_id`, `assistant_message_id`) REFERENCES `messages`(`session_id`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `vectorization_tasks` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `doc_id` TEXT, `doc_title` TEXT, `workspace_root_uuid` TEXT, `session_id` TEXT, `user_content` TEXT, `ai_content` TEXT, `user_message_id` TEXT, `assistant_message_id` TEXT, `last_chunk_index` INTEGER NOT NULL, `total_chunks` INTEGER, `progress` REAL NOT NULL, `error` TEXT, `kg_strategy` TEXT, `skip_vectorization` INTEGER NOT NULL, `sub_status` TEXT, `source_mime_type` TEXT, `content_truncated` INTEGER NOT NULL, `target_content_hash` TEXT, `target_epoch` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`workspace_root_uuid`, `doc_id`) REFERENCES `workspace_files`(`workspace_root_uuid`, `uuid`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE TABLE `vectors` (`id` TEXT NOT NULL, `doc_id` TEXT, `session_id` TEXT, `content` TEXT NOT NULL, `embedding` BLOB NOT NULL, `metadata` TEXT, `start_message_id` TEXT, `end_message_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER, `stale` INTEGER NOT NULL DEFAULT 0, `version` INTEGER NOT NULL DEFAULT 1, `file_uuid` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );

CREATE VIRTUAL TABLE `vectors_fts` USING FTS4(`content` TEXT NOT NULL, content=`vectors`);

CREATE TABLE `workspace_files` (`uuid` TEXT NOT NULL, `workspace_root_uuid` TEXT NOT NULL, `parent_uuid` TEXT, `name` TEXT NOT NULL, `hash` TEXT NOT NULL, `mime_type` TEXT, `size_bytes` INTEGER NOT NULL, `is_directory` INTEGER NOT NULL, `physical_root_path` TEXT NOT NULL, `materialized_path` TEXT NOT NULL, `vectorized_at` INTEGER, `vector_version` INTEGER NOT NULL, `kg_extracted_at` INTEGER, `kg_version` INTEGER NOT NULL, `last_write_session_id` TEXT, `locked_by_session_id` TEXT, `lock_expires_at` INTEGER, `in_recycle_bin` INTEGER NOT NULL, `recycled_at` INTEGER, `original_parent_uuid` TEXT, `original_materialized_path` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`));

CREATE TABLE `workspace_seq` (`date_key` TEXT NOT NULL, `last_seq` INTEGER NOT NULL, PRIMARY KEY(`date_key`));

CREATE INDEX `index_artifacts_created_at` ON `artifacts` (`created_at`);

CREATE INDEX `index_artifacts_session_id` ON `artifacts` (`session_id`);

CREATE INDEX `index_artifacts_type` ON `artifacts` (`type`);

CREATE INDEX `index_attachments_message_id` ON `attachments` (`message_id`);

CREATE INDEX `index_audit_logs_action` ON `audit_logs` (`action`);

CREATE INDEX `index_audit_logs_created_at` ON `audit_logs` (`created_at`);

CREATE INDEX `index_audit_logs_session_id` ON `audit_logs` (`session_id`);

CREATE INDEX `index_context_summaries_session_id` ON `context_summaries` (`session_id`);

CREATE INDEX `index_document_tags_tag_id` ON `document_tags` (`tag_id`);

CREATE INDEX `index_file_versions_file_uuid` ON `file_versions` (`file_uuid`);

CREATE INDEX `index_file_versions_workspace_root_uuid` ON `file_versions` (`workspace_root_uuid`);

CREATE INDEX `index_kg_edges_doc_id` ON `kg_edges` (`doc_id`);

CREATE INDEX `index_kg_edges_source_id` ON `kg_edges` (`source_id`);

CREATE INDEX `index_kg_edges_target_id` ON `kg_edges` (`target_id`);

CREATE INDEX `index_kg_jit_cache_expires_at` ON `kg_jit_cache` (`expires_at`);

CREATE INDEX `index_messages_session_id` ON `messages` (`session_id`);

CREATE INDEX `index_messages_session_id_created_at` ON `messages` (`session_id`, `created_at`);

CREATE UNIQUE INDEX `index_messages_session_id_id` ON `messages` (`session_id`, `id`);

CREATE INDEX `index_messages_session_id_parent_message_id` ON `messages` (`session_id`, `parent_message_id`);

CREATE INDEX `index_task_nodes_parent_id` ON `task_nodes` (`parent_id`);

CREATE INDEX `index_task_nodes_session_id` ON `task_nodes` (`session_id`);

CREATE INDEX `index_task_nodes_status` ON `task_nodes` (`status`);

CREATE INDEX `index_tool_execution_ledger_session_id` ON `tool_execution_ledger` (`session_id`);

CREATE INDEX `index_tool_execution_ledger_session_id_assistant_message_id` ON `tool_execution_ledger` (`session_id`, `assistant_message_id`);

CREATE INDEX `index_vectorization_tasks_doc_id` ON `vectorization_tasks` (`doc_id`);

CREATE INDEX `index_vectorization_tasks_session_id` ON `vectorization_tasks` (`session_id`);

CREATE INDEX `index_vectorization_tasks_status` ON `vectorization_tasks` (`status`);

CREATE INDEX `index_vectorization_tasks_workspace_root_uuid_doc_id` ON `vectorization_tasks` (`workspace_root_uuid`, `doc_id`);

CREATE UNIQUE INDEX `index_vectorization_tasks_workspace_root_uuid_doc_id_type` ON `vectorization_tasks` (`workspace_root_uuid`, `doc_id`, `type`);

CREATE INDEX `index_vectors_doc_id` ON `vectors` (`doc_id`);

CREATE INDEX `index_vectors_session_id` ON `vectors` (`session_id`);

CREATE INDEX `index_workspace_files_hash` ON `workspace_files` (`hash`);

CREATE INDEX `index_workspace_files_in_recycle_bin_physical_root_path_recycled_at` ON `workspace_files` (`in_recycle_bin`, `physical_root_path`, `recycled_at`);

CREATE INDEX `index_workspace_files_is_directory` ON `workspace_files` (`is_directory`);

CREATE INDEX `index_workspace_files_materialized_path` ON `workspace_files` (`materialized_path`);

CREATE INDEX `index_workspace_files_parent_uuid` ON `workspace_files` (`parent_uuid`);

CREATE UNIQUE INDEX `index_workspace_files_workspace_root_uuid_uuid` ON `workspace_files` (`workspace_root_uuid`, `uuid`);

CREATE TRIGGER room_fts_content_sync_vectors_fts_AFTER_INSERT AFTER INSERT ON `vectors` BEGIN INSERT INTO `vectors_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END;

CREATE TRIGGER room_fts_content_sync_vectors_fts_AFTER_UPDATE AFTER UPDATE ON `vectors` BEGIN INSERT INTO `vectors_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END;

CREATE TRIGGER room_fts_content_sync_vectors_fts_BEFORE_DELETE BEFORE DELETE ON `vectors` BEGIN DELETE FROM `vectors_fts` WHERE `docid`=OLD.`rowid`; END;

CREATE TRIGGER room_fts_content_sync_vectors_fts_BEFORE_UPDATE BEFORE UPDATE ON `vectors` BEGIN DELETE FROM `vectors_fts` WHERE `docid`=OLD.`rowid`; END;

INSERT INTO room_master_table(id,identity_hash) VALUES(42,'7777303c63145d5bbb9b161b38f94495');

PRAGMA user_version = 2;
