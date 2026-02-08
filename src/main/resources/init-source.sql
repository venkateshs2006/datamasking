-- Initialize source database with sample data

-- Create users table
CREATE TABLE IF NOT EXISTS users (
                                     id SERIAL PRIMARY KEY,
                                     username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
    );

-- Create products table
CREATE TABLE IF NOT EXISTS products (
                                        id SERIAL PRIMARY KEY,
                                        product_name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10,2),
    stock_quantity INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
    );

-- Create orders table
CREATE TABLE IF NOT EXISTS orders (
                                      id SERIAL PRIMARY KEY,
                                      user_id INTEGER REFERENCES users(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER,
    total_amount DECIMAL(10,2),
    order_date TIMESTAMP DEFAULT NOW()
    );

-- Insert sample users
INSERT INTO users (username, email, first_name, last_name)
SELECT
    'user' || generate_series,
    'user' || generate_series || '@example.com',
    'FirstName' || generate_series,
    'LastName' || generate_series
FROM generate_series(1, 1000);

-- Insert sample products
INSERT INTO products (product_name, description, price, stock_quantity)
SELECT
    'Product ' || generate_series,
    'Description for product ' || generate_series,
    (random() * 1000 + 10)::DECIMAL(10,2),
    (random() * 1000)::INTEGER
FROM generate_series(1, 100);

-- Insert sample orders
INSERT INTO orders (user_id, product_id, quantity, total_amount)
SELECT
    (random() * 999 + 1)::INTEGER,
    (random() * 99 + 1)::INTEGER,
    (random() * 10 + 1)::INTEGER,
    (random() * 5000 + 100)::DECIMAL(10,2)
FROM generate_series(1, 5000);

-- Create indexes for better performance
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_product_id ON orders(product_id);
