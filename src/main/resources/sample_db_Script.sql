-- 1. Customers Table
CREATE TABLE Customers (
    customer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Credit Cards Table
CREATE TABLE CreditCards (
    card_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES Customers(customer_id) ON DELETE CASCADE,
    card_number_masked VARCHAR(19) NOT NULL, -- e.g., '****-****-****-1234'
    expiration_date VARCHAR(5) NOT NULL,    -- Format: MM/YY
    cvv_hash VARCHAR(255) NOT NULL,         -- Stored as a secure hash
    credit_limit NUMERIC(10, 2) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. Merchants Table
CREATE TABLE Merchants (
    merchant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    country VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Transactions Table
CREATE TABLE Transactions (
    transaction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id UUID NOT NULL REFERENCES CreditCards(card_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL REFERENCES Merchants(merchant_id) ON DELETE RESTRICT,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    transaction_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'APPROVED' -- APPROVED, DECLINED, PENDING
);

-- 5. Disputes Table
CREATE TABLE Disputes (
    dispute_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES Transactions(transaction_id) ON DELETE CASCADE,
    dispute_reason TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'OPEN', -- OPEN, INVESTIGATING, RESOLVED_REFUNDED, RESOLVED_DENIED
    amount_claimed NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexing for performance
CREATE INDEX idx_transactions_card_id ON Transactions(card_id);
CREATE INDEX idx_transactions_merchant_id ON Transactions(merchant_id);
CREATE INDEX idx_transactions_date ON Transactions(transaction_date);


INSERT INTO Customers (customer_id, first_name, last_name, email, phone_number, created_at)
SELECT 
    gen_random_uuid(),
    'First_' || i,
    'Last_' || i,
    'user_' || i || '@example.com',
    '+91' || FLOOR(RANDOM() * (9999999999 - 6000000000 + 1) + 6000000000)::BIGINT::TEXT,
    NOW() - (RANDOM() * INTERVAL '365 days')
FROM generate_series(1, 100000) AS i;



INSERT INTO CreditCards (card_id, customer_id, card_number_masked, expiration_date, cvv_hash, credit_limit, is_active, created_at)
SELECT 
    gen_random_uuid(),
    customer_id,
    '****-****-****-' || FLOOR(RANDOM() * 9000 + 1000)::TEXT,
    LPAD(FLOOR(RANDOM() * 12 + 1)::TEXT, 2, '0') || '/' || FLOOR(RANDOM() * (32 - 26 + 1) + 26)::TEXT, -- MM/YY format (2026-2032)
    MD5(RANDOM()::TEXT),
    (FLOOR(RANDOM() * 10 + 1) * 50000)::NUMERIC(10,2), -- Limits between 50k and 500k
    (RANDOM() > 0.05), -- 95% active cards
    created_at + INTERVAL '1 day'
FROM Customers;




INSERT INTO Merchants (merchant_id, merchant_name, category, country, created_at)
SELECT 
    gen_random_uuid(),
    'Merchant_' || i,
    (ARRAY['Grocery', 'Electronics', 'Travel', 'Dining', 'Apparel', 'Entertainment'])[FLOOR(RANDOM() * 6 + 1)],
    (ARRAY['India', 'USA', 'UK', 'UAE', 'Singapore'])[FLOOR(RANDOM() * 5 + 1)],
    NOW() - (RANDOM() * INTERVAL '365 days')
FROM generate_series(1, 50000) AS i;




-- Creates an equal mapping by pairing ordered rows from both tables
INSERT INTO Transactions (transaction_id, card_id, merchant_id, amount, currency, transaction_date, status)
WITH ranked_cards AS (
    SELECT card_id, ROW_NUMBER() OVER () as rnum FROM CreditCards
),
ranked_merchants AS (
    SELECT merchant_id, ROW_NUMBER() OVER () as rnum FROM Merchants
)
SELECT 
    gen_random_uuid(),
    c.card_id,
    m.merchant_id,
    ROUND((RANDOM() * 25000 + 10)::NUMERIC, 2), -- Amounts from 10 to 25,000
    'INR',
    NOW() - (RANDOM() * INTERVAL '30 days'),
    (ARRAY['APPROVED', 'APPROVED', 'APPROVED', 'DECLINED', 'PENDING'])[FLOOR(RANDOM() * 5 + 1)]
FROM ranked_cards c
JOIN ranked_merchants m ON c.rnum = m.rnum;



INSERT INTO Disputes (dispute_id, transaction_id, dispute_reason, status, amount_claimed, created_at)
SELECT 
    gen_random_uuid(),
    transaction_id,
    'Unauthorized charge / Item not received - Ref #' || FLOOR(RANDOM() * 100000)::TEXT,
    (ARRAY['OPEN', 'INVESTIGATING', 'RESOLVED_REFUNDED', 'RESOLVED_DENIED'])[FLOOR(RANDOM() * 4 + 1)],
    amount,
    transaction_date + INTERVAL '2 days'
FROM Transactions;


