-- ==========================================
-- DDL: TABLE CREATION SCRIPTS
-- ==========================================

-- 1. Create the main Users table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL
);

-- 2. Create the User Roles mapping table (One-to-Many / Many-to-Many conceptual)
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. Create the User Departments mapping table (One-to-Many / Many-to-Many conceptual)
CREATE TABLE IF NOT EXISTS user_departments (
    user_id BIGINT NOT NULL,
    department VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 4. Create the DB Connections table
CREATE TABLE IF NOT EXISTS db_connections (
    id BIGSERIAL PRIMARY KEY,
    connection_name VARCHAR(255) NOT NULL,
    environment VARCHAR(50) NOT NULL,
    department VARCHAR(50) NOT NULL,
    dburi VARCHAR(500) NOT NULL,
    username VARCHAR(255),
    password VARCHAR(255),
    type_of_database VARCHAR(50) NOT NULL
);

-- 5. Create the Job Requests table
CREATE TABLE IF NOT EXISTS job_requests (
    id BIGSERIAL PRIMARY KEY,
    migration_name VARCHAR(255) NOT NULL,
    job_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    comments TEXT,
    submitted_by VARCHAR(255),
    created_at BIGINT NOT NULL,
    config_details TEXT
);

-- 6. Create the COS Connections table
CREATE TABLE IF NOT EXISTS cos_connections (
    id BIGSERIAL PRIMARY KEY,
    cos_name VARCHAR(255) NOT NULL,
    storage_type VARCHAR(50),
    storage_location VARCHAR(500),
    location VARCHAR(255),
    apikey VARCHAR(255),
    service_instance_id VARCHAR(255),
    accesskey VARCHAR(255),
    secretkey VARCHAR(255),
    bucketurl VARCHAR(500),
    bucket_id VARCHAR(255),
    bucket_name VARCHAR(255),
    authendication_type VARCHAR(50),
    department_id VARCHAR(50) NOT NULL,
    env_type VARCHAR(50) NOT NULL,
    created_by VARCHAR(255),
    created_at BIGINT
);

-- 7. Create Migration Job Tracking table
CREATE TABLE IF NOT EXISTS migration_job (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(255) NOT NULL UNIQUE,
    project_id VARCHAR(255),
    source_db_type VARCHAR(50),
    target_db_type VARCHAR(50),
    job_status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 8. Create Mongo Migration Details Tracking table
CREATE TABLE IF NOT EXISTS mongo_migration_details (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(255) NOT NULL,
    collection_name VARCHAR(255) NOT NULL,
    source_count BIGINT,
    migrated_count BIGINT,
    failed_count BIGINT,
    status VARCHAR(50),
    remarks TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

-- ==========================================
-- DML: SAMPLE DATA INSERTION SCRIPTS
-- ==========================================
-- NOTE: Passwords must be encoded using BCrypt.
-- The hash below for 'admin123' is: $2a$10$wE.6C.K5R7Vw2k7hX.4p8eLw9Ym4q/N7V2X.y9R/pT8wP.E6M.3Oq
-- The hash below for 'pass123' is: $2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq

-- 1. Insert Admin User (Access to ALL departments)
INSERT INTO users (id, username, password) VALUES (1, 'admin', '$2a$10$wE.6C.K5R7Vw2k7hX.4p8eLw9Ym4q/N7V2X.y9R/pT8wP.E6M.3Oq') ON CONFLICT (id) DO NOTHING;
INSERT INTO user_roles (user_id, role) VALUES (1, 'ADMIN');
INSERT INTO user_departments (user_id, department) VALUES (1, 'ALL');

-- 2. Insert Finance Scheduler
INSERT INTO users (id, username, password) VALUES (2, 'finance_sched', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq') ON CONFLICT (id) DO NOTHING;
INSERT INTO user_roles (user_id, role) VALUES (2, 'SCHEDULER');
INSERT INTO user_departments (user_id, department) VALUES (2, 'Finance');

-- 3. Insert Finance Approver
INSERT INTO users (id, username, password) VALUES (3, 'finance_appr', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq') ON CONFLICT (id) DO NOTHING;
INSERT INTO user_roles (user_id, role) VALUES (3, 'APPROVER');
INSERT INTO user_departments (user_id, department) VALUES (3, 'Finance');

-- 4. Insert Multi-Department Approver (Demonstrating multiple roles and departments)
INSERT INTO users (id, username, password) VALUES (4, 'manager_appr', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq') ON CONFLICT (id) DO NOTHING;
INSERT INTO user_roles (user_id, role) VALUES (4, 'MANAGER');
INSERT INTO user_departments (user_id, department) VALUES (4, 'Finance');
INSERT INTO user_departments (user_id, department) VALUES (4, 'HR');

-- 5. Insert Sample DB Connections
--INSERT INTO db_connections (id, connection_name, environment, department, dburi, username, password, type_of_database) VALUES
--(1, 'Finance Prod DB', 'source', 'Finance', 'jdbc:postgresql://localhost:5432/finance_prod', 'fin_user', 'fin_pass', 'postgres'),
--(2, 'Finance QA DB', 'destination', 'Finance', 'jdbc:postgresql://localhost:5432/finance_qa', 'qa_user', 'qa_pass', 'postgres'),
--(3, 'HR Prod Mongo', 'source', 'HR', 'mongodb://localhost:27017/hr_prod', 'hr_user', 'hr_pass', 'mongo'),
--(4, 'HR Test Mongo', 'destination', 'HR', 'mongodb://localhost:27017/hr_test', 'hr_test', 'hr_test', 'mongo'),
--(5, 'IT Logs Dir', 'source', 'IT', '/var/logs/it/prod', '', '', 'json'),
--(6, 'IT Masked Logs Dir', 'destination', 'IT', '/var/logs/it/masked', '', '', 'json'),
--(7, 'Admin Master DB', 'source', 'Admin', 'jdbc:postgresql://localhost:5432/admin_master', 'admin_db', 'admin_db', 'postgres'),
--(8, 'Admin Masked DB', 'destination', 'Admin', 'jdbc:postgresql://localhost:5432/admin_masked', 'admin_db', 'admin_db', 'postgres')
--ON CONFLICT (id) DO NOTHING;

-- 6. Insert Sample Job Requests
--INSERT INTO job_requests (id, migration_name, job_type, status, comments, submitted_by, created_at, config_details) VALUES
--(1, 'Finance Q3 Data Migration', 'postgres', 'WAITING', NULL, 'finance_sched', 1700000000000, '{"source":{"id":"1","url":"jdbc:postgresql://localhost:5432/finance_prod","schema":"public"},"dest":{"id":"2","url":"jdbc:postgresql://localhost:5432/finance_qa","schema":"public"},"rules":{"maskingColumns":["public.employees.salary"],"constraintColumns":[],"partialMaskingColumns":[],"targetTables":["public.employees"]}}')
--ON CONFLICT (id) DO NOTHING;

-- 7. Insert Sample COS Connections
--INSERT INTO cos_connections (id, cos_name, storage_type, storage_location, location, apikey, service_instance_id, accesskey, secretkey, bucketurl, bucket_id, bucket_name, authendication_type, department_id, env_type, created_by, created_at) VALUES
--(1, 'Finance Prod Bucket', 'COS', NULL, 'us-south', 'dummy-api-key', 'crn:v1:bluemix:public:cloud-object-storage:global:a/12345:12345::', 'dummy-access', 'dummy-secret', 'https://s3.us-south.cloud-object-storage.appdomain.cloud', 'bucket-123', 'fin-prod-data', 'IAM', 'Finance', 'source', 'admin', 1700000000000),
--(2, 'Finance QA Bucket', 'COS', NULL, 'us-south', 'dummy-api-key', 'crn:v1:bluemix:public:cloud-object-storage:global:a/12345:67890::', 'dummy-access', 'dummy-secret', 'https://s3.us-south.cloud-object-storage.appdomain.cloud', 'bucket-456', 'fin-qa-data', 'IAM', 'Finance', 'destination', 'admin', 1700000000000)
--ON CONFLICT (id) DO NOTHING;

-- ==========================================
-- DDL: TABLE ALTERATION SCRIPTS
-- ==========================================
--ALTER TABLE cos_connections ALTER COLUMN bucket_name DROP NOT NULL;

-- Update sequence to prevent ID collisions on future inserts
-- SELECT setval('users_id_seq', (SELECT MAX(id) FROM users));
-- SELECT setval('db_connections_id_seq', (SELECT MAX(id) FROM db_connections));
-- SELECT setval('job_requests_id_seq', (SELECT MAX(id) FROM job_requests));
-- SELECT setval('cos_connections_id_seq', (SELECT MAX(id) FROM cos_connections));
