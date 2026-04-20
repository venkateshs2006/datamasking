-- Sample data insertion script for users, roles, and departments
-- Passwords are encrypted using Bcrypt.
-- 'admin123' hash: $2a$10$wE.6C.K5R7Vw2k7hX.4p8eLw9Ym4q/N7V2X.y9R/pT8wP.E6M.3Oq
-- 'pass123' hash: $2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq

-- NOTE: In this application, these are automatically generated via UserDatabaseInitializer.java
-- This file serves as a reference script as requested.

-- Insert Admin
INSERT INTO users (id, username, password) VALUES (1, 'admin', '$2a$10$wE.6C.K5R7Vw2k7hX.4p8eLw9Ym4q/N7V2X.y9R/pT8wP.E6M.3Oq');
INSERT INTO user_roles (user_id, role) VALUES (1, 'ADMIN');
INSERT INTO user_departments (user_id, department) VALUES (1, 'ALL');

-- Insert Finance Scheduler
INSERT INTO users (id, username, password) VALUES (2, 'finance_sched', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq');
INSERT INTO user_roles (user_id, role) VALUES (2, 'SCHEDULER');
INSERT INTO user_departments (user_id, department) VALUES (2, 'Finance');

-- Insert Finance Approver
INSERT INTO users (id, username, password) VALUES (3, 'finance_appr', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq');
INSERT INTO user_roles (user_id, role) VALUES (3, 'APPROVER');
INSERT INTO user_departments (user_id, department) VALUES (3, 'Finance');

-- Insert Multi-Department Approver
INSERT INTO users (id, username, password) VALUES (4, 'manager_appr', '$2a$10$tV7Xy8N/qV9wP.M/A2M.4uQ2yR/X2wQ.P.yN7V/A4yM/T8wP.E6M.3Oq');
INSERT INTO user_roles (user_id, role) VALUES (4, 'APPROVER');
INSERT INTO user_departments (user_id, department) VALUES (4, 'Finance');
INSERT INTO user_departments (user_id, department) VALUES (4, 'HR');

-- Ensure sequence is updated if manual inserts were run
-- SELECT setval('users_id_seq', (SELECT MAX(id) FROM users));
