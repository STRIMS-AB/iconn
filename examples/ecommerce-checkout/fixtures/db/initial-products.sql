CREATE TABLE products (
    sku VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);

INSERT INTO products VALUES ('ITEM_TECH_42', 'Quantum Neural Co-Processor', 249.99);
INSERT INTO products VALUES ('ITEM_TECH_99', 'Holographic Lens Array', 499.00);
