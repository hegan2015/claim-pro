-- 003 材料文件本体：claim_material 补列（存量库一次性执行；新环境 001_schema.sql 已含）。
-- 仅登记材料三列为 NULL；真实上传材料 sha256 为文件内容哈希，size_bytes 为正。
USE claim;
ALTER TABLE claim_material
    ADD COLUMN file_name VARCHAR(256) NULL,
    ADD COLUMN content_type VARCHAR(128) NULL,
    ADD COLUMN size_bytes BIGINT NULL;