-- Initialize destination database with empty table structure

-- Create users table (same structure as source)
CREATE TABLE IF NOT EXISTS users (
                                     id SERIAL PRIMARY KEY,
                                     username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
    );

-- Create products table (same structure as source)
CREATE TABLE IF NOT EXISTS products (
                                        id SERIAL PRIMARY KEY,
                                        product_name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10,2),
    stock_quantity INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
    );

-- Create orders table (same structure as source)
CREATE TABLE IF NOT EXISTS orders (
                                      id SERIAL PRIMARY KEY,
                                      user_id INTEGER REFERENCES users(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER,
    total_amount DECIMAL(10,2),
    order_date TIMESTAMP DEFAULT NOW()
    );

-- Create indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_product_id ON orders(product_id);