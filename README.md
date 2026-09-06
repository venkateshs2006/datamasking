Project Design Document Anonify (Secure Enterprise Data Masking & Migration) Platform Version: 1.0

Date: January 26, 2026

Executive Summary 1.1 The Business Problem In the modern software lifecycle, enterprises face a critical bottleneck: Data Friction. Developers need realistic, "production-like" data to test applications effectively. However, stringent privacy regulations (GDPR, HIPAA, DPDP, CCPA) strictly prohibit copying raw PII (Personally Identifiable Information) to lower environments (Staging/QA).
Current solutionsâ€”manual SQL scripts or legacy ETL toolsâ€”are slow, error-prone, and expensive. This results in developers waiting days for data or using poor-quality synthetic data that hides bugs.

1.2 The Anonify Solution Anonify (Secure Efficient Data Migration) is a purpose-built infrastructure platform that automates the secure streaming of data from Production to Non-Production environments.

By leveraging Java 25 Virtual Threads and Spring Boot 4, Anonify moves Terabytes of data while applying military-grade obfuscation in real-time, in-memory. It ensures that sensitive data never lands on a disk in plain text, reducing data provisioning time from days to minutes.

Key Value Propositions:

Compliance: Zero PII leakage via deterministic, in-memory masking. Performance: 10x faster than traditional ETL using parallel virtual threads. Integrity: Preserves complex database relationships (Referential Integrity) even after encryption.

Solution Architecture The platform follows a Hub-and-Spoke architecture, designed for high throughput and security isolation.
2.1 Component Breakdown The Anonify Engine (Backend): Role: The core orchestration layer. It manages the "Extract-Mask-Load" pipeline using Structured Concurrency. It reads raw data, masks it in the JVM Heap, and commits to the target. The Cockpit (Frontend): Role: A "Blind" Operational Dashboard. It visualizes throughput, progress, and health via Server-Sent Events (SSE). Crucially, it never receives or displays actual business data, ensuring the UI is not a security vector. The Vault (Metadata DB): Role: A PostgreSQL database that stores Job Profiles, Masking Rules, Audit Logs, and Checkpoints. Connectors & Adaptors: Gemini_Generated_Image_38qu9b38qu9b38qu.pngRole: Specialized JDBC components to handle complex legacy schemas (Oracle, DB2) and modern PostgeSQL/MySQL targets.

Technical Specifications 3.1 The "Power Stack" Layer
Technology

Strategic Justification

Framework

Spring Boot 4.0.0

Uses Project Leyden for instant startup and modular architecture.

Language

Java 25 (LTS)

Native support for Scoped Values, allowing context sharing across 100k+ threads with zero memory overhead.

Batch Core

Spring Batch 6.x

Optimized for fault tolerance and auto-cleanup of failed sub-tasks.

Frontend

React 19

High-performance concurrent rendering for real-time dashboards.

Security

Spring Security 7

OAuth2 / RBAC for accessing the dashboard.

3.2 Hardware Requirements (Host Node) Processor: 8 vCPU (Optimized for massive parallel threading). RAM: 32 GB (To support high-concurrency buffers without OOM). Network: 10 Gbps (Direct Private Link between Source and Target).

Core Functional Features 4.1 High-Performance Migration Chunk-Oriented Processing: Streams data in configurable batches (default: 1000 rows) to keep memory usage constant, regardless of total dataset size. Parallel Partitioning: Automatically detects large tables (>100MB) and splits them into partition ranges, processing them via parallel Virtual Threads. Intelligent Resume: Checkpoints are saved after every chunk. If a network failure occurs at Row 9,000,000, the job resumes from Row 9,000,001 rather than restarting. 4.2 Security & Data Integrity Format-Preserving Encryption (FPE): Encrypts data while keeping the original format. Example: User_ID 12345 (Integer) -> 58291 (Integer). Benefit: Maintains schema validity and foreign key joins across tables. Deterministic Masking: The same input (Seed + Value) always produces the same output. Example: john@gmail.com always becomes alice.smith@faker.com in every environment, aiding debugging. In-Memory Processing: Unmasked data is piped directly from Source to Target via the RAM. It is never written to temporary files or logs. Gemini_Generated_Image_m50jenm50jenm50j.png

Legacy System Migration Strategy Legacy databases (Oracle 9i/10g, SQL Server 2008, DB2) often present unique challenges such as missing constraints or complex keys. Anonify includes specific features to handle these "Brownfield" environments.

5.1 Virtual Foreign Keys (The "Implicit Link" Solver) Legacy applications often enforce relationships in code (Java/C++) rather than in the database (FK Constraints) to improve write performance. Standard ETL tools fail here because they don't know the load order.

The Feature: Anonify allows users to define Virtual Relationships in the configuration. Mechanism: User defines: Map Table_A.Cust_Ref to Table_B.Customer_ID. Anonify Engine treats this as a strict dependency, ensuring Table_B is migrated and masked before Table_A to prevent "Orphan Record" errors. 5.2 Composite Key Handling Many legacy banking/telecom tables do not have a single ID column. Instead, they use a combination of columns as a Primary Key (e.g., Branch_ID + Sequence_No + Fiscal_Year).

The Feature: Custom Composite Reader. Mechanism: Anonify implements a specialized PagingQueryProvider that generates SQL like: SQL: SELECT * FROM Transactions ORDER BY Branch_ID, Sequence_No OFFSET ? LIMIT ?

This ensures stable pagination and restart capability even on tables without a singular auto-increment ID. 5.3 Legacy Data Type Support LOB/CLOB Streaming: Automatic handling of legacy BLOB (Images) and CLOB (XML/Text) columns. Anonify streams these effectively using Zero-Copy Buffers to avoid blowing up the Heap memory.

Configuration & Rules Engine Anonify uses a Declarative Configuration model. Migration logic is decoupled from the code, allowing Data Engineers to manage rules via simple YAML files.
6.1 The Job Profile (job_config.yaml) This file defines what to move and how to mask it.

YAML

job_profile: Â name: "End_of_Month_Refresh" Â batch_mode: "PARALLEL_PARTITIONING" Â chunk_size: 1000 Â threads: 50

connections: Â source: Â Â url: "jdbc:oracle:thin:@prod-db:1521:ORCL" Â Â username: "
Â
Â
Â
Â
Â
{VAULT_STAGE_USER}"

LEGACY SUPPORT: Define relationships not present in DB Schema
virtual_relationships: Â - parent: "Customer_Master.CIF_Number" Â Â child:Â "Loan_Accounts.Cust_Ref_ID"

masking_rules: Â - table: "Customer_Master" Â Â columns: Â Â Â - name: "Email_Address" Â Â Â Â action: "FAKER_EMAIL"Â Â Â # Generates realistic fake emails Â Â Â Â Â Â Â - name: "Phone_Number" Â Â Â Â action: "FAKER_PHONE_IN" Â # Generates +91 format phones Â Â Â Â Â Â Â - name: "National_ID" Â Â Â Â action: "FPE_ENCRYPT"Â Â Â # Format-Preserving Encryption Â Â Â Â key_ref: "master-key-v1" Â Â Â Â Â Â Â - name: "Salary" Â Â Â Â action: "NUMERIC_VARIANCE" # Varies value by +/- 10% Â Â Â Â variance_percent: 10

6.2 Internal Metadata DB Schema The engine uses an internal PostgreSQL database (The Vault) to track state.

job_definitions: Stores the YAML configurations. job_checkpoints: Stores the last_read_offset for every thread, enabling the "Resume" feature. audit_logs: Immutable record of executions (e.g., "User X started Job Y at 10:00 AM"). Gemini_Generated_Image_8vkt8e8vkt8e8vkt.png

Operational Monitoring (The Cockpit) The User Interface is designed for Observability, not Data Browsing.
7.1 Blind Dashboard Features Throughput Speedometer: Visualizes Rows Per Second (RPS) in real-time. Partition Heatmap: Shows which parallel threads are active, blocked, or completed. Error Fingerprinting: Aggregates errors by type (e.g., "Data Truncation: 45 errors") rather than showing sensitive row data. 7.2 Real-Time Architecture Protocol: Server-Sent Events (SSE). Flow: The Spring Boot backend pushes lightweight JSON metrics packets every 500ms. The React Frontend listens to this stream and updates the DOM efficiently without polling. uml diagram .jpg

Development Roadmap Phase
Duration

Goals & Deliverables

Phase 1: The Core

Weeks 1-4

Deliverable: Engine.jar.

Spring Batch Setup.

Connection Management.

Composite Key Readers.

Phase 2: Security

Weeks 5-6

Deliverable: Masking Service.

Implement FPE & Faker Logic.

Unit Tests for referential integrity.

Phase 3: The Cockpit

Weeks 7-9

Deliverable: React Dashboard.

SSE Integration.

YAML Configuration UI.

Phase 4: Hardening

Weeks 10-12

Deliverable: Production Release.

End-to-End Stress Testing (1TB).

Docker/Kubernetes Deployment Scripts.

Gemini_Generated_Image_h5r3efh5r3efh5r3.png

Future Roadmap (Version 2.0) We have laid the groundwork for these future features in our architectural choices:
Distributed Remote Workers: Using Project Leyden, we will create tiny, instant-start Docker containers. If a job is too big for one server, the system will spin up 50 worker containers across a Kubernetes cluster to share the load. AI PII Auto-Discovery: An intelligent scanner that looks at column data (not just names) and suggests: "This column looks like a Passport Number. Apply Masking?" Adaptive Throttling: The engine will monitor the Production Database's latency. If Production slows down, Anonify will automatically pause or slow its reading to prevent impacting real users ("Good Neighbor Policy").

Conclusion The Anonify Platform transforms data migration from a high-risk manual task into a secure, automated infrastructure service. By solving the specific challenges of Legacy Database Compatibility and providing a flexible Configuration Engine, Anonify fits seamlessly into complex enterprise environments while ensuring absolute data privacy.